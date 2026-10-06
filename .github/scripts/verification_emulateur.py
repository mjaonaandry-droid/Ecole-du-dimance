#!/usr/bin/env python3
"""Vérification de l'application sur un émulateur Android, exécutée par la CI (job « emulateur »).

Le script pilote l'application installée comme un utilisateur (adb + uiautomator) et n'affiche que des
faits observés : chaque contrôle donne « OK » ou « ÉCHEC », et chaque échec montre les textes visibles
à l'écran. Rien ici ne remplace un essai sur un vrai téléphone : l'émulateur n'a pas la vraie caméra.

Code de sortie : 0 si tous les contrôles sont OK, 1 sinon.
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "mg.ecoledimanche.presences"
APK = "apks/app-debug.apk"
RESULTATS = []  # (nom, ok)


# ------------------------------------------------------------------------------------------------
# adb
# ------------------------------------------------------------------------------------------------
def sh(commande, timeout=90):
    """Commande shell sur l'appareil ; retourne la sortie standard."""
    try:
        r = subprocess.run(["adb", "shell", commande], capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return ""
    return r.stdout.replace("\r", "")


def adb(*args, timeout=180):
    try:
        r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return "(délai dépassé)"
    return (r.stdout + r.stderr).replace("\r", "")


def octets_appli(chemin):
    """Contenu binaire d'un fichier du dossier privé de l'application (APK de débogage : run-as autorisé)."""
    r = subprocess.run(["adb", "exec-out", "run-as", PKG, "cat", chemin], capture_output=True, timeout=60)
    return r.stdout


# ------------------------------------------------------------------------------------------------
# Lecture de l'écran (uiautomator) et gestes
# ------------------------------------------------------------------------------------------------
class Noeud:
    def __init__(self, element):
        a = element.attrib
        self.texte = a.get("text", "")
        self.desc = a.get("content-desc", "")
        self.classe = a.get("class", "")
        self.cliquable = a.get("clickable") == "true"
        self.actif = a.get("enabled") == "true"
        self.resid = a.get("resource-id", "")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get("bounds", ""))
        self.x1, self.y1, self.x2, self.y2 = (int(v) for v in m.groups()) if m else (0, 0, 0, 0)

    @property
    def centre(self):
        return (self.x1 + self.x2) // 2, (self.y1 + self.y2) // 2

    def __repr__(self):
        return f"<{self.classe.split('.')[-1]} texte={self.texte!r} desc={self.desc!r} [{self.x1},{self.y1}][{self.x2},{self.y2}]>"


def ecran():
    """Noeuds visibles. uiautomator échoue parfois quand l'écran s'anime : on réessaie."""
    for _ in range(6):
        sh("rm -f /sdcard/ui.xml")
        sortie = sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml", timeout=60)
        debut = sortie.find("<hierarchy")
        if debut >= 0:
            try:
                return [Noeud(e) for e in ET.fromstring(sortie[debut:]).iter("node")]
            except ET.ParseError:
                pass
        time.sleep(1.5)
    return []


def fermer_boites_systeme(noeuds):
    """« L'application ne répond pas » et autres boîtes du système : on attend, on ne ferme pas l'appli."""
    for n in noeuds:
        if n.resid.endswith("aerr_wait"):
            print("   (boîte système « ne répond pas » : Attendre)")
            toucher(n)
            return True
    return False


def trouve(noeuds, texte=None, motif=None, resid_fin=None, classe=None, cliquable=None, bas=None, ecran_h=None):
    resultat = []
    for n in noeuds:
        if n.x2 <= n.x1 or n.y2 <= n.y1:
            continue
        if texte is not None and texte not in (n.texte, n.desc):
            continue
        if motif is not None and not (re.search(motif, n.texte) or re.search(motif, n.desc)):
            continue
        if resid_fin is not None and not n.resid.endswith(resid_fin):
            continue
        if classe is not None and not n.classe.endswith(classe):
            continue
        if cliquable is not None and n.cliquable != cliquable:
            continue
        if bas is not None and ecran_h is not None and (n.y1 + n.y2) / 2 < ecran_h * bas:
            continue
        resultat.append(n)
    return resultat


def textes_visibles(noeuds):
    vus = []
    for n in noeuds:
        for t in (n.texte, n.desc):
            t = t.strip()
            if t and t not in vus:
                vus.append(t)
    return vus


def attendre(condition, delai=30, pas=1.5):
    """Relit l'écran jusqu'à ce que `condition(noeuds)` soit vraie ; retourne (valeur, derniers noeuds)."""
    fin = time.time() + delai
    noeuds = []
    while True:
        noeuds = ecran()
        if fermer_boites_systeme(noeuds):
            noeuds = ecran()
        valeur = condition(noeuds)
        if valeur:
            return valeur, noeuds
        if time.time() > fin:
            return None, noeuds
        time.sleep(pas)


def toucher(noeud):
    x, y = noeud.centre
    sh(f"input tap {x} {y}")
    time.sleep(0.8)


def toucher_xy(x, y):
    sh(f"input tap {x} {y}")
    time.sleep(0.8)


def taper(texte):
    sh("input text '" + texte.replace(" ", "%s") + "'")
    time.sleep(0.5)


HAUTEUR = 2400
LARGEUR = 1080


def lire_taille():
    global LARGEUR, HAUTEUR
    m = re.search(r"(\d+)x(\d+)", sh("wm size"))
    if m:
        LARGEUR, HAUTEUR = int(m.group(1)), int(m.group(2))


def barre_nav(libelle, noeuds):
    """Onglet de la barre basse (le libellé peut aussi être un titre d'écran : on ne garde que le bas)."""
    c = trouve(noeuds, texte=libelle, bas=0.85, ecran_h=HAUTEUR)
    return c[0] if c else None


def aller_a(libelle):
    noeud, noeuds = attendre(lambda ns: barre_nav(libelle, ns), delai=20)
    if not noeud:
        return False
    toucher(noeud)
    return True


# ------------------------------------------------------------------------------------------------
# Contrôles
# ------------------------------------------------------------------------------------------------
def controle(nom, ok, detail=""):
    RESULTATS.append((nom, bool(ok)))
    print(("   OK      " if ok else "   ÉCHEC   ") + nom + (f" — {detail}" if detail else ""), flush=True)
    return bool(ok)


def montrer(noeuds, titre="Écran"):
    print(f"   [{titre}] textes visibles : {textes_visibles(noeuds)}", flush=True)


def etape(titre):
    print(f"\n=== {titre}", flush=True)


def plantage():
    """Texte du journal de plantages d'Android (vide = aucun plantage)."""
    return sh("logcat -b crash -d").strip()


def est_dimanche_affiche(noeuds):
    return trouve(noeuds, motif=r"^DIMANCHE \d{1,2} \S+ \d{4}$")


def dimensions_jpeg(donnees):
    i = 2
    while i + 9 <= len(donnees):
        if donnees[i] != 0xFF:
            i += 1
            continue
        marqueur = donnees[i + 1]
        if marqueur in (0xC0, 0xC1, 0xC2):
            return int.from_bytes(donnees[i + 7:i + 9], "big"), int.from_bytes(donnees[i + 5:i + 7], "big")
        if marqueur == 0xD8 or 0xD0 <= marqueur <= 0xD7 or marqueur == 0x01:
            i += 2
            continue
        i += 2 + int.from_bytes(donnees[i + 2:i + 4], "big")
    return None


# ------------------------------------------------------------------------------------------------
# Scénario
# ------------------------------------------------------------------------------------------------
def infos():
    etape("Appareil")
    lire_taille()
    print("   Android", sh("getprop ro.build.version.release").strip(), "(API", sh("getprop ro.build.version.sdk").strip() + ")",
          "|", sh("getprop ro.product.model").strip(), "|", LARGEUR, "x", HAUTEUR, "|", sh("wm density").strip())
    print("   Heure de l'appareil :", sh("date").strip(), "| fuseau :", sh("getprop persist.sys.timezone").strip())


def installer():
    etape("Installation de l'APK de débogage")
    sortie = adb("install", "-r", "-t", APK)
    controle("APK installé", "Success" in sortie, sortie.strip().splitlines()[-1] if sortie.strip() else "")
    sh(f"pm revoke {PKG} android.permission.CAMERA")
    paquet = sh(f"dumpsys package {PKG}")
    m = re.search(r"requested permissions:(.*?)(install permissions:|runtime permissions:|User 0)", paquet, re.S)
    demandees = [l.strip() for l in (m.group(1).strip().splitlines() if m else [])]
    print("   Permissions demandées :", demandees)
    controle("Android connaît la liste des permissions demandées", len(demandees) > 0)
    controle("Permission INTERNET absente côté Android", not any("android.permission.INTERNET" in p for p in demandees))


def mode_avion():
    etape("Mode avion")
    sh("cmd connectivity airplane-mode enable")
    sh("svc wifi disable")
    sh("svc data disable")
    time.sleep(4)
    valeur = sh("settings get global airplane_mode_on").strip()
    ping = sh("ping -c 1 -W 2 8.8.8.8; echo code=$?")
    controle("Mode avion activé", valeur == "1", f"airplane_mode_on={valeur}")
    controle("Aucun accès réseau", "code=0" not in ping, ping.strip().splitlines()[-1] if ping.strip() else "")


def demarrage():
    etape("Démarrage de l'application (mode avion)")
    sh("logcat -c")
    sortie = adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")
    print("  ", " | ".join(l.strip() for l in sortie.splitlines() if "Status" in l or "TotalTime" in l or "Error" in l))
    valeur, noeuds = attendre(est_dimanche_affiche, delai=90)
    montrer(noeuds, "Accueil")
    controle("L'écran Dimanche s'affiche (titre « DIMANCHE … »)", valeur, valeur[0].texte or valeur[0].desc if valeur else "")
    for libelle in ("Dimanche", "Enfants", "Ajouter", "Assiduité"):
        controle(f"Onglet « {libelle} » dans la barre basse", barre_nav(libelle, noeuds))
    controle("État vide lisible, aucune donnée d'exemple", trouve(noeuds, texte="Aucun enfant prévu ce dimanche"))
    controle("Processus vivant", sh(f"pidof {PKG}").strip() != "")
    controle("Aucun plantage dans le journal Android", plantage() == "", plantage()[:300])


def refus_permission():
    etape("Permission caméra : refus, puis annulation de l'ajout")
    if not aller_a("Ajouter"):
        return controle("Bouton Ajouter touché", False)
    refuser, noeuds = attendre(lambda ns: trouve(ns, resid_fin="permission_deny_button"), delai=25)
    montrer(noeuds, "Après « Ajouter »")
    if not controle("Demande de permission affichée au premier usage", refuser):
        return
    toucher(refuser[0])
    ok, noeuds = attendre(lambda ns: trouve(ns, texte="Autorisation de la caméra"), delai=20)
    montrer(noeuds, "Après refus")
    controle("Explication en français après refus", ok)
    controle("Bouton « Autoriser la caméra » (Réessayer)", trouve(noeuds, texte="Autoriser la caméra"))
    controle("Bouton « Annuler l’ajout »", trouve(noeuds, motif=r"^Annuler l.ajout$"))
    annuler = trouve(noeuds, motif=r"^Annuler l.ajout$")
    if annuler:
        toucher(annuler[0])
        ok, noeuds = attendre(est_dimanche_affiche, delai=20)
        controle("Retour à Dimanche, rien n'est bloqué (les autres fonctions restent accessibles)", ok)
        controle("Aucune fiche créée", trouve(noeuds, texte="Aucun enfant prévu ce dimanche"))


def photo(titre):
    """Du clic sur « Ajouter » jusqu'au formulaire : permission accordée, capture, aperçu, validation."""
    if not aller_a("Ajouter"):
        return controle(f"{titre} : bouton Ajouter touché", False)
    autoriser, noeuds = attendre(lambda ns: trouve(ns, resid_fin="permission_allow_foreground_only_button") or trouve(ns, desc="Prendre la photo"), delai=25)
    if autoriser and autoriser[0].resid.endswith("permission_allow_foreground_only_button"):
        toucher(autoriser[0])
    # Caméra : l'aperçu anime l'écran, uiautomator peut échouer ; repli sur la position du bouton de capture.
    declencheur, noeuds = attendre(lambda ns: trouve(ns, desc="Prendre la photo"), delai=30)
    if declencheur:
        time.sleep(3)  # laisse l'aperçu CameraX démarrer
        toucher(declencheur[0])
    else:
        print("   (bouton de capture introuvable par uiautomator : repli sur sa position)")
        time.sleep(6)
        toucher_xy(LARGEUR // 2, HAUTEUR - 230)
    utiliser, noeuds = attendre(lambda ns: trouve(ns, texte="Utiliser cette photo"), delai=60)
    montrer(noeuds, f"{titre} : aperçu")
    return utiliser


def parcours_photo_et_formulaire():
    etape("Ajout d'un enfant : caméra, aperçu, reprise, validation")
    utiliser = photo("Première capture")
    if not controle("Caméra ouverte, photo prise, aperçu « Utiliser cette photo » affiché", utiliser):
        return False
    controle("Fichier temporaire créé dans le dossier privé (photos_tmp)", ".jpg" in sh(f"run-as {PKG} ls files/photos_tmp"))
    reprendre = trouve(ecran(), texte="Reprendre")
    if controle("Bouton « Reprendre » présent", reprendre):
        toucher(reprendre[0])
        declencheur, noeuds = attendre(lambda ns: trouve(ns, desc="Prendre la photo"), delai=30)
        controle("« Reprendre » rouvre la caméra", declencheur)
        if declencheur:
            time.sleep(3)
            toucher(declencheur[0])
        utiliser, noeuds = attendre(lambda ns: trouve(ns, texte="Utiliser cette photo"), delai=60)
        if not controle("Deuxième photo prise, aperçu affiché", utiliser):
            return False
    toucher(utiliser[0])
    ok, noeuds = attendre(lambda ns: trouve(ns, motif=r"^ENREGISTRER L.ENFANT$"), delai=30)
    montrer(noeuds, "Formulaire")
    controle("Formulaire affiché après validation de la photo", ok)
    return bool(ok)


def champs(noeuds):
    return sorted(trouve(noeuds, classe="EditText"), key=lambda n: (n.y1, n.x1))


def remplir_et_enregistrer(nom, prenom, sexe):
    etape(f"Formulaire : validation puis saisie de {prenom} {nom}")
    ok, noeuds = attendre(lambda ns: trouve(ns, motif=r"^ENREGISTRER L.ENFANT$"), delai=20)
    if not ok:
        return controle("Bouton ENREGISTRER L’ENFANT trouvé", False)
    toucher(ok[0])
    time.sleep(1.5)
    noeuds = ecran()
    erreurs = trouve(noeuds, texte="Ce champ est obligatoire.")
    controle("Validation : erreurs près des champs obligatoires vides", len(erreurs) >= 2, f"{len(erreurs)} message(s) visibles")

    liste = champs(noeuds)
    print("   Champs de saisie :", [(c.texte, c.actif) for c in liste])
    if len(liste) < 4:
        return controle("Champs de saisie détectés", False, f"{len(liste)} trouvés")
    toucher(liste[0])
    taper(nom)
    noeuds = ecran()
    liste = champs(noeuds)
    toucher(liste[1])
    taper(prenom)
    sh("input keyevent 4")  # BACK : ferme le clavier
    time.sleep(1)

    noeuds = ecran()
    liste = champs(noeuds)
    toucher(liste[2])  # date de naissance : ouvre le calendrier
    ok, noeuds = attendre(lambda ns: trouve(ns, texte="OK", cliquable=True) and trouve(ns, texte="15"), delai=20)
    montrer(noeuds, "Calendrier")
    if not controle("Calendrier de la date de naissance affiché", ok):
        return False
    toucher(trouve(noeuds, texte="15")[0])
    toucher(trouve(ecran(), texte="OK")[0])
    time.sleep(1.5)
    noeuds = ecran()
    liste = champs(noeuds)
    age = liste[3].texte if len(liste) > 3 else ""
    controle("Âge calculé automatiquement après choix de la date", re.fullmatch(r"\d+ ans?", age), f"« {age} »")
    controle("Date affichée au format JJ/MM/AAAA", re.fullmatch(r"15/\d{2}/\d{4}", liste[2].texte), f"« {liste[2].texte} »")

    choix = trouve(noeuds, texte=sexe)
    if controle(f"Choix « {sexe} » disponible", choix):
        toucher(choix[0])

    enregistrer = trouve(ecran(), motif=r"^ENREGISTRER L.ENFANT$")
    toucher(enregistrer[0])
    ok, noeuds = attendre(lambda ns: trouve(ns, motif=r"^Fiche de l.enfant$"), delai=30)
    montrer(noeuds, "Fiche")
    controle("Fiche affichée après enregistrement", ok)
    controle("Nom et prénom sur la fiche", trouve(noeuds, texte=f"{prenom} {nom}"))
    return bool(ok)


def verifier_photo_definitive():
    etape("Photo définitive dans le stockage privé")
    liste = [l.strip() for l in sh(f"run-as {PKG} ls files/photos").splitlines() if l.strip().endswith(".jpg")]
    controle("Une photo définitive dans files/photos", len(liste) >= 1, str(liste))
    tmp = [l for l in sh(f"run-as {PKG} ls files/photos_tmp").splitlines() if l.strip().endswith(".jpg")]
    controle("Plus de photo temporaire une fois la fiche enregistrée", len(tmp) == 0, str(tmp))
    if liste:
        dims = dimensions_jpeg(octets_appli(f"files/photos/{liste[0]}"))
        controle("Photo redimensionnée (grand côté ≤ 1 280 px)", dims and max(dims) <= 1280, f"{dims}")
    externe = sh("ls /sdcard/Pictures /sdcard/DCIM 2>&1 | head -5")
    controle("Rien dans la galerie publique", ".jpg" not in externe, externe.strip()[:120])


def dimanche_avec_enfant(prenom, nom):
    etape("Écran Dimanche avec l'enfant ajouté en semaine")
    sh("input keyevent 4")  # retour de la fiche
    ok, noeuds = attendre(est_dimanche_affiche, delai=20)
    montrer(noeuds, "Dimanche")
    carte = trouve(noeuds, motif=rf"^{prenom} {nom} : ")
    controle("La carte de l'enfant est dans la grille du prochain dimanche", carte, carte[0].desc if carte else "")
    controle("« Séance à venir » affiché (pointage désactivé)", trouve(noeuds, texte="Séance à venir"))
    if carte:
        toucher(carte[0])
        time.sleep(1.5)
        noeuds = ecran()
        controle("Aucun panneau de pointage pour une séance à venir", not trouve(noeuds, texte="PRÉSENT"))


def relance():
    etape("Fermeture puis relance de l'application")
    sh(f"am force-stop {PKG}")
    time.sleep(2)
    sh("logcat -c")
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")
    ok, noeuds = attendre(est_dimanche_affiche, delai=60)
    controle("Application relancée", ok)
    controle("L'enfant est toujours là après relance", trouve(noeuds, motif=r" : (Non enregistré|Présent|En retard|Absent)$"))
    controle("Aucun plantage dans le journal Android", plantage() == "", plantage()[:300])


def principal():
    infos()
    installer()
    mode_avion()
    demarrage()
    refus_permission()
    if parcours_photo_et_formulaire():
        if remplir_et_enregistrer("Rakoto", "Sarah", "Fille"):
            verifier_photo_definitive()
            dimanche_avec_enfant("Sarah", "Rakoto")
            relance()
    etape("Résumé")
    echecs = [nom for nom, ok in RESULTATS if not ok]
    print(f"   {len(RESULTATS) - len(echecs)} contrôles OK, {len(echecs)} en échec sur {len(RESULTATS)}")
    for nom in echecs:
        print("   ÉCHEC :", nom)
    print("\n   Dernières lignes du journal Android (avertissements et erreurs de l'application) :")
    print(sh(f"logcat -d -t 400 *:W | grep -E '{PKG}|AndroidRuntime|FATAL' | tail -30"))
    return 0 if not echecs else 1


if __name__ == "__main__":
    sys.exit(principal())
