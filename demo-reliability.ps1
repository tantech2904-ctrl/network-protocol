$ErrorActionPreference = "Stop"

$java = "java"
if (Test-Path "C:\Program Files\Java\jdk-24\bin\java.exe") {
    $java = "C:\Program Files\Java\jdk-24\bin\java.exe"
}

if (-not (Test-Path "bin\nexus\demo\ReliabilityDemoMain.class")) {
    Write-Host "Building project first..." -ForegroundColor Yellow
    & .\build.ps1
}

& $java -cp bin nexus.demo.ReliabilityDemoMain

