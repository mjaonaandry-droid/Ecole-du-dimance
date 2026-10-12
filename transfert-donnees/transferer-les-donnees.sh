#!/usr/bin/env bash
# =============================================================================================
#  Ecole du Dimanche - installation de la nouvelle version SANS PERTE DE DONNEES (Mac / Linux).
#  Meme logique, commande pour commande, que TRANSFERER-LES-DONNEES.bat (Windows).
#
#  1. Essaie d'abord une mise a jour directe (adb install -r) : si l'APK porte la meme cle que
#     l'application installee, c'est fini, les donnees sont conservees par Android.
#  2. Sinon (cle differente) : sauvegarde de la base et des photos sur l'ordinateur, verification
#     de la sauvegarde, desinstallation, installation de la nouvelle version, restauration.
#  Rien n'est desinstalle tant que la sauvegarde n'est pas verifiee.
#
#  Usage : ./transferer-les-donnees.sh [chemin/vers/nouvelle.apk] [--oui]
#  Variable ADB : chemin d'adb (par defaut ./platform-tools/adb, sinon adb du PATH).
# =============================================================================================
set -u

PKG="mg.ecoledimanche.presences"
DOSSIER="$(cd "$(dirname "$0")" && pwd)"
TMP_TEL="/data/local/tmp/ecole-sauvegarde.tar"
DONNEES="/data/data/$PKG"
JOURNAL="$DOSSIER/journal-transfert.txt"
SANS_QUESTION=0
APK=""
for argument in "$@"; do
    case "$argument" in
        --oui) SANS_QUESTION=1 ;;
        *) APK="$argument" ;;
    esac
done

if [ -z "${ADB:-}" ]; then
    if [ -x "$DOSSIER/platform-tools/adb" ]; then ADB="$DOSSIER/platform-tools/adb"; else ADB="adb"; fi
fi
if [ -z "$APK" ]; then
    APK="$(ls "$DOSSIER"/*.apk 2>/dev/null | head -n 1)"
fi

journal() { echo "$*" | tee -a "$JOURNAL"; }
echec() {
    journal ""
    journal "ECHEC : $*"
    exit 1
}
attendre() { [ "$SANS_QUESTION" = 1 ] || read -r -p "$1" _; }

journal "=== Transfert $(date '+%Y-%m-%d %H:%M:%S') ==="
command -v "$ADB" >/dev/null 2>&1 || [ -x "$ADB" ] || echec "adb introuvable. Placez le dossier platform-tools a cote de ce script."
[ -f "$APK" ] || echec "Nouvel APK introuvable. Placez le fichier .apk a cote de ce script."
journal "APK : $APK"

# --- 1. Telephone connecte et autorise ---------------------------------------------------------
"$ADB" start-server >/dev/null 2>&1
ETAT="$("$ADB" get-state 2>&1)"
journal "Etat du telephone : $ETAT"
case "$ETAT" in
    *unauthorized*) echec "Le telephone n'a pas autorise cet ordinateur : deverrouillez-le et acceptez « Autoriser le debogage USB »." ;;
    *"more than one"*) echec "Plusieurs telephones sont branches : n'en laissez qu'un." ;;
    device) ;;
    *) echec "Aucun telephone detecte : verifiez le cable et le debogage USB." ;;
esac

# --- 2. Application installee ? ----------------------------------------------------------------
SAUVEGARDE=""
if "$ADB" shell pm path "$PKG" 2>&1 | grep -q "package:"; then
    # 2a. Mise a jour directe : fonctionne si la cle est la meme (versions futures).
    journal "Essai de mise a jour directe..."
    SORTIE="$("$ADB" install -r "$APK" 2>&1)"
    journal "$SORTIE"
    if echo "$SORTIE" | grep -q "Success"; then
        journal "Mise a jour directe reussie : les donnees sont conservees par Android."
        "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
        exit 0
    fi
    echo "$SORTIE" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE" \
        || echec "Installation refusee pour une autre raison (voir ci-dessus). Rien n'a ete modifie."

    # 2b. Cle differente : sauvegarde complete d'abord.
    journal "Cle differente : sauvegarde des donnees avant remplacement."
    "$ADB" shell run-as "$PKG" ls >/dev/null 2>&1 \
        || echec "Impossible de lire les donnees de l'application installee (application non « debug »). Rien n'a ete modifie."
    "$ADB" shell run-as "$PKG" tar --help >/dev/null 2>&1 \
        || echec "Ce telephone n'a pas la commande tar. Rien n'a ete modifie."
    "$ADB" shell am force-stop "$PKG"
    "$ADB" shell run-as "$PKG" mkdir -p "$DONNEES/files" "$DONNEES/databases"
    "$ADB" shell rm -f "$TMP_TEL"
    "$ADB" shell "run-as $PKG tar -cf - -C $DONNEES databases files | cat > $TMP_TEL"
    LISTE="$("$ADB" shell "tar -tf $TMP_TEL" 2>&1)"
    echo "$LISTE" | grep -q "databases/ecole_dimanche.db" \
        || echec "La sauvegarde ne contient pas la base de donnees. Rien n'a ete desinstalle."
    NB_PHOTOS="$(echo "$LISTE" | grep -c "files/photos/.*\.jpg" || true)"
    mkdir -p "$DOSSIER/sauvegardes"
    SAUVEGARDE="$DOSSIER/sauvegardes/sauvegarde-$(date '+%Y%m%d-%H%M%S').tar"
    "$ADB" pull "$TMP_TEL" "$SAUVEGARDE" >/dev/null 2>&1 || echec "Copie de la sauvegarde sur l'ordinateur impossible. Rien n'a ete desinstalle."
    TAILLE_TEL="$("$ADB" exec-out stat -c %s "$TMP_TEL" | tr -d '\r\n ')"
    TAILLE_PC="$(wc -c < "$SAUVEGARDE" | tr -d ' ')"
    journal "Sauvegarde : $SAUVEGARDE ($TAILLE_PC octets, $NB_PHOTOS photo(s))"
    [ -n "$TAILLE_TEL" ] && [ "$TAILLE_TEL" = "$TAILLE_PC" ] \
        || echec "La copie sur l'ordinateur est incomplete ($TAILLE_PC / $TAILLE_TEL octets). Rien n'a ete desinstalle."
    journal "Sauvegarde verifiee."
    attendre "La sauvegarde est verifiee. Appuyez sur Entree pour remplacer l'application..."

    # 2c. Remplacement.
    SORTIE="$("$ADB" uninstall "$PKG" 2>&1)"
    journal "$SORTIE"
    echo "$SORTIE" | grep -q "Success" || echec "Desinstallation impossible. Les donnees sont toujours dans l'application."
else
    # Reprise apres une interruption : l'application a deja ete desinstallee, on repart de la derniere sauvegarde.
    SAUVEGARDE="$(ls -t "$DOSSIER"/sauvegardes/sauvegarde-*.tar 2>/dev/null | head -n 1)"
    if [ -n "$SAUVEGARDE" ]; then
        journal "Application absente : reprise avec la sauvegarde $SAUVEGARDE"
    else
        journal "Application absente et aucune sauvegarde : simple installation."
    fi
fi

# --- 3. Installation de la nouvelle version ---------------------------------------------------
SORTIE="$("$ADB" install "$APK" 2>&1)"
journal "$SORTIE"
echo "$SORTIE" | grep -q "Success" \
    || echec "Installation de la nouvelle version impossible. Vos donnees sont en securite dans : ${SAUVEGARDE:-(aucune)}. Relancez ce script."

# --- 4. Restauration ----------------------------------------------------------------------------
if [ -n "$SAUVEGARDE" ]; then
    "$ADB" shell am force-stop "$PKG"
    "$ADB" push "$SAUVEGARDE" "$TMP_TEL" >/dev/null 2>&1 || echec "Envoi de la sauvegarde vers le telephone impossible. Relancez ce script."
    "$ADB" shell run-as "$PKG" rm -rf "$DONNEES/databases" "$DONNEES/files"
    "$ADB" shell "cat $TMP_TEL | run-as $PKG tar -xf - -C $DONNEES"
    "$ADB" shell run-as "$PKG" ls "$DONNEES/databases" 2>&1 | grep -q "ecole_dimanche.db" \
        || echec "La restauration n'a pas abouti. Vos donnees sont en securite dans : $SAUVEGARDE. Relancez ce script."
    "$ADB" shell rm -f "$TMP_TEL"
    journal "Donnees restaurees."
fi

"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
journal ""
journal "TERMINE : la nouvelle version est installee avec vos donnees. Verifiez la liste des enfants."
exit 0
