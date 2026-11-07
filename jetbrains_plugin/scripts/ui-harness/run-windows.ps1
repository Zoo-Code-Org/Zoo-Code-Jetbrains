# Run only in an interactive Windows desktop session. Live mode uses the Solheim relay.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$IdeDir,
    [Parameter(Mandatory = $true)][string]$PluginZip,
    [Parameter(Mandatory = $true)][string]$EvidenceDir,
    [ValidateRange(60, 1800)][int]$DeadlineSeconds = 600,
    [ValidateSet('fixture', 'solheim')][string]$Provider = 'fixture',
    [switch]$RequireWindows11
)
$ErrorActionPreference = 'Stop'
$IdeDir = (Resolve-Path $IdeDir).Path
$PluginZip = (Resolve-Path $PluginZip).Path
$EvidenceDir = [IO.Path]::GetFullPath($EvidenceDir)
New-Item -ItemType Directory -Force $EvidenceDir | Out-Null
if (Test-Path (Join-Path $EvidenceDir 'status.txt')) { throw 'Use a fresh evidence directory for each run.' }
$runDir = Join-Path ([IO.Path]::GetTempPath()) ('zoo-focus-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $runDir | Out-Null
$ideProcess = $null
$fixtureProcess = $null
$live = $Provider -eq 'solheim'
$originalRelayToken = $env:SOLHEIM_RELAY_TOKEN
$originalKeyFile = $env:SOLHEIM_KEY_FILE
$originalProxyLog = $env:SOLHEIM_PROXY_LOG
if ($live) {
    if ([string]::IsNullOrWhiteSpace($env:SOLHEIM_API_KEY)) { throw 'Solheim mode requires SOLHEIM_API_KEY' }
    # Remove the upstream key from the environment before starting any children.
    $upstreamKey = $env:SOLHEIM_API_KEY
    $env:SOLHEIM_API_KEY = $null
}
$originalProperties = $env:IDEA_PROPERTIES
$originalOptions = $env:IDEA_VM_OPTIONS
$original64Options = $env:IDEA64_VM_OPTIONS
$originalWebideProperties = $env:WEBIDE_PROPERTIES
$originalWebideOptions = $env:WEBIDE_VM_OPTIONS

function Invoke-CheckedNative {
    param([string]$Executable, [string[]]$Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE" }
}

try {
    $windows = Get-CimInstance Win32_OperatingSystem
    if ($RequireWindows11 -and ($windows.ProductType -ne 1 -or [int]$windows.BuildNumber -lt 22000 -or
        [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString() -ne 'X64')) {
        throw 'This reproduction requires Windows 11 x64, not Windows Server or ARM64'
    }
    $identity = [ordered]@{
        commit = (& git rev-parse HEAD)
        os = [Environment]::OSVersion.VersionString
        windowsCaption = $windows.Caption
        windowsBuild = $windows.BuildNumber
        windowsProductType = $windows.ProductType
        architecture = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
        interactive = [Environment]::UserInteractive
        sessionId = (Get-Process -Id $PID).SessionId
        pluginSha256 = (Get-FileHash $PluginZip -Algorithm SHA256).Hash
        ide = (Get-Content (Join-Path $IdeDir 'product-info.json') -Raw | ConvertFrom-Json)
        provider = $Provider
        runnerImage = $env:ImageVersion
        run = "$env:GITHUB_SERVER_URL/$env:GITHUB_REPOSITORY/actions/runs/$env:GITHUB_RUN_ID"
    }
    $identity | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $EvidenceDir 'run-identity.json')
    if (-not [Environment]::UserInteractive -or (Get-Process -Id $PID).SessionId -eq 0) {
        throw 'A logged-in interactive desktop is required; do not run the agent as a Windows service.'
    }
    # JetBrains recommends dismissing the Windows Start overlay for UI tests.
    Stop-Process -Name StartMenuExperienceHost -Force -ErrorAction SilentlyContinue
    $javaBin = Join-Path $IdeDir 'jbr/bin'
    $classes = Join-Path $runDir 'driver-classes'
    New-Item -ItemType Directory -Force (Join-Path $classes 'META-INF') | Out-Null
    Invoke-CheckedNative "$javaBin/javac.exe" @('-cp', "$IdeDir/lib/*", '-d', $classes,
        "$PSScriptRoot/HarnessDriver.java", "$PSScriptRoot/DesktopTypingProbe.java")
    # Separate desktop setup failures from IDE/JCEF focus regressions.
    $probe = Start-Process -FilePath "$javaBin/java.exe" -ArgumentList @(
        '-cp', "`"$classes`"", 'DesktopTypingProbe', "`"$EvidenceDir`""
    ) -PassThru -RedirectStandardOutput "$EvidenceDir/desktop-stdout.log" -RedirectStandardError "$EvidenceDir/desktop-stderr.log"
    if (-not $probe.WaitForExit(30000)) {
        & taskkill.exe /PID $probe.Id /T /F | Out-Null
        throw 'Native desktop typing preflight timed out'
    }
    if ($probe.ExitCode -ne 0) { throw "Native desktop typing preflight failed: $($probe.ExitCode)" }
    Copy-Item "$PSScriptRoot/plugin.xml" (Join-Path $classes 'META-INF/plugin.xml')
    $plugins = Join-Path $runDir 'plugins'
    $driverLib = Join-Path $plugins 'harness-driver/lib'
    New-Item -ItemType Directory -Force $driverLib | Out-Null
    # The IDE's bundled JBR includes javac but may omit the jar executable.
    # A plugin JAR is a ZIP with classes and META-INF/plugin.xml at its root.
    [IO.Compression.ZipFile]::CreateFromDirectory($classes, "$driverLib/driver.jar")
    Expand-Archive -LiteralPath $PluginZip -DestinationPath $plugins
    if (-not (Test-Path "$plugins/ZooCode/runtime/extension.js")) {
        throw 'Distribution must contain the release extension-host runtime; build with debugMode=release.'
    }
    $project = Join-Path $runDir 'workspace'
    New-Item -ItemType Directory -Force "$project/src" | Out-Null
    'public class Main { public static void main(String[] args) { System.out.println("throwaway"); } }' | Set-Content "$project/src/Main.java"
    if ($live) {
        New-Item -ItemType Directory "$project/.focus-ci" | Out-Null
        foreach ($step in @('first', 'second', 'third')) {
            # Fixed scripts: the model only chooses when to invoke them.
            @"
`$ErrorActionPreference = 'Stop'
Write-Output SOLHEIM_$($step.ToUpper())
Start-Sleep -Seconds 8
Get-Content src/Main.java
Set-Content .focus-ci/$step.done 'completed'
"@ | Set-Content "$project/.focus-ci/$step.ps1"
        }
    }
    $properties = Join-Path $runDir 'idea.properties'
    $profilePath = $runDir.Replace('\', '/')
    @"
idea.config.path=$profilePath/config
idea.system.path=$profilePath/system
idea.log.path=$profilePath/log
idea.plugins.path=$profilePath/plugins
"@ | Set-Content $properties
    $options = Join-Path $runDir 'idea.vmoptions'
    $evidencePath = $EvidenceDir.Replace('\', '/')
    @"
-Xmx1536m
-XX:ActiveProcessorCount=4
-Didea.trust.all.projects=true
-Didea.initially.ask.config=false
-Djb.consents.confirmation.enabled=false
-Djb.privacy.policy.text=<!--999.999-->
-Dide.show.tips.on.startup.default.value=false
-Dzoo.harness.mode=P
-Dzoo.harness.fixture=$((-not $live).ToString().ToLowerInvariant())
-Dzoo.harness.evidence=$evidencePath
"@ | Set-Content $options
    $env:IDEA_PROPERTIES = $properties
    $env:IDEA_VM_OPTIONS = $options
    $env:IDEA64_VM_OPTIONS = $options

    # Refuse to use a stale provider left by another run.
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 19487)
    try { $listener.Start() } finally { $listener.Stop() }
    # A separate visible Python console can cover the IDE and consume Robot keys.
    # Keep the provider alive without creating a foreground test obstacle.
    $providerScript = "$PSScriptRoot/streaming_fixture.py"
    $providerArguments = @("`"$providerScript`"", '--log', "`"$EvidenceDir/provider.jsonl`"")
    $readyUri = 'http://127.0.0.1:19487/health'
    $readyHeaders = @{}
    if ($live) {
        $privateDir = New-Item -ItemType Directory "$runDir/private"
        $acl = Get-Acl $privateDir.FullName
        $acl.SetAccessRuleProtection($true, $false)
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.WindowsIdentity]::GetCurrent().User, 'FullControl',
            'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
        Set-Acl -Path $privateDir.FullName -AclObject $acl
        $env:SOLHEIM_KEY_FILE = "$runDir/private/key"
        [IO.File]::WriteAllText($env:SOLHEIM_KEY_FILE, $upstreamKey)
        $upstreamKey = $null
        $env:SOLHEIM_RELAY_TOKEN = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        $env:SOLHEIM_PROXY_LOG = "$EvidenceDir/provider.jsonl"
        $providerArguments = @("`"$PSScriptRoot/solheim_proxy.py`"")
        $readyUri = 'http://127.0.0.1:19487/v1/health'
        $readyHeaders = @{ Authorization = "Bearer $env:SOLHEIM_RELAY_TOKEN" }
    }
    $fixtureProcess = Start-Process -FilePath 'python' -WindowStyle Hidden -ArgumentList $providerArguments -PassThru -RedirectStandardOutput "$EvidenceDir/provider-stdout.log" -RedirectStandardError "$EvidenceDir/provider-stderr.log"
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($fixtureProcess.HasExited) { throw 'Streaming fixture exited during startup' }
        try {
            $response = Invoke-WebRequest $readyUri -Headers $readyHeaders -UseBasicParsing -TimeoutSec 1
            if ($response.StatusCode -eq 200) { $ready = $true; break }
        } catch { Start-Sleep -Milliseconds 250 }
    }
    if (-not $ready) { throw 'Local provider did not become healthy' }
    if ($live) {
        Remove-Item $env:SOLHEIM_KEY_FILE -Force
        $env:SOLHEIM_KEY_FILE = $null
        $env:SOLHEIM_PROXY_LOG = $null
    }
    $product = Get-Content "$IdeDir/product-info.json" -Raw | ConvertFrom-Json
    $launcher = if ($product.productCode -eq 'WS') { 'webstorm64.exe' } else { 'idea64.exe' }
    if ($product.productCode -eq 'WS') {
        # WebStorm's launcher ignores IDEA_PROPERTIES and IDEA_VM_OPTIONS.
        # Without these variables it opens a normal first-run profile, without
        # our plugins, custom paths, or harness JVM properties.
        $env:WEBIDE_PROPERTIES = $properties
        $env:WEBIDE_VM_OPTIONS = $options
    }
    $ideProcess = Start-Process -FilePath "$IdeDir/bin/$launcher" -ArgumentList "`"$project`"" -PassThru `
        -RedirectStandardOutput "$EvidenceDir/launcher-stdout.log" -RedirectStandardError "$EvidenceDir/launcher-stderr.log"
    $deadline = [DateTime]::UtcNow.AddSeconds($DeadlineSeconds)
    $startupDeadline = [DateTime]::UtcNow.AddSeconds(120)
    $statusPath = Join-Path $EvidenceDir 'status.txt'
    while ([DateTime]::UtcNow -lt $deadline -and -not (Test-Path $statusPath)) {
        if ($ideProcess.HasExited) { throw "IDE exited before producing evidence: $($ideProcess.ExitCode)" }
        if ([DateTime]::UtcNow -gt $startupDeadline -and -not (Test-Path "$EvidenceDir/progress.log")) {
            throw 'IDE harness did not start within 120 seconds; inspect launcher configuration and startup dialogs in runner-final.png'
        }
        Start-Sleep -Seconds 1
    }
    if (-not (Test-Path $statusPath)) { throw "E2E timed out after $DeadlineSeconds seconds" }
    $status = (Get-Content $statusPath -Raw).Trim()
    if ($status -ne 'PASS') {
        $failureReason = 'See evidence.json and progress.log'
        $evidenceJson = Join-Path $EvidenceDir 'evidence.json'
        if (Test-Path $evidenceJson) {
            $checks = (Get-Content $evidenceJson -Raw | ConvertFrom-Json).checks
            if ($checks.failReason) { $failureReason = $checks.failReason }
        }
        throw "Chat focus E2E result: ${status}: $failureReason"
    }
    $events = @(Get-Content "$EvidenceDir/provider.jsonl" | ForEach-Object { $_ | ConvertFrom-Json })
    if ($live) {
        $responses = @($events | Where-Object event -eq 'response')
        $tools = @($responses | ForEach-Object { $_.tools } | Sort-Object -Unique)
        $requiredTools = @('execute_command', 'read_file', 'apply_diff', 'attempt_completion')
        $missingTools = @($requiredTools | Where-Object { $_ -notin $tools })
        $missingCommands = @(@('first', 'second', 'third') | Where-Object { -not (Test-Path "$project/.focus-ci/$_.done") })
        $ideLog = Get-Content "$runDir/log/idea.log" -Raw
        $validation = [ordered]@{
            missingTools = $missingTools
            missingCommands = $missingCommands
            preservingDiff = $ideLog.Contains('Opening diff, preserveFocus=true') -and $ideLog.Contains('Opening diff completed')
            successfulResponses = @($responses | Where-Object status -eq 200).Count
            failedResponses = @($responses | Where-Object status -ne 200).Count
        }
        $validation | ConvertTo-Json -Depth 5 | Set-Content "$EvidenceDir/live-validation.json"
        if ($missingTools.Count -or $missingCommands.Count -or -not $validation.preservingDiff -or
            $validation.successfulResponses -lt 2 -or $validation.failedResponses) {
            throw 'Live terminal/diff/provider coverage incomplete; see live-validation.json'
        }
        Copy-Item "$project/src/Main.java" "$EvidenceDir/changed-fixture.java"
    } elseif (@($events | Where-Object event -eq 'completed').Count -lt 2) {
        throw 'Expected at least two completed provider streams'
    }
    Write-Output "PASS: provider=$Provider, native desktop/chat typing, two streamed turns, and required tool coverage"
} catch {
    $_ | Out-String | Set-Content "$EvidenceDir/runner-error.txt"
    throw
} finally {
    try {
        Add-Type -AssemblyName System.Windows.Forms
        Add-Type -AssemblyName System.Drawing
        $bounds = [Windows.Forms.SystemInformation]::VirtualScreen
        $bitmap = [Drawing.Bitmap]::new($bounds.Width, $bounds.Height)
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.CopyFromScreen($bounds.Location, [Drawing.Point]::Empty, $bounds.Size)
            $bitmap.Save("$EvidenceDir/runner-final.png", [Drawing.Imaging.ImageFormat]::Png)
        } finally { $graphics.Dispose(); $bitmap.Dispose() }
    } catch { Write-Warning "Could not capture final desktop: $_" }
    if ($ideProcess -and -not $ideProcess.HasExited) { & taskkill.exe /PID $ideProcess.Id /T /F | Out-Null }
    if ($fixtureProcess -and -not $fixtureProcess.HasExited) { Stop-Process -Id $fixtureProcess.Id -Force -ErrorAction SilentlyContinue }
    if (Test-Path "$runDir/log") { Copy-Item "$runDir/log" "$EvidenceDir/ide-log" -Recurse -Force }
    $upstreamKey = $null
    $env:SOLHEIM_RELAY_TOKEN = $originalRelayToken
    $env:SOLHEIM_KEY_FILE = $originalKeyFile
    $env:SOLHEIM_PROXY_LOG = $originalProxyLog
    $env:IDEA_PROPERTIES = $originalProperties
    $env:IDEA_VM_OPTIONS = $originalOptions
    $env:IDEA64_VM_OPTIONS = $original64Options
    $env:WEBIDE_PROPERTIES = $originalWebideProperties
    $env:WEBIDE_VM_OPTIONS = $originalWebideOptions
    # Keep evidence; remove the disposable IDE profile and workspace.
    Remove-Item $runDir -Recurse -Force -ErrorAction SilentlyContinue
}
