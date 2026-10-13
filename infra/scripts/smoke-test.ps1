# Rydex smoke test (Windows PowerShell 5.1+ or PowerShell 7). Checks everything built so far
# against the running local stack:
#   Phase 0  infra containers + Kafka topics
#   Phase 1  ride-service: login, places, estimate, booking, idempotency, cancel
#   Phase 2  driver-service: driver profile, go online, grid, candidate ranking, roles
#   Phase 3  a full ride across services over Kafka: book -> offer -> accept -> assigned ->
#            arrived -> start (OTP) -> complete -> driver free again; and a cancelled offer
#
# Prerequisites: `docker compose -f infra/docker-compose.yml up -d`, then ride-service (:8081)
# and driver-service (:8082) running (see README). Usage, from the repo root:
#   powershell -ExecutionPolicy Bypass -File infra\scripts\smoke-test.ps1

param(
    [string]$RideUrl = "http://localhost:8081",
    [string]$DriverUrl = "http://localhost:8082"
)

$ErrorActionPreference = "Continue"
$script:passed = 0
$script:failed = 0

function Check([string]$name, [bool]$ok, [string]$detail = "") {
    if ($ok) {
        $script:passed++
        Write-Host ("[PASS] " + $name + $(if ($detail) { "  ($detail)" } else { "" })) -ForegroundColor Green
    } else {
        $script:failed++
        Write-Host ("[FAIL] " + $name + $(if ($detail) { "  ($detail)" } else { "" })) -ForegroundColor Red
    }
}

function Section([string]$title) {
    Write-Host ""
    Write-Host "== $title" -ForegroundColor Cyan
}

# Calls the API and returns @{ Status = <int>; Body = <object or $null>; Headers = <dict> }; never throws on 4xx/5xx.
function Api([string]$method, [string]$url, [string]$token = $null, $body = $null, [hashtable]$headers = @{}) {
    $h = @{} + $headers
    if ($token) { $h["Authorization"] = "Bearer $token" }
    $req = @{ Method = $method; Uri = $url; Headers = $h; UseBasicParsing = $true; TimeoutSec = 30 }
    # PowerShell 7 throws a different exception type on 4xx/5xx; ask it not to throw at all
    if ($PSVersionTable.PSVersion.Major -ge 7) { $req["SkipHttpErrorCheck"] = $true }
    if ($null -ne $body) {
        $req["ContentType"] = "application/json"
        $req["Body"] = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 6))
    }
    try {
        $res = Invoke-WebRequest @req
        $content = $res.Content
        # PowerShell 5.1 hands back bytes for content types it does not know (e.g. actuator's vnd.*+json)
        if ($content -is [byte[]]) { $content = [System.Text.Encoding]::UTF8.GetString($content) }
        $parsed = $null
        if ($content) { try { $parsed = $content | ConvertFrom-Json } catch { } }
        return @{ Status = [int]$res.StatusCode; Body = $parsed; Headers = $res.Headers }
    } catch [System.Net.WebException] {
        $r = $_.Exception.Response
        if ($null -eq $r) { return @{ Status = 0; Body = $null; Headers = @{} } }
        $text = (New-Object System.IO.StreamReader($r.GetResponseStream())).ReadToEnd()
        $parsed = $null
        if ($text) { try { $parsed = $text | ConvertFrom-Json } catch { } }
        return @{ Status = [int]$r.StatusCode; Body = $parsed; Headers = $r.Headers }
    }
}

# Polls $probe (a script block returning $true when done) every 500 ms for up to $seconds.
function WaitFor([int]$seconds, [scriptblock]$probe) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if (& $probe) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Login([string]$email) {
    $r = Api POST "$RideUrl/api/auth/login" $null @{ email = $email; password = "Rydex@123" }
    if ($r.Status -ne 200) { throw "login failed for $email (HTTP $($r.Status))" }
    return $r.Body.accessToken
}

$center = @{ lat = 13.0827; lng = 80.2707; address = "Chennai Central" }
$airport = @{ lat = 12.9941; lng = 80.1709; address = "Chennai Airport" }

# ---------------------------------------------------------------- Phase 0
Section "Phase 0 - infrastructure"
foreach ($c in "rydex-postgres-1", "rydex-redis-1", "rydex-kafka-1") {
    $state = (docker inspect -f "{{.State.Health.Status}}" $c 2>$null)
    Check "$c is healthy" ($state -eq "healthy") $state
}
$topics = (docker exec rydex-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --list 2>$null)
foreach ($t in "ride.events", "driver.location", "payment.events", "ride.events.DLT") {
    Check "Kafka topic $t exists" ($topics -contains $t)
}
$postgis = (docker exec rydex-postgres-1 psql -U rydex -d rydex -tAc "SELECT postgis_version()" 2>$null)
Check "PostGIS is installed" ([bool]$postgis) ("$postgis".Trim())

# ---------------------------------------------------------------- Phase 1
Section "Phase 1 - ride-service ($RideUrl)"
$health = Api GET "$RideUrl/actuator/health"
Check "ride-service is UP" ($health.Status -eq 200 -and $health.Body.status -eq "UP")
if ($health.Status -ne 200) {
    Write-Host "ride-service is not running; start it first (README step 4)." -ForegroundColor Yellow
    exit 1
}

$rider = Login "rider1@rydex.dev"
$driverToken = Login "driver01@rydex.dev"
$admin = Login "admin@rydex.dev"
Check "demo users can log in" ($rider -and $driverToken -and $admin)

$me = Api GET "$RideUrl/api/me" $rider
Check "GET /api/me returns the rider" ($me.Body.email -eq "rider1@rydex.dev") "$($me.Body.name), $($me.Body.role)"

$badLogin = Api POST "$RideUrl/api/auth/login" $null @{ email = "rider1@rydex.dev"; password = "wrong-password" }
Check "wrong password is rejected" ($badLogin.Status -eq 401) "HTTP $($badLogin.Status)"

$places = Api GET "$RideUrl/api/places/search?q=Marina+Beach&lat=13.08&lng=80.27" $rider
if ($places.Status -eq 200) {
    Check "place search finds Marina Beach (OpenStreetMap)" (@($places.Body).Count -gt 0) "$(@($places.Body)[0].name)"
} else {
    Write-Host "[WARN] place search returned HTTP $($places.Status) - the free OSM server may be busy; not counted" -ForegroundColor Yellow
}

$est = Api POST "$RideUrl/api/rides/estimate" $rider @{ pickup = $center; drop = $airport }
$fares = @{}
foreach ($o in @($est.Body.options)) { $fares[$o.rideType] = $o.fare }
Check "estimate returns 3 ride types" ($est.Status -eq 200 -and $fares.Count -eq 3) `
    ("{0:N1} km, ECONOMY Rs {1}, COMFORT Rs {2}, XL Rs {3}, route {4}" -f ($est.Body.distanceM / 1000), $fares["ECONOMY"], $fares["COMFORT"], $fares["XL"], $est.Body.route.source)

# a previous run may have left rider1 with an active ride: cancel it first
$mine = Api GET "$RideUrl/api/rides?mine=true&size=20" $rider
foreach ($r in @($mine.Body.items)) {
    if ($r.status -in "REQUESTED", "DRIVER_ASSIGNED", "DRIVER_ARRIVED") {
        Api POST "$RideUrl/api/rides/$($r.id)/cancel" $rider @{ reason = "smoke test cleanup" } | Out-Null
    }
}

$key = [guid]::NewGuid().ToString()
$book = Api POST "$RideUrl/api/rides" $rider @{ pickup = $center; drop = $airport; rideType = "ECONOMY" } @{ "Idempotency-Key" = $key }
Check "booking creates a REQUESTED ride" ($book.Status -eq 201 -and $book.Body.status -eq "REQUESTED") `
    "fare Rs $($book.Body.estFare), OTP $($book.Body.otp)"
$replay = Api POST "$RideUrl/api/rides" $rider @{ pickup = $center; drop = $airport; rideType = "ECONOMY" } @{ "Idempotency-Key" = $key }
Check "same Idempotency-Key returns the same ride" ($replay.Body.id -eq $book.Body.id)
$second = Api POST "$RideUrl/api/rides" $rider @{ pickup = $center; drop = $airport; rideType = "XL" } @{ "Idempotency-Key" = [guid]::NewGuid().ToString() }
Check "a second active ride is refused" ($second.Status -eq 409) "HTTP $($second.Status)"
$cancel = Api POST "$RideUrl/api/rides/$($book.Body.id)/cancel" $rider @{ reason = "smoke test" }
Check "the ride can be cancelled" ($cancel.Body.status -eq "CANCELLED")
$again = Api POST "$RideUrl/api/rides/$($book.Body.id)/cancel" $rider @{ reason = "again" }
Check "cancelling twice is an illegal transition" ($again.Status -eq 409) "HTTP $($again.Status)"
Check "ride-service Swagger UI is up" ((Api GET "$RideUrl/v3/api-docs").Status -eq 200)

# ---------------------------------------------------------------- Phase 2
Section "Phase 2 - driver-service ($DriverUrl)"
$dhealth = Api GET "$DriverUrl/actuator/health"
Check "driver-service is UP" ($dhealth.Status -eq 200 -and $dhealth.Body.status -eq "UP")
if ($dhealth.Status -ne 200) {
    Write-Host "driver-service is not running; start it first (README step 5)." -ForegroundColor Yellow
    exit 1
}

$profile = Api GET "$DriverUrl/api/drivers/me" $driverToken
Check "driver01 profile (token verified via ride-service JWKS)" ($profile.Status -eq 200) `
    "$($profile.Body.vehicleType), $($profile.Body.vehicleModel), $($profile.Body.vehicleNumber), rating $($profile.Body.rating)"
Check "riders cannot use driver endpoints" ((Api GET "$DriverUrl/api/drivers/me" $rider).Status -eq 403)
Check "drivers cannot use admin endpoints" ((Api GET "$DriverUrl/api/admin/drivers/live" $driverToken).Status -eq 403)
Check "requests without a token are refused" ((Api GET "$DriverUrl/api/drivers/me").Status -eq 401)

# driver01 goes online about 650 m from Chennai Central
$online = Api PATCH "$DriverUrl/api/drivers/me/status" $driverToken @{ status = "AVAILABLE"; lat = 13.0870; lng = 80.2750 }
Check "driver01 goes online and is searchable" ($online.Body.status -eq "AVAILABLE" -and $online.Body.searchable)

$grid = Api GET "$DriverUrl/api/admin/grid?lat=13.0827&lng=80.2707" $admin
Check "admin grid shows 9 geohash cells with the online driver" (@($grid.Body.cells).Count -eq 9 -and $grid.Body.availableDrivers -ge 1) `
    "centre $($grid.Body.center), $($grid.Body.availableDrivers) driver(s) available"

$cands = Api GET "$DriverUrl/api/admin/matching/candidates?lat=13.0827&lng=80.2707&rideType=ECONOMY" $admin
$first = @($cands.Body)[0]
Check "matching would pick driver01 for an ECONOMY ride here" ($first.driverId -eq $profile.Body.id) `
    "$($first.distanceM) m away, ETA $($first.etaS) s, estimated=$($first.etaEstimated)"
$xl = Api GET "$DriverUrl/api/admin/matching/candidates?lat=13.0827&lng=80.2707&rideType=XL" $admin
Check "an ECONOMY driver is not offered XL rides" (-not (@($xl.Body) | Where-Object { $_.driverId -eq $profile.Body.id }))

$offer = Api GET "$DriverUrl/api/drivers/me/offer" $driverToken
Check "no offer while nobody is booking" ($offer.Status -eq 204) "HTTP $($offer.Status)"

$offline = Api PATCH "$DriverUrl/api/drivers/me/status" $driverToken @{ status = "OFFLINE" }
$after = Api GET "$DriverUrl/api/admin/matching/candidates?lat=13.0827&lng=80.2707&rideType=ECONOMY" $admin
Check "after going offline driver01 is no longer a candidate" ($offline.Body.status -eq "OFFLINE" -and -not (@($after.Body) | Where-Object { $_.driverId -eq $profile.Body.id }))
Check "driver-service Swagger UI is up" ((Api GET "$DriverUrl/v3/api-docs").Status -eq 200)

# ---------------------------------------------------------------- Phase 3
Section "Phase 3 - a full ride over Kafka"
# driver01 waits 50 m from the pickup, so the matcher picks them
$online = Api PATCH "$DriverUrl/api/drivers/me/status" $driverToken @{ status = "AVAILABLE"; lat = 13.0831; lng = 80.2707 }
Check "driver01 is online next to the pickup" ($online.Body.searchable -eq $true)

$book = Api POST "$RideUrl/api/rides" $rider @{ pickup = $center; drop = $airport; rideType = "ECONOMY" } @{ "Idempotency-Key" = [guid]::NewGuid().ToString() }
$rideId = $book.Body.id
$otp = $book.Body.otp
Check "rider1 books an ECONOMY ride" ($book.Status -eq 201) "ride $rideId"

$watch = [System.Diagnostics.Stopwatch]::StartNew()
$gotOffer = WaitFor 30 { $o = Api GET "$DriverUrl/api/drivers/me/offer" $driverToken; $o.Status -eq 200 -and $o.Body.rideId -eq $rideId }
Check "RIDE_REQUESTED reaches driver-service and driver01 gets the offer" $gotOffer ("after {0:N1} s" -f $watch.Elapsed.TotalSeconds)

$accept = Api POST "$DriverUrl/api/drivers/me/offers/$rideId/accept" $driverToken
Check "driver01 accepts" ($accept.Status -eq 200) "HTTP $($accept.Status)"

$watch.Restart()
$assigned = WaitFor 15 { $r = Api GET "$RideUrl/api/rides/$rideId" $rider; $r.Body.status -eq "DRIVER_ASSIGNED" }
$ride = (Api GET "$RideUrl/api/rides/$rideId" $rider).Body
Check "DRIVER_ASSIGNED reaches ride-service: the rider sees driver01" ($assigned -and $ride.driverId -eq $profile.Body.id) ("after {0:N1} s" -f $watch.Elapsed.TotalSeconds)

$arrived = Api POST "$RideUrl/api/rides/$rideId/arrived" $driverToken
Check "driver01 marks arrival" ($arrived.Body.status -eq "DRIVER_ARRIVED")
$wrongOtp = if ($otp -eq "0000") { "1111" } else { "0000" }
Check "a wrong OTP does not start the trip" ((Api POST "$RideUrl/api/rides/$rideId/start" $driverToken @{ otp = $wrongOtp }).Status -eq 422)
$started = Api POST "$RideUrl/api/rides/$rideId/start" $driverToken @{ otp = $otp }
Check "the rider's OTP starts the trip" ($started.Body.status -eq "IN_PROGRESS")
$completed = Api POST "$RideUrl/api/rides/$rideId/complete" $driverToken
Check "driver01 completes the trip" ($completed.Body.status -eq "COMPLETED") "fare Rs $($completed.Body.finalFare)"

$watch.Restart()
$freed = WaitFor 15 { (Api GET "$DriverUrl/api/drivers/me" $driverToken).Body.status -eq "AVAILABLE" }
Check "RIDE_COMPLETED reaches driver-service: driver01 is AVAILABLE again" $freed ("after {0:N1} s" -f $watch.Elapsed.TotalSeconds)
$final = (Api GET "$RideUrl/api/rides/$rideId" $rider).Body
$timeline = @($final.timeline | ForEach-Object { $_.to })
Check "the ride's timeline has every step" (($timeline -join ",") -eq "REQUESTED,DRIVER_ASSIGNED,DRIVER_ARRIVED,IN_PROGRESS,COMPLETED") ($timeline -join " > ")

# cancellation while the offer is open
Api POST "$DriverUrl/api/drivers/me/location" $driverToken @{ lat = 13.0831; lng = 80.2707 } | Out-Null
$book2 = Api POST "$RideUrl/api/rides" $rider @{ pickup = $center; drop = $airport; rideType = "ECONOMY" } @{ "Idempotency-Key" = [guid]::NewGuid().ToString() }
$ride2 = $book2.Body.id
$gotOffer2 = WaitFor 30 { $o = Api GET "$DriverUrl/api/drivers/me/offer" $driverToken; $o.Status -eq 200 -and $o.Body.rideId -eq $ride2 }
Check "a second booking is offered to driver01" $gotOffer2
Api POST "$RideUrl/api/rides/$ride2/cancel" $rider @{ reason = "smoke test: changed plans" } | Out-Null
$withdrawn = WaitFor 15 { (Api GET "$DriverUrl/api/drivers/me/offer" $driverToken).Status -eq 204 }
Check "RIDE_CANCELLED reaches driver-service: the offer is withdrawn" $withdrawn
$job = Api GET "$DriverUrl/api/admin/matching/$ride2" $admin
Check "the matching job is CANCELLED" ($job.Body.status -eq "CANCELLED") "offers: $(@($job.Body.offers).Count)"

# admin recovery for a driver stuck on a trip (ADR 0010); driver01 is not on one, so nothing to release
$driverId = (Api GET "$DriverUrl/api/drivers/me" $driverToken).Body.id
$release = "$DriverUrl/api/admin/drivers/$driverId/release"
Check "only admins can release a driver" ((Api POST $release $driverToken).Status -eq 403)
Check "releasing a driver who is not on a trip is refused (409)" ((Api POST $release $admin).Status -eq 409)

Api PATCH "$DriverUrl/api/drivers/me/status" $driverToken @{ status = "OFFLINE" } | Out-Null

# ---------------------------------------------------------------- summary
Write-Host ""
$color = if ($script:failed -eq 0) { "Green" } else { "Red" }
Write-Host ("{0} passed, {1} failed" -f $script:passed, $script:failed) -ForegroundColor $color
if ($script:failed -gt 0) { exit 1 }
