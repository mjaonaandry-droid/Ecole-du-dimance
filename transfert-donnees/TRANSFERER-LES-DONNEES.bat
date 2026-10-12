@echo off
rem ============================================================================================
rem  Ecole du Dimanche - installation de la nouvelle version SANS PERTE DE DONNEES (Windows).
rem
rem  1. Essaie d'abord une mise a jour directe (adb install -r) : si l'APK porte la meme cle que
rem     l'application installee, c'est fini, les donnees sont conservees par Android.
rem  2. Sinon (cle differente) : sauvegarde de la base et des photos sur cet ordinateur,
rem     verification de la sauvegarde, desinstallation, installation, restauration.
rem  Rien n'est desinstalle tant que la sauvegarde n'est pas verifiee.
rem
rem  Placer a cote de ce fichier : le dossier "platform-tools" (adb) et le fichier .apk.
rem ============================================================================================
setlocal EnableExtensions
cd /d "%~dp0"

set "PKG=mg.ecoledimanche.presences"
set "TMP_TEL=/data/local/tmp/ecole-sauvegarde.tar"
set "DONNEES=/data/data/%PKG%"
set "JOURNAL=%~dp0journal-transfert.txt"
set "SORTIE=%TEMP%\ecole-transfert-sortie.txt"
set "SAUVEGARDE="
rem --oui : aucune question (verification automatique de ce script).
set "OUI="
if /i "%~1"=="--oui" set "OUI=1"

echo.
echo  =====================================================================
echo   ECOLE DU DIMANCHE - Installation de la nouvelle version
echo   Vos enfants, photos et presences seront conserves.
echo  =====================================================================
echo.
echo   Avant de commencer :
echo    - le telephone est branche a l'ordinateur par un cable USB ;
echo    - le "Debogage USB" est active (voir LISEZMOI.txt) ;
echo    - le telephone est deverrouille.
echo.
if not defined OUI pause

echo === Transfert %DATE% %TIME% ===>> "%JOURNAL%"

rem --- adb et APK --------------------------------------------------------------------------------
set "ADB=%~dp0platform-tools\adb.exe"
if exist "%ADB%" goto adb_ok
set "ADB="
for %%X in (adb.exe) do set "ADB=%%~$PATH:X"
if not defined ADB (
    call :echec "adb introuvable. Decompressez platform-tools a cote de ce fichier, dans un dossier nomme platform-tools."
    goto fin
)
:adb_ok
set "APK="
for %%F in ("%~dp0*.apk") do set "APK=%%~fF"
if not defined APK (
    call :echec "Nouvel APK introuvable. Placez le fichier .apk a cote de ce fichier."
    goto fin
)
call :dire "APK : %APK%"

rem --- 1. Telephone connecte et autorise -------------------------------------------------------
"%ADB%" start-server > nul 2>&1
"%ADB%" get-state > "%SORTIE%" 2>&1
type "%SORTIE%" >> "%JOURNAL%"
findstr /c:"unauthorized" "%SORTIE%" > nul && (
    call :echec "Le telephone n'a pas autorise cet ordinateur : deverrouillez-le et acceptez Autoriser le debogage USB, puis relancez."
    goto fin
)
findstr /c:"more than one" "%SORTIE%" > nul && (
    call :echec "Plusieurs telephones sont branches : n'en laissez qu'un."
    goto fin
)
findstr /x /c:"device" "%SORTIE%" > nul || (
    call :echec "Aucun telephone detecte : verifiez le cable, le debogage USB, puis relancez."
    goto fin
)
call :dire "Telephone detecte."

rem --- 2. Application installee ? ---------------------------------------------------------------
"%ADB%" shell pm path %PKG% > "%SORTIE%" 2>&1
findstr /c:"package:" "%SORTIE%" > nul || goto reprise

rem 2a. Mise a jour directe : fonctionne si la cle est la meme (versions futures).
call :dire "Essai de mise a jour directe..."
"%ADB%" install -r "%APK%" > "%SORTIE%" 2>&1
type "%SORTIE%"
type "%SORTIE%" >> "%JOURNAL%"
findstr /c:"Success" "%SORTIE%" > nul && (
    call :dire "Mise a jour directe reussie : les donnees sont conservees par Android."
    goto lancer
)
findstr /c:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" "%SORTIE%" > nul || (
    call :echec "Installation refusee pour une autre raison, voir ci-dessus. Rien n'a ete modifie."
    goto fin
)

rem 2b. Cle differente : sauvegarde complete d'abord.
call :dire "Cle differente : sauvegarde des donnees avant remplacement..."
"%ADB%" shell run-as %PKG% ls > "%SORTIE%" 2>&1 || (
    type "%SORTIE%"
    call :echec "Impossible de lire les donnees de l'application installee. Rien n'a ete modifie."
    goto fin
)
"%ADB%" shell run-as %PKG% tar --help > "%SORTIE%" 2>&1 || (
    call :echec "Ce telephone n'a pas la commande tar. Rien n'a ete modifie."
    goto fin
)
"%ADB%" shell am force-stop %PKG%
"%ADB%" shell run-as %PKG% mkdir -p %DONNEES%/files %DONNEES%/databases
"%ADB%" shell rm -f %TMP_TEL%
"%ADB%" shell "run-as %PKG% tar -cf - -C %DONNEES% databases files | cat > %TMP_TEL%"
"%ADB%" shell "tar -tf %TMP_TEL%" > "%SORTIE%" 2>&1
type "%SORTIE%" >> "%JOURNAL%"
findstr /c:"databases/ecole_dimanche.db" "%SORTIE%" > nul || (
    call :echec "La sauvegarde ne contient pas la base de donnees. Rien n'a ete desinstalle."
    goto fin
)
set "NB_PHOTOS=0"
for /f %%N in ('findstr /r /c:"files/photos/.*\.jpg" "%SORTIE%" ^| find /c /v ""') do set "NB_PHOTOS=%%N"

if not exist "%~dp0sauvegardes" mkdir "%~dp0sauvegardes"
set "HORODATAGE=%DATE:/=-%_%TIME::=-%"
set "HORODATAGE=%HORODATAGE: =0%"
set "HORODATAGE=%HORODATAGE:,=-%"
set "HORODATAGE=%HORODATAGE:.=-%"
set "SAUVEGARDE=%~dp0sauvegardes\sauvegarde-%HORODATAGE%.tar"
"%ADB%" pull %TMP_TEL% "%SAUVEGARDE%" > "%SORTIE%" 2>&1
if not exist "%SAUVEGARDE%" (
    type "%SORTIE%"
    call :echec "Copie de la sauvegarde sur l'ordinateur impossible. Rien n'a ete desinstalle."
    goto fin
)
"%ADB%" exec-out stat -c %%s %TMP_TEL% > "%SORTIE%" 2>&1
set "TAILLE_TEL="
for /f "usebackq delims=" %%T in ("%SORTIE%") do set "TAILLE_TEL=%%T"
set "TAILLE_PC="
for %%A in ("%SAUVEGARDE%") do set "TAILLE_PC=%%~zA"
call :dire "Sauvegarde : %SAUVEGARDE% (%TAILLE_PC% octets, %NB_PHOTOS% photo(s))"
if not "%TAILLE_TEL%"=="%TAILLE_PC%" (
    call :echec "La copie sur l'ordinateur est incomplete (%TAILLE_PC% / %TAILLE_TEL% octets). Rien n'a ete desinstalle."
    goto fin
)
call :dire "Sauvegarde verifiee."
echo.
echo   La sauvegarde est verifiee. L'ancienne version va etre remplacee.
if not defined OUI pause

rem 2c. Remplacement.
"%ADB%" uninstall %PKG% > "%SORTIE%" 2>&1
type "%SORTIE%" >> "%JOURNAL%"
findstr /c:"Success" "%SORTIE%" > nul || (
    type "%SORTIE%"
    call :echec "Desinstallation impossible. Les donnees sont toujours dans l'application."
    goto fin
)
goto installer

:reprise
rem Reprise apres une interruption : l'application est deja desinstallee, on repart de la derniere sauvegarde.
set "SAUVEGARDE="
for /f "delims=" %%S in ('dir /b /o:d "%~dp0sauvegardes\sauvegarde-*.tar" 2^>nul') do set "SAUVEGARDE=%~dp0sauvegardes\%%S"
if defined SAUVEGARDE (
    call :dire "Application absente : reprise avec la sauvegarde %SAUVEGARDE%"
) else (
    call :dire "Application absente et aucune sauvegarde : simple installation."
)

:installer
rem --- 3. Installation de la nouvelle version --------------------------------------------------
call :dire "Installation de la nouvelle version..."
"%ADB%" install "%APK%" > "%SORTIE%" 2>&1
type "%SORTIE%"
type "%SORTIE%" >> "%JOURNAL%"
findstr /c:"Success" "%SORTIE%" > nul || (
    call :echec "Installation impossible. Vos donnees sont en securite dans le dossier sauvegardes. Relancez ce fichier."
    goto fin
)

rem --- 4. Restauration --------------------------------------------------------------------------
if not defined SAUVEGARDE goto lancer
call :dire "Restauration des donnees..."
"%ADB%" shell am force-stop %PKG%
"%ADB%" push "%SAUVEGARDE%" %TMP_TEL% > "%SORTIE%" 2>&1 || (
    type "%SORTIE%"
    call :echec "Envoi de la sauvegarde vers le telephone impossible. Relancez ce fichier."
    goto fin
)
"%ADB%" shell run-as %PKG% rm -rf %DONNEES%/databases %DONNEES%/files
"%ADB%" shell "cat %TMP_TEL% | run-as %PKG% tar -xf - -C %DONNEES%"
"%ADB%" shell run-as %PKG% ls %DONNEES%/databases > "%SORTIE%" 2>&1
findstr /c:"ecole_dimanche.db" "%SORTIE%" > nul || (
    call :echec "La restauration n'a pas abouti. Vos donnees sont en securite dans le dossier sauvegardes. Relancez ce fichier."
    goto fin
)
"%ADB%" shell rm -f %TMP_TEL%
call :dire "Donnees restaurees."

:lancer
"%ADB%" shell am start -n %PKG%/.MainActivity > nul 2>&1
echo.
call :dire "TERMINE : la nouvelle version est installee avec vos donnees. Verifiez la liste des enfants sur le telephone."
goto fin

rem --- Outils -------------------------------------------------------------------------------------
:dire
echo   %~1
echo %~1>> "%JOURNAL%"
exit /b 0

:echec
echo.
echo   ECHEC : %~1
echo ECHEC : %~1>> "%JOURNAL%"
exit /b 1

:fin
echo.
if not defined OUI pause
endlocal
