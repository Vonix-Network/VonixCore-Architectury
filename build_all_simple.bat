@echo off
setlocal enabledelayedexpansion

:: ============================================================================
:: VonixCore Build All - Java 21 Optimized with JAR Collection
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

:: Setup builds directory
set "ROOT_DIR=%~dp0"
set "BUILDS_DIR=%ROOT_DIR%Builds"

:: Clean and create Builds directory
echo [INFO] Preparing Builds directory...
if exist "%BUILDS_DIR%" (
    rmdir /s /q "%BUILDS_DIR%"
    echo [INFO] Cleaned existing Builds directory
)
mkdir "%BUILDS_DIR%"
echo [INFO] Created fresh Builds directory
echo.

:: Directories
set "DIRS=vonixcore-1.18.2-fabric-quilt-forge-template vonixcore-1.19.2-fabric-quilt-forge-template vonixcore-1.20.1-fabric-quilt-forge-template vonixcore-1.21.1-fabric-neoforge-template"
set "PASS=0"
set "FAIL=0"

for %%d in (%DIRS%) do (
    echo ========================================
    echo Building %%d
    echo ========================================
    cd "%%d"
    call gradlew.bat build --parallel --build-cache --configure-on-demand
    if !errorlevel!==0 (
        set /a PASS+=1
        echo [OK] %%d built successfully
        
        :: Copy JARs to Builds directory
        call :copy_jars "%%d"
    ) else (
        set /a FAIL+=1
        echo [FAIL] %%d build failed
    )
    cd "%ROOT_DIR%"
    echo.
)

echo ========================================
echo Build Complete
echo ========================================
echo Passed: %PASS%
echo Failed: %FAIL%
echo.
echo All JARs collected in: %BUILDS_DIR%
echo.

:: List collected JARs
echo Collected JARs:
dir /b "%BUILDS_DIR%\*.jar" 2>nul || echo No JARs found

echo.
pause
exit /b 0

:: Function to copy JARs from all platforms
:copy_jars
set "VER_DIR=%~1"
set "VER_NAME=%VER_DIR:vonixcore-=%"

for %%p in (fabric, forge, quilt, neoforge) do (
    if exist "%ROOT_DIR%%VER_DIR%\%%p\build\libs" (
        for %%f in ("%ROOT_DIR%%VER_DIR%\%%p\build\libs\*.jar") do (
            :: Skip sources and javadoc JARs
            echo %%~nxf | findstr /i "sources" >nul && continue
            echo %%~nxf | findstr /i "javadoc" >nul && continue
            
            :: Copy with version prefix
            copy "%%f" "%BUILDS_DIR%\[%VER_NAME%]_[%%p]_%%~nxf" >nul
            echo   Copied: [%%p] %%~nxf
        )
    )
)
goto :eof
