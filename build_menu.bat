@echo off
setlocal enabledelayedexpansion

:: ============================================================================
:: VonixCore Multi-Version Build Script
:: Supports: Java 17, 21, 25
:: ============================================================================

:: Check for available Java versions
set "JAVA_17=%JAVA_17_HOME%"
set "JAVA_21=%JAVA_21_HOME%"
set "JAVA_25=%JAVA_25_HOME%"

:: Fallback: try to find Java from common locations
if not defined JAVA_17 (
    if exist "C:\Program Files\Java\jdk-17" set "JAVA_17=C:\Program Files\Java\jdk-17"
    if exist "C:\Program Files\Eclipse Adoptium\jdk-17" set "JAVA_17=C:\Program Files\Eclipse Adoptium\jdk-17"
)
if not defined JAVA_21 (
    if exist "C:\Program Files\Java\jdk-21" set "JAVA_21=C:\Program Files\Java\jdk-21"
    if exist "C:\Program Files\Eclipse Adoptium\jdk-21" set "JAVA_21=C:\Program Files\Eclipse Adoptium\jdk-21"
)
if not defined JAVA_25 (
    if exist "C:\Program Files\Java\jdk-25" set "JAVA_25=C:\Program Files\Java\jdk-25"
)

echo ============================================
echo VonixCore Multi-Version Build System
echo ============================================
echo.
echo Detected Java Versions:
if defined JAVA_17 echo   [OK] Java 17: %JAVA_17%
if defined JAVA_21 echo   [OK] Java 21: %JAVA_21%
if defined JAVA_25 echo   [OK] Java 25: %JAVA_25%
echo.

:: Use Java 21 as default (best compatibility)
if defined JAVA_21 (
    set "JAVA_HOME=%JAVA_21%"
    set "PATH=%JAVA_21%\bin;%PATH%"
    echo Using Java 21 as default
) else if defined JAVA_17 (
    set "JAVA_HOME=%JAVA_17%"
    set "PATH=%JAVA_17%\bin;%PATH%"
    echo Using Java 17 as default
)
echo.

:: Version directories
set "V_1_18_2=vonixcore-1.18.2-fabric-quilt-forge-template"
set "V_1_19_2=vonixcore-1.19.2-fabric-quilt-forge-template"
set "V_1_20_1=vonixcore-1.20.1-fabric-quilt-forge-template"
set "V_1_21_1=vonixcore-1.21.1-fabric-neoforge-template"

set "SUCCESS_COUNT=0"
set "FAIL_COUNT=0"

:menu
cls
echo ============================================
echo VonixCore Build Menu
echo ============================================
echo.
echo [1] Build All Versions (1.18.2 - 1.21.1)
echo [2] Build 1.18.2 only (Java 21 required)
echo [3] Build 1.19.2 only
echo [4] Build 1.20.1 only
echo [5] Build 1.21.1 only
echo [6] Clean All Builds
echo [0] Exit
echo.
set /p choice="Enter choice: "

if "%choice%"=="1" goto build_all
if "%choice%"=="2" goto build_1_18_2
if "%choice%"=="3" goto build_1_19_2
if "%choice%"=="4" goto build_1_20_1
if "%choice%"=="5" goto build_1_21_1
if "%choice%"=="6" goto clean_all
if "%choice%"=="0" exit /b 0
goto menu

:build_all
echo.
echo Starting full build sequence...
echo.
call :build_version "1.18.2" "%V_1_18_2%" "21"
if !errorlevel!==0 (set /a SUCCESS_COUNT+=1) else (set /a FAIL_COUNT+=1)

call :build_version "1.19.2" "%V_1_19_2%" "21"
if !errorlevel!==0 (set /a SUCCESS_COUNT+=1) else (set /a FAIL_COUNT+=1)

call :build_version "1.20.1" "%V_1_20_1%" "21"
if !errorlevel!==0 (set /a SUCCESS_COUNT+=1) else (set /a FAIL_COUNT+=1)

call :build_version "1.21.1" "%V_1_21_1%" "21"
if !errorlevel!==0 (set /a SUCCESS_COUNT+=1) else (set /a FAIL_COUNT+=1)

goto summary

:build_1_18_2
call :build_version "1.18.2" "%V_1_18_2%" "21"
pause
goto menu

:build_1_19_2
call :build_version "1.19.2" "%V_1_19_2%" "21"
pause
goto menu

:build_1_20_1
call :build_version "1.20.1" "%V_1_20_1%" "21"
pause
goto menu

:build_1_21_1
call :build_version "1.21.1" "%V_1_21_1%" "21"
pause
goto menu

:clean_all
echo Cleaning all build directories...
for %%d in (%V_1_18_2%, %V_1_19_2%, %V_1_20_1%, %V_1_21_1%) do (
    if exist "%%d\build" (
        rmdir /s /q "%%d\build"
        echo Cleaned %%d\build
    )
)
echo Done!
pause
goto menu

:summary
echo.
echo ============================================
echo Build Summary
echo ============================================
echo Successful: %SUCCESS_COUNT%
echo Failed: %FAIL_COUNT%
echo.
if %FAIL_COUNT%==0 (
    echo All builds completed successfully!
) else (
    echo Some builds failed. Check logs above.
)
pause
goto menu

:: Function to build a specific version
:build_version
set "VER_NAME=%~1"
set "VER_DIR=%~2"
set "REQ_JAVA=%~3"

echo ----------------------------------------
echo Building VonixCore %VER_NAME%
echo ----------------------------------------

if not exist "%VER_DIR%" (
    echo ERROR: Directory not found: %VER_DIR%
    exit /b 1
)

cd "%VER_DIR%"

echo [INFO] Using Java %REQ_JAVA%
echo [INFO] Building Common module...
call gradlew.bat :common:build --parallel --build-cache --configure-on-demand

if %errorlevel% neq 0 (
    echo [ERROR] Common build failed for %VER_NAME%
    cd ..
    exit /b 1
)

echo [INFO] Building platform modules...
call gradlew.bat build --parallel --build-cache --configure-on-demand

if %errorlevel% neq 0 (
    echo [WARN] Some platform builds failed for %VER_NAME%
) else (
    echo [OK] %VER_NAME% built successfully
)

cd ..
exit /b 0
