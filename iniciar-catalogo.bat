@echo off
cd /d "%~dp0"
javac CatalogoMariaGomes.java
if errorlevel 1 (
  pause
  exit /b 1
)
java -cp . CatalogoMariaGomes
pause
