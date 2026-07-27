# Resilience test scenarios for Internal Gateway PoC
$Gateway = "http://localhost:8080"
$TestProcessor = "http://localhost:8092"
$OfferService = "http://localhost:8090"

Write-Host "=== Registered test processors ==="
curl.exe -s "$Gateway/internal/admin/test/processors" | Write-Host

Write-Host "`n=== 1. Dedup scenario ==="
curl.exe -s -X POST "$Gateway/internal/admin/test/scenarios/dedup" | Write-Host
Start-Sleep -Seconds 2
curl.exe -s "$Gateway/internal/admin/requests?limit=5" | Write-Host

Write-Host "`n=== 2. Retry scenario (inject 503 x2, then publish) ==="
curl.exe -s -X POST "$Gateway/internal/admin/test/scenarios/retry?failCount=2" | Write-Host
Start-Sleep -Seconds 3
curl.exe -s "$Gateway/internal/admin/requests?limit=5" | Write-Host

Write-Host "`n=== 3. Rate limit burst ==="
curl.exe -s -X POST "$Gateway/internal/admin/test/scenarios/rate-limit?burstCount=8" | Write-Host
Start-Sleep -Seconds 2
curl.exe -s "$Gateway/internal/admin/requests?limit=10" | Write-Host

Write-Host "`n=== Resilience stats ==="
curl.exe -s "$Gateway/internal/admin/test/resilience/stats" | Write-Host

Write-Host "`nDone. Check gateway.html for DEDUP / RATE_LIMIT / retry details in request log."
