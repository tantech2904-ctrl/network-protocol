@echo off
setlocal

set "JAVA=java"
if exist "C:\Program Files\Java\jdk-24\bin\java.exe" (
    set "JAVA=C:\Program Files\Java\jdk-24\bin\java.exe"
)

if not exist bin\nexus\demo\ReliabilityDemoMain.class (
    echo Building project first...
    call build.bat
    if errorlevel 1 exit /b 1
)

"%JAVA%" -cp bin nexus.demo.ReliabilityDemoMain

