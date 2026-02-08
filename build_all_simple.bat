@echo off
setlocal enabledelayedexpansion

:: ============================================================================
:: VonixCore Build All - Java 21 Optimized
:: ============================================================================

:: Auto-detect Java 21
if defined JAVA_21_HOME (
    set "JAVA_HOME=%JAVA_21_HOME%"
) else if exist "C:\Program Files\Java\jdk-21" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-21"
) else if exist "C:\Program Files\Eclipse Adoptium\jdk-21" (
    set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21"
) else (
    echo ERROR: Java 21 not found!
    echo Please set JAVA_21_HOME environment variable
    exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%PATH%"
echo Using Java: %JAVA_HOME%
java -version
echo.

:: Directories
set "DIRS=vonixcore-1.18.2-fabric-quilt-forge-template vonixcore-1.19.2-fabric-quilt-forge-template vonixcore-1.20.1-fabric-quilt-forge-template vonixcore-1.21.1-fabric-neoforge-template"
set "PASS=0"
set "FAIL=0"

for %%d in (%DIRS%) do (
    echo ========================================
    echo Building %%d
    echo ========================================
    cd %%d
    call gradlew.bat build --parallel --build-cache --configure-on-demand
    if !errorlevel!==0 (
        set /a PASS+=1
        echo [OK] %%d built successfully
    ) else (
        set /a FAIL+=1
        echo [FAIL] %%d build failed
    )
    cd ..
    echo.
)

echo ========================================
echo Build Complete
echo ========================================
echo Passed: %PASS%
echo Failed: %FAIL%
