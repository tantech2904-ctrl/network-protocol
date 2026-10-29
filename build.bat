@echo off
setlocal

echo ========================================================================
echo  NEXUS - Building and Testing System
echo ========================================================================

set "JAVAC=javac"
set "JAVA=java"

if exist "C:\Program Files\Java\jdk-24\bin\javac.exe" (
    set "JAVAC=C:\Program Files\Java\jdk-24\bin\javac.exe"
    set "JAVA=C:\Program Files\Java\jdk-24\bin\java.exe"
)

if not exist bin mkdir bin
if not exist bin\static mkdir bin\static

copy /y "nexus\src\main\resources\static\*" "bin\static\" >nul

echo [1/2] Compiling Java sources and test suites...
powershell -NoProfile -ExecutionPolicy Bypass -Command "& { $sources = Get-ChildItem -Path 'nexus\src\main\java', 'nexus\src\test\java' -Filter *.java -Recurse | ForEach-Object { '\"' + $_.FullName + '\"' }; & '%JAVAC%' -encoding UTF-8 -d bin ($sources -split ' ') }"

if errorlevel 1 (
    echo [ERROR] Compilation failed.
    exit /b 1
)
echo [OK] Compilation successful.

echo.
echo [2/2] Running automated test suite...
"%JAVA%" -cp bin nexus.test.TestRunner
if errorlevel 1 (
    echo [ERROR] Test suite failed.
    exit /b 1
)

echo.
echo ========================================================================
echo  Build and Tests Successful! Run 'run.bat' to start the server.
echo ========================================================================

