$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/http.ps1"
$base = 'http://localhost:8080'
$deadline = [DateTime]::UtcNow.AddSeconds(60)
$token = $null
while ([DateTime]::UtcNow -lt $deadline) {
    try {
        $auth = Invoke-LocalApi "$base/user/auth" -Method Post -ContentType 'application/json' -Body (@{username='alice';password='Alice-demo-123'} | ConvertTo-Json)
        $token = $auth.token
        break
    } catch { Start-Sleep -Milliseconds 500 }
}
if (-not $token) { throw 'Gateway did not discover Booking Service. Inspect .run logs.' }
$headers = @{ Authorization="Bearer $token" }
$start = [DateTime]::UtcNow.AddDays(30).ToString('yyyy-MM-dd')
$end = [DateTime]::UtcNow.AddDays(32).ToString('yyyy-MM-dd')
$rooms = Invoke-LocalApi "$base/api/rooms/recommend?hotelId=1&startDate=$start&endDate=$end" -Headers $headers
Write-Host "Recommended room ids: $($rooms.content.id -join ', ')"
$headers['X-Request-Id'] = [Guid]::NewGuid().ToString()
$body = @{autoSelect=$true;hotelId=1;startDate=$start;endDate=$end} | ConvertTo-Json
$booking = Invoke-LocalApi "$base/api/bookings" -Method Post -Headers $headers -ContentType 'application/json' -Body $body
if ($booking.status -ne 'CONFIRMED') { throw 'Booking was not confirmed' }
$replayed = Invoke-LocalApi "$base/api/bookings" -Method Post -Headers $headers -ContentType 'application/json' -Body $body
if ($replayed.id -ne $booking.id) { throw 'Idempotency check failed' }
Write-Host "Confirmed booking $($booking.id), room $($booking.roomId). Duplicate request returned the same id."
$cancelled = Invoke-LocalApi "$base/api/bookings/$($booking.id)" -Method Delete -Headers $headers
if ($cancelled.status -ne 'CANCELLED') { throw 'Cancellation failed' }
$deadline = [DateTime]::UtcNow.AddSeconds(20)
$released=$false
while ([DateTime]::UtcNow -lt $deadline) {
    $free = Invoke-LocalApi "$base/api/rooms?hotelId=1&startDate=$start&endDate=$end" -Headers $headers
    if ($free.content.id -contains $booking.roomId) { $released=$true; break }
    Start-Sleep -Milliseconds 300
}
if (-not $released) { throw 'Compensation has not released the room' }
Write-Host 'Demo passed: login, discovery, recommendations, confirmation, idempotency, cancellation and release.'
