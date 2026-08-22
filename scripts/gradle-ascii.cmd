@echo off
setlocal EnableExtensions DisableDelayedExpansion

rem Run Gradle through a temporary ASCII drive letter. This avoids the
rem Windows Gradle Test Worker class-loading failure for this CJK workspace.
set "GRADLE_ASCII_DRIVE=%LYRIC_PROVIDER_SUBST_DRIVE%"
if not defined GRADLE_ASCII_DRIVE set "GRADLE_ASCII_DRIVE=R:"

for %%I in ("%~dp0..") do set "PROJECT_ROOT=%%~fI"

if exist "%GRADLE_ASCII_DRIVE%\" (
    echo [gradle-ascii] %GRADLE_ASCII_DRIVE% is already in use. Set LYRIC_PROVIDER_SUBST_DRIVE to an unused drive letter.
    exit /b 2
)

subst %GRADLE_ASCII_DRIVE% "%PROJECT_ROOT%"
if errorlevel 1 (
    echo [gradle-ascii] Failed to map %GRADLE_ASCII_DRIVE% to "%PROJECT_ROOT%".
    exit /b 1
)

pushd "%GRADLE_ASCII_DRIVE%\"
if errorlevel 1 (
    subst %GRADLE_ASCII_DRIVE% /D >nul 2>&1
    echo [gradle-ascii] Failed to enter %GRADLE_ASCII_DRIVE%.
    exit /b 1
)

call gradlew.bat %*
set "RESULT=%ERRORLEVEL%"
popd
subst %GRADLE_ASCII_DRIVE% /D >nul 2>&1
exit /b %RESULT%
