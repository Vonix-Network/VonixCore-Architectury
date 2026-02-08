@echo off
setlocal enabledelayedexpansion

:: ============================================================================
:: VonixCore Multi-Version Build Script
:: Builds: 1.18.2 ? 1.19.2 ? 1.20.1 ? 1.21.1
:: ============================================================================

:: Colors for Windows CMD (try to enable ANSI)
set "RESET="
set "GREEN="
set "RED="
set "YELLOW="
set "BLUE="
set "CYAN="
set "MAGENTA="

:: Try to enable ANSI colors (Windows 10+)
for /f "tokens=3" %%a in ('reg query "HKCU\Console" /v VirtualTerminalLevel 2^>nul ^| findstr "0x1"') do (
    set "GREEN=[92m"
    set "RED=[91m"
    set "YELLOW=[93m"
    set "BLUE=[94m"
    set "CYAN=[96m"
    set "MAGENTA=[95m"
    set "RESET=[0m"
)

:: ============================================================================
:: Configuration
:: ============================================================================
set "ROOT_DIR=%~dp0"
set "BUILD_LOG=%ROOT_DIR%build_all.log"

:: Version directories
set "V_1_18_2=vonixcore-1.18.2-fabric-quilt-forge-template"
set "V_1_19_2=vonixcore-1.19.2-fabric-quilt-forge-template"
set "V_1_20_1=vonixcore-1.20.1-fabric-quilt-forge-template"
set "V_1_21_1=vonixcore-1.21.1-fabric-neoforge-template"

:: Track results
set "SUCCESS_COUNT=0"
set "FAIL_COUNT=0"
set "TOTAL_COUNT=4"

:: Clear log file
echo. > "%BUILD_LOG%"

:: ============================================================================
:: Header
:: ============================================================================
cls
echo %BLUE%================================================================================%RESET%
echo %BLUE%  VonixCore Multi-Version Build System%RESET%
echo %BLUE%================================================================================%RESET%
echo.
echo %YELLOW%Build Order:%RESET% 1.18.2 -^> 1.19.2 -^> 1.20.1 -^> 1.21.1
echo %YELLOW%Started:%RESET% %date% %time%
echo %YELLOW%Log File:%RESET% build_all.log
echo.
echo %MAGENTA%Press any key to start building...%RESET%
pause ^>nul
cls

:: ============================================================================
:: Build Functions
:: ============================================================================

call :draw_header "STARTING BUILD PROCESS"
echo.

:: Build 1.18.2
call :build_version "1.18.2" "%V_1_18_2%" "fabric,forge,quilt"
if !errorlevel! neq 0 (
    call :log_error "1.18.2 build failed"
    set /a "FAIL_COUNT+=1"
) else (
    set /a "SUCCESS_COUNT+=1"
)
echo.

:: Build 1.19.2
call :build_version "1.19.2" "%V_1_19_2%" "fabric,forge,quilt"
if !errorlevel! neq 0 (
    call :log_error "1.19.2 build failed"
    set /a "FAIL_COUNT+=1"
) else (
    set /a "SUCCESS_COUNT+=1"
)
echo.

:: Build 1.20.1
call :build_version "1.20.1" "%V_1_20_1%" "fabric,forge,quilt"
if !errorlevel! neq 0 (
    call :log_error "1.20.1 build failed"
    set /a "FAIL_COUNT+=1"
) else (
    set /a "SUCCESS_COUNT+=1"
)
echo.

:: Build 1.21.1
call :build_version "1.21.1" "%V_1_21_1%" "fabric,neoforge"
if !errorlevel! neq 0 (
    call :log_error "1.21.1 build failed"
    set /a "FAIL_COUNT+=1"
) else (
    set /a "SUCCESS_COUNT+=1"
)

:: ============================================================================
:: Summary
:: ============================================================================
echo.
call :draw_header "BUILD SUMMARY"
echo.

echo %CYAN%Results:%RESET%
echo   %GREEN%SUCCESS: %SUCCESS_COUNT%/%TOTAL_COUNT%%RESET%
echo   %RED%FAILED: %FAIL_COUNT%/%TOTAL_COUNT%%RESET%
echo.

:: List output JARs
echo %CYAN%Output JARs:%RESET%
call :list_jars "1.18.2" "%V_1_18_2%"
call :list_jars "1.19.2" "%V_1_19_2%"
call :list_jars "1.20.1" "%V_1_20_1%"
call :list_jars "1.21.1" "%V_1_21_1%"

echo.
echo %YELLOW%Completed:%RESET% %date% %time%
echo %YELLOW%Log File:%RESET% build_all.log
echo.

if %FAIL_COUNT% gtr 0 (
    echo %RED%================================================================================%RESET%
    echo %RED%  BUILD COMPLETED WITH ERRORS%RESET%
    echo %RED%================================================================================%RESET%
    exit /b 1
) else (
    echo %GREEN%================================================================================%RESET%
    echo %GREEN%  ALL BUILDS SUCCESSFUL%RESET%
    echo %GREEN%================================================================================%RESET%
    exit /b 0
)

:: ============================================================================
:: Functions
:: ============================================================================

:draw_header
echo %BLUE%================================================================================%RESET%
echo %BLUE%  %~1%RESET%
echo %BLUE%================================================================================%RESET%
goto :eof

:log_info
echo [%date% %time%] [INFO] %~1 >> "%BUILD_LOG%"
echo %GREEN%[INFO]%RESET% %~1
goto :eof

:log_warn
echo [%date% %time%] [WARN] %~1 >> "%BUILD_LOG%"
echo %YELLOW%[WARN]%RESET% %~1
goto :eof

:log_error
echo [%date% %time%] [ERROR] %~1 >> "%BUILD_LOG%"
echo %RED%[ERROR]%RESET% %~1
goto :eof

:build_version
set "VER_NAME=%~1"
set "VER_DIR=%~2"
set "PLATFORMS=%~3"

call :draw_header "Building VonixCore %VER_NAME%"

if not exist "%ROOT_DIR%%VER_DIR%" (
    call :log_error "Directory not found: %VER_DIR%"
    exit /b 1
)

cd /d "%ROOT_DIR%%VER_DIR%"

call :log_info "Building Common module..."
call gradlew.bat :common:build --parallel --build-cache --configure-on-demand >> "%BUILD_LOG%" 2>&1
if !errorlevel! neq 0 (
    call :log_error "Common module build failed for %VER_NAME%"
    exit /b 1
)
call :log_info "Common module built successfully"

:: Build Fabric
echo %PLATFORMS% | findstr /i "fabric" >nul && (
    call :log_info "Building Fabric module..."
    call gradlew.bat :fabric:build --parallel --build-cache --configure-on-demand >> "%BUILD_LOG%" 2>&1
    if !errorlevel! neq 0 (
        call :log_warn "Fabric module build failed for %VER_NAME%"
    ) else (
        call :log_info "Fabric module built successfully"
    )
)

:: Build Forge
echo %PLATFORMS% | findstr /i "forge" >nul && (
    call :log_info "Building Forge module..."
    call gradlew.bat :forge:build --parallel --build-cache --configure-on-demand >> "%BUILD_LOG%" 2>&1
    if !errorlevel! neq 0 (
        call :log_warn "Forge module build failed for %VER_NAME%"
    ) else (
        call :log_info "Forge module built successfully"
    )
)

:: Build Quilt
echo %PLATFORMS% | findstr /i "quilt" >nul && (
    call :log_info "Building Quilt module..."
    call gradlew.bat :quilt:build --parallel --build-cache --configure-on-demand >> "%BUILD_LOG%" 2>&1
    if !errorlevel! neq 0 (
        call :log_warn "Quilt module build failed for %VER_NAME%"
    ) else (
        call :log_info "Quilt module built successfully"
    )
)

:: Build NeoForge
echo %PLATFORMS% | findstr /i "neoforge" >nul && (
    call :log_info "Building NeoForge module..."
    call gradlew.bat :neoforge:build --parallel --build-cache --configure-on-demand >> "%BUILD_LOG%" 2>&1
    if !errorlevel! neq 0 (
        call :log_warn "NeoForge module build failed for %VER_NAME%"
    ) else (
        call :log_info "NeoForge module built successfully"
    )
)

cd /d "%ROOT_DIR%"
call :log_info "VonixCore %VER_NAME% build completed"
goto :eof

:list_jars
set "VER_NAME=%~1"
set "VER_DIR=%~2"

echo.
echo %CYAN%[%VER_NAME%]%RESET%

for %%p in (fabric, forge, quilt, neoforge) do (
    if exist "%ROOT_DIR%%VER_DIR%\%%p\build\libs\*.jar" (
        for %%f in ("%ROOT_DIR%%VER_DIR%\%%p\build\libs\*.jar") do (
            echo   %GREEN%^-> %%~nxf%RESET%
        )
    )
)

if exist "%ROOT_DIR%%VER_DIR%\common\build\libs\*.jar" (
    for %%f in ("%ROOT_DIR%%VER_DIR%\common\build\libs\*.jar") do (
        echo   %YELLOW%[common] %%~nxf%RESET%
    )
)
goto :eof
