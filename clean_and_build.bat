@echo off
echo ============================================================
echo  CobbleTrainer - Limpiando cache y compilando
echo ============================================================

echo [1/4] Deteniendo daemons de Gradle...
call gradlew.bat --stop >nul 2>&1

echo [2/4] Limpiando cache local (.gradle)...
if exist ".gradle" rmdir /s /q ".gradle"

echo [3/4] Limpiando cache global de Loom...
if exist "%USERPROFILE%\.gradle\caches\fabric-loom" (
    rmdir /s /q "%USERPROFILE%\.gradle\caches\fabric-loom"
)

echo [4/4] Compilando con --no-daemon...
echo.
call gradlew.bat clean build --no-daemon

echo.
if errorlevel 1 (
    echo [FALLO] Build fallido.
    echo Ejecuta: .\GET_ERROR.bat
) else (
    echo [EXITO] build\libs\CobbleTrainer-1.0.0.jar listo.
)
