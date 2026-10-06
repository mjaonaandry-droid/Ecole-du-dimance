#!/usr/bin/env python3
"""Résume la sortie brute de « am instrument -r » : un test par ligne, détail des échecs, code de sortie."""
import sys

CODES = {"0": "OK", "-1": "ERREUR", "-2": "ÉCHEC", "-3": "IGNORÉ", "-4": "HYPOTHÈSE NON VÉRIFIÉE"}


def analyser(lignes):
    courant, dernier_cle, tests = {}, None, []
    for ligne in lignes:
        ligne = ligne.rstrip("\n")
        if ligne.startswith("INSTRUMENTATION_STATUS_CODE:"):
            code = ligne.split(":", 1)[1].strip()
            if code != "1":  # 1 = début du test
                tests.append((courant.get("class", "?").split(".")[-1], courant.get("test", "?"), code, courant.get("stack", "")))
            courant, dernier_cle = {}, None
        elif ligne.startswith("INSTRUMENTATION_STATUS:"):
            cle, _, valeur = ligne.split(":", 1)[1].strip().partition("=")
            courant[cle], dernier_cle = valeur, cle
        elif ligne.startswith("INSTRUMENTATION_"):
            dernier_cle = None
        elif dernier_cle:
            courant[dernier_cle] += "\n" + ligne
    return tests


def main(chemin):
    tests = analyser(open(chemin, encoding="utf-8", errors="replace"))
    print("################ TESTS INSTRUMENTÉS (émulateur) ################")
    for classe, nom, code, pile in tests:
        print(f"  {CODES.get(code, code):8s} {classe}.{nom}")
        if code != "0" and pile:
            print("\n".join("      " + l for l in pile.splitlines()[:12]))
    mauvais = [t for t in tests if t[2] not in ("0", "-3")]
    print(f"TOTAL : {len(tests)} tests, {len(tests) - len(mauvais)} réussis, {len(mauvais)} en échec")
    return 1 if (mauvais or not tests) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
