# École du Dimanche — suivi des présences (Android, 100 % hors ligne)

Application Android **native** (Kotlin, Jetpack Compose, Material 3, Room) pour suivre les présences des
enfants de l'École du Dimanche : grille de photos pour le pointage, fiches, historique et assiduité.
Elle fonctionne **sans aucune connexion Internet**, y compris après un redémarrage du téléphone.

> Le nom affiché (« École du Dimanche ») est une ressource : `app/src/main/res/values/strings.xml`, clé `app_name`.
> Le modifier ne change pas l'identifiant technique de l'application (`mg.ecoledimanche.presences`).

---

## 1. État de la livraison — ce qui est fait, et ce qui ne l'est pas

Les lignes « exécuté » ont été **réellement exécutées** par l'intégration continue GitHub Actions (workflow
`.github/workflows/apk.yml`, runner `ubuntu-latest`, JDK 17 Temurin, Gradle 8.14.3, AGP 8.13.2, SDK Android 36).
Les chiffres ci-dessous sont lus dans le journal de ce workflow (étape « Résumé vérifiable »), pas supposés.

| Étape | État | Preuve |
|---|---|---|
| Code créé | ✅ fait | Projet Android complet (≈ 5 500 lignes Kotlin), dossier `Ecole-du-dimance/`. |
| **Compilation Android** (Gradle + AGP + KSP + Compose) | ✅ **exécutée** | `:app:assembleDebug` : *BUILD SUCCESSFUL* ; la tâche `verifierSansInternetDebug` (garde-fou INTERNET) est exécutée. |
| **Tests unitaires JVM** | ✅ **exécutés** | **129 tests** dans 12 classes : 0 échec, 0 erreur, 0 ignoré (comptés dans les rapports JUnit XML). |
| **Lint** | ✅ **exécuté** | `:app:lintDebug` : *BUILD SUCCESSFUL*, **0 erreur**, 30 avertissements (§11). |
| **APK de débogage produit** | ✅ **produit** | `app-debug.apk`, 21 971 501 octets (≈ 22 Mo), `mg.ecoledimanche.presences` 1.0.0, minSdk 26, targetSdk 36, libellé « École du Dimanche », signé (schéma v2) avec la clé de débogage Android. |
| Permission `INTERNET` | ✅ **absente** de l'APK | `aapt2 dump permissions` : `CAMERA`, `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` (ces quatre dernières viennent de WorkManager). |
| Sauvegarde cloud / transfert | ✅ **désactivés** dans l'APK | Manifeste de l'APK : `allowBackup=false`, `fullBackupContent` et `dataExtractionRules` présents. |
| Schéma Room | ✅ versionné | `app/schemas/.../1.json`, identique octet pour octet (SHA-256) au fichier généré par la compilation. |
| Variante *release* | ✅ compile | `:app:assembleRelease` réussit mais reste **non signée** donc non installable (§6). |
| Tests instrumentés Room + parcours sur **émulateur** Android 14 | ⏳ résultat non encore relevé | Job « emulateur » du workflow (vraie base SQLite, mode avion, caméra virtuelle). Ce tableau sera complété avec le résultat **observé** ; tant qu'il ne l'est pas, considérez ces vérifications comme **non exécutées**. |
| Vérification sur un **vrai téléphone** | 🟡 **retour de l'utilisateur** | Après installation de l'APK de débogage : « test sur mon téléphone ok ». C'est un retour déclaré : je ne sais pas précisément ce qui a été essayé (caméra, mode avion, redémarrage, clôture à 10 h…) et je ne l'ai pas reproduit. Les points non essayés restent à dérouler avec la liste du §9. |
| APK *release* signé | ❌ non produit | Il faut **votre** clé de signature (§6) : je n'en ai ni créé ni utilisé. |

### Où récupérer l'APK

L'APK est produit par GitHub Actions, pas dans l'environnement de développement (celui-ci n'a pas accès au SDK
Android, voir ci-dessous). Dans le dépôt GitHub : onglet **Actions** ▸ workflow **« Construire l'APK »** ▸ choisir
une exécution **verte** ▸ section **Artifacts** ▸ **`app-debug-apk`** (un `.zip` contenant `app-debug.apk`).
Il faut être connecté à GitHub pour le télécharger ; les artefacts sont conservés 90 jours. Le workflow se lance à chaque
envoi (`push`) sur `main` et sur les branches `claude/**`, ou à la demande (« Run workflow ») une fois le fichier présent
sur la branche par défaut.

### Pourquoi cela n'a pas pu être fait localement

L'environnement où ce projet a été écrit n'autorise pas l'accès à `dl.google.com`, qui héberge le SDK Android, le
plugin Android Gradle et toutes les bibliothèques AndroidX. Sans ces fichiers, `./gradlew assembleDebug` ne peut pas
s'y exécuter. Les runners GitHub y ont accès : c'est donc là que la compilation a été réellement vérifiée.
Sur votre poste (Android Studio + Internet pour le développement), la commande du §5 fait la même chose.

---

## 2. Choix retenus pour la V1 (appliqués partout)

- **Clôture à 10 h 00**, heure locale du téléphone — une seule valeur dans le code :
  `ConfigurationSeance.HEURE_CLOTURE = LocalTime.of(10, 0)` (`domain/ReglesSeance.kt`). Aucun écran de réglage.
- **Tous les dimanches** sont des séances prévues, y compris ceux où l'application n'a jamais été ouverte.
- Le **suivi d'un enfant commence à son inscription dans l'application** (jamais antidaté). La date d'arrivée dans
  l'église est une simple information de la fiche.
- Un enfant **archivé** garde fiche, photo et historique ; il n'apparaît plus dans les dimanches **après** sa fin de suivi.
- L'**assiduité** n'utilise que les séances **clôturées**.
- **« En retard »** est toujours choisi à la main : jamais déduit de l'heure du clic.
- Âge : toujours calculé (`java.time.Period`), jamais saisi ni stocké. Pour une naissance un **29 février**, l'âge
  augmente le **1ᵉʳ mars** des années non bissextiles (comportement de `Period`).
- Pourcentages : une décimale au plus, virgule française, `87,5 %` / `100 %` ; `—` et « Aucune séance clôturée » sans séance.

---

## 3. Architecture technique

Un seul module `app`, MVVM, injection de dépendances manuelle (un conteneur, `di/ConteneurApp`).

```
app/src/main/kotlin/mg/ecoledimanche/presences/
├── domain/        Règles pures (aucune dépendance Android, entièrement testées) :
│                  âge, dimanches, phases d'une séance (à venir / en cours / clôturée), début de suivi,
│                  admissibilité, assiduité et taux, validation du formulaire, recherche sans accents.
├── data/local/    Room : 3 entités, DAO, convertisseurs de dates / instants / énumérations, AppDatabase.
├── data/repository/  EnfantRepository, PresenceRepository : transactions de pointage, clôture, rattrapage.
├── camera/        CameraX, permission, parcours photo, stockage privé, redimensionnement, nettoyage.
├── worker/        WorkManager : rattrapage en arrière-plan.
├── di/            ConteneurApp.
└── ui/            ViewModels, écrans Compose (sans état), navigation, thème.
```

Les écrans ne contiennent **ni logique de clôture ni SQL** : ils lisent des flux (`Flow`) alimentés par Room et
appellent les repositories. Aucun accès disque ou base sur le thread principal.

### Tables Room (`data/local/Entites.kt`)

| Table | Contenu | Clé / contraintes |
|---|---|---|
| `enfants` | identité, parents (appartenance **nullable** : « non renseigné » ≠ « Non »), fratrie, `photoPath` **relatif**, `dateArriveeEglise`, `dateDebutSuivi`, `dateFinSuivi` (borne incluse), `isArchived`, `archivedAt`, `createdAt`, `updatedAt` | `id` auto-incrémenté (deux homonymes = deux identifiants) |
| `seances` | `dateDimanche`, `zoneId`, `cloturePrevueAt` (10 h dans ce fuseau, figée à la création), `cloturee`, `clotureeAt` | `dateDimanche` clé primaire |
| `presences` | `enfantId`, `dateDimanche`, `statut` (`NON_ENREGISTRE`, `PRESENT`, `EN_RETARD`, `ABSENT`), horodatages | **clé primaire composite** `(enfantId, dateDimanche)` ; clés étrangères vers les deux tables en **RESTRICT** (jamais de suppression en cascade) ; index sur `dateDimanche` et `(enfantId, statut)` |

Dates civiles : texte ISO `AAAA-MM-JJ` (triable, indépendant de l'affichage). Horodatages : millisecondes Unix.
Pas d'âge stocké, pas de photo en BLOB. Export du schéma Room activé ; aucune migration destructive.

### Écrans

Barre basse à quatre accès : **Dimanche**, **Enfants**, **Ajouter** (action centrale, démarre le parcours caméra ;
pas d'onglet vide), **Assiduité**. Plus : caméra, aperçu de la photo, formulaire, fiche, historique, panneau de
choix du statut, confirmation d'archivage.

### Parcours « photo en premier »

1. « Ajouter un enfant » → 2. permission `CAMERA` **seulement si nécessaire** → 3. caméra CameraX intégrée →
4. capture → 5. aperçu **Reprendre / Utiliser cette photo** → 6. formulaire → 7. « ENREGISTRER L'ENFANT ».

Rien n'est créé en base avant l'étape 7. Annulation, refus de permission, caméra indisponible ou erreur : message
clair en français, retour possible, **aucune fiche incomplète**. Refus simple → « Réessayer » ; refus définitif →
« Ouvrir les paramètres » ; toujours « Annuler l'ajout ». Le reste de l'application reste accessible.
La photo (côté max. 1 280 px, orientation corrigée, JPEG) reste temporaire (`photos_tmp/`) jusqu'à l'enregistrement,
puis est **copiée** dans `photos/`, et la temporaire n'est supprimée **qu'après** le succès en base : si l'écriture
échoue, on peut réessayer sans reprendre la photo. Le formulaire et la photo temporaire survivent à une rotation et,
quand Android le permet, à la recréation du processus.

### Présences, clôture et absences automatiques

- **Début de suivi** : ajout un dimanche avant 10 h → ce dimanche ; un dimanche à partir de 10 h, ou du lundi au
  samedi → le dimanche suivant. Exemple : ajouté le mardi 06/10/2026 → suivi dès le dimanche 11/10/2026, aucune absence avant.
- **Admissibilité** à un dimanche D : `D ≥ dateDebutSuivi` et (`dateFinSuivi` nulle ou `D ≤ dateFinSuivi`).
  Archiver le dimanche garde ce dimanche, puis exclut l'enfant dès le suivant ; archiver avant le premier dimanche : aucune séance, aucune absence.
- **Pointage** : toucher une photo ouvre un panneau avec deux grands boutons, **PRÉSENT** (vert) et **EN RETARD**
  (rouge), plus « Annuler le pointage » (retour à *Non enregistré*). L'écriture est immédiate dans Room ; la carte ne
  change **qu'après** la réussite (sinon un message d'erreur s'affiche). Les couleurs ne sont jamais seules : chaque
  statut a aussi une icône et un libellé.
- **À 10 h 00** : *Présent* et *En retard* sont conservés ; *Non enregistré* devient *Absent* ; une ligne manquante est
  créée en *Absent*. À 09 h 59 un enfant non pointé est encore *Non enregistré*. La clôture se fait dans **une transaction Room**
  par séance, et elle est **idempotente** (jamais de doublon, jamais d'écrasement).
- **Après clôture** : correction explicite vers *Présent*, *En retard* ou *Absent* depuis le même panneau ; *Non
  enregistré* n'y est plus proposé ; la séance reste clôturée ; une correction n'est **jamais** écrasée par un
  traitement automatique ultérieur ; historique et statistiques sont recalculés immédiatement.
- **Rattrapage** (`PresenceRepository.rattraper`) : examine **tous** les dimanches échus depuis le premier début de
  suivi, même sans séance en base (trois semaines sans ouvrir l'application = trois dimanches reconstitués).
  Exécuté : au lancement (écran de démarrage), au retour au premier plan, **avant** chaque lecture de présences /
  historique / statistiques, à 10 h pile pendant que l'application est ouverte (minuteur calé sur la prochaine
  frontière), et en arrière-plan par WorkManager.
- **WorkManager** : un travail périodique unique (toutes les heures) et un travail unique ciblé sur le prochain
  dimanche 10 h, **sans contrainte réseau**, sans alarme exacte, sans service permanent. Android peut les différer
  (économie d'énergie, application arrêtée de force, téléphone éteint) : ils ne garantissent **pas** l'heure exacte.
  Le rattrapage à l'ouverture garantit un résultat cohérent dans tous les cas.
- **Horloge** : le téléphone hors ligne est la référence ; l'accès au temps passe par `AppClock` (injectable, testé
  avec une horloge contrôlée). Une séance enregistrée garde son fuseau et son instant de clôture : un changement de
  fuseau ou un retour en arrière de l'horloge ne la déplace ni ne la rouvre.

---

## 4. Prérequis et versions

Les versions sont **fixées explicitement** (aucune version dynamique) dans `gradle/libs.versions.toml`.

| Élément | Version |
|---|---|
| JDK pour Gradle | **17 minimum** (le JDK 21 intégré à Android Studio convient) |
| Gradle (wrapper) | 8.14.3 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / KSP | 2.2.21 / 2.2.21-2.0.5 |
| `minSdk` / `targetSdk` / `compileSdk` | 26 (Android 8.0) / 36 / 36 |
| Compose BOM | 2025.10.01 (famille Compose 1.9, Material 3 1.4) |
| Room / WorkManager / CameraX | 2.8.4 / 2.10.5 / 1.5.1 |
| Activity / Core / Lifecycle / Navigation | 1.11.0 / 1.17.0 / 2.9.4 / 2.9.5 |

**Pourquoi pas les toutes dernières versions ?** Cet ensemble de versions est celui qui a été **compilé et testé** (§1).
Des versions plus récentes existent : le lint de la compilation le signale (par exemple AGP 9.4.1, Compose BOM 2026.09.00,
Room 2.8.5, CameraX 1.6.2, Kotlin 2.4.20, Gradle 8.14.5). Ne les adoptez qu'**ensemble** et après essai : la montée vers
AGP 9 change notamment la façon d'intégrer Kotlin et demande Gradle 9 (Android Studio propose l'« AGP Upgrade Assistant »).
La CI utilise le JDK **Temurin 17**.

À installer sur le poste de développement :

1. **Android Studio** récent (compatible AGP 8.13), qui apporte le JDK ;
2. depuis le *SDK Manager* : **Android SDK Platform 36** et les *Build-Tools* correspondants, puis accepter les licences.

> **Internet est nécessaire sur le poste de développement uniquement**, pour télécharger les outils Android et les
> dépendances Gradle. L'application installée, elle, n'effectue **aucun** appel réseau, upload, synchronisation,
> télémétrie ou rapport d'erreur, et ne déclare **pas** la permission `INTERNET`.

---

## 5. Ouvrir, synchroniser, lancer, générer l'APK

**Android Studio :** *File ▸ Open…* ▸ choisir le dossier **`Ecole-du-dimance/`** du dépôt (celui qui contient `settings.gradle.kts`, pas la racine du dépôt) ▸ attendre la synchronisation Gradle ▸
choisir un téléphone ou un émulateur ▸ ▶ *Run*.

**Ligne de commande** (depuis ce dossier). macOS / Linux :

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows :

```bat
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Après une compilation réussie, l'APK de débogage se trouve ici :

```
app/build/outputs/apk/debug/app-debug.apk
```

Le Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) a été **généré par Gradle 8.14.3**
(`gradle wrapper`), ce n'est pas un fichier factice. Il télécharge la distribution Gradle au premier lancement. Pour
renforcer la sécurité, vous pouvez ajouter `distributionSha256Sum=<somme officielle>` dans
`gradle/wrapper/gradle-wrapper.properties` (la somme est publiée sur gradle.org pour la version 8.14.3).

### Tâche de contrôle « hors ligne »

`assembleDebug` / `assembleRelease` exécutent d'abord `verifierSansInternetDebug` / `verifierSansInternetRelease`, qui
**font échouer le build** si le manifeste **fusionné** (application + toutes les dépendances) déclare la permission
`INTERNET`. Le manifeste la retire de plus explicitement (`tools:node="remove"`). Vérification manuelle possible :

```bash
$ANDROID_HOME/build-tools/<version>/aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
# ou : apkanalyzer manifest permissions app/build/outputs/apk/debug/app-debug.apk
```

On doit y voir `CAMERA` et les permissions techniques ajoutées par WorkManager (par exemple `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `ACCESS_NETWORK_STATE` : cette dernière ne permet pas d'envoyer de données, seulement de connaître l'état du réseau), mais **jamais `INTERNET`**.

---

## 6. Installer l'APK sur un téléphone

L'APK de **débogage** est installable pour les essais. Aucun navigateur n'est nécessaire.

**Sans ordinateur branché :** copier `app-debug.apk` sur le téléphone (câble USB, carte SD, Bluetooth…), l'ouvrir depuis
l'application *Fichiers*, puis autoriser l'installation quand Android le demande :
*Paramètres ▸ Applications ▸ Accès spécial ▸ Installer des applis inconnues ▸ (votre gestionnaire de fichiers) ▸ Autoriser*.

**Par USB** (débogage USB activé, `adb` disponible) :

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### APK de version « release » (signé)

Une release **non signée n'est pas installable**. Pour en produire une signée :

1. Créer une clé **une seule fois** et la garder en lieu sûr (sauvegarde chiffrée, hors dépôt) :
   `keytool -genkeypair -v -keystore ecole-dimanche.jks -alias ecole-dimanche -keyalg RSA -keysize 2048 -validity 10000`
2. Soit **Android Studio** : *Build ▸ Generate Signed Bundle / APK…* ▸ APK ▸ choisir la clé ;
3. Soit **en ligne de commande** : copier `keystore.properties.example` en `keystore.properties` (déjà ignoré par Git),
   y mettre le chemin de la clé et les mots de passe, puis `./gradlew :app:assembleRelease`
   → `app/build/outputs/apk/release/app-release.apk`.

**Ne jamais** versionner la clé ni ses mots de passe, ne pas en afficher, et ne pas présenter une release signée avec
la clé de *débogage* comme une version de production.

**Mises à jour et données.** Une mise à jour ne conserve les données locales que si l'**identifiant d'application** et la
**signature** sont les mêmes. Passer d'un APK signé en débogage à un APK signé avec une autre clé empêche la mise à jour directe ;
le seul recours est de **désinstaller**, ce qui **efface définitivement les données et les photos** de l'application.
Choisissez donc la clé définitive **avant** d'enregistrer de vraies données.

---

## 7. Données, sauvegarde et schéma Room

- Base et photos sont **uniquement** dans le stockage privé de l'application (`filesDir` et base Room). Aucune
  permission de stockage ou de galerie.
- **Désinstaller l'application efface ses données privées.** Une mise à jour normale (même identifiant, signature compatible)
  les conserve. **Archiver** un enfant conserve fiche, photo et historique.
- Aucune sauvegarde cloud : `android:allowBackup="false"`, `fullBackupContent` (Android ≤ 11) et `dataExtractionRules`
  (Android 12+, **sauvegarde cloud et transfert d'appareil à appareil**) excluent base, photos et préférences.
- La V1 n'a pas d'import/export ; les accès à la base et aux photos sont centralisés (`AppDatabase`, `StockagePhotosPrive`)
  pour permettre plus tard une sauvegarde **locale** complète, sans service cloud.
- **Schéma Room** : l'export est activé (`room.schemaLocation` → `app/schemas/`). Le fichier `.../1.json` est **généré par
  la première compilation** : le versionner ensuite avec le code. À chaque évolution : incrémenter `version`, ajouter une
  `Migration` explicite dans `AppDatabase.MIGRATIONS` (jamais de migration destructive) et un test dans `MigrationRoomTest`.
- Fichiers orphelins : au démarrage et dans le travail WorkManager, les photos non référencées **et anciennes** (> 1 h) et les
  brouillons abandonnés (> 24 h) sont supprimés ; un fichier référencé, récent ou en cours d'utilisation ne l'est jamais.

---

## 8. Personnaliser

| Souhait | Où |
|---|---|
| Nom affiché | `app/src/main/res/values/strings.xml` → `app_name` |
| Heure de clôture | `ConfigurationSeance.HEURE_CLOTURE` (`domain/ReglesSeance.kt`) |
| Identifiant technique | `applicationId` et `namespace` dans `app/build.gradle.kts` — **à fixer avant la première installation réelle** |
| Taille des photos | `CalculsImage.COTE_MAX` (`camera/CalculsImage.kt`) |

---

## 9. Vérifications manuelles (non exécutées ici)

Elles demandent un appareil ou un émulateur. À dérouler après l'installation :

1. **Permission caméra** : au premier « Ajouter », la demande apparaît une fois ; accepter → caméra ; rouvrir « Ajouter » → caméra directe, sans nouvelle demande.
2. **Refus** : refuser → message avec « Réessayer » et « Annuler l'ajout » ; refuser une seconde fois (ou « Ne plus demander ») → « Ouvrir les paramètres » ; accorder dans les paramètres puis revenir → la caméra s'ouvre. Vérifier que Dimanche, Enfants et Assiduité fonctionnent pendant ce temps.
3. **Annulation** : fermer la caméra, ou « Reprendre » puis retour, ou retour dans le formulaire (confirmation) → aucune fiche créée dans *Enfants*.
4. **Photo** : ajouter un enfant, vérifier la photo (bon sens, pas déformée) dans la grille, la fiche et l'historique ; tourner l'écran pendant le formulaire → saisie et photo conservées.
5. **Remplacement de photo** : *Fiche ▸ Changer la photo* ; annuler garde l'ancienne ; valider remplace. Le dossier privé ne doit pas contenir l'ancienne.
6. **Redémarrage** : fermer l'application, redémarrer le téléphone → données et photos intactes.
7. **Mode avion** : activer le mode avion, réaliser un cycle complet (ajout, pointage, clôture, assiduité) → tout fonctionne.
8. **Fichier photo manquant** : supprimer une photo avec un outil de développement (`adb shell run-as mg.ecoledimanche.presences rm files/photos/<fichier>`) → visuel neutre, aucun plantage, message sur la fiche, « Changer la photo » possible.
9. **Clôture à 10 h** : sur un émulateur, régler l'horloge sur un dimanche à 09 h 58, pointer deux enfants, laisser passer 10 h → l'écran passe à « Séance clôturée » tout seul, l'enfant non pointé devient *Absent*. Puis avancer l'horloge de plusieurs semaines sans ouvrir l'application et la rouvrir → les dimanches manqués sont reconstitués.
10. **Permissions** : `aapt2 dump permissions` (§5) ne montre pas `INTERNET`.
11. **Sauvegarde** : `adb shell bmgr` / paramètres de sauvegarde Google ne proposent pas cette application.
12. **Tests instrumentés Room** (appareil/émulateur) : `./gradlew :app:connectedDebugAndroidTest`.

---

## 10. Tests

```bash
./gradlew :app:testDebugUnitTest          # tests JVM (logique métier, repositories, ViewModels)
./gradlew :app:connectedDebugAndroidTest  # tests Room sur appareil / émulateur
```

**Tests JVM (`app/src/test`)** — 129 tests : âge (avant / le jour / après l'anniversaire, 29 février, changement d'année),
calendrier des dimanches, frontière exacte **09 h 59 / 10 h 00**, début de suivi (dimanche avant / après 10 h, semaine),
admissibilité, assiduité (exemples 90 %, 87,5 %, 100 %, dénominateur nul), validations du formulaire, recherche sans accents,
scénario principal (Sarah présente, David en retard, Nathan absent), rattrapage de plusieurs dimanches sans séance
existante, idempotence, correction conservée, archivage (avec et avant le premier dimanche), séance à venir, fuseau figé,
horloge qui recule, atomicité de la clôture, unicité d'une présence, photo (échec de fichier, échec de base, remplacement),
et les ViewModels (double clic, échec puis réessai, restauration après rotation, photo temporaire perdue).
**Résultat réel en CI : 129 tests, 0 échec** (voir §1). Lors de la rédaction initiale du code, 21 défauts avaient été injectés
volontairement pour éprouver ces tests (heure de clôture décalée, frontière 09 h 59 / 10 h, retards non comptés, arrondi tronqué,
absences non créées, garde anti-double-clic retirée…) ; cette vérification n'a **pas** été rejouée dans la CI.
Les repositories sont testés sur une base **en mémoire** qui reproduit clés primaires, `INSERT OR IGNORE`, clés
étrangères RESTRICT et annulation de transaction ; les vraies requêtes SQL et contraintes sont testées en instrumenté
(ci-dessous) et validées à la compilation par Room.

**Tests instrumentés (`app/src/androidTest`)** — rejouent le scénario principal sur la vraie base Room, vérifient clé
primaire composite, clés étrangères RESTRICT, conservation après fermeture / relance, archivage, et le schéma
(`MigrationRoomTest`). **Non exécutés ici.**

---

## 11. Limites connues et choix simples

- Pas de suppression définitive ni de réactivation d'un enfant, pas d'import / export, pas de saisie rétroactive (V1).
- Les dimanches futurs ne sont proposés que sur 8 semaines dans le sélecteur.
- L'interface impose la langue française, même sur un téléphone réglé dans une autre langue.
- Pas de minification (R8) en release par défaut ; elle peut être activée (`isMinifyEnabled`) après essais.
- La planification WorkManager n'est pas exacte (voir §3). Sur certains téléphones aux économies d'énergie agressives, elle peut
  être très différée : l'ouverture de l'application rattrape alors tout.
