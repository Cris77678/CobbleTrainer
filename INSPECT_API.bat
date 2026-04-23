@echo off
echo ============================================================
echo  Inspeccionando API real de Cobblemon 1.7.1
echo ============================================================

REM Find the cobblemon jar in gradle cache
echo.
echo [1] Buscando JAR de Cobblemon...
for /r "%USERPROFILE%\.gradle\caches" %%f in (*cobblemon*fabric*.jar) do (
    if not "%%~nf"=="%%" (
        echo Encontrado: %%f
        set COBBLEMON_JAR=%%f
    )
)

REM Also check the loom cache (remapped jar)
for /r "%USERPROFILE%\.gradle\caches\fabric-loom" %%f in (*cobblemon*.jar) do (
    echo Encontrado (loom): %%f
    set COBBLEMON_JAR=%%f
)

echo.
echo [2] Inspeccionando BattleBuilder...
javap -classpath "%COBBLEMON_JAR%" com.cobblemon.mod.common.battles.BattleBuilder 2>nul
if errorlevel 1 (
    echo Error con classpath directo, intentando extraer...
    REM Extract specific class from jar
    cd /tmp
    jar xf "%COBBLEMON_JAR%" com/cobblemon/mod/common/battles/BattleBuilder.class 2>nul
    javap com/cobblemon/mod/common/battles/BattleBuilder.class
)

echo.
echo [3] Inspeccionando BattleStartResult...
javap -classpath "%COBBLEMON_JAR%" com.cobblemon.mod.common.battles.BattleStartResult 2>nul

echo.
echo [4] Inspeccionando ExperienceSource...
javap -classpath "%COBBLEMON_JAR%" com.cobblemon.mod.common.api.pokemon.experience.ExperienceSource 2>nul

echo.
echo [5] Inspeccionando CobblemonEvents (solo lineas con BATTLE)...
javap -classpath "%COBBLEMON_JAR%" com.cobblemon.mod.common.api.events.CobblemonEvents 2>nul | findstr /i "BATTLE"

echo.
echo [6] Inspeccionando EVs...
javap -classpath "%COBBLEMON_JAR%" com.cobblemon.mod.common.api.pokemon.stats.EVs 2>nul

echo.
echo ============================================================
echo  Copia todo el output de arriba y pegalo en el chat
echo ============================================================
pause
