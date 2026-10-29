@echo off
setlocal

where java >nul 2>&1
if %ERRORLEVEL% neq 0 (
    if exist "C:\Program Files\Java\jdk-24\bin\java.exe" (
        set "JAVA=C:\Program Files\Java\jdk-24\bin\java.exe"
    ) else (
        echo [ERROR] Java runtime (java) not found. Please install JDK 21 or later.
        exit /b 1
    )
) else (
    set "JAVA=java"
)

if not exist "bin\nexus\demo\Main.class" (
    echo Binaries not found. Running build first...
    call build.bat
    if %ERRORLEVEL% neq 0 exit /b 1
)

"%JAVA%" -cp bin nexus.demo.Main %*

