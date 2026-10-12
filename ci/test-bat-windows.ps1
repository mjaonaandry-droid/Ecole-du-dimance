# Vérifie TRANSFERER-LES-DONNEES.bat (syntaxe cmd et enchaînement) avec un faux adb.exe, sur Windows.
$ErrorActionPreference = "Continue"
$racine = Split-Path -Parent $PSScriptRoot
$dossier = Join-Path $racine "transfert-donnees"
$stub = Join-Path $env:RUNNER_TEMP "telephone"
$env:STUB_DIR = $stub
$echecs = 0

function Verifier($condition, $message) {
    if ($condition) { Write-Host "OK      $message" } else { Write-Host "ECHEC   $message"; $script:echecs++ }
}

function Preparer($etat) {
    if (Test-Path $stub) { Remove-Item -Recurse -Force $stub }
    New-Item -ItemType Directory -Path $stub | Out-Null
    Set-Content -Path (Join-Path $stub "etat.txt") -Value $etat
    Copy-Item (Join-Path $racine "ci-fixture\fixture.tar") $stub
    Copy-Item (Join-Path $racine "ci-fixture\fixture-liste.txt") $stub
    if (Test-Path (Join-Path $dossier "journal-transfert.txt")) { Remove-Item (Join-Path $dossier "journal-transfert.txt") }
}

function Lancer($nom) {
    Write-Host "----- $nom -----"
    $sortie = cmd /c "`"$dossier\TRANSFERER-LES-DONNEES.bat`" --oui" 2>&1 | Out-String
    Write-Host $sortie
    $commandes = Get-Content (Join-Path $stub "commandes.log") -Raw
    $etat = Get-Content (Join-Path $stub "etat.txt") -Raw
    return @{ Sortie = $sortie; Commandes = $commandes; Etat = $etat }
}

# Faux adb.exe et APK factice à côté du script.
$csc = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"
New-Item -ItemType Directory -Force -Path (Join-Path $dossier "platform-tools") | Out-Null
& $csc /nologo /out:"$dossier\platform-tools\adb.exe" (Join-Path $PSScriptRoot "adb-simule.cs")
Set-Content -Path (Join-Path $dossier "ecole-du-dimance-1.1.0.apk") -Value "apk factice"
if (Test-Path (Join-Path $dossier "sauvegardes")) { Remove-Item -Recurse -Force (Join-Path $dossier "sauvegardes") }

# 1. Cas réel : ancienne clé, données présentes -> sauvegarde, vérification, remplacement, restauration.
Preparer "installe=1`ncle=ancienne`ndonnees=1"
$r = Lancer "1. transfert complet"
$c = $r.Commandes
Verifier ($r.Sortie -match "TERMINE") "transfert complet : TERMINE"
Verifier ($r.Etat -match "restaure=1" -and $r.Etat -match "cle=nouvelle") "données restaurées dans la nouvelle version"
$ordre = @("install -r", "tar -cf -", "`npull", "`nexec-out stat", "`nuninstall", "`ninstall ", "`npush", "tar -xf -")
$position = 0; $ordreOk = $true
foreach ($etape in $ordre) {
    $i = $c.IndexOf($etape, $position)
    if ($i -lt 0) { $ordreOk = $false; Write-Host "   étape manquante ou hors ordre : $etape" } else { $position = $i + 1 }
}
Verifier $ordreOk "ordre : essai direct, sauvegarde, copie, vérification, désinstallation, installation, restauration"
$sauvegardes = Get-ChildItem (Join-Path $dossier "sauvegardes") -Filter "sauvegarde-*.tar"
Verifier ($sauvegardes.Count -eq 1) "une sauvegarde sur l'ordinateur : $($sauvegardes.Name)"
Verifier ((Get-FileHash $sauvegardes[0].FullName).Hash -eq (Get-FileHash (Join-Path $stub "fixture.tar")).Hash) "sauvegarde identique octet pour octet"
Verifier ($r.Sortie -match "2 photo") "nombre de photos annoncé"

# 2. Reprise : application déjà désinstallée, la sauvegarde existe -> installation + restauration.
Preparer "installe=0`ncle=`ndonnees=0"
$r = Lancer "2. reprise après interruption"
Verifier ($r.Sortie -match "reprise avec la sauvegarde") "reprise : dernière sauvegarde retrouvée"
Verifier ($r.Etat -match "restaure=1") "reprise : données restaurées"
Verifier (-not ($r.Commandes -match "uninstall")) "reprise : aucune désinstallation"

# 3. Version future signée avec la même clé : mise à jour directe, aucune sauvegarde ni désinstallation.
Preparer "installe=1`ncle=nouvelle`ndonnees=1"
$r = Lancer "3. mise à jour directe"
Verifier ($r.Sortie -match "Mise a jour directe reussie") "mise à jour directe"
Verifier (-not ($r.Commandes -match "uninstall") -and -not ($r.Commandes -match "tar -cf")) "mise à jour directe : ni sauvegarde ni désinstallation"

# 4. Sauvegarde impossible -> arrêt AVANT toute désinstallation.
Preparer "installe=1`ncle=ancienne`ndonnees=0"
$r = Lancer "4. sauvegarde impossible"
Verifier ($r.Sortie -match "ECHEC") "sauvegarde impossible : arrêt"
Verifier (-not ($r.Commandes -match "uninstall")) "sauvegarde impossible : rien n'est désinstallé"

# 5. Copie incomplète (tailles différentes) -> arrêt AVANT toute désinstallation.
Preparer "installe=1`ncle=ancienne`ndonnees=1`ntaille_fausse=1"
$r = Lancer "5. copie incomplète"
Verifier ($r.Sortie -match "incomplete") "copie incomplète : arrêt"
Verifier (-not ($r.Commandes -match "uninstall")) "copie incomplète : rien n'est désinstallé"

# 6. Téléphone non autorisé -> message clair, rien n'est fait.
Preparer "installe=1`ncle=ancienne`ndonnees=1`netat=unauthorized"
$r = Lancer "6. téléphone non autorisé"
Verifier ($r.Sortie -match "autorise") "non autorisé : message"
Verifier (-not ($r.Commandes -match "install")) "non autorisé : aucune installation"

Write-Host ""
Write-Host "Échecs : $echecs"
if ($echecs -gt 0) { exit 1 }
