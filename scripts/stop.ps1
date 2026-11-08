$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$registryFile = Join-Path $projectRoot '.run/processes.json'
if (-not (Test-Path -LiteralPath $registryFile)) { Write-Host 'No recorded demo processes.'; return }
$entries = @(Get-Content -LiteralPath $registryFile -Raw | ConvertFrom-Json)
[array]::Reverse($entries)
foreach ($entry in $entries) {
    $process = Get-Process -Id $entry.id -ErrorAction SilentlyContinue
    if ($process -and $process.StartTime.ToUniversalTime().Ticks -eq [long]$entry.startedTicks) {
        Stop-Process -Id $process.Id
        if (-not $process.WaitForExit(10000)) { throw "Process did not stop: $($entry.module)" }
        Write-Host "Stopped $($entry.module)"
    }
}
Remove-Item -LiteralPath $registryFile
