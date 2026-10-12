#!/usr/bin/env bash
# =============================================================================================
#  Vérification sur émulateur du passage 1.0.0 -> 1.1.0 SANS PERTE DE DONNÉES, avec l'APK exact
#  installé chez l'utilisateur (clé différente), puis tour des nouveautés avec captures d'écran.
#  Usage : bash ci/test-migration.sh <ancien.apk> <nouveau.apk>
# =============================================================================================
set -uo pipefail
PKG="mg.ecoledimanche.presences"
ICI="$(cd "$(dirname "$0")" && pwd)"
RACINE="$(dirname "$ICI")"
ANCIEN="$1"
NOUVEAU="$2"
SORTIES="$RACINE/ci-sorties"
CAPTURES="$SORTIES/captures"
mkdir -p "$CAPTURES"
RESUME="$SORTIES/resume-migration.txt"
: > "$RESUME"
UI="python3 $ICI/ui.py"
ECHECS_DONNEES=0
ECHECS_ECRANS=0
EMPREINTE_ANCIEN="ac0a9a327b18afe5052fe02885dca8cc10021e6d35b1647569574c1bb8f5bafd"

ok() { echo "OK      $*" | tee -a "$RESUME"; }
ko_donnees() { echo "ECHEC   [données] $*" | tee -a "$RESUME"; ECHECS_DONNEES=$((ECHECS_DONNEES + 1)); }
ko_ecran() { echo "ECHEC   [écran] $*" | tee -a "$RESUME"; ECHECS_ECRANS=$((ECHECS_ECRANS + 1)); }
verifier_ecran() { if $UI attendre "$1" "${2:-20}" >> "$SORTIES/ui.log" 2>&1; then ok "écran : « $1 » visible"; else ko_ecran "« $1 » absent de l'écran"; fi; }
capture() { $UI capture "$CAPTURES/$1.png" > /dev/null 2>&1; }
toucher() { $UI toucher "$@" >> "$SORTIES/ui.log" 2>&1 || ko_ecran "impossible de toucher « $1 »"; }
toucher_exact() { $UI toucher-exact "$@" >> "$SORTIES/ui.log" 2>&1 || ko_ecran "impossible de toucher « $1 »"; }
pousser() { # pousser <fichier local> <chemin dans les données de l'application>
    adb push "$1" /data/local/tmp/fichier-test > /dev/null
    adb shell "cat /data/local/tmp/fichier-test | run-as $PKG sh -c 'cat > /data/data/$PKG/$2'"
}
extraire_base() { # extraire_base <dossier>
    mkdir -p "$1"
    adb shell am force-stop "$PKG"
    for f in ecole_dimanche.db ecole_dimanche.db-wal ecole_dimanche.db-shm; do
        adb exec-out run-as "$PKG" sh -c "cat /data/data/$PKG/databases/$f 2>/dev/null" > "$1/$f"
    done
}

adb wait-for-device
adb shell input keyevent 82
adb shell settings put system screen_off_timeout 1800000
echo "Appareil : $(adb shell getprop ro.build.version.release) / API $(adb shell getprop ro.build.version.sdk)" | tee -a "$RESUME"

# --- 0. L'ancien APK est bien celui de l'utilisateur -------------------------------------------
if [ "$(sha256sum "$ANCIEN" | cut -d' ' -f1)" = "$EMPREINTE_ANCIEN" ]; then
    ok "ancien APK identique à celui de l'utilisateur (SHA-256 $EMPREINTE_ANCIEN)"
else
    ko_donnees "l'ancien APK n'est pas celui de l'utilisateur"
fi

echo "Nouvel APK vérifié : SHA-256 $(sha256sum "$NOUVEAU" | cut -d' ' -f1)" | tee -a "$RESUME"

# --- 1. Ancienne version avec des données réalistes ------------------------------------------
adb install "$ANCIEN" | tee -a "$SORTIES/adb.log"
python3 "$ICI/creer_base_test.py" "$RACINE/Ecole-du-dimance/app/schemas/mg.ecoledimanche.presences.data.local.AppDatabase/1.json" "$SORTIES/base-test" | tee -a "$RESUME"
adb shell run-as "$PKG" mkdir -p "/data/data/$PKG/databases" "/data/data/$PKG/files/photos"
pousser "$SORTIES/base-test/ecole_dimanche.db" "databases/ecole_dimanche.db"
for photo in "$SORTIES"/base-test/photos/*.jpg; do
    pousser "$photo" "files/photos/$(basename "$photo")"
done
adb shell am start -n "$PKG/.MainActivity"
sleep 6
verifier_ecran "Sarah" 60
capture "01-ancienne-version-1.0.0"
# Un pointage fait dans l'ancienne version (dimanche à venir) : il doit survivre au transfert.
toucher "Sarah Rakoto"
sleep 1
toucher_exact "PRÉSENT"
sleep 2
capture "02-ancienne-version-sarah-pointee"
# Dimanche affiché par défaut (celui du jour, sinon le prochain) : c'est là que Sarah vient d'être pointée.
PROCHAIN="$(python3 -c "import datetime as d;t=d.date.today();print(t+d.timedelta(days=6-t.weekday()))")"
echo "Dimanche à venir : $PROCHAIN" | tee -a "$RESUME"
adb shell am force-stop "$PKG"

# --- 2. Transfert avec le script fourni à l'utilisateur ---------------------------------------
if ADB=adb bash "$RACINE/transfert-donnees/transferer-les-donnees.sh" "$NOUVEAU" --oui > "$SORTIES/transfert-1.log" 2>&1; then
    ok "script de transfert terminé sans erreur"
else
    ko_donnees "le script de transfert a échoué (voir transfert-1.log)"
fi
cat "$SORTIES/transfert-1.log"
grep -q "Cle differente" "$SORTIES/transfert-1.log" && ok "clé différente détectée : sauvegarde / restauration utilisées" || ko_donnees "le chemin « clé différente » n'a pas été pris"
VERSION="$(adb shell dumpsys package "$PKG" | grep -m1 versionName | tr -d ' \r')"
[ "$VERSION" = "versionName=1.1.0" ] && ok "version installée : 1.1.0" || ko_donnees "version installée inattendue : $VERSION"

# --- 3. Les données sont intactes --------------------------------------------------------------
sleep 5
extraire_base "$SORTIES/base-apres"
for photo in "$SORTIES"/base-test/photos/*.jpg; do
    nom="$(basename "$photo")"
    adb exec-out run-as "$PKG" cat "/data/data/$PKG/files/photos/$nom" > "$SORTIES/base-apres/$nom"
done
if python3 - "$SORTIES/base-test" "$SORTIES/base-apres" "$PROCHAIN" >> "$RESUME" 2>&1 <<'PY'
import hashlib, json, sqlite3, sys
avant, apres, prochain = sys.argv[1:4]
attendu = json.load(open(f"{avant}/attendu.json"))
base = sqlite3.connect(f"{apres}/ecole_dimanche.db")
erreurs = 0
enfants = {r[0]: r for r in base.execute("select id, nom, prenom, photoPath, dateDebutSuivi, isArchived, adresse, nomPere from enfants")}
for e in attendu["enfants"]:
    r = enfants.get(e["id"])
    if r is None or (r[1], r[2], r[3], r[4], bool(r[5])) != (e["nom"], e["prenom"], e["photoPath"], e["dateDebutSuivi"], e["isArchived"]):
        print("   enfant modifié ou perdu :", e, r); erreurs += 1
presences = {(r[0], r[1]): r[2] for r in base.execute("select enfantId, dateDimanche, statut from presences")}
for i, d, s in attendu["presences"]:
    if presences.get((i, d)) != s:
        print("   présence modifiée ou perdue :", i, d, s, presences.get((i, d))); erreurs += 1
if presences.get((1, prochain)) != "PRESENT":
    print("   le pointage fait dans l'ancienne version a été perdu :", presences.get((1, prochain))); erreurs += 1
for chemin, taille in attendu["photos"].items():
    nom = chemin.split("/")[-1]
    a = hashlib.sha256(open(f"{avant}/photos/{nom}", "rb").read()).hexdigest()
    b = hashlib.sha256(open(f"{apres}/{nom}", "rb").read()).hexdigest()
    if a != b:
        print("   photo différente ou perdue :", chemin); erreurs += 1
print(f"   {len(enfants)} enfants, {len(presences)} présences, {len(attendu['photos'])} photos vérifiées après transfert")
print(f"   adresse et parents conservés : {enfants[1][6]!r}, {enfants[1][7]!r}")
sys.exit(1 if erreurs else 0)
PY
then
    ok "données identiques après transfert : enfants, fiches complètes, présences, pointage récent, photos (octet pour octet)"
else
    ko_donnees "des données diffèrent après transfert (détail ci-dessus)"
fi

# --- 4. Nouveautés à l'écran ------------------------------------------------------------------
adb shell am start -n "$PKG/.MainActivity"
sleep 6
verifier_ecran "Sarah" 60
capture "03-nouvelle-version-dimanche-grille-3-colonnes"
$UI ordre > "$SORTIES/ordre-grille.txt" 2>&1
cat "$SORTIES/ordre-grille.txt" | tee -a "$RESUME"
if [ "$(tail -n 1 "$SORTIES/ordre-grille.txt")" = "Sarah Rakoto : Présent" ]; then
    ok "grille : l'enfant pointé est en bas, les non pointés en haut"
else
    ko_ecran "ordre de la grille inattendu"
fi
toucher "Dimanche précédent"
sleep 2
verifier_ecran "Séance clôturée"
verifier_ecran "Non inscrit"
capture "04-dimanche-passe-cloture-non-inscrits-grises"
toucher "Zo Rafidy"
sleep 1
verifier_ecran "pas encore inscrit"
capture "05-saisie-avant-inscription"
toucher_exact "PRÉSENT"
sleep 2
verifier_ecran "Zo Rafidy : Présent"
capture "06-zo-saisi-avant-inscription"

toucher_exact "Enfants"
sleep 2
verifier_ecran "Importer"
capture "07-enfants-bouton-importer"
toucher_exact "Archivés"
sleep 2
verifier_ecran "Mamy"
capture "08-archives-bouton-desarchiver"
toucher_exact "Désarchiver"
sleep 1
capture "09-confirmation-desarchivage"
toucher_exact "Désarchiver"
sleep 2
verifier_ecran "Aucun enfant archivé"
toucher_exact "Actifs"
sleep 2
verifier_ecran "Mamy"

toucher_exact "Importer"
sleep 2
verifier_ecran "Choisir un fichier CSV"
capture "10-ecran-import-csv"
# Import réel par le sélecteur de fichiers du système (au mieux : l'interface du sélecteur varie).
printf 'Nom;Prénom;Sexe;Date de naissance\r\nRavelo;Fanja;Fille;03/04/2016\r\nRavelo;Tojo;Garçon;12/09/2014\r\nRakoto;Sarah;Fille;15/03/2017\r\n' > "$SORTIES/enfants-import.csv"
adb push "$SORTIES/enfants-import.csv" /sdcard/Download/enfants-import.csv > /dev/null
toucher "Choisir un fichier CSV"
sleep 3
capture "11-selecteur-fichiers"
# Toucher le NOM du fichier (texte exact), pas l'icône « Preview the file … ».
if ! $UI toucher-exact "enfants-import.csv" >> "$SORTIES/ui.log" 2>&1; then
    $UI toucher "Show roots" >> "$SORTIES/ui.log" 2>&1 || $UI toucher "Afficher les racines" >> "$SORTIES/ui.log" 2>&1
    sleep 1
    $UI toucher-exact "Downloads" >> "$SORTIES/ui.log" 2>&1 || $UI toucher-exact "Download" >> "$SORTIES/ui.log" 2>&1
    sleep 2
    toucher_exact "enfants-import.csv"
fi
sleep 3
verifier_ecran "2 enfants prêts à être ajoutés"
verifier_ecran "Déjà inscrit"
capture "12-apercu-import"
toucher "AJOUTER 2 ENFANTS"
sleep 2
verifier_ecran "2 enfants ajoutés"
capture "13-import-termine"
toucher "Voir la liste des enfants"
sleep 2
verifier_ecran "Fanja Ravelo"

# Si une étape précédente a échoué, revenir à la liste des enfants.
$UI attendre "Hery Razafy" 3 > /dev/null 2>&1 || { adb shell input keyevent 4; sleep 1; adb shell input keyevent 4; sleep 1; toucher_exact "Enfants"; sleep 2; }
toucher "Hery Razafy"
sleep 2
verifier_ecran "Supprimer définitivement"
capture "14-fiche-supprimer-definitivement"
toucher "Ajouter une photo"
sleep 1
verifier_ecran "Choisir dans la galerie"
capture "15-choix-camera-ou-galerie"
toucher "Choisir dans la galerie"
sleep 4
capture "16-selecteur-photos-systeme"
adb shell input keyevent 4
sleep 2
capture "17-retour-apres-galerie"
adb shell am start -n "$PKG/.MainActivity" > /dev/null
sleep 2
$UI attendre "Supprimer définitivement" 5 > /dev/null 2>&1 || { toucher_exact "Enfants"; sleep 1; toucher "Hery Razafy"; sleep 2; }
toucher "Supprimer définitivement"
sleep 1
capture "18-confirmation-suppression"
toucher "Je comprends"
toucher_exact "Supprimer"
sleep 3
capture "19-apres-suppression"
$UI attendre "Hery" 3 > /dev/null 2>&1 && ko_ecran "Hery est encore affiché après suppression" || ok "écran : Hery n'apparaît plus"

# --- 5. Mise à jour future : même clé, mise à jour directe -------------------------------------
if ADB=adb bash "$RACINE/transfert-donnees/transferer-les-donnees.sh" "$NOUVEAU" --oui > "$SORTIES/transfert-2.log" 2>&1 \
    && grep -q "Mise a jour directe reussie" "$SORTIES/transfert-2.log"; then
    ok "mise à jour suivante (même clé) : installation directe, sans sauvegarde"
else
    ko_donnees "la mise à jour directe (même clé) n'a pas fonctionné (voir transfert-2.log)"
fi
sleep 4
extraire_base "$SORTIES/base-finale"
if python3 - "$SORTIES/base-finale" "$SORTIES/base-test" >> "$RESUME" 2>&1 <<'PY'
import json, sqlite3, sys
base = sqlite3.connect(f"{sys.argv[1]}/ecole_dimanche.db")
attendu = json.load(open(f"{sys.argv[2]}/attendu.json"))
s0 = attendu["dimanches"]["s0"]
erreurs = 0
def verifier(condition, message):
    global erreurs
    print(("   ok : " if condition else "   ÉCHEC : ") + message)
    erreurs += 0 if condition else 1
enfants = {r[1]: r for r in base.execute("select id, prenom, dateDebutSuivi, isArchived, dateFinSuivi from enfants")}
verifier("Hery" not in enfants and base.execute("select count(*) from presences where enfantId = 6").fetchone()[0] == 0, "Hery supprimé avec son historique")
verifier(enfants.get("Mamy", (0, 0, 0, 1, 0))[3] == 0 and enfants["Mamy"][4] is None, "Mamy désarchivé")
verifier(enfants.get("Zo", (0, 0, ""))[2] == s0, f"suivi de Zo avancé au {s0}")
verifier(base.execute("select statut from presences p join enfants e on e.id = p.enfantId where e.prenom = 'Zo' and p.dateDimanche = ?", (s0,)).fetchone() == ("PRESENT",), "Zo présent le " + s0)
verifier("Fanja" in enfants and "Tojo" in enfants, "Fanja et Tojo importés depuis le CSV")
verifier(sum(1 for p in enfants if p == "Sarah") == 1, "Sarah n'a pas été importée en double")
verifier(base.execute("select count(*) from enfants where prenom = 'Sarah'").fetchone()[0] == 1, "une seule Sarah")
sys.exit(1 if erreurs else 0)
PY
then
    ok "base finale cohérente (suppression, désarchivage, saisie avant inscription, import CSV)"
else
    ko_ecran "base finale : au moins une nouveauté n'a pas été appliquée via l'écran (détail ci-dessus)"
fi
adb shell am start -n "$PKG/.MainActivity" > /dev/null
sleep 4
capture "20-apres-mise-a-jour-directe"

echo "" | tee -a "$RESUME"
echo "Échecs données : $ECHECS_DONNEES ; échecs écran : $ECHECS_ECRANS" | tee -a "$RESUME"
# Seuls les contrôles de DONNÉES font échouer le job ; les contrôles d'écran sont relus sur les captures.
[ "$ECHECS_DONNEES" -eq 0 ]
