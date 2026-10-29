$ErrorActionPreference = "Stop"

Write-Host "========================================================================" -ForegroundColor Cyan
Write-Host " NEXUS - Building and Testing System" -ForegroundColor Cyan
Write-Host "========================================================================" -ForegroundColor Cyan

$javac = "javac"
$java = "java"
if (Test-Path "C:\Program Files\Java\jdk-24\bin\javac.exe") {
    $javac = "C:\Program Files\Java\jdk-24\bin\javac.exe"
    $java = "C:\Program Files\Java\jdk-24\bin\java.exe"
}

if (-not (Test-Path "bin")) { New-Item -ItemType Directory -Path "bin" | Out-Null }
if (-not (Test-Path "bin\static")) { New-Item -ItemType Directory -Path "bin\static" | Out-Null }

Copy-Item "nexus\src\main\resources\static\*" "bin\static\" -Force

Write-Host "[1/2] Compiling Java sources and test suites..." -ForegroundColor Yellow

$sources = Get-ChildItem -Path "nexus\src\main\java", "nexus\src\test\java" -Filter *.java -Recurse | ForEach-Object { "`"$($_.FullName)`"" }

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $javac
$psi.Arguments = "-encoding UTF-8 -d bin " + ($sources -join " ")
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$proc = [System.Diagnostics.Process]::Start($psi)
$proc.WaitForExit()

if ($proc.ExitCode -ne 0) {
    Write-Host "[ERROR] Compilation failed:" -ForegroundColor Red
    Write-Host ($proc.StandardError.ReadToEnd())
    exit 1
}
Write-Host "[OK] Compilation successful." -ForegroundColor Green

Write-Host "`n[2/2] Running automated test suite..." -ForegroundColor Yellow
$psiTest = New-Object System.Diagnostics.ProcessStartInfo
$psiTest.FileName = $java
$psiTest.Arguments = "-cp bin nexus.test.TestRunner"
$psiTest.RedirectStandardOutput = $true
$psiTest.RedirectStandardError = $true
$psiTest.UseShellExecute = $false
$procTest = [System.Diagnostics.Process]::Start($psiTest)
$procTest.WaitForExit()

Write-Host ($procTest.StandardOutput.ReadToEnd())
if ($procTest.ExitCode -ne 0) {
    Write-Host ($procTest.StandardError.ReadToEnd())
    Write-Host "[ERROR] Test suite failed." -ForegroundColor Red
    exit 1
}

Write-Host "========================================================================" -ForegroundColor Cyan
Write-Host " Build and Tests Successful! Run .\run.ps1 or run.bat to start." -ForegroundColor Green
Write-Host "========================================================================" -ForegroundColor Cyan

