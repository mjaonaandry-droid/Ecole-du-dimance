"""Archive factice (comme celle produite par le téléphone) pour le test Windows du script .bat."""
import io, os, sys, tarfile
dossier = sys.argv[1]
os.makedirs(dossier, exist_ok=True)
noms = []
with tarfile.open(os.path.join(dossier, "fixture.tar"), "w", format=tarfile.USTAR_FORMAT) as archive:
    for nom, contenu in [("databases/ecole_dimanche.db", b"SQLite format 3\x00" + b"x" * 4000),
                         ("databases/ecole_dimanche.db-wal", b"w" * 100),
                         ("files/photos/photo-1.jpg", b"\xff\xd8" + b"p" * 3000),
                         ("files/photos/photo-2.jpg", b"\xff\xd8" + b"q" * 2000)]:
        info = tarfile.TarInfo(nom)
        info.size = len(contenu)
        archive.addfile(info, io.BytesIO(contenu))
        noms.append(nom)
with open(os.path.join(dossier, "fixture-liste.txt"), "w", newline="\n") as liste:
    liste.write("databases/\n" + "\n".join(noms[:2]) + "\nfiles/\nfiles/photos/\n" + "\n".join(noms[2:]) + "\n")
print("fixture prête")
