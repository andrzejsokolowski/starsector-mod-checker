@echo off
setlocal
rem Starsector Mod Checker.
rem Put the StarsectorModChecker folder inside your Starsector folder and double-click this file,
rem or drag your Starsector folder onto it, or run: check-mods "C:\path\to\Starsector" [options]

set "HERE=%~dp0"
set "JAVA="
set "GAME=%~1"

rem Use the Java that comes with the game, so nothing has to be installed.
if not "%GAME%"=="" if exist "%GAME%\jre\bin\java.exe" set "JAVA=%GAME%\jre\bin\java.exe"
if not defined JAVA if exist "%HERE%..\jre\bin\java.exe" set "JAVA=%HERE%..\jre\bin\java.exe"
if not defined JAVA if exist "%HERE%..\..\jre\bin\java.exe" set "JAVA=%HERE%..\..\jre\bin\java.exe"
if not defined JAVA (
    where java >nul 2>nul
    if errorlevel 1 (
        echo Could not find Java. Put the StarsectorModChecker folder inside your Starsector folder,
        echo or drag your Starsector folder onto check-mods.bat.
        set "CODE=2"
        goto done
    )
    set "JAVA=java"
)

"%JAVA%" -jar "%HERE%mod-checker.jar" %*
set "CODE=%ERRORLEVEL%"

:done
rem Keep the window open when the file was double-clicked.
echo %cmdcmdline% | findstr /i /c:"%~nx0" >nul && (echo. & pause)
exit /b %CODE%
