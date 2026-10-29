param(
    [int]$webPort = 8080,
    [int]$udpPort = 5001,
    [string]$peerHost = "127.0.0.1",
    [int]$peerPort = 5000,
    [string]$mode = "A"
)

$java = "java"
if (Test-Path "C:\Program Files\Java\jdk-24\bin\java.exe") {
    $java = "C:\Program Files\Java\jdk-24\bin\java.exe"
}

if (-not (Test-Path "bin\nexus\demo\Main.class")) {
    Write-Host "Binaries not found. Building project first..." -ForegroundColor Yellow
    & .\build.ps1
}

& $java -cp bin nexus.demo.Main --web-port $webPort --udp-port $udpPort --peer-ip $peerHost --peer-port $peerPort --mode $mode

