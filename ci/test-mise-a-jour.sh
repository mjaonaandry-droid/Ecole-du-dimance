#!/usr/bin/env bash
# =============================================================================================
#  Vérification sur émulateur du passage 1.1.0 -> version actuelle SANS DÉSINSTALLER :
#  1. l'APK 1.1.0 livré (clé permanente) est installé avec des données réalistes (base version 1) ;
#  2. le nouvel APK est installé PAR-DESSUS (adb install -r), comme le fera le téléphone ;
#  3. au premier lancement, Room migre la base 1 -> dernière version : les données doivent être intactes ;
#  4. tour des nouveautés : onglets Notes et Données, export partiel, « Repartir de zéro », écran d'accueil.
#  Si le nouvel APK n'a pas la même clé que la 1.1.0 installée, la mise à jour directe est impossible :
#  le passage se fait alors avec le script fourni à l'utilisateur (transfert-donnees/), exactement comme
#  sur son téléphone (sauvegarde, remplacement, restauration), puis les données sont vérifiées.
#  Usage : bash ci/test-mise-a-jour.sh <apk 1.1.0 livré> <nouvel apk> [dossier de sortie]
# =============================================================================================
set -uo pipefail
PKG="mg.ecoledimanche.presences"
ICI="$(cd "$(dirname "$0")" && pwd)"
RACINE="$(dirname "$ICI")"
ANCIEN="$1"
NOUVEAU="$2"
SORTIES="$RACINE/${3:-ci-sorties-v12}"
CAPTURES="$SORTIES/captures"
mkdir -p "$CAPTURES"
RESUME="$SORTIES/resume-mise-a-jour.txt"
: > "$RESUME"
UI="python3 $ICI/ui.py"
SCHEMAS="$RACINE/Ecole-du-dimance/app/schemas/mg.ecoledimanche.presences.data.local.AppDatabase"
ECHECS_DONNEES=0
ECHECS_ECRANS=0
# Empreintes (SHA-256 du certificat) des clés de l'application :
# - celle de la 1.1.0 livrée le 12/10/2026 (clé perdue depuis) ;
# - la clé PERMANENTE actuelle, créée le 16/11/2026 pour la 1.3.0 et les versions suivantes.
EMPREINTE_1_1_0="b549da91ebc975828879b9fc9954732a6a04b49101e835068dc46dfa8f676f89"
EMPREINTE_PERMANENTE="bc3ee6d6f56628518c04a4f4a8ba28e61de78b89a356518f719eb52bd243adcd"

ok() { echo "OK      $*" | tee -a "$RESUME"; }
info() { echo "INFO    $*" | tee -a "$RESUME"; }
attention() { echo "ATTENTION $*" | tee -a "$RESUME"; echo "::warning::$*"; }
ko_donnees() { echo "ECHEC   [données] $*" | tee -a "$RESUME"; ECHECS_DONNEES=$((ECHECS_DONNEES + 1)); }
ko_ecran() { echo "ECHEC   [écran] $*" | tee -a "$RESUME"; ECHECS_ECRANS=$((ECHECS_ECRANS + 1)); }
# L'émulateur de la CI affiche parfois « Pixel Launcher isn't responding » par-dessus l'application :
# ce dialogue du système (pas de l'application) est fermé avant chaque vérification d'écran.
fermer_dialogue_systeme() {
    if $UI texte 2>/dev/null | grep -q "isn't responding"; then
        $UI toucher-exact "Wait" >> "$SORTIES/ui.log" 2>&1 || $UI toucher-exact "Close app" >> "$SORTIES/ui.log" 2>&1
        echo "(dialogue système « ne répond pas » fermé)" >> "$SORTIES/ui.log"
        sleep 2
    fi
}
verifier_ecran() {
    fermer_dialogue_systeme
    if $UI attendre "$1" "${2:-20}" >> "$SORTIES/ui.log" 2>&1; then
        ok "écran : « $1 » visible"
    else
        fermer_dialogue_systeme
        if $UI attendre "$1" 10 >> "$SORTIES/ui.log" 2>&1; then ok "écran : « $1 » visible"; else ko_ecran "« $1 » absent de l'écran"; fi
    fi
}
capture() { $UI capture "$CAPTURES/$1.png" > /dev/null 2>&1; }
toucher() { fermer_dialogue_systeme; $UI toucher "$@" >> "$SORTIES/ui.log" 2>&1 || ko_ecran "impossible de toucher « $1 »"; }
toucher_exact() { fermer_dialogue_systeme; $UI toucher-exact "$@" >> "$SORTIES/ui.log" 2>&1 || ko_ecran "impossible de toucher « $1 »"; }
defiler() { adb shell input swipe 540 1700 540 600 300; sleep 1; }
empreinte() { "$APKSIGNER" verify --print-certs "$1" 2>/dev/null | grep -m1 'certificate SHA-256 digest' | awk '{print $NF}'; }
pousser() { # pousser <fichier local> <chemin dans les données de l'application>
    adb push "$1" /data/local/tmp/fichier-test > /dev/null
    adb shell "cat /data/local/tmp/fichier-test | run-as $PKG sh -c 'cat > /data/data/$PKG/$2'"
}
deposer_base_v1() {
    adb shell am force-stop "$PKG"
    adb shell run-as "$PKG" mkdir -p "/data/data/$PKG/databases" "/data/data/$PKG/files/photos"
    adb shell run-as "$PKG" rm -f "/data/data/$PKG/databases/ecole_dimanche.db-wal" "/data/data/$PKG/databases/ecole_dimanche.db-shm"
    pousser "$SORTIES/base-test/ecole_dimanche.db" "databases/ecole_dimanche.db"
    for photo in "$SORTIES"/base-test/photos/*.jpg; do
        pousser "$photo" "files/photos/$(basename "$photo")"
    done
}
extraire_base() { # extraire_base <dossier>
    mkdir -p "$1"
    adb shell am force-stop "$PKG"
    for f in ecole_dimanche.db ecole_dimanche.db-wal ecole_dimanche.db-shm; do
        adb exec-out run-as "$PKG" sh -c "cat /data/data/$PKG/databases/$f 2>/dev/null" > "$1/$f"
    done
}

APKSIGNER="$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -1)"
AAPT2="$(dirname "$APKSIGNER")/aapt2"
VERSION_ATTENDUE="$("$AAPT2" dump badging "$NOUVEAU" | grep -m1 '^package:' | sed -E "s/.*versionName='([^']*)'.*/\1/")"
SCHEMA_ACTUEL="$(ls "$SCHEMAS"/*.json | sort -V | tail -1)"
echo "Version attendue : $VERSION_ATTENDUE ; schéma : $(basename "$SCHEMA_ACTUEL")" | tee -a "$RESUME"
adb wait-for-device
adb shell input keyevent 82
adb shell settings put system screen_off_timeout 1800000
adb shell settings put global hide_error_dialogs 1 > /dev/null 2>&1 || true
echo "Appareil : $(adb shell getprop ro.build.version.release) / API $(adb shell getprop ro.build.version.sdk)" | tee -a "$RESUME"

# --- 0. Clés de signature ---------------------------------------------------------------------
E_ANCIEN="$(empreinte "$ANCIEN")"
E_NOUVEAU="$(empreinte "$NOUVEAU")"
info "clé de l'APK 1.1.0 livré : $E_ANCIEN"
info "clé du nouvel APK        : $E_NOUVEAU"
[ "$E_ANCIEN" = "$EMPREINTE_1_1_0" ] && ok "l'APK 1.1.0 vérifié est bien celui du téléphone" || ko_donnees "l'APK 1.1.0 n'est pas celui du téléphone"
if [ "$E_NOUVEAU" = "$EMPREINTE_PERMANENTE" ]; then
    ok "le nouvel APK porte la clé permanente actuelle : les versions suivantes s'installeront par-dessus"
else
    attention "le nouvel APK N'EST PAS signé avec la clé permanente (secrets SIGNATURE_KEYSTORE_B64 / SIGNATURE_PASSWORD absents) : il devra être re-signé"
fi

# --- 1. Version 1.1.0 avec des données réalistes (base version 1) ------------------------------
adb uninstall "$PKG" > /dev/null 2>&1
adb install "$ANCIEN" | tee -a "$SORTIES/adb.log"
python3 "$ICI/creer_base_test.py" "$SCHEMAS/1.json" "$SORTIES/base-test" | tee -a "$RESUME"
deposer_base_v1
adb shell am start -n "$PKG/.MainActivity"
sleep 6
verifier_ecran "Sarah" 60
capture "01-version-1.1.0"
adb shell am force-stop "$PKG"

# --- 2. Mise à jour : par-dessus si la clé est la même, sinon avec le script de transfert ------------
SORTIE="$(adb install -r "$NOUVEAU" 2>&1)"
echo "$SORTIE" >> "$SORTIES/adb.log"
if echo "$SORTIE" | grep -q "Success"; then
    ok "mise à jour 1.1.0 -> $VERSION_ATTENDUE installée PAR-DESSUS (adb install -r), sans désinstaller"
elif echo "$SORTIE" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
    if [ "$E_NOUVEAU" = "$E_ANCIEN" ]; then
        ko_donnees "mise à jour refusée alors que les clés sont identiques : $SORTIE"
    else
        info "clé différente de la 1.1.0 installée : passage par le script de transfert (comme sur le téléphone)"
    fi
    if ADB=adb bash "$RACINE/transfert-donnees/transferer-les-donnees.sh" "$NOUVEAU" --oui > "$SORTIES/transfert.log" 2>&1; then
        ok "script de transfert terminé sans erreur (sauvegarde, remplacement, restauration)"
    else
        ko_donnees "le script de transfert a échoué (voir transfert.log)"
    fi
    cat "$SORTIES/transfert.log"
    grep -q "Sauvegarde verifiee" "$SORTIES/transfert.log" && ok "sauvegarde des données vérifiée avant désinstallation" || ko_donnees "sauvegarde non vérifiée"
else
    ko_donnees "installation du nouvel APK impossible : $SORTIE"
fi
VERSION="$(adb shell dumpsys package "$PKG" | grep -m1 versionName | tr -d ' \r')"
[ "$VERSION" = "versionName=$VERSION_ATTENDUE" ] && ok "version installée : $VERSION_ATTENDUE" || ko_donnees "version installée inattendue : $VERSION"

# --- 3. Premier lancement : migration Room 1 -> dernière version, données intactes ----------------
adb shell am start -n "$PKG/.MainActivity"
sleep 8
verifier_ecran "Sarah" 60
capture "02-nouvelle-version-apres-mise-a-jour"
extraire_base "$SORTIES/base-apres"
if python3 - "$SORTIES/base-test" "$SORTIES/base-apres" "$SCHEMA_ACTUEL" >> "$RESUME" 2>&1 <<'PY'
import json, sqlite3, sys
avant, apres, chemin_schema = sys.argv[1:4]
attendu = json.load(open(f"{avant}/attendu.json"))
schema = json.load(open(chemin_schema))["database"]
hash2 = schema["identityHash"]
base = sqlite3.connect(f"{apres}/ecole_dimanche.db")
erreurs = 0
version = base.execute("PRAGMA user_version").fetchone()[0]
if version != schema["version"]:
    print("   version de la base :", version, f"({schema['version']} attendu)"); erreurs += 1
tables = {r[0] for r in base.execute("select name from sqlite_master where type = 'table'")}
manquantes = {e["tableName"] for e in schema["entities"]} - tables
if manquantes:
    print("   tables manquantes :", manquantes); erreurs += 1
identite = base.execute("select identity_hash from room_master_table where id = 42").fetchone()
if identite is None or identite[0] != hash2:
    print("   empreinte du schéma inattendue :", identite, "attendu", hash2); erreurs += 1
parametres = dict(base.execute("select cle, valeur from parametres"))
if parametres.get("accueil.fait") != "1":
    print("   accueil.fait absent : l'écran d'accueil s'afficherait chez un utilisateur existant"); erreurs += 1
enfants = {r[0]: r for r in base.execute("select id, nom, prenom, photoPath, dateDebutSuivi, isArchived from enfants")}
for e in attendu["enfants"]:
    r = enfants.get(e["id"])
    if r is None or (r[1], r[2], r[3], r[4], bool(r[5])) != (e["nom"], e["prenom"], e["photoPath"], e["dateDebutSuivi"], e["isArchived"]):
        print("   enfant modifié ou perdu :", e, r); erreurs += 1
presences = {(r[0], r[1]): r[2] for r in base.execute("select enfantId, dateDimanche, statut from presences")}
for i, d, s in attendu["presences"]:
    if presences.get((i, d)) != s:
        print("   présence modifiée ou perdue :", i, d, s, presences.get((i, d))); erreurs += 1
print(f"   base version {version}, {len(enfants)} enfants, {len(presences)} présences, paramètres {sorted(parametres)}")
sys.exit(1 if erreurs else 0)
PY
then
    ok "migration Room 1 -> $(basename "$SCHEMA_ACTUEL" .json) : schéma exact, enfants et présences intacts, écran d'accueil non affiché"
else
    ko_donnees "migration Room : écart (détail ci-dessus)"
fi

# --- 4. Nouveautés à l'écran ----------------------------------------------------------------------
adb shell am start -n "$PKG/.MainActivity"
sleep 6
verifier_ecran "Sarah" 60
toucher_exact "Notes"
verifier_ecran "Nouvel examen"
capture "03a-onglet-notes"
toucher "Nouvel examen"
verifier_ecran "ENREGISTRER · NOTES"
capture "03b-saisie-examen"
adb shell input keyevent 4
sleep 2
toucher_exact "Données"
verifier_ecran "Sauvegarde complète"
capture "03-onglet-donnees"
toucher "Exporter en CSV"
verifier_ecran "Tout exporter"
verifier_ecran "Parents"
capture "04-export-partiel"
adb shell input keyevent 4
sleep 2
toucher "Importer un CSV"
verifier_ecran "ajout et mise à jour"
capture "05-import-mise-a-jour"
adb shell input keyevent 4
sleep 2
defiler
toucher "Repartir de zéro…"
verifier_ecran "Sauvegarder d’abord"
capture "06-repartir-de-zero"
toucher "Je comprends"
toucher_exact "Tout effacer"
verifier_ecran "COMMENCER AVEC DES DONNÉES VIERGES" 30
capture "07-accueil-donnees-vierges"
toucher "COMMENCER AVEC DES DONNÉES VIERGES"
verifier_ecran "Aucun enfant" 30
capture "08-application-vierge"

# --- 5. Nouvelle installation : l'accueil s'affiche d'emblée -----------------------------------------
adb uninstall "$PKG" > /dev/null
adb install "$NOUVEAU" > /dev/null
adb shell am start -n "$PKG/.MainActivity"
sleep 6
verifier_ecran "Restaurer une sauvegarde" 60
capture "09-premiere-installation"

echo | tee -a "$RESUME"
echo "Échecs : données $ECHECS_DONNEES, écrans $ECHECS_ECRANS" | tee -a "$RESUME"
# Les données font échouer la vérification ; les écrans aussi (textes attendus de la 1.2.0).
[ "$ECHECS_DONNEES" -eq 0 ] && [ "$ECHECS_ECRANS" -eq 0 ]
