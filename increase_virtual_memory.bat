@echo off
REM This script increases Windows virtual memory (paging file)
REM Run as Administrator

echo Increasing Windows Paging File for Gradle builds...
echo This requires Administrator privileges.
pause

REM Increase virtual memory to 8GB initial, 16GB max
wmic pagefileset where name="D:\pagefile.sys" delete
wmic pagefileset create name="D:\pagefile.sys" InitialSize=8192 MaximumSize=16384

echo.
echo Paging file configuration updated:
echo - Initial: 8GB
echo - Maximum: 16GB
echo.
echo You MUST RESTART your computer for changes to take effect!
echo.
pause
