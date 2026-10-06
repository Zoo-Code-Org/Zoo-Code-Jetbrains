$ErrorActionPreference = 'Stop'
$profilePath = Join-Path $PSScriptRoot '../src/main/resources/run-vs-agent-shell-integrations/vscode-powershell/profile.ps1'
$tokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($profilePath, [ref]$tokens, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count) { throw ($parseErrors.Message -join '; ') }
if ([IO.File]::ReadAllText($profilePath) -match '[^\x00-\x7F]') { throw 'Profile must remain ASCII for Windows PowerShell 5.1.' }

# Mock only console input/history; load the real profile in a child script scope.
Import-Module PSReadLine
$global:testHistoryId = 1
function global:Get-History { [pscustomobject]@{ Id = $global:testHistoryId } }
function global:PSConsoleHostReadLine { 'Write-Output "hello;world"' }
$env:VSCODE_NONCE = 'test-nonce'
$originalConsole = [Console]::Out
$capture = New-Object IO.StringWriter
try {
    [Console]::SetOut($capture)
    & $profilePath
    $unicodeValue = 'caf' + [char]0xe9 + ' ' + [char]0x65e5 + [char]0x672c + [char]0x8a9e + ' ' + [char]::ConvertFromUtf32(0x1f680)
    $escapedUnicode = __VSCode-Escape-Value $unicodeValue
    if ($escapedUnicode -match '[^\x00-\x7F]') { throw 'Unicode marker is not ASCII-safe.' }
    if ($escapedUnicode -ne 'caf\xc3\xa9 \xe6\x97\xa5\xe6\x9c\xac\xe8\xaa\x9e \xf0\x9f\x9a\x80') { throw 'Incorrect UTF-8 Unicode escapes.' }
    $initialPrompt = prompt
    $command = PSConsoleHostReadLine
    $global:testHistoryId = 2
    $finishedPrompt = prompt
    $null = PSConsoleHostReadLine
    $global:testHistoryId = 3
    cmd.exe /c exit 7
    $failedPrompt = prompt
    $null = PSConsoleHostReadLine
    $global:testHistoryId = 4
    Write-Output 'alive' | Out-Null
    $recoveredPrompt = prompt
} finally { [Console]::SetOut($originalConsole) }
$esc = [char]27
$bell = [char]7
$markers = $capture.ToString()
if ($command -ne 'Write-Output "hello;world"') { throw 'ReadLine did not preserve the command.' }
if (-not $markers.Contains("${esc}]633;E;Write-Output `"hello\x3bworld`";test-nonce${bell}")) { throw 'Missing command/nonce marker after script scope exited.' }
if (-not $markers.Contains("${esc}]633;C${bell}")) { throw 'Missing execution start marker.' }
if (-not $initialPrompt.Contains("${esc}]633;P;Cwd=")) { throw 'Missing working directory marker.' }
if (-not $finishedPrompt.Contains("${esc}]633;D;0${bell}")) { throw 'Missing successful completion marker.' }
if (-not $failedPrompt.Contains("${esc}]633;D;7${bell}")) { throw 'Native exit code 7 was not preserved.' }
if (-not $recoveredPrompt.Contains("${esc}]633;D;0${bell}")) { throw 'Stale native exit code reused for successful command.' }
'PASS: Windows PowerShell parsing, script-scope lifetime, command escaping, nonce, start, cwd and completion markers.'
