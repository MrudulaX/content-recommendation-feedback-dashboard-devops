<#
.SYNOPSIS
    Windows-native CRFD deployment smoke test. Mirrors scripts/smoke-test.sh.

.EXAMPLE
    scripts/smoke-test.ps1 -BaseUrl http://localhost:18080
#>
[CmdletBinding()]
param(
    [string] $BaseUrl = 'http://localhost:18080'
)

$ErrorActionPreference = 'Continue'
$pass = 0; $fail = 0
$uniq = [guid]::NewGuid().ToString('N').Substring(0, 8)
$item = "ITM-SMOKE-$uniq"

function Write-Head($t) { Write-Host "`n== $t" -ForegroundColor Cyan }
function Ok($t)   { $script:pass++; Write-Host "   PASS  $t" -ForegroundColor Green }
function Bad($t)  { $script:fail++; Write-Host "   FAIL  $t" -ForegroundColor Red }

function Invoke-Json {
    param([string]$Method, [string]$Uri, [string]$Body)
    try {
        if ($Body) {
            return Invoke-RestMethod -Method $Method -Uri $Uri -Body $Body -ContentType 'application/json' -TimeoutSec 15
        }
        return Invoke-RestMethod -Method $Method -Uri $Uri -TimeoutSec 15
    } catch {
        Write-Host "   (request error: $($_.Exception.Message))" -ForegroundColor DarkYellow
        return $null
    }
}

Write-Head "1/7 health"
$health = Invoke-Json GET "$BaseUrl/api/v1/health"
if ($health.database -eq 'UP') { Ok "readiness endpoint reports a healthy database" } else { Bad "readiness endpoint (database: $($health.database))" }
if ($health.status) { Ok "status reported as $($health.status) (SLO breached: $($health.sloBreached))" } else { Bad "no status in the health payload" }

Write-Head "2/7 ingest a feedback event"
$payload = @{
    userId = 'U-SMOKE'; itemId = $item; itemTitle = 'Smoke test item'
    eventType = 'IMPRESSION'; source = 'HYBRID'; surface = 'HOME_FEED'; score = 0.88
} | ConvertTo-Json -Compress
$ingest = Invoke-Json POST "$BaseUrl/api/v1/events" $payload
if ($ingest.eventId) { Ok "event accepted (id $($ingest.eventId))" } else { Bad "event ingest" }

Write-Head "3/7 searchable dashboard"
$search = Invoke-Json GET "$BaseUrl/api/v1/events?q=$item"
if ($search.content.Count -ge 1 -and ($search.content[0].itemId -eq $item)) { Ok "search finds the ingested item" }
else { Bad "search did not find $item" }

Write-Head "4/7 summary indicators"
$summary = Invoke-Json GET "$BaseUrl/api/v1/metrics/summary"
if ($null -ne $summary.totalEvents)      { Ok "summary exposes totalEvents ($($summary.totalEvents))" } else { Bad "totalEvents missing" }
if ($null -ne $summary.clickThroughRate) { Ok "summary exposes clickThroughRate ($($summary.clickThroughRate))" } else { Bad "clickThroughRate missing" }

Write-Head "5/7 status drill-down"
if ($ingest.eventId) {
    $detail = Invoke-Json GET "$BaseUrl/api/v1/events/$($ingest.eventId)"
    if ($detail.timeline)         { Ok "drill-down returns the audit timeline ($($detail.timeline.Count) entries)" } else { Bad "drill-down timeline" }
    if ($detail.event.status)     { Ok "drill-down exposes the pipeline status ($($detail.event.status))" } else { Bad "drill-down status" }
} else { Bad "drill-down skipped - no event id" }

Write-Head "6/7 alert / exception view"
$alerts = Invoke-Json GET "$BaseUrl/api/v1/alerts"
if ($null -ne $alerts.content) { Ok "alerts endpoint returns a page ($($alerts.totalElements) alert(s))" } else { Bad "alerts endpoint" }

Write-Head "7/7 dashboard UI served"
try {
    $index = Invoke-WebRequest -Uri "$BaseUrl/" -TimeoutSec 15 -UseBasicParsing
    if ($index.Content -match 'Content Recommendation Feedback Dashboard') { Ok "dashboard shell is served" } else { Bad "dashboard shell content" }
    if ($index.Content -match '/app.js') { Ok "dashboard wires up the client script" } else { Bad "client script not referenced" }
} catch { Bad "dashboard UI: $($_.Exception.Message)" }

Write-Host "`nresult: $pass passed, $fail failed" -ForegroundColor Cyan
if ($fail -gt 0) { Write-Host "SMOKE TEST FAILED" -ForegroundColor Red; exit 1 }
Write-Host "SMOKE TEST PASSED" -ForegroundColor Green; exit 0
