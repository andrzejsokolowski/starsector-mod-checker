@echo off
setlocal EnableExtensions
rem Starsector Mod Checker.
rem Put the StarsectorModChecker folder inside your Starsector folder and double-click this file,
rem or drag your Starsector folder onto this file. If it cannot find the game, it asks for the folder.

set "HERE=%~dp0"
set "GAME="
set "ASKED="
set "CODE=0"
rem A folder dropped onto this file arrives as the first argument.
if not "%~1"=="" if exist "%~1\" set "GAME=%~1"

:run
call :findjava
if not defined JAVA goto ask
if defined ASKED (
    "%JAVA%" -jar "%HERE%mod-checker.jar" --game "%GAME%"
) else (
    "%JAVA%" -jar "%HERE%mod-checker.jar" %*
)
set "CODE=%ERRORLEVEL%"
rem 3 means the game folder was not found: ask for it.
if "%CODE%"=="3" goto ask
goto done

:ask
echo.
echo Drag your Starsector folder into this window and press Enter.
echo It is the folder with starsector-core and mods in it.
set "GAME="
set /p "GAME=Starsector folder: "
if not defined GAME (
    set "CODE=3"
    goto done
)
set "GAME=%GAME:"=%"
set "ASKED=1"
goto run

:findjava
rem Use the Java that comes with the game, so nothing has to be installed.
set "JAVA="
if defined GAME if exist "%GAME%\jre\bin\java.exe" set "JAVA=%GAME%\jre\bin\java.exe"
if not defined JAVA if exist "%HERE%..\jre\bin\java.exe" set "JAVA=%HERE%..\jre\bin\java.exe"
if not defined JAVA if exist "%HERE%..\..\jre\bin\java.exe" set "JAVA=%HERE%..\..\jre\bin\java.exe"
if not defined JAVA (
    where java >nul 2>nul && set "JAVA=java"
)
exit /b 0

:done
echo.
rem Keep the window open when the file was double-clicked.
echo %cmdcmdline% | findstr /i /c:"%~nx0" >nul && (echo Press any key to close this window. & pause >nul)
exit /b %CODE%
