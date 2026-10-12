"""Crée une base Room « version 1.0.0 » réaliste (schéma exact de l'APK installé chez l'utilisateur),
avec des photos, pour vérifier sur émulateur que le transfert vers la 1.1.0 conserve tout.

Usage : python3 creer_base_test.py <schema 1.json> <dossier de sortie>
Produit : <sortie>/ecole_dimanche.db, <sortie>/photos/*.jpg, <sortie>/attendu.json
"""
import datetime as dt
import json
import os
import sqlite3
import sys

from PIL import Image, ImageDraw

schema = json.load(open(sys.argv[1]))["database"]
sortie = sys.argv[2]
os.makedirs(os.path.join(sortie, "photos"), exist_ok=True)
chemin_base = os.path.join(sortie, "ecole_dimanche.db")
if os.path.exists(chemin_base):
    os.remove(chemin_base)

base = sqlite3.connect(chemin_base)
for entite in schema["entities"]:
    base.execute(entite["createSql"].replace("${TABLE_NAME}", entite["tableName"]))
    for index in entite.get("indices", []):
        base.execute(index["createSql"].replace("${TABLE_NAME}", entite["tableName"]))
for requete in schema["setupQueries"]:
    base.execute(requete)
base.execute(f"PRAGMA user_version = {schema['version']}")

aujourdhui = dt.date.today()
# Dernier dimanche strictement passé (sa séance est clôturée depuis 10 h), et celui d'avant.
s0 = aujourdhui - dt.timedelta(days=(aujourdhui.weekday() + 1) % 7 or 7)
s1 = s0 - dt.timedelta(days=7)
prochain = s0 + dt.timedelta(days=7)
ms = lambda d, h=10: int(dt.datetime(d.year, d.month, d.day, h, tzinfo=dt.timezone.utc).timestamp() * 1000)
maintenant = ms(aujourdhui, 8)

couleurs = [(46, 125, 50), (21, 101, 192), (198, 40, 40), (106, 27, 154), (239, 108, 0)]
enfants = [
    # nom, prénom, naissance, sexe, photo, début de suivi, archivé
    ("Rakoto", "Sarah", "2017-03-15", "FILLE", True, s1, False),
    ("Rabe", "David", "2015-11-02", "GARCON", True, s1, False),
    ("Randria", "Nathan", "2016-06-21", "GARCON", True, s1, False),
    ("Rasoa", "Hanta", "2018-01-09", "FILLE", False, s1, False),
    ("Andry", "Lova", "2014-09-30", "FILLE", False, s1, False),
    ("Razafy", "Hery", "2017-12-12", "GARCON", False, s1, False),
    ("Ranaivo", "Tiana", "2016-02-14", "FILLE", False, s1, False),
    ("Rajao", "Mamy", "2015-05-05", "GARCON", False, s1, True),  # archivé après le dernier dimanche
    ("Rafidy", "Zo", "2019-08-08", "GARCON", False, prochain, False),  # inscrit cette semaine
]
statuts = {
    s1: ["PRESENT", "PRESENT", "EN_RETARD", "ABSENT", "PRESENT", "PRESENT", "ABSENT", "PRESENT"],
    s0: ["PRESENT", "EN_RETARD", "PRESENT", "PRESENT", "ABSENT", "PRESENT", "PRESENT", "ABSENT"],
}

for dimanche in (s1, s0):
    base.execute(
        "INSERT INTO seances VALUES (?,?,?,?,?,?,?)",
        (dimanche.isoformat(), "UTC", ms(dimanche), 1, ms(dimanche), ms(dimanche, 8), ms(dimanche)),
    )

attendu = {"enfants": [], "presences": [], "photos": {}}
for i, (nom, prenom, naissance, sexe, photo, debut, archive) in enumerate(enfants, start=1):
    chemin_photo = ""
    if photo:
        chemin_photo = f"photos/photo-test-{i}.jpg"
        image = Image.new("RGB", (640, 640), couleurs[i % len(couleurs)])
        dessin = ImageDraw.Draw(image)
        dessin.ellipse((170, 90, 470, 390), fill=(255, 224, 189))
        dessin.rectangle((150, 420, 490, 640), fill=(255, 255, 255))
        image.save(os.path.join(sortie, chemin_photo), "JPEG", quality=85)
        attendu["photos"][chemin_photo] = os.path.getsize(os.path.join(sortie, chemin_photo))
    fin = s0.isoformat() if archive else None
    base.execute(
        "INSERT INTO enfants (id, nom, prenom, dateNaissance, sexe, adresse, nomPere, pereMembre, nomMere, mereMembre, "
        "nombreFreresSoeurs, nombreFreresSoeursMembres, photoPath, dateArriveeEglise, dateDebutSuivi, dateFinSuivi, "
        "isArchived, archivedAt, createdAt, updatedAt) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (i, nom, prenom, naissance, sexe, f"Lot {i} Antananarivo", f"Père {nom}", 1, f"Mère {nom}", 0, 2, 1,
         chemin_photo, "2024-01-07", debut.isoformat(), fin, 1 if archive else 0,
         maintenant if archive else None, maintenant, maintenant),
    )
    attendu["enfants"].append({"id": i, "nom": nom, "prenom": prenom, "photoPath": chemin_photo,
                               "dateDebutSuivi": debut.isoformat(), "isArchived": archive})
    for dimanche, liste in statuts.items():
        if i <= len(liste) and debut <= dimanche:
            base.execute("INSERT INTO presences VALUES (?,?,?,?,?)", (i, dimanche.isoformat(), liste[i - 1], ms(dimanche, 9), ms(dimanche, 9)))
            attendu["presences"].append([i, dimanche.isoformat(), liste[i - 1]])

base.commit()
base.close()
attendu["dimanches"] = {"s0": s0.isoformat(), "s1": s1.isoformat()}
json.dump(attendu, open(os.path.join(sortie, "attendu.json"), "w"), ensure_ascii=False, indent=1)
print(f"Base créée : {len(attendu['enfants'])} enfants, {len(attendu['presences'])} présences, {len(attendu['photos'])} photos ; s1={s1} s0={s0}")
