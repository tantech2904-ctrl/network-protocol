param(
    [int]$localPort = 5000,
    [string]$remoteHost = "127.0.0.1",
    [int]$remotePort = 5001
)

$java = "java"
if (Test-Path "C:\Program Files\Java\jdk-24\bin\java.exe") {
    $java = "C:\Program Files\Java\jdk-24\bin\java.exe"
}

if (-not (Test-Path "bin\nexus\demo\PeerNodeMain.class")) {
    Write-Host "Binaries not found. Building project first..." -ForegroundColor Yellow
    & .\build.ps1
}

& $java -cp bin nexus.demo.PeerNodeMain --local-port $localPort --remote-host $remoteHost --remote-port $remotePort

