# Rydex smoke test (Windows PowerShell 5.1+ or PowerShell 7). Checks everything built so far
# against the running local stack:
#   Phase 0  infra containers + Kafka topics
#   Phase 1  ride-service: login, places, estimate, booking, idempotency, cancel
#   Phase 2  driver-service: driver profile, go online, grid, candidate ranking, roles
#   Phase 3  a full ride across services over Kafka: book -> offer -> accept -> assigned ->
#            arrived -> start (OTP) -> complete -> driver free again; and a cancelled offer
#   Phase 4  the same ride live over WebSockets (STOMP): offer pushed to the driver, accepted over
#            the socket, notifications to the rider, the driver's position streamed with an ETA
#   Phase 5  payments: the fare charged on completion, a fee for cancelling after the driver
#            arrived, an admin refund, and the rider notified at each step
#   Phase 6  what the web app reads besides that: the rider's driver card, driving routes, admin
#            KPIs and demand; and, if the frontend dev server runs, its proxy to every service
#   Audit    encrypted accounts, password rules, lockout, admin rides in flight, trip paths
#   City     the living city (ADR 0016): nearby cars without driver ids, NPC rides flagged
# Stop the simulator first: its drivers can win this test's offers.
#
# Prerequisites: `docker compose -f infra/docker-compose.yml up -d`, then ride-service (:8081),
# driver-service (:8082), tracking-service (:8083) and payment-service (:8084) running (see README).
# Usage, from the repo root:
#   powershell -ExecutionPolicy Bypass -File infra\scripts\smoke-test.ps1
# With payment-service in Razorpay test mode, add -PayInBrowser to pay the fee in a browser
# (Netbanking, any bank, then Success; or a Razorpay test card) and also check the refund.
# Avoid Wallet: Razorpay test mode refused to refund a wallet payment.

param(
    [string]$RideUrl = "http://localhost:8081",
    [string]$DriverUrl = "http://localhost:8082",
    [string]$TrackingUrl = "http://localhost:8083",
    [string]$PaymentUrl = "http://localhost:8084",
    [string]$FrontendUrl = "http://localhost:5173",
    [switch]$PayInBrowser
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

# ---- a minimal STOMP 1.2 client over .NET's ClientWebSocket (no modules needed)
# A connection is a hashtable: the socket, one pending receive (cancelling a receive would abort
# the socket on Windows PowerShell, so it is kept for the next call), and frames not yet consumed.
function StompConnect([string]$url, [string]$token) {
    $ws = New-Object System.Net.WebSockets.ClientWebSocket
    $conn = @{ Ws = $ws; Pending = $null; RecvBuf = $null; Buffer = ""; Frames = (New-Object System.Collections.ArrayList); Connected = $false }
    # [void]: these tasks are Task<VoidTaskResult> at run time, and PowerShell would return their results
    try { [void]$ws.ConnectAsync([Uri]$url, [Threading.CancellationToken]::None).GetAwaiter().GetResult() } catch { return $conn }
    $headers = [ordered]@{ "accept-version" = "1.2"; "host" = "localhost"; "heart-beat" = "0,0" }
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    StompSend $conn "CONNECT" $headers
    $frame = StompNext $conn 5000 { param($f) $true }
    $conn.Connected = ($null -ne $frame -and $frame.Command -eq "CONNECTED")
    return $conn
}

function StompSend($conn, [string]$command, $headers, [string]$body = "") {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append("$command`n")
    foreach ($k in $headers.Keys) { [void]$sb.Append("${k}:$($headers[$k])`n") }
    [void]$sb.Append("`n").Append($body).Append([char]0)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($sb.ToString())
    try {
        [void]$conn.Ws.SendAsync([System.ArraySegment[byte]]::new($bytes), [System.Net.WebSockets.WebSocketMessageType]::Text, $true,
            [Threading.CancellationToken]::None).GetAwaiter().GetResult()
    } catch { }
}

function StompSubscribe($conn, [string]$destination) {
    $id = "sub-" + [guid]::NewGuid().ToString("N").Substring(0, 8)
    StompSend $conn "SUBSCRIBE" ([ordered]@{ "id" = $id; "destination" = $destination })
    Start-Sleep -Milliseconds 300 # the broker registers subscriptions asynchronously
    return $id
}

function StompSendJson($conn, [string]$destination, $payload) {
    StompSend $conn "SEND" ([ordered]@{ "destination" = $destination; "content-type" = "application/json" }) ($payload | ConvertTo-Json -Compress)
}

function ParseFrame([string]$raw) {
    $split = $raw.IndexOf("`n`n")
    $head = if ($split -ge 0) { $raw.Substring(0, $split) } else { $raw }
    $body = if ($split -ge 0) { $raw.Substring($split + 2) } else { "" }
    $lines = $head -split "`n"
    $headers = @{}
    for ($i = 1; $i -lt $lines.Count; $i++) {
        $idx = $lines[$i].IndexOf(":")
        if ($idx -gt 0) {
            $key = $lines[$i].Substring(0, $idx)
            if (-not $headers.ContainsKey($key)) { $headers[$key] = $lines[$i].Substring($idx + 1).TrimEnd("`r") }
        }
    }
    $json = $null
    if ($body) { try { $json = $body | ConvertFrom-Json } catch { } }
    return @{ Command = $lines[0].Trim(); Headers = $headers; Json = $json }
}

# The next frame for which $predicate returns $true, within $timeoutMs; $null on timeout or close.
# (Script blocks see their caller's variables, so this function must not use the names the
# blocks passed to it rely on, such as $match and $subId in StompMessage.)
function StompNext($conn, [int]$timeoutMs, [scriptblock]$predicate) {
    $deadline = (Get-Date).AddMilliseconds($timeoutMs)
    while ($true) {
        for ($i = 0; $i -lt $conn.Frames.Count; $i++) {
            $f = $conn.Frames[$i]
            if (& $predicate $f) { $conn.Frames.RemoveAt($i); return $f }
        }
        $left = [int]($deadline - (Get-Date)).TotalMilliseconds
        if ($left -le 0) { return $null }
        if ($null -eq $conn.Pending) {
            if ($conn.Ws.State -ne [System.Net.WebSockets.WebSocketState]::Open) { return $null }
            $conn.RecvBuf = New-Object byte[] 65536
            $conn.Pending = $conn.Ws.ReceiveAsync([System.ArraySegment[byte]]::new($conn.RecvBuf), [Threading.CancellationToken]::None)
        }
        try { $done = $conn.Pending.Wait([Math]::Min($left, 500)) } catch { $conn.Pending = $null; return $null }
        if (-not $done) { continue }
        $result = $conn.Pending.Result
        $conn.Pending = $null
        if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) { continue }
        $conn.Buffer += [System.Text.Encoding]::UTF8.GetString($conn.RecvBuf, 0, $result.Count)
        while (($nul = $conn.Buffer.IndexOf([char]0)) -ge 0) {
            $raw = $conn.Buffer.Substring(0, $nul).TrimStart("`r", "`n")
            $conn.Buffer = $conn.Buffer.Substring($nul + 1)
            if ($raw) { [void]$conn.Frames.Add((ParseFrame $raw)) }
        }
    }
}

# The next MESSAGE on subscription $subId whose JSON body satisfies $match.
function StompMessage($conn, [string]$subId, [int]$timeoutMs, [scriptblock]$match = { param($j) $true }) {
    return StompNext $conn $timeoutMs { param($f) $f.Command -eq "MESSAGE" -and $f.Headers["subscription"] -eq $subId -and (& $match $f.Json) }
}

function StompClose($conn) {
    try { $conn.Ws.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, "", [Threading.CancellationToken]::None).Wait(2000) | Out-Null } catch { }
    $conn.Ws.Dispose()
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

# ---------------------------------------------------------------- Phase 4
Section "Phase 4 - live tracking over WebSocket ($TrackingUrl)"
$thealth = Api GET "$TrackingUrl/actuator/health"
Check "tracking-service is UP" ($thealth.Status -eq 200 -and $thealth.Body.status -eq "UP")
if ($thealth.Status -ne 200) {
    Write-Host "tracking-service is not running; start it first (README step 6)." -ForegroundColor Yellow
    exit 1
}
$wsUrl = ($TrackingUrl -replace "^http", "ws") + "/ws"

$anon = StompConnect $wsUrl $null
Check "a WebSocket without a token is refused" (-not $anon.Connected)
StompClose $anon

$dws = StompConnect $wsUrl $driverToken
$rws = StompConnect $wsUrl $rider
Check "driver01 and rider1 connect with their access tokens" ($dws.Connected -and $rws.Connected)
$offersSub = StompSubscribe $dws "/user/queue/offers"
$driverNotes = StompSubscribe $dws "/user/queue/notifications"
$riderNotes = StompSubscribe $rws "/user/queue/notifications"

$online = Api PATCH "$DriverUrl/api/drivers/me/status" $driverToken @{ status = "AVAILABLE"; lat = 13.0831; lng = 80.2707 }
Check "driver01 is online next to the pickup" ($online.Body.searchable -eq $true)

$book3 = Api POST "$RideUrl/api/rides" $rider @{ pickup = $center; drop = $airport; rideType = "ECONOMY" } @{ "Idempotency-Key" = [guid]::NewGuid().ToString() }
$ride3 = $book3.Body.id
$otp3 = $book3.Body.otp
$watch.Restart()
$pushed = StompMessage $dws $offersSub 30000 { param($j) $j.type -eq "OFFER" -and $j.rideId -eq $ride3 }
Check "the offer is pushed to driver01's socket" ($null -ne $pushed) ("after {0:N1} s, {1} s to answer, pickup ETA {2} s" -f $watch.Elapsed.TotalSeconds, $pushed.Json.offer.secondsLeft, $pushed.Json.offer.pickupEtaS)
$requested = StompMessage $rws $riderNotes 15000 { param($j) $j.type -eq "RIDE_REQUESTED" -and $j.rideId -eq $ride3 }
Check "rider1 is notified: finding a driver" ($null -ne $requested) "$($requested.Json.title)"

StompSendJson $dws "/app/offer.respond" @{ rideId = $ride3; accept = $true }
$answer = StompMessage $dws $offersSub 15000 { param($j) $j.type -eq "OFFER_RESPONSE" -and $j.rideId -eq $ride3 }
Check "driver01 accepts over the socket" ($answer.Json.ok -eq $true) "HTTP $($answer.Json.status) from driver-service"
$watch.Restart()
$assignedNote = StompMessage $rws $riderNotes 15000 { param($j) $j.type -eq "DRIVER_ASSIGNED" -and $j.rideId -eq $ride3 }
Check "rider1 is notified: driver on the way" ($assignedNote.Json.driverId -eq $profile.Body.id) ("after {0:N1} s: {1}" -f $watch.Elapsed.TotalSeconds, $assignedNote.Json.title)
$driverAssigned = StompMessage $dws $driverNotes 15000 { param($j) $j.type -eq "DRIVER_ASSIGNED" -and $j.rideId -eq $ride3 }
Check "driver01 is notified: ride assigned" ($null -ne $driverAssigned)

$liveSub = StompSubscribe $rws "/topic/rides/$ride3/location"
$rider2 = Login "rider2@rydex.dev"
$snoop = StompConnect $wsUrl $rider2
[void](StompSubscribe $snoop "/topic/rides/$ride3/location")
$refused = StompNext $snoop 5000 { param($f) $f.Command -eq "ERROR" }
Check "another rider cannot watch this ride" ($null -ne $refused) "$($refused.Headers['message'])"
StompClose $snoop

StompSendJson $dws "/app/driver.location" @{ lat = 13.0850; lng = 80.2712; heading = 180; speed = 7.5 }
$watch.Restart()
$live = StompMessage $rws $liveSub 10000 { param($j) $j.rideId -eq $ride3 }
Check "rider1 sees driver01 move, with an ETA" ($live.Json.phase -eq "TO_PICKUP" -and $live.Json.etaS -ge 0) `
    ("after {0:N0} ms: {1} m away, ETA {2} s{3}" -f $watch.Elapsed.TotalMilliseconds, $live.Json.distanceM, $live.Json.etaS, $(if ($live.Json.etaEstimated) { " (estimated)" } else { " (OSRM)" }))
$restLoc = Api GET "$TrackingUrl/api/tracking/rides/$ride3/location" $rider
Check "the same position over REST (for screens opened mid-ride)" ($restLoc.Status -eq 200 -and $restLoc.Body.lat -eq 13.085)

# marking arrival needs the car near the pickup (ride-service checks the last position)
Start-Sleep -Milliseconds 600
Api POST "$DriverUrl/api/drivers/me/location" $driverToken @{ lat = 13.0828; lng = 80.2707; heading = 180; speed = 0 } | Out-Null
$early = Api POST "$RideUrl/api/rides/$ride3/arrived" $driverToken
Check "driver01 can mark arrival once at the pickup" ($early.Body.status -eq "DRIVER_ARRIVED") "HTTP $($early.Status) $($early.Body.detail)"
$arrivedNote = StompMessage $rws $riderNotes 15000 { param($j) $j.type -eq "DRIVER_ARRIVED" -and $j.rideId -eq $ride3 }
Check "rider1 is notified: driver has arrived" ($null -ne $arrivedNote) "$($arrivedNote.Json.message)"
Api POST "$RideUrl/api/rides/$ride3/start" $driverToken @{ otp = $otp3 } | Out-Null
$started = StompMessage $rws $riderNotes 15000 { param($j) $j.type -eq "RIDE_STARTED" -and $j.rideId -eq $ride3 }
StompSendJson $dws "/app/driver.location" @{ lat = 13.0700; lng = 80.2400; heading = 220; speed = 11 }
$watch.Restart()
$toDrop = StompMessage $rws $liveSub 10000 { param($j) $j.phase -eq "TO_DROP" }
Check "during the trip the ETA is to the drop" ($null -ne $started -and $null -ne $toDrop) `
    ("after {0:N0} ms: {1} m to go, ETA {2} s" -f $watch.Elapsed.TotalMilliseconds, $toDrop.Json.distanceM, $toDrop.Json.etaS)
Api POST "$RideUrl/api/rides/$ride3/complete" $driverToken | Out-Null
$doneNote = StompMessage $rws $riderNotes 15000 { param($j) $j.type -eq "RIDE_COMPLETED" -and $j.rideId -eq $ride3 }
Check "rider1 is notified: trip completed" ($null -ne $doneNote) "$($doneNote.Json.message)"
$hasPath = WaitFor 15 { @((Api GET "$TrackingUrl/api/tracking/rides/$ride3/path" $rider).Body).Count -ge 1 }
Check "the trip's path was recorded (driver.location -> history)" $hasPath
Check "tracking-service Swagger UI is up" ((Api GET "$TrackingUrl/v3/api-docs").Status -eq 200)

StompClose $dws
StompClose $rws
[void](WaitFor 15 { (Api GET "$DriverUrl/api/drivers/me" $driverToken).Body.status -eq "AVAILABLE" })
Api PATCH "$DriverUrl/api/drivers/me/status" $driverToken @{ status = "OFFLINE" } | Out-Null

# ---------------------------------------------------------------- Phase 5
Section "Phase 5 - payments ($PaymentUrl)"
$phealth = Api GET "$PaymentUrl/actuator/health"
$provider = (Api GET "$PaymentUrl/api/payments/config").Body.provider
Check "payment-service is UP" ($phealth.Status -eq 200 -and $phealth.Body.status -eq "UP") "provider $provider"
if ($phealth.Status -ne 200) {
    Write-Host "payment-service is not running; start it first (README step 7)." -ForegroundColor Yellow
    exit 1
}
$mock = $provider -eq "MOCK"

# the fare of the Phase 3 ride
$watch.Restart()
$hasFare = WaitFor 20 { (Api GET "$PaymentUrl/api/payments/rides/$rideId" $rider).Status -eq 200 }
$fare = (Api GET "$PaymentUrl/api/payments/rides/$rideId" $rider).Body
Check "RIDE_COMPLETED reaches payment-service: the fare is charged" ($hasFare -and $fare.kind -eq "RIDE_FARE" -and $fare.amount -eq $completed.Body.finalFare) ("Rs {0} after {1:N1} s" -f $fare.amount, $watch.Elapsed.TotalSeconds)
if ($mock) {
    $farePaid = WaitFor 15 { (Api GET "$PaymentUrl/api/payments/rides/$rideId" $rider).Body.status -eq "SUCCEEDED" }
    Check "the mock provider takes the payment" $farePaid
    $rideSeesIt = WaitFor 15 { (Api GET "$RideUrl/api/rides/$rideId" $rider).Body.paymentStatus -eq "SUCCEEDED" }
    Check "PAYMENT_SUCCEEDED reaches ride-service: the ride shows it paid" $rideSeesIt
} else {
    $ordered = WaitFor 20 { $null -ne (Api GET "$PaymentUrl/api/payments/rides/$rideId" $rider).Body.checkout }
    $fare = (Api GET "$PaymentUrl/api/payments/rides/$rideId" $rider).Body
    Check "a Razorpay test-mode order awaits rider1" ($ordered -and $fare.checkout.orderId -like "order_*" -and $fare.checkout.keyId -like "rzp_test_*") "$($fare.checkout.orderId), $($fare.checkout.amount) paise"
    $rideSeesIt = WaitFor 15 { (Api GET "$RideUrl/api/rides/$rideId" $rider).Body.paymentStatus -eq "PENDING" }
    Check "PAYMENT_INITIATED reaches ride-service: the ride shows it unpaid" $rideSeesIt
}
Check "driver01 sees the payment for their ride" ((Api GET "$PaymentUrl/api/payments/rides/$rideId" $driverToken).Status -eq 200)
Check "another rider cannot see it (404)" ((Api GET "$PaymentUrl/api/payments/rides/$rideId" $rider2).Status -eq 404)

# a late cancellation: the driver has arrived, the rider cancels and pays the fee; an admin refunds it
$rws = StompConnect $wsUrl $rider
$notes = StompSubscribe $rws "/user/queue/notifications"
Api PATCH "$DriverUrl/api/drivers/me/status" $driverToken @{ status = "AVAILABLE"; lat = 13.0831; lng = 80.2707 } | Out-Null
$book5 = Api POST "$RideUrl/api/rides" $rider @{ pickup = $center; drop = $airport; rideType = "ECONOMY" } @{ "Idempotency-Key" = [guid]::NewGuid().ToString() }
$ride5 = $book5.Body.id
[void](WaitFor 30 { $o = Api GET "$DriverUrl/api/drivers/me/offer" $driverToken; $o.Status -eq 200 -and $o.Body.rideId -eq $ride5 })
Api POST "$DriverUrl/api/drivers/me/offers/$ride5/accept" $driverToken | Out-Null
[void](WaitFor 15 { (Api GET "$RideUrl/api/rides/$ride5" $rider).Body.status -eq "DRIVER_ASSIGNED" })
Api POST "$RideUrl/api/rides/$ride5/arrived" $driverToken | Out-Null
$cancel5 = Api POST "$RideUrl/api/rides/$ride5/cancel" $rider @{ reason = "smoke test: changed plans" }
Check "cancelling after the driver arrived costs a fee" ($cancel5.Body.status -eq "CANCELLED" -and $cancel5.Body.cancellationFee -gt 0) "Rs $($cancel5.Body.cancellationFee)"
$feeNote = StompMessage $rws $notes 15000 { param($j) $j.type -eq "RIDE_CANCELLED" -and $j.rideId -eq $ride5 }
Check "rider1 is told about the fee" ($feeNote.Json.message -like "*cancellation fee*") "$($feeNote.Json.message)"
$hasFee = WaitFor 20 { (Api GET "$PaymentUrl/api/payments/rides/$ride5" $rider).Status -eq 200 }
$fee = (Api GET "$PaymentUrl/api/payments/rides/$ride5" $rider).Body
Check "RIDE_CANCELLED reaches payment-service: the fee is charged" ($hasFee -and $fee.kind -eq "CANCELLATION_FEE" -and $fee.amount -eq $cancel5.Body.cancellationFee)

$feePaid = $false
if ($mock) {
    $paidNote = StompMessage $rws $notes 15000 { param($j) $j.type -eq "PAYMENT_SUCCEEDED" -and $j.rideId -eq $ride5 }
    Check "rider1 is notified: cancellation fee paid" ($null -ne $paidNote) "$($paidNote.Json.message)"
    $feePaid = $null -ne $paidNote
} else {
    $dueNote = StompMessage $rws $notes 20000 { param($j) $j.type -eq "PAYMENT_INITIATED" -and $j.rideId -eq $ride5 }
    Check "rider1 is notified: payment due (Razorpay Checkout)" ($null -ne $dueNote) "$($dueNote.Json.message)"
    $checkoutUrl = "$PaymentUrl/dev/checkout.html#ride=$ride5&token=$rider"
    if ($PayInBrowser) {
        Write-Host "Opening the test checkout: pay with Netbanking (any bank, then Success) or a test card, not Wallet (waiting up to 5 minutes)..." -ForegroundColor Yellow
        Start-Process $checkoutUrl
        $paidNote = StompMessage $rws $notes 300000 { param($j) $j.type -eq "PAYMENT_SUCCEEDED" -and $j.rideId -eq $ride5 }
        Check "rider1 paid in Razorpay Checkout and is notified" ($null -ne $paidNote) "$($paidNote.Json.message)"
        $feePaid = $null -ne $paidNote
    } else {
        Write-Host "To pay it in Razorpay test mode, re-run with -PayInBrowser (refund checks are skipped until then)." -ForegroundColor Yellow
    }
}

$feeId = $fee.id
Check "only admins can refund (403)" ((Api POST "$PaymentUrl/api/admin/payments/$feeId/refunds" $rider @{ reason = "x" }).Status -eq 403)
if ($feePaid) {
    $refund = Api POST "$PaymentUrl/api/admin/payments/$feeId/refunds" $admin @{ reason = "Smoke test: driver was late" }
    Check "an admin refunds the fee" ($refund.Status -eq 202) "HTTP $($refund.Status)"
    $refundNote = StompMessage $rws $notes 60000 { param($j) $j.type -eq "PAYMENT_REFUNDED" -and $j.rideId -eq $ride5 }
    Check "rider1 is notified: refund issued" ($null -ne $refundNote) "$($refundNote.Json.message)"
    $refunded = WaitFor 15 { (Api GET "$RideUrl/api/rides/$ride5" $rider).Body.paymentStatus -eq "REFUNDED" }
    Check "PAYMENT_REFUNDED reaches ride-service" $refunded
    Check "the fee cannot be refunded twice (409)" ((Api POST "$PaymentUrl/api/admin/payments/$feeId/refunds" $admin @{ reason = "again" }).Status -eq 409)
}
Check "payment-service Swagger UI is up" ((Api GET "$PaymentUrl/v3/api-docs").Status -eq 200)

StompClose $rws

# ---------------------------------------------------------------- Phase 6
Section "Phase 6 - what the web app reads"
$card = Api GET "$DriverUrl/api/drivers/for-ride/$rideId" $rider
Check "rider1 sees the driver card of their ride" ($card.Status -eq 200 -and $card.Body.vehicleNumber -eq $profile.Body.vehicleNumber) "$($card.Body.name), $($card.Body.vehicleModel), $($card.Body.vehicleNumber)"
Check "another rider cannot see it (404)" ((Api GET "$DriverUrl/api/drivers/for-ride/$rideId" $rider2).Status -eq 404)
$route = Api GET "$RideUrl/api/places/route?fromLat=13.0831&fromLng=80.2707&toLat=13.0500&toLng=80.2824" $driverToken
Check "a driving route for the navigation map" ($route.Status -eq 200 -and $route.Body.distanceM -gt 0) "$($route.Body.distanceM) m, $($route.Body.source)"
$stats = Api GET "$RideUrl/api/admin/stats?hours=24" $admin
Check "admin KPIs count today's rides" ($stats.Status -eq 200 -and $stats.Body.requested -ge 3 -and $stats.Body.completed -ge 1) "requested $($stats.Body.requested), completed $($stats.Body.completed), revenue Rs $($stats.Body.revenue)"
$demand = Api GET "$RideUrl/api/admin/demand?hours=24" $admin
Check "admin demand heatmap has cells" ($demand.Status -eq 200 -and @($demand.Body).Count -ge 1) "$(@($demand.Body).Count) cells"
Check "riders cannot read admin KPIs (403)" ((Api GET "$RideUrl/api/admin/stats" $rider).Status -eq 403)

$page = $null
try { $page = Invoke-WebRequest -Uri "$FrontendUrl/" -UseBasicParsing -TimeoutSec 5 } catch { }
if ($page) {
    Check "the web app is served" ($page.Content -match '<div id="root">') $FrontendUrl
    # the dev server routes each /api prefix to its service, as Caddy does in production
    Check "frontend proxy reaches ride-service" ((Api GET "$FrontendUrl/api/me" $rider).Body.email -eq "rider1@rydex.dev")
    Check "frontend proxy reaches driver-service" ((Api GET "$FrontendUrl/api/drivers/me" $driverToken).Status -eq 200)
    Check "frontend proxy reaches tracking-service" ((Api GET "$FrontendUrl/api/tracking/rides/$rideId/path" $rider).Status -eq 200)
    Check "frontend proxy reaches payment-service" ((Api GET "$FrontendUrl/api/payments/config").Status -eq 200)
    Check "frontend proxy sends admin stats to ride-service" ((Api GET "$FrontendUrl/api/admin/stats" $admin).Status -eq 200)
    Check "frontend proxy sends the admin grid to driver-service" ((Api GET "$FrontendUrl/api/admin/grid?lat=13.0827&lng=80.2707" $admin).Status -eq 200)
} else {
    Write-Host "Frontend not running at $FrontendUrl (cd frontend; npm run dev): its checks are skipped." -ForegroundColor Yellow
}

Api PATCH "$DriverUrl/api/drivers/me/status" $driverToken @{ status = "OFFLINE" } | Out-Null

# ---------------------------------------------------------------- audit (ADR 0014, 0015)
Section "Security audit - accounts, trips"
$mixedCase = Api POST "$RideUrl/api/auth/login" $null @{ email = "Rider1@RYDEX.dev"; password = "Rydex@123" }
Check "sign-in finds the encrypted account by any email casing" ($mixedCase.Status -eq 200) "HTTP $($mixedCase.Status)"
$weak = Api POST "$RideUrl/api/auth/register" $null @{ name = "Weak"; email = "weak-$([guid]::NewGuid())@test.rydex.dev"; password = "onlyletters"; role = "RIDER" }
Check "a password without a digit is refused" ($weak.Status -eq 400) "HTTP $($weak.Status)"
$lockEmail = "lockout-$([guid]::NewGuid())@test.rydex.dev"
$lockCodes = @(1..11 | ForEach-Object { (Api POST "$RideUrl/api/auth/login" $null @{ email = $lockEmail; password = "Wrong-pass-$_" }).Status })
Check "ten wrong passwords lock the account (429)" ($lockCodes[9] -eq 401 -and $lockCodes[10] -eq 429) "last two: $($lockCodes[9]), $($lockCodes[10])"
$inFlight = Api GET "$RideUrl/api/rides?mine=false&active=true&size=100" $admin
Check "admins list the rides in flight" ($inFlight.Status -eq 200 -and @($inFlight.Body.items | Where-Object { $_.status -in 'COMPLETED', 'CANCELLED', 'NO_DRIVER_FOUND' }).Count -eq 0) "$($inFlight.Body.total) in flight"
$driverTrip = Api GET "$TrackingUrl/api/tracking/rides/$rideId/path" $driverToken
Check "the driver reads their trip's driven path" ($driverTrip.Status -eq 200) "$(@($driverTrip.Body).Count) points"

# ---------------------------------------------------------------- living city (ADR 0016)
Section "Living city - nearby cars, NPC accounts"
Api PATCH "$DriverUrl/api/drivers/me/status" $driverToken @{ status = "AVAILABLE"; lat = 13.0831; lng = 80.2707 } | Out-Null
$nearby = Api GET "$DriverUrl/api/drivers/nearby?lat=13.0827&lng=80.2707" $rider
$closeBy = @($nearby.Body | Where-Object { $_.distanceM -lt 100 })
Check "a rider sees the free cars around them" ($nearby.Status -eq 200 -and $closeBy.Count -ge 1) "$(@($nearby.Body).Count) cars within 3 km"
Check "... with opaque ids, not who drives them" (($nearby.Body | ConvertTo-Json -Depth 5) -notmatch $profile.Body.id)
Api PATCH "$DriverUrl/api/drivers/me/status" $driverToken @{ status = "OFFLINE" } | Out-Null
# rider60: an NPC account the default city (--city: riders 2-31) leaves alone
$npc = Login "rider60@rydex.dev"
foreach ($open in @((Api GET "$RideUrl/api/rides?mine=true&size=5" $npc).Body.items | Where-Object { $_.status -in 'REQUESTED', 'DRIVER_ASSIGNED' })) {
    Api POST "$RideUrl/api/rides/$($open.id)/cancel" $npc @{ reason = "smoke test" } | Out-Null
}
$npcRide = Api POST "$RideUrl/api/rides" $npc @{ pickup = $center; drop = $airport; rideType = "ECONOMY" } @{ "Idempotency-Key" = [guid]::NewGuid().ToString() }
Check "an NPC rider's ride is marked simulated (paid through the mock provider)" ($npcRide.Status -eq 201 -and $npcRide.Body.simulated -eq $true) "HTTP $($npcRide.Status)"
if ($npcRide.Status -eq 201) { Api POST "$RideUrl/api/rides/$($npcRide.Body.id)/cancel" $npc @{ reason = "smoke test" } | Out-Null }
Check "the manual tester's rides are not" ((Api GET "$RideUrl/api/rides/$rideId" $rider).Body.simulated -eq $false)

# ---------------------------------------------------------------- summary
Write-Host ""
$color = if ($script:failed -eq 0) { "Green" } else { "Red" }
Write-Host ("{0} passed, {1} failed" -f $script:passed, $script:failed) -ForegroundColor $color
if ($script:failed -gt 0) { exit 1 }
