package mg.ecoledimanche.presences.domain

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/** Identité d'un enfant (nom, prénom, date de naissance), comparée sans casse ni accents. */
data class IdentiteEnfant(
    val nom: String,
    val prenom: String,
    val dateNaissance: LocalDate,
)

/** Un enfant déjà enregistré dans l'application, tel que l'import le voit. */
data class EnfantConnu(
    val id: Long,
    val donnees: DonneesEnfant,
)

/** Informations d'une fiche qu'un import peut modifier (pour annoncer ce qui change). */
enum class ChampImport {
    NOM,
    PRENOM,
    SEXE,
    NAISSANCE,
    ADRESSE,
    NOM_PERE,
    PERE_MEMBRE,
    NOM_MERE,
    MERE_MEMBRE,
    FRATRIE,
    FRATRIE_MEMBRES,
    ARRIVEE,
}

/** Un enfant existant que l'import va modifier : sa fiche avant et après. */
data class MiseAJourEnfant(
    val id: Long,
    val avant: DonneesEnfant,
    val apres: DonneesEnfant,
) {
    /** Informations qui changent, dans l'ordre de la fiche. */
    val champsModifies: List<ChampImport>
        get() = buildList {
            if (avant.nom != apres.nom) add(ChampImport.NOM)
            if (avant.prenom != apres.prenom) add(ChampImport.PRENOM)
            if (avant.sexe != apres.sexe) add(ChampImport.SEXE)
            if (avant.dateNaissance != apres.dateNaissance) add(ChampImport.NAISSANCE)
            if (avant.adresse != apres.adresse) add(ChampImport.ADRESSE)
            if (avant.nomPere != apres.nomPere) add(ChampImport.NOM_PERE)
            if (avant.pereMembre != apres.pereMembre) add(ChampImport.PERE_MEMBRE)
            if (avant.nomMere != apres.nomMere) add(ChampImport.NOM_MERE)
            if (avant.mereMembre != apres.mereMembre) add(ChampImport.MERE_MEMBRE)
            if (avant.nombreFreresSoeurs != apres.nombreFreresSoeurs) add(ChampImport.FRATRIE)
            if (avant.nombreFreresSoeursMembres != apres.nombreFreresSoeursMembres) add(ChampImport.FRATRIE_MEMBRES)
            if (avant.dateArrivee != apres.dateArrivee) add(ChampImport.ARRIVEE)
        }
}

/** Pourquoi une ligne du fichier n'est pas importée. */
enum class MotifRejet {
    NOM_MANQUANT,
    PRENOM_MANQUANT,
    NAISSANCE_MANQUANTE,
    NAISSANCE_INVALIDE,
    NAISSANCE_FUTURE,
    SEXE_MANQUANT,
    SEXE_INVALIDE,
    TEXTE_TROP_LONG,

    /** La fiche obtenue aurait la même identité qu'un AUTRE enfant déjà inscrit. */
    DEJA_INSCRIT,

    /** La même identité (ou le même enfant à mettre à jour) figure plus haut dans le fichier. */
    DOUBLON_DANS_LE_FICHIER,

    /** Colonne « Statut » à « Archivé » pour un enfant inconnu : un enfant archivé n'est pas recréé. */
    ARCHIVE,

    /** « Identifiant » qui n'est pas un nombre entier. */
    IDENTIFIANT_INVALIDE,

    /**
     * L'identifiant existe mais désigne un enfant trop différent (au moins deux informations parmi nom,
     * prénom et date de naissance diffèrent) : probablement un fichier venant d'un autre téléphone.
     */
    IDENTIFIANT_NE_CORRESPOND_PAS,

    /** Colonne Oui/Non (parent membre de l'église) avec une autre valeur. */
    OUI_NON_INVALIDE,

    /** Nombre de frères et sœurs qui n'est pas un entier de 0 à 99. */
    NOMBRE_INVALIDE,

    DATE_ARRIVEE_INVALIDE,

    /** Informations contradictoires (arrivée avant la naissance ou dans le futur, plus de membres que de frères et sœurs). */
    DONNEES_INCOHERENTES,
}

/** Une ligne écartée, avec son numéro dans le fichier (l'en-tête est la ligne 1). */
data class LigneRejetee(
    val numeroLigne: Int,
    /** « Prénom Nom » tel que lu (peut être vide si les deux cellules le sont). */
    val libelle: String,
    val motif: MotifRejet,
)

sealed interface AnalyseImport {
    /** Le fichier ne contient aucune ligne. */
    data object FichierVide : AnalyseImport

    /** L'en-tête ne contient pas toutes les colonnes obligatoires (noms des colonnes manquantes). */
    data class ColonnesManquantes(val colonnes: List<String>) : AnalyseImport

    data class Pret(
        /** Nouveaux enfants valides, dans l'ordre du fichier, prêts à être ajoutés. */
        val aAjouter: List<DonneesEnfant>,
        val rejets: List<LigneRejetee>,
        /** Enfants existants dont la fiche change, dans l'ordre du fichier. */
        val aMettreAJour: List<MiseAJourEnfant> = emptyList(),
        /** Enfants existants reconnus dont le fichier ne change rien. */
        val inchanges: Int = 0,
    ) : AnalyseImport
}

/**
 * Import CSV : relit un fichier au format de l'export ([ExportCsv]) pour AJOUTER de nouveaux enfants et
 * METTRE À JOUR la fiche des enfants déjà inscrits, d'un coup.
 *
 * Colonnes reconnues (dans n'importe quel ordre, casse et accents ignorés) :
 * - obligatoires : « Nom », « Prénom », « Sexe » (Garçon / Fille), « Date de naissance » (JJ/MM/AAAA) ;
 * - facultatives : « Identifiant », « Statut », « Adresse », « Nom du père », « Père membre de l'église »
 *   (Oui / Non), « Nom de la mère », « Mère membre de l'église », « Frères et sœurs »,
 *   « Frères et sœurs membres de l'église », « Date d'arrivée dans l'église ».
 * Les autres colonnes de l'export (âge, suivi, dimanches, totaux) sont ignorées : l'âge est recalculé et
 * l'historique des présences n'est pas importé.
 *
 * Reconnaître un enfant déjà inscrit :
 * 1. par son « Identifiant » s'il est rempli et existe, à condition que nom, prénom et date de naissance
 *    lui correspondent au moins à deux sur trois (on peut ainsi corriger l'un des trois) ;
 * 2. sinon par son identité : même nom, même prénom et même date de naissance.
 * Un enfant reconnu est mis à jour avec les cellules REMPLIES de sa ligne : une cellule vide ne change rien
 * (elle n'efface jamais une information). Un enfant non reconnu est ajouté. L'archivage n'est jamais modifié
 * par un import.
 *
 * Tolérances : séparateur « ; », « , » ou tabulation (détecté sur l'en-tête), UTF-8 avec ou sans BOM
 * ou Windows-1252 (enregistrement « CSV » d'Excel), guillemets RFC 4180, apostrophe de protection de
 * l'export retirée, dates J/M/AAAA, JJ-MM-AAAA, JJ.MM.AAAA, AAAA-MM-JJ ou JJ/MM/AA.
 */
object ImportCsv {
    const val COLONNE_NOM = "Nom"
    const val COLONNE_PRENOM = "Prénom"
    const val COLONNE_SEXE = "Sexe"
    const val COLONNE_NAISSANCE = "Date de naissance"

    private enum class Colonne {
        IDENTIFIANT, NOM, PRENOM, SEXE, NAISSANCE, STATUT,
        ADRESSE, NOM_PERE, PERE_MEMBRE, NOM_MERE, MERE_MEMBRE, FRATRIE, FRATRIE_MEMBRES, ARRIVEE,
    }

    /** Titres de colonnes reconnus, après [cleEntete] (minuscules, sans accents ni apostrophes). */
    private val synonymes: Map<String, Colonne> = mapOf(
        "identifiant" to Colonne.IDENTIFIANT,
        "id" to Colonne.IDENTIFIANT,
        "numero" to Colonne.IDENTIFIANT,
        "nom" to Colonne.NOM,
        "nom de famille" to Colonne.NOM,
        "prenom" to Colonne.PRENOM,
        "prenoms" to Colonne.PRENOM,
        "sexe" to Colonne.SEXE,
        "genre" to Colonne.SEXE,
        "date de naissance" to Colonne.NAISSANCE,
        "date naissance" to Colonne.NAISSANCE,
        "naissance" to Colonne.NAISSANCE,
        "ne le" to Colonne.NAISSANCE,
        "ne(e) le" to Colonne.NAISSANCE,
        "nee le" to Colonne.NAISSANCE,
        "statut" to Colonne.STATUT,
        "adresse" to Colonne.ADRESSE,
        "nom du pere" to Colonne.NOM_PERE,
        "pere" to Colonne.NOM_PERE,
        "nom pere" to Colonne.NOM_PERE,
        "pere membre de l eglise" to Colonne.PERE_MEMBRE,
        "pere membre" to Colonne.PERE_MEMBRE,
        "nom de la mere" to Colonne.NOM_MERE,
        "mere" to Colonne.NOM_MERE,
        "nom mere" to Colonne.NOM_MERE,
        "mere membre de l eglise" to Colonne.MERE_MEMBRE,
        "mere membre" to Colonne.MERE_MEMBRE,
        "freres et soeurs" to Colonne.FRATRIE,
        "nombre de freres et soeurs" to Colonne.FRATRIE,
        "nombre de freres et/ou soeurs" to Colonne.FRATRIE,
        "fratrie" to Colonne.FRATRIE,
        "freres et soeurs membres de l eglise" to Colonne.FRATRIE_MEMBRES,
        "freres et soeurs membres" to Colonne.FRATRIE_MEMBRES,
        "nombre de freres et/ou soeurs membres de l eglise" to Colonne.FRATRIE_MEMBRES,
        "date d arrivee dans l eglise" to Colonne.ARRIVEE,
        "date d arrivee" to Colonne.ARRIVEE,
        "arrivee dans l eglise" to Colonne.ARRIVEE,
        "arrivee" to Colonne.ARRIVEE,
    )

    private val garcon = setOf("garcon", "g", "m", "masculin", "homme", "h")
    private val fille = setOf("fille", "f", "feminin", "femme")
    private val oui = setOf("oui", "o", "yes", "y", "vrai", "1", "x")
    private val non = setOf("non", "n", "no", "faux", "0")

    /** Marque d'ordre des octets UTF-8 (U+FEFF), construite par son code : le lint refuse un BOM littéral. */
    private val BOM: String = 0xFEFF.toChar().toString()

    /** Caractères que l'export protège par une apostrophe (voir [ExportCsv]). */
    private const val DEBUTS_DE_FORMULE = "=+-@\t\r"

    /** Taille maximale acceptée (garde-fou mémoire) : bien au-delà d'une école du dimanche. */
    const val TAILLE_MAX_OCTETS = 5 * 1024 * 1024

    /**
     * Texte d'un fichier : UTF-8 (BOM retiré) s'il est valide, sinon Windows-1252, l'encodage des
     * fichiers « CSV (séparateur : point-virgule) » enregistrés par Excel en français.
     */
    fun decoder(octets: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val texte = try {
            utf8.decode(ByteBuffer.wrap(octets)).toString()
        } catch (erreur: CharacterCodingException) {
            String(octets, Charset.forName("windows-1252"))
        }
        return texte.removePrefix(BOM)
    }

    /** Une ligne écartée pendant l'analyse : remonte jusqu'à la boucle principale. */
    private class Rejet(val motif: MotifRejet) : Exception(null, null, false, false)

    /** Valeurs lues sur une ligne ; nul = cellule vide ou colonne absente (« ne rien changer »). */
    private class Valeurs(
        val nom: String?,
        val prenom: String?,
        val sexe: Sexe?,
        val naissance: LocalDate?,
        val adresse: String?,
        val nomPere: String?,
        val pereMembre: Boolean?,
        val nomMere: String?,
        val mereMembre: Boolean?,
        val fratrie: Int?,
        val fratrieMembres: Int?,
        val arrivee: LocalDate?,
    )

    /**
     * @param connus enfants déjà inscrits (actifs et archivés), pour les reconnaître et les mettre à jour.
     */
    fun analyser(texte: String, aujourdhui: LocalDate, connus: List<EnfantConnu>): AnalyseImport {
        val propre = texte.removePrefix(BOM)
        val separateur = detecterSeparateur(propre)
        val enregistrements = lire(propre, separateur).filter { (_, cellules) -> cellules.any { it.isNotBlank() } }
        if (enregistrements.isEmpty()) return AnalyseImport.FichierVide

        val (_, entete) = enregistrements.first()
        val positions = HashMap<Colonne, Int>()
        entete.forEachIndexed { index, titre ->
            val colonne = synonymes[cleEntete(titre)]
            if (colonne != null && colonne !in positions) positions[colonne] = index
        }
        val manquantes = buildList {
            if (Colonne.NOM !in positions) add(COLONNE_NOM)
            if (Colonne.PRENOM !in positions) add(COLONNE_PRENOM)
            if (Colonne.SEXE !in positions) add(COLONNE_SEXE)
            if (Colonne.NAISSANCE !in positions) add(COLONNE_NAISSANCE)
        }
        if (manquantes.isNotEmpty()) return AnalyseImport.ColonnesManquantes(manquantes)

        val parId = connus.associateBy { it.id }
        val parIdentite = HashMap<String, EnfantConnu>()
        for (enfant in connus) parIdentite.putIfAbsent(cle(enfant.donnees), enfant)
        // Identité de chaque enfant inscrit après les mises à jour déjà acceptées (renommages compris).
        val identiteParId = connus.associateTo(HashMap()) { it.id to cle(it.donnees) }
        val identitesAjoutees = HashSet<String>()
        val idsTraites = HashSet<Long>()

        val aAjouter = ArrayList<DonneesEnfant>()
        val aMettreAJour = ArrayList<MiseAJourEnfant>()
        var inchanges = 0
        val rejets = ArrayList<LigneRejetee>()

        for ((numero, cellules) in enregistrements.drop(1)) {
            fun cellule(colonne: Colonne): String = positions[colonne]?.let { cellules.getOrNull(it) }?.let { nettoyer(it) }.orEmpty()
            val libelle = "${cellule(Colonne.PRENOM)} ${cellule(Colonne.NOM)}".trim()
            try {
                val valeurs = lireValeurs(::cellule, aujourdhui)
                val texteId = cellule(Colonne.IDENTIFIANT)
                val id = if (texteId.isEmpty()) null else lireIdentifiant(texteId) ?: throw Rejet(MotifRejet.IDENTIFIANT_INVALIDE)

                // 1. Par l'identifiant (s'il existe ici et correspond), 2. sinon par l'identité complète.
                val parIdentifiant = id?.let { parId[it] }
                if (parIdentifiant != null && ecarts(parIdentifiant.donnees, valeurs) >= 2) {
                    throw Rejet(MotifRejet.IDENTIFIANT_NE_CORRESPOND_PAS)
                }
                val cible = parIdentifiant ?: identiteLue(valeurs)?.let { parIdentite[it] }

                if (cible == null) {
                    if (Recherche.normaliser(cellule(Colonne.STATUT)).startsWith("archiv")) throw Rejet(MotifRejet.ARCHIVE)
                    val donnees = valider(fusionner(null, valeurs), aujourdhui)
                    val identite = cle(donnees)
                    when {
                        identiteParId.containsValue(identite) -> throw Rejet(MotifRejet.DEJA_INSCRIT)
                        !identitesAjoutees.add(identite) -> throw Rejet(MotifRejet.DOUBLON_DANS_LE_FICHIER)
                    }
                    aAjouter += donnees
                } else {
                    val apres = valider(fusionner(cible.donnees, valeurs), aujourdhui)
                    val identite = cle(apres)
                    if (cible.id in idsTraites) throw Rejet(MotifRejet.DOUBLON_DANS_LE_FICHIER)
                    val autre = identiteParId.any { (autreId, autreIdentite) -> autreId != cible.id && autreIdentite == identite }
                    if (autre || identite in identitesAjoutees) throw Rejet(MotifRejet.DEJA_INSCRIT)
                    idsTraites += cible.id
                    identiteParId[cible.id] = identite
                    if (apres == cible.donnees) inchanges++ else aMettreAJour += MiseAJourEnfant(cible.id, cible.donnees, apres)
                }
            } catch (rejet: Rejet) {
                rejets += LigneRejetee(numero, libelle, rejet.motif)
            }
        }
        return AnalyseImport.Pret(aAjouter, rejets, aMettreAJour, inchanges)
    }

    /** Lit et contrôle le format de chaque cellule remplie ; une cellule vide donne nul. */
    private fun lireValeurs(cellule: (Colonne) -> String, aujourdhui: LocalDate): Valeurs {
        fun texte(colonne: Colonne): String? = cellule(colonne).ifEmpty { null }
        fun date(colonne: Colonne, motif: MotifRejet): LocalDate? =
            texte(colonne)?.let { lireDate(it, aujourdhui) ?: throw Rejet(motif) }
        fun ouiNon(colonne: Colonne): Boolean? = texte(colonne)?.let {
            when (Recherche.normaliser(it).trimEnd('.')) {
                in oui -> true
                in non -> false
                else -> throw Rejet(MotifRejet.OUI_NON_INVALIDE)
            }
        }
        fun nombre(colonne: Colonne): Int? = texte(colonne)?.let {
            ValidationEnfant.analyserEntier(it.removeSuffix(".0").removeSuffix(",0")) ?: throw Rejet(MotifRejet.NOMBRE_INVALIDE)
        }

        val naissance = date(Colonne.NAISSANCE, MotifRejet.NAISSANCE_INVALIDE)
        val sexe = texte(Colonne.SEXE)?.let {
            when (Recherche.normaliser(it).trimEnd('.')) {
                in garcon -> Sexe.GARCON
                in fille -> Sexe.FILLE
                else -> throw Rejet(MotifRejet.SEXE_INVALIDE)
            }
        }
        return Valeurs(
            nom = texte(Colonne.NOM),
            prenom = texte(Colonne.PRENOM),
            sexe = sexe,
            naissance = naissance,
            adresse = texte(Colonne.ADRESSE),
            nomPere = texte(Colonne.NOM_PERE),
            pereMembre = ouiNon(Colonne.PERE_MEMBRE),
            nomMere = texte(Colonne.NOM_MERE),
            mereMembre = ouiNon(Colonne.MERE_MEMBRE),
            fratrie = nombre(Colonne.FRATRIE),
            fratrieMembres = nombre(Colonne.FRATRIE_MEMBRES),
            arrivee = date(Colonne.ARRIVEE, MotifRejet.DATE_ARRIVEE_INVALIDE),
        )
    }

    /** « 12 » ; Excel peut écrire « 12.0 » ou « 12,0 ». */
    private fun lireIdentifiant(texte: String): Long? =
        texte.removeSuffix(".0").removeSuffix(",0").toLongOrNull()?.takeIf { it > 0 }

    /** Nombre d'informations d'identité REMPLIES dans la ligne qui diffèrent de la fiche. */
    private fun ecarts(fiche: DonneesEnfant, valeurs: Valeurs): Int {
        var n = 0
        if (valeurs.nom != null && Recherche.normaliser(valeurs.nom) != Recherche.normaliser(fiche.nom)) n++
        if (valeurs.prenom != null && Recherche.normaliser(valeurs.prenom) != Recherche.normaliser(fiche.prenom)) n++
        if (valeurs.naissance != null && valeurs.naissance != fiche.dateNaissance) n++
        return n
    }

    private fun identiteLue(valeurs: Valeurs): String? {
        val nom = valeurs.nom ?: return null
        val prenom = valeurs.prenom ?: return null
        val naissance = valeurs.naissance ?: return null
        return cle(nom, prenom, naissance)
    }

    /** Fiche existante (ou vierge) complétée par les cellules remplies de la ligne. */
    private fun fusionner(fiche: DonneesEnfant?, v: Valeurs): SaisieEnfant = SaisieEnfant(
        nom = v.nom ?: fiche?.nom.orEmpty(),
        prenom = v.prenom ?: fiche?.prenom.orEmpty(),
        dateNaissance = v.naissance ?: fiche?.dateNaissance,
        sexe = v.sexe ?: fiche?.sexe,
        adresse = v.adresse ?: fiche?.adresse.orEmpty(),
        nomPere = v.nomPere ?: fiche?.nomPere.orEmpty(),
        pereMembre = v.pereMembre ?: fiche?.pereMembre,
        nomMere = v.nomMere ?: fiche?.nomMere.orEmpty(),
        mereMembre = v.mereMembre ?: fiche?.mereMembre,
        nombreFreresSoeurs = (v.fratrie ?: fiche?.nombreFreresSoeurs ?: 0).toString(),
        nombreFreresSoeursMembres = (v.fratrieMembres ?: fiche?.nombreFreresSoeursMembres ?: 0).toString(),
        dateArrivee = v.arrivee ?: fiche?.dateArrivee,
    )

    /** Mêmes règles que le formulaire de l'application. */
    private fun valider(saisie: SaisieEnfant, aujourdhui: LocalDate): DonneesEnfant =
        when (val resultat = ValidationEnfant.valider(saisie, aujourdhui)) {
            is ResultatValidation.Valide -> resultat.donnees
            is ResultatValidation.Invalide -> throw Rejet(motifDepuis(resultat.erreurs))
        }

    /** Premier motif dans l'ordre de lecture d'une ligne : nom, prénom, naissance, sexe, puis le reste. */
    private fun motifDepuis(erreurs: Map<ChampEnfant, ErreurChamp>): MotifRejet {
        erreurs[ChampEnfant.NOM]?.let { return if (it == ErreurChamp.TROP_LONG) MotifRejet.TEXTE_TROP_LONG else MotifRejet.NOM_MANQUANT }
        erreurs[ChampEnfant.PRENOM]?.let { return if (it == ErreurChamp.TROP_LONG) MotifRejet.TEXTE_TROP_LONG else MotifRejet.PRENOM_MANQUANT }
        erreurs[ChampEnfant.DATE_NAISSANCE]?.let {
            return if (it == ErreurChamp.DATE_FUTURE) MotifRejet.NAISSANCE_FUTURE else MotifRejet.NAISSANCE_MANQUANTE
        }
        if (ChampEnfant.SEXE in erreurs) return MotifRejet.SEXE_MANQUANT
        if (erreurs.values.any { it == ErreurChamp.TROP_LONG }) return MotifRejet.TEXTE_TROP_LONG
        if (erreurs.values.any { it == ErreurChamp.NOMBRE_INVALIDE }) return MotifRejet.NOMBRE_INVALIDE
        return MotifRejet.DONNEES_INCOHERENTES
    }

    private fun cle(donnees: DonneesEnfant): String = cle(donnees.nom, donnees.prenom, donnees.dateNaissance)

    private fun cle(nom: String, prenom: String, naissance: LocalDate): String =
        "${Recherche.normaliser(nom)}|${Recherche.normaliser(prenom)}|$naissance"

    /** « Père membre de l’église ? » devient « pere membre de l eglise ». */
    private fun cleEntete(titre: String): String =
        Recherche.normaliser(nettoyer(titre))
            .replace("œ", "oe")
            .replace('’', '\'')
            .replace("'", " ")
            .trimEnd('?', ' ', ':')
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Espaces retirés et apostrophe de protection de l'export enlevée (« '-Rakoto » redevient « -Rakoto »). */
    internal fun nettoyer(valeur: String): String {
        val coupee = valeur.trim()
        return if (coupee.length >= 2 && coupee[0] == '\'' && coupee[1] in DEBUTS_DE_FORMULE) coupee.substring(1).trim() else coupee
    }

    // ---------------------------------------------------------------------------------------------
    // Dates
    // ---------------------------------------------------------------------------------------------

    private val formatsDate: List<DateTimeFormatter> = listOf("d/M/uuuu", "d-M-uuuu", "d.M.uuuu", "uuuu-M-d", "uuuu/M/d")
        .map { DateTimeFormatter.ofPattern(it).withResolverStyle(ResolverStyle.STRICT) }

    private val dateAnneeCourte = Regex("""(\d{1,2})[/.-](\d{1,2})[/.-](\d{2})""")

    /** Date lue dans une cellule, ou nul si elle n'est pas reconnue (ou n'existe pas, comme un 31/02). */
    internal fun lireDate(texte: String, aujourdhui: LocalDate): LocalDate? {
        val valeur = texte.trim().substringBefore(' ') // « 15/03/2017 00:00 » (Excel) → la date seule
        for (format in formatsDate) {
            try {
                return LocalDate.parse(valeur, format)
            } catch (erreur: DateTimeParseException) {
                // format suivant
            }
        }
        val courte = dateAnneeCourte.matchEntire(valeur) ?: return null
        val (jour, mois, annee) = courte.destructured
        val siecle = if (annee.toInt() <= aujourdhui.year % 100) 2000 else 1900
        return try {
            LocalDate.of(siecle + annee.toInt(), mois.toInt(), jour.toInt())
        } catch (erreur: java.time.DateTimeException) {
            null
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Lecture CSV (RFC 4180)
    // ---------------------------------------------------------------------------------------------

    /** Séparateur le plus fréquent sur la première ligne (hors guillemets) ; « ; » par défaut. */
    internal fun detecterSeparateur(texte: String): Char {
        val candidats = charArrayOf(';', ',', '\t')
        val comptes = IntArray(candidats.size)
        var entreGuillemets = false
        for (c in texte) {
            if (c == '"') entreGuillemets = !entreGuillemets
            if (!entreGuillemets && (c == '\n' || c == '\r')) break
            if (!entreGuillemets) {
                val i = candidats.indexOf(c)
                if (i >= 0) comptes[i]++
            }
        }
        val meilleur = comptes.indices.maxByOrNull { comptes[it] } ?: 0
        return if (comptes[meilleur] == 0) ';' else candidats[meilleur]
    }

    /**
     * Enregistrements du fichier, chacun avec le numéro de la ligne où il commence. Un champ entre
     * guillemets peut contenir le séparateur, des guillemets doublés ("") et des sauts de ligne.
     */
    internal fun lire(texte: String, separateur: Char): List<Pair<Int, List<String>>> {
        val resultat = ArrayList<Pair<Int, List<String>>>()
        var cellules = ArrayList<String>()
        val champ = StringBuilder()
        var entreGuillemets = false
        var ligne = 1
        var debut = 1
        var i = 0
        var enregistrementEnCours = false

        fun finirChamp() {
            cellules.add(champ.toString())
            champ.setLength(0)
        }

        fun finirEnregistrement() {
            finirChamp()
            resultat.add(debut to cellules)
            cellules = ArrayList()
            enregistrementEnCours = false
        }

        while (i < texte.length) {
            val c = texte[i]
            if (entreGuillemets) {
                when {
                    c == '"' && i + 1 < texte.length && texte[i + 1] == '"' -> {
                        champ.append('"')
                        i++
                    }
                    c == '"' -> entreGuillemets = false
                    else -> {
                        if (c == '\n') ligne++
                        champ.append(c)
                    }
                }
            } else {
                if (!enregistrementEnCours) {
                    debut = ligne
                    enregistrementEnCours = true
                }
                when (c) {
                    '"' -> entreGuillemets = true
                    separateur -> finirChamp()
                    '\r' -> {
                        if (i + 1 < texte.length && texte[i + 1] == '\n') i++
                        finirEnregistrement()
                        ligne++
                    }
                    '\n' -> {
                        finirEnregistrement()
                        ligne++
                    }
                    else -> champ.append(c)
                }
            }
            i++
        }
        if (enregistrementEnCours || champ.isNotEmpty() || cellules.isNotEmpty()) finirEnregistrement()
        return resultat
    }
}
