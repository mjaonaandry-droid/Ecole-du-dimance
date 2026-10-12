package mg.ecoledimanche.presences.domain

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/** Identité d'un enfant, pour reconnaître un doublon à l'import (casse et accents ignorés). */
data class IdentiteEnfant(
    val nom: String,
    val prenom: String,
    val dateNaissance: LocalDate,
)

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

    /** Un enfant de même nom, prénom et date de naissance existe déjà dans l'application. */
    DEJA_INSCRIT,

    /** La même identité figure plus haut dans le fichier. */
    DOUBLON_DANS_LE_FICHIER,

    /** Colonne « Statut » à « Archivé » : un enfant archivé n'est pas recréé. */
    ARCHIVE,
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
        /** Enfants valides, dans l'ordre du fichier, prêts à être ajoutés. */
        val aAjouter: List<DonneesEnfant>,
        val rejets: List<LigneRejetee>,
    ) : AnalyseImport
}

/**
 * Import CSV : relit un fichier au format de l'export ([ExportCsv]) pour ajouter plusieurs enfants d'un coup.
 *
 * Colonnes reconnues (dans n'importe quel ordre, casse et accents ignorés) :
 * - obligatoires : « Nom », « Prénom », « Sexe » (Garçon / Fille), « Date de naissance » (JJ/MM/AAAA) ;
 * - facultative : « Statut » (« Archivé » : la ligne est ignorée).
 * Les autres colonnes de l'export (âge, dimanches, totaux) sont ignorées : l'âge est recalculé et
 * l'historique des présences n'est pas importé.
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

    private enum class Colonne { NOM, PRENOM, SEXE, NAISSANCE, STATUT }

    private val synonymes: Map<String, Colonne> = mapOf(
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
    )

    private val garcon = setOf("garcon", "g", "m", "masculin", "homme", "h")
    private val fille = setOf("fille", "f", "feminin", "femme")

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

    fun analyser(texte: String, aujourdhui: LocalDate, existants: Collection<IdentiteEnfant>): AnalyseImport {
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

        val dejaVus = existants.mapTo(HashSet()) { cle(it.nom, it.prenom, it.dateNaissance) }
        val dansLeFichier = HashSet<String>()
        val aAjouter = ArrayList<DonneesEnfant>()
        val rejets = ArrayList<LigneRejetee>()

        for ((numero, cellules) in enregistrements.drop(1)) {
            fun cellule(colonne: Colonne): String = positions[colonne]?.let { cellules.getOrNull(it) }?.let { nettoyer(it) }.orEmpty()

            val nom = cellule(Colonne.NOM)
            val prenom = cellule(Colonne.PRENOM)
            val libelle = "$prenom $nom".trim()
            fun rejeter(motif: MotifRejet) {
                rejets += LigneRejetee(numero, libelle, motif)
            }

            if (Recherche.normaliser(cellule(Colonne.STATUT)).startsWith("archiv")) {
                rejeter(MotifRejet.ARCHIVE)
                continue
            }

            val texteNaissance = cellule(Colonne.NAISSANCE)
            val naissance = if (texteNaissance.isEmpty()) null else lireDate(texteNaissance, aujourdhui)
            if (texteNaissance.isNotEmpty() && naissance == null) {
                rejeter(MotifRejet.NAISSANCE_INVALIDE)
                continue
            }
            val texteSexe = Recherche.normaliser(cellule(Colonne.SEXE)).trimEnd('.')
            val sexe = when (texteSexe) {
                in garcon -> Sexe.GARCON
                in fille -> Sexe.FILLE
                else -> null
            }
            if (texteSexe.isNotEmpty() && sexe == null) {
                rejeter(MotifRejet.SEXE_INVALIDE)
                continue
            }

            val resultat = ValidationEnfant.valider(
                SaisieEnfant(nom = nom, prenom = prenom, dateNaissance = naissance, sexe = sexe),
                aujourdhui,
            )
            when (resultat) {
                is ResultatValidation.Invalide -> rejeter(motifDepuis(resultat.erreurs))
                is ResultatValidation.Valide -> {
                    val donnees = resultat.donnees
                    val identite = cle(donnees.nom, donnees.prenom, donnees.dateNaissance)
                    when {
                        identite in dejaVus -> rejeter(MotifRejet.DEJA_INSCRIT)
                        !dansLeFichier.add(identite) -> rejeter(MotifRejet.DOUBLON_DANS_LE_FICHIER)
                        else -> aAjouter += donnees
                    }
                }
            }
        }
        return AnalyseImport.Pret(aAjouter, rejets)
    }

    /** Premier motif dans l'ordre de lecture d'une ligne : nom, prénom, naissance, sexe. */
    private fun motifDepuis(erreurs: Map<ChampEnfant, ErreurChamp>): MotifRejet {
        erreurs[ChampEnfant.NOM]?.let { return if (it == ErreurChamp.TROP_LONG) MotifRejet.TEXTE_TROP_LONG else MotifRejet.NOM_MANQUANT }
        erreurs[ChampEnfant.PRENOM]?.let { return if (it == ErreurChamp.TROP_LONG) MotifRejet.TEXTE_TROP_LONG else MotifRejet.PRENOM_MANQUANT }
        erreurs[ChampEnfant.DATE_NAISSANCE]?.let {
            return if (it == ErreurChamp.DATE_FUTURE) MotifRejet.NAISSANCE_FUTURE else MotifRejet.NAISSANCE_MANQUANTE
        }
        if (ChampEnfant.SEXE in erreurs) return MotifRejet.SEXE_MANQUANT
        return MotifRejet.TEXTE_TROP_LONG
    }

    private fun cle(nom: String, prenom: String, naissance: LocalDate): String =
        "${Recherche.normaliser(nom)}|${Recherche.normaliser(prenom)}|$naissance"

    private fun cleEntete(titre: String): String =
        Recherche.normaliser(nettoyer(titre)).replace('’', '\'').replace("'", " ").replace(Regex("\\s+"), " ").trim()

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
