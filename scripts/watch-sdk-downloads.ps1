# Watchdog for Android SDK / Gradle downloads: resume if stalled, truncated, or failed
$tmp = "$env:TEMP\androidsdk"
$log = "$tmp\watchdog.log"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

$packages = @(
  @{ name = "platform34"; url = "https://dl.google.com/android/repository/platform-34-ext7_r03.zip"; out = "$tmp\platform34.zip"; minBytes = 50000000 },
  @{ name = "buildtools34"; url = "https://dl.google.com/android/repository/build-tools_r34-windows.zip"; out = "$tmp\buildtools34.zip"; minBytes = 40000000 },
  @{ name = "platform-tools"; url = "https://dl.google.com/android/repository/platform-tools_r34.0.5-windows.zip"; out = "$tmp\platform-tools.zip"; minBytes = 5000000 },
  @{ name = "ndk"; url = "https://dl.google.com/android/repository/android-ndk-r26b-windows.zip"; out = "$tmp\ndk.zip"; minBytes = 500000000 },
  @{ name = "cmake"; url = "https://dl.google.com/android/repository/cmake-3.22.1-windows.zip"; out = "$tmp\cmake.zip"; minBytes = 10000000 },
  @{ name = "gradle84"; url = "https://services.gradle.org/distributions/gradle-8.4-bin.zip"; out = "$tmp\gradle-8.4-bin.zip"; minBytes = 120000000 }
)

function Write-Log($msg) {
  $line = "$(Get-Date -Format 'HH:mm:ss') $msg"
  Add-Content -Path $log -Value $line
  Write-Host $line
}

function Get-CurlPidFor($outFile) {
  Get-CimInstance Win32_Process -Filter "Name='curl.exe'" -EA SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine.Contains($outFile) } |
    Select-Object -ExpandProperty ProcessId -First 1
}

function Get-RemoteContentLength($url) {
  try {
    $r = curl.exe -sI -L --ssl-no-revoke --connect-timeout 20 --max-time 40 $url 2>$null
    $line = ($r | Where-Object { $_ -match '(?i)^content-length:\s*(\d+)' } | Select-Object -Last 1)
    if ($line -match '(?i)^content-length:\s*(\d+)') { return [long]$Matches[1] }
  } catch {}
  return $null
}

function Ensure-Download($pkg) {
  $out = $pkg.out
  $size = if (Test-Path $out) { (Get-Item $out).Length } else { 0 }

  # Prefer exact Content-Length when known; fall back to minBytes
  $expected = $pkg.expectedBytes
  if (-not $expected) {
    $expected = Get-RemoteContentLength $pkg.url
    if ($expected -and $expected -gt 0) { $pkg.expectedBytes = $expected }
  }
  $target = if ($expected -and $expected -gt 0) { $expected } else { $pkg.minBytes }

  if ($size -ge $target -and $size -ge $pkg.minBytes) {
    # Extra sanity: tiny files that somehow pass minBytes (corrupt HTML/error pages)
    if ($size -lt $pkg.minBytes) {
      Write-Log "BAD $($pkg.name) size=$size < minBytes=$($pkg.minBytes) — redownload"
    } else {
      Write-Log "OK $($pkg.name) size=$size target=$target"
      return $true
    }
  } elseif ($expected -and $size -gt 0 -and $size -lt $expected) {
    Write-Log "TRUNCATED $($pkg.name) size=$size / expected=$expected"
  }

  $curlPid = Get-CurlPidFor $out
  if ($curlPid) {
    $prev = $size
    Start-Sleep -Seconds 45
    $now = if (Test-Path $out) { (Get-Item $out).Length } else { 0 }
    if ($now -gt $prev) {
      Write-Log "PROGRESS $($pkg.name) $prev -> $now (pid=$curlPid)"
      return $false
    }
    Start-Sleep -Seconds 45
    $now2 = if (Test-Path $out) { (Get-Item $out).Length } else { 0 }
    if ($now2 -gt $prev) {
      Write-Log "PROGRESS $($pkg.name) $prev -> $now2 (pid=$curlPid)"
      return $false
    }
    Write-Log "STALL $($pkg.name) at $now2 — killing curl $curlPid and resuming"
    Stop-Process -Id $curlPid -Force -EA SilentlyContinue
    Start-Sleep -Seconds 2
  } else {
    Write-Log "MISSING/INCOMPLETE $($pkg.name) size=$size target=$target — starting/resuming download"
  }

  $args = @(
    "-L", "--ssl-no-revoke", "--connect-timeout", "60",
    "--retry", "20", "--retry-delay", "5", "--retry-all-errors",
    "-C", "-", "-o", $out, $pkg.url
  )
  Start-Process -FilePath "curl.exe" -ArgumentList $args -WindowStyle Hidden
  Write-Log "STARTED curl for $($pkg.name)"
  return $false
}

Write-Log "WATCHDOG START"
$deadline = (Get-Date).AddHours(2)
while ((Get-Date) -lt $deadline) {
  $allOk = $true
  foreach ($p in $packages) {
    if (-not (Ensure-Download $p)) { $allOk = $false }
  }
  if ($allOk) {
    Write-Log "ALL DOWNLOADS COMPLETE"
    Set-Content -Path "$tmp\DOWNLOADS_COMPLETE" -Value (Get-Date).ToString("o")
    break
  }
  Start-Sleep -Seconds 30
}
if (-not (Test-Path "$tmp\DOWNLOADS_COMPLETE")) {
  Write-Log "WATCHDOG TIMEOUT"
  exit 1
}
Write-Log "WATCHDOG DONE"
