@echo off
:: Quick build script for single version
:: Usage: build.bat [1.18.2|1.19.2|1.20.1|1.21.1]

if "%~1"=="" (
    echo Usage: build.bat [version]
    echo Available versions: 1.18.2, 1.19.2, 1.20.1, 1.21.1
    echo.
    echo To build all versions, use: build_all.bat
    exit /b 1
)

set "VERSION=%~1"
set "ROOT=%~dp0"

if "%VERSION%"=="1.18.2" (
    set "DIR=vonixcore-1.18.2-fabric-quilt-forge-template"
) else if "%VERSION%"=="1.19.2" (
    set "DIR=vonixcore-1.19.2-fabric-quilt-forge-template"
) else if "%VERSION%"=="1.20.1" (
    set "DIR=vonixcore-1.20.1-fabric-quilt-forge-template"
) else if "%VERSION%"=="1.21.1" (
    set "DIR=vonixcore-1.21.1-fabric-neoforge-template"
) else (
    echo Unknown version: %VERSION%
    echo Available: 1.18.2, 1.19.2, 1.20.1, 1.21.1
    exit /b 1
)

echo ========================================
echo Building VonixCore %VERSION%
echo ========================================
echo.

cd /d "%ROOT%%DIR%"

echo [INFO] Building all modules...
call gradlew.bat build --parallel --build-cache --configure-on-demand

if %errorlevel% neq 0 (
    echo [ERROR] Build failed!
    exit /b 1
)

echo.
echo ========================================
echo Build completed successfully!
echo ========================================
echo.
echo Output JARs:
for %%p in (fabric, forge, quilt, neoforge) do (
    if exist "%%p\build\libs\*.jar" (
        for %%f in ("%%p\build\libs\*.jar") do (
            echo   - %%~nxf
        )
    )
)

cd /d "%ROOT%"
