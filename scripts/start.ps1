param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/http.ps1"
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if (Test-Path '.run/processes.json') { throw 'Run scripts/stop.ps1 before starting a fresh demo.' }
    foreach ($port in 8761,8080,8081,8082) {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
        try { $listener.Start() } finally { $listener.Stop() }
    }
    & java scripts/GenerateKeys.java
    if ($LASTEXITCODE -ne 0) { throw 'Key generation failed' }
    if (-not $SkipBuild) {
        & .\mvnw.cmd -B -ntp verify
        if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
    }
    New-Item -ItemType Directory -Force '.run' | Out-Null
    $env:DEMO_BASE_DATE = [DateTime]::UtcNow.ToString('yyyy-MM-dd')
    $started = @()
    try {
        foreach ($module in 'discovery-server','hotel-service','booking-service','api-gateway') {
            $jar = Join-Path $projectRoot "$module/target/$module-1.0.0.jar"
            if (-not (Test-Path -LiteralPath $jar)) { throw "Build output missing: $jar" }
            $process = Start-Process -FilePath 'java' -ArgumentList @('-Xmx256m','-jar',"`"$jar`"",'--spring.profiles.active=demo','--eureka.client.registry-fetch-interval-seconds=2','--spring.cloud.loadbalancer.cache.enabled=false') -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput ".run/$module.log" -RedirectStandardError ".run/$module.err.log" -PassThru
            $started += @{ id=$process.Id; startedTicks=$process.StartTime.ToUniversalTime().Ticks; module=$module }
            ConvertTo-Json -InputObject @($started) | Set-Content '.run/processes.json'
            $port = @{ 'discovery-server'=8761; 'hotel-service'=8081; 'booking-service'=8082; 'api-gateway'=8080 }[$module]
            $deadline = [DateTime]::UtcNow.AddSeconds(90)
            $ready = $false
            while ([DateTime]::UtcNow -lt $deadline) {
                if ($process.HasExited) { throw "$module exited. Inspect .run/$module.log" }
                try { $reply=Invoke-LocalApi "http://localhost:$port/actuator/health" -TimeoutSec 2; if ($reply.status -eq 'UP') { $ready=$true; break } } catch { }
                Start-Sleep -Milliseconds 500
            }
            if (-not $ready) { throw "$module did not become ready" }
        }
        foreach ($documentPath in '/docs/booking','/docs/hotel') {
            $deadline = [DateTime]::UtcNow.AddSeconds(60)
            $routeReady = $false
            while ([DateTime]::UtcNow -lt $deadline) {
                try {
                    $document = Invoke-LocalApi "http://localhost:8080$documentPath" -TimeoutSec 3
                    if ($document.openapi) { $routeReady=$true; break }
                } catch { }
                Start-Sleep -Milliseconds 500
            }
            if (-not $routeReady) { throw "Gateway route is not ready: $documentPath" }
        }
        Write-Host 'Applications and Gateway routes are ready. Gateway: http://localhost:8080'
        Write-Host 'Swagger: http://localhost:8082/swagger-ui.html and http://localhost:8081/swagger-ui.html'
        Write-Host 'Run ./scripts/demo.ps1 to check the complete scenario.'
    } catch {
        & "$PSScriptRoot/stop.ps1"
        throw
    }
} finally { Pop-Location }
