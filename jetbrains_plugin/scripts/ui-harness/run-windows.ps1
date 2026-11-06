# Run only in an interactive Windows desktop session; no API credentials needed.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$IdeDir,
    [Parameter(Mandatory = $true)][string]$PluginZip,
    [Parameter(Mandatory = $true)][string]$EvidenceDir,
    [ValidateRange(60, 1800)][int]$DeadlineSeconds = 600
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
$originalProperties = $env:IDEA_PROPERTIES
$originalOptions = $env:IDEA_VM_OPTIONS
$original64Options = $env:IDEA64_VM_OPTIONS

function Invoke-CheckedNative {
    param([string]$Executable, [string[]]$Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE" }
}

try {
    $identity = [ordered]@{
        commit = (& git rev-parse HEAD)
        os = [Environment]::OSVersion.VersionString
        interactive = [Environment]::UserInteractive
        sessionId = (Get-Process -Id $PID).SessionId
        pluginSha256 = (Get-FileHash $PluginZip -Algorithm SHA256).Hash
        ide = (Get-Content (Join-Path $IdeDir 'product-info.json') -Raw | ConvertFrom-Json)
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
    Invoke-CheckedNative "$javaBin/jar.exe" @('cf', "$driverLib/driver.jar", '-C', $classes, '.')
    Expand-Archive -LiteralPath $PluginZip -DestinationPath $plugins
    if (-not (Test-Path "$plugins/ZooCode/runtime/extension.js")) {
        throw 'Distribution must contain the release extension-host runtime; build with debugMode=release.'
    }
    $project = Join-Path $runDir 'workspace'
    New-Item -ItemType Directory -Force "$project/src" | Out-Null
    'public class Main { public static void main(String[] args) {} }' | Set-Content "$project/src/Main.java"
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
-Dzoo.harness.fixture=true
-Dzoo.harness.evidence=$evidencePath
"@ | Set-Content $options
    $env:IDEA_PROPERTIES = $properties
    $env:IDEA_VM_OPTIONS = $options
    $env:IDEA64_VM_OPTIONS = $options

    # Refuse to use a stale provider left by another run.
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 19487)
    try { $listener.Start() } finally { $listener.Stop() }
    $fixtureProcess = Start-Process -FilePath 'python' -ArgumentList @(
        "`"$PSScriptRoot/streaming_fixture.py`"", '--log', "`"$EvidenceDir/provider.jsonl`""
    ) -PassThru -RedirectStandardOutput "$EvidenceDir/provider-stdout.log" -RedirectStandardError "$EvidenceDir/provider-stderr.log"
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($fixtureProcess.HasExited) { throw 'Streaming fixture exited during startup' }
        try {
            $response = Invoke-WebRequest 'http://127.0.0.1:19487/health' -UseBasicParsing -TimeoutSec 1
            if ($response.StatusCode -eq 200) { $ready = $true; break }
        } catch { Start-Sleep -Milliseconds 250 }
    }
    if (-not $ready) { throw 'Streaming fixture did not become healthy' }
    $ideProcess = Start-Process -FilePath "$IdeDir/bin/idea64.exe" -ArgumentList "`"$project`"" -PassThru `
        -RedirectStandardOutput "$EvidenceDir/launcher-stdout.log" -RedirectStandardError "$EvidenceDir/launcher-stderr.log"
    $deadline = [DateTime]::UtcNow.AddSeconds($DeadlineSeconds)
    $statusPath = Join-Path $EvidenceDir 'status.txt'
    while ([DateTime]::UtcNow -lt $deadline -and -not (Test-Path $statusPath)) {
        if ($ideProcess.HasExited) { throw "IDE exited before producing evidence: $($ideProcess.ExitCode)" }
        Start-Sleep -Seconds 1
    }
    if (-not (Test-Path $statusPath)) { throw "E2E timed out after $DeadlineSeconds seconds" }
    $status = (Get-Content $statusPath -Raw).Trim()
    if ($status -ne 'PASS') { throw "Chat focus E2E result: $status (see evidence.json and progress.log)" }
    $events = @(Get-Content "$EvidenceDir/provider.jsonl" | ForEach-Object { $_ | ConvertFrom-Json })
    if (@($events | Where-Object event -eq 'completed').Count -lt 2) {
        throw 'Expected at least two completed provider streams'
    }
    Write-Output 'PASS: native desktop typing, native JCEF typing, and two streamed chat turns'
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
    $env:IDEA_PROPERTIES = $originalProperties
    $env:IDEA_VM_OPTIONS = $originalOptions
    $env:IDEA64_VM_OPTIONS = $original64Options
    # Keep evidence; remove the disposable IDE profile and workspace.
    Remove-Item $runDir -Recurse -Force -ErrorAction SilentlyContinue
}
