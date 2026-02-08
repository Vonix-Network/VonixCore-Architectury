@echo off
:: Quick build script for single version with JAR collection
:: Fixed: Removed invalid 'continue' commands
:: Usage: build.bat [1.18.2|1.19.2|1.20.1|1.21.1]

setlocal enabledelayedexpansion

set "ROOT_DIR=%~dp0"
set "BUILDS_DIR=%ROOT_DIR%Builds"

if "%~1"=="" (
    echo Usage: build.bat [version]
    echo Available versions: 1.18.2, 1.19.2, 1.20.1, 1.21.1
    echo.
    echo To build all versions, use: build_all_simple.bat
    exit /b 1
)

set "VERSION=%~1"
set "DIR="

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

:: Create Builds directory if not exists
if not exist "%BUILDS_DIR%" mkdir "%BUILDS_DIR%"

echo ========================================
echo Building VonixCore %VERSION%
echo ========================================
echo.

cd /d "%ROOT_DIR%%DIR%"

echo [INFO] Building all modules...
call gradlew.bat build --parallel --build-cache --configure-on-demand

if %errorlevel% neq 0 (
    echo [ERROR] Build failed!
    cd /d "%ROOT_DIR%"
    exit /b 1
)

echo.
echo ========================================
echo Build completed successfully!
echo ========================================
echo.

:: Copy JARs to Builds directory
echo [INFO] Collecting JARs...
for %%p in (fabric, forge, quilt, neoforge) do (
    if exist "%%p\build\libs\*.jar" (
        for %%f in ("%%p\build\libs\*.jar") do (
            :: Skip sources and javadoc - using goto instead of continue
            echo %%~nxf | findstr /i "sources" >nul && goto :skip_single
            echo %%~nxf | findstr /i "javadoc" >nul && goto :skip_single
            
            copy "%%f" "%BUILDS_DIR%\[%VERSION%]_[%%p]_%%~nxf" >nul
            echo   Copied: [%%p] %%~nxf
            :skip_single
        )
    )
)

echo.
echo JARs collected in: %BUILDS_DIR%
cd /d "%ROOT_DIR%"
