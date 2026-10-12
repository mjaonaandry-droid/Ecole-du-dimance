"""Petit pilote d'interface pour l'émulateur (uiautomator) : lire l'écran, toucher un texte, capturer.

Usage : python3 ui.py attendre <texte> [secondes]   -> code 0 si le texte apparaît
        python3 ui.py toucher <texte> [index]        -> touche le n-ième élément contenant le texte
        python3 ui.py capture <fichier.png>
        python3 ui.py toucher-exact <texte> [index]  -> idem, texte exact (insensible à la casse)
        python3 ui.py ordre                          -> cartes de la grille dans l'ordre de lecture
        python3 ui.py texte                          -> affiche tous les textes visibles
Le texte est cherché dans les attributs text et content-desc (insensible à la casse).
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args, binaire=False):
    resultat = subprocess.run(["adb", *args], capture_output=True, timeout=120)
    return resultat.stdout if binaire else resultat.stdout.decode("utf-8", "replace")


def noeuds():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ecran.xml")
        contenu = adb("exec-out", "cat", "/sdcard/ecran.xml")
        if contenu.strip().startswith("<?xml"):
            try:
                return list(ET.fromstring(contenu).iter("node"))
            except ET.ParseError:
                pass
        time.sleep(1)
    return []


def libelle(noeud):
    return (noeud.get("text", "") + " | " + noeud.get("content-desc", "")).strip(" |")


def trouver(texte):
    cible = texte.lower()
    return [n for n in noeuds() if cible in libelle(n).lower()]


def centre(noeud):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", noeud.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def main():
    action = sys.argv[1]
    if action == "attendre":
        limite = time.time() + (float(sys.argv[3]) if len(sys.argv) > 3 else 30)
        while time.time() < limite:
            if trouver(sys.argv[2]):
                print(f"OK : « {sys.argv[2]} » visible")
                return 0
            time.sleep(1)
        print(f"ABSENT : « {sys.argv[2]} »")
        for n in noeuds():
            if libelle(n):
                print("   ", libelle(n))
        return 1
    if action == "toucher":
        index = int(sys.argv[3]) if len(sys.argv) > 3 else 0
        trouves = [n for n in trouver(sys.argv[2]) if n.get("bounds")]
        if len(trouves) <= index:
            print(f"INTROUVABLE : « {sys.argv[2]} »")
            return 1
        x, y = centre(trouves[index])
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(1.5)
        print(f"Touché : « {libelle(trouves[index])} » ({x},{y})")
        return 0
    if action == "toucher-exact":
        index = int(sys.argv[3]) if len(sys.argv) > 3 else 0
        cible = sys.argv[2].lower()
        trouves = [n for n in noeuds() if n.get("bounds") and cible in (n.get("text", "").lower(), n.get("content-desc", "").lower())]
        if len(trouves) <= index:
            print(f"INTROUVABLE (exact) : « {sys.argv[2]} »")
            return 1
        x, y = centre(trouves[index])
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(1.5)
        print(f"Touché : « {libelle(trouves[index])} » ({x},{y})")
        return 0
    if action == "ordre":
        # Cartes de la grille (« Prénom Nom : Statut »), dans l'ordre de lecture (ligne, puis colonne).
        cartes = [n for n in noeuds() if " : " in n.get("content-desc", "")]
        cartes.sort(key=lambda n: (centre(n)[1] // 40, centre(n)[0]))
        for n in cartes:
            print(n.get("content-desc"))
        return 0
    if action == "capture":
        with open(sys.argv[2], "wb") as f:
            f.write(adb("exec-out", "screencap", "-p", binaire=True))
        print("Capture :", sys.argv[2])
        return 0
    if action == "texte":
        for n in noeuds():
            if libelle(n):
                print(libelle(n))
        return 0
    return 2


sys.exit(main())
