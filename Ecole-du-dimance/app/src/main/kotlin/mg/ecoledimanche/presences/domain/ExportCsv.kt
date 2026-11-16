package mg.ecoledimanche.presences.domain

import java.time.LocalDate

/** Informations de la fiche d'un enfant, exportées selon les [GroupeExport] choisis. */
data class DetailsExport(
    /** Identifiant technique de l'enfant dans l'application (colonne « Identifiant »). */
    val id: Long? = null,
    val adresse: String? = null,
    val nomPere: String? = null,
    val pereMembre: Boolean? = null,
    val nomMere: String? = null,
    val mereMembre: Boolean? = null,
    val nombreFreresSoeurs: Int = 0,
    val nombreFreresSoeursMembres: Int = 0,
    val dateArrivee: LocalDate? = null,
    val debutSuivi: LocalDate? = null,
    val finSuivi: LocalDate? = null,
)

/** Une ligne de l'export : un enfant et sa présence, dimanche par dimanche. */
data class LigneExport(
    val nom: String,
    val prenom: String,
    val sexe: Sexe,
    val dateNaissance: LocalDate,
    val archive: Boolean,
    /**
     * Statut affiché pour chaque dimanche où l'enfant était suivi. Un dimanche absent de la table :
     * l'enfant n'était pas suivi ce jour-là (cellule vide).
     */
    val statuts: Map<LocalDate, StatutPresence>,
    /** Comptes sur les séances clôturées uniquement, comme l'écran Assiduité. */
    val compteurs: CompteursAssiduite,
    val details: DetailsExport = DetailsExport(),
)

/** Les dimanches (colonnes, du plus ancien au plus récent) et les enfants (lignes) à exporter. */
data class TableauExport(
    val dimanches: List<LocalDate>,
    val lignes: List<LigneExport>,
)

/**
 * Groupes de colonnes que l'utilisateur peut inclure ou non. Nom, prénom, sexe et date de naissance
 * sont TOUJOURS exportés : ils identifient l'enfant et permettent de réimporter le fichier.
 * L'ordre de l'énumération est celui des colonnes dans le fichier.
 */
enum class GroupeExport(val colonnes: List<String>) {
    /** Permet la mise à jour par import, même après un changement de nom ou de date de naissance. */
    IDENTIFIANT(listOf(ExportCsv.COLONNE_IDENTIFIANT)),
    AGE_STATUT(listOf("Âge", ExportCsv.COLONNE_STATUT)),
    ADRESSE(listOf(ExportCsv.COLONNE_ADRESSE)),
    PARENTS(listOf(ExportCsv.COLONNE_NOM_PERE, ExportCsv.COLONNE_PERE_MEMBRE, ExportCsv.COLONNE_NOM_MERE, ExportCsv.COLONNE_MERE_MEMBRE)),
    FRATRIE(listOf(ExportCsv.COLONNE_FRATRIE, ExportCsv.COLONNE_FRATRIE_MEMBRES)),
    EGLISE(listOf(ExportCsv.COLONNE_ARRIVEE)),
    SUIVI(listOf("Début du suivi", "Fin du suivi")),

    /** Une colonne par dimanche (Présent, En retard, Absent). */
    PRESENCES(emptyList()),
    TOTAUX(listOf("Présences", "Retards", "Absences", "Assiduité")),
    ;

    companion object {
        /** Tout exporter. */
        val TOUS: Set<GroupeExport> = entries.toSet()

        /** Contenu de l'export des versions 1.0 et 1.1 (informations de base et présences). */
        val BASE_ET_PRESENCES: Set<GroupeExport> = setOf(AGE_STATUT, PRESENCES, TOTAUX)

        /** Groupes qui contiennent des informations personnelles sur la famille. */
        val PERSONNELS: Set<GroupeExport> = setOf(ADRESSE, PARENTS, FRATRIE)
    }
}

/**
 * Export CSV des enfants, complet ou partiel (voir [GroupeExport]). Le format est pensé pour s'ouvrir
 * directement dans Excel ou LibreOffice en français : séparateur point-virgule, UTF-8 avec BOM (les
 * accents s'affichent correctement), fins de ligne CRLF. Le fichier se réimporte tel quel (onglet
 * Données ▸ Importer) pour ajouter ou mettre à jour des enfants.
 */
object ExportCsv {
    const val COLONNE_IDENTIFIANT = "Identifiant"
    const val COLONNE_STATUT = "Statut"
    const val COLONNE_ADRESSE = "Adresse"
    const val COLONNE_NOM_PERE = "Nom du père"
    const val COLONNE_PERE_MEMBRE = "Père membre de l'église"
    const val COLONNE_NOM_MERE = "Nom de la mère"
    const val COLONNE_MERE_MEMBRE = "Mère membre de l'église"
    const val COLONNE_FRATRIE = "Frères et sœurs"
    const val COLONNE_FRATRIE_MEMBRES = "Frères et sœurs membres de l'église"
    const val COLONNE_ARRIVEE = "Date d'arrivée dans l'église"

    private const val SEPARATEUR = ";"
    private const val FIN_DE_LIGNE = "\r\n"
    /**
     * Marque d'ordre des octets (U+FEFF) : Excel s'en sert pour reconnaître l'UTF-8. Construite par son
     * code plutôt qu'écrite en toutes lettres, car le lint refuse un BOM littéral dans une source.
     */
    private val BOM: String = 0xFEFF.toChar().toString()

    /** Caractères qui feraient interpréter la cellule comme une formule dans un tableur. */
    private const val DEBUTS_DE_FORMULE = "=+-@\t\r"

    /** Nom de fichier proposé : « presences-ecole-du-dimanche-2026-10-09.csv ». */
    fun nomFichier(aujourdhui: LocalDate): String = "presences-ecole-du-dimanche-$aujourdhui.csv"

    /**
     * @param groupes colonnes à inclure en plus de l'identité (nom, prénom, sexe, date de naissance).
     */
    fun construire(
        tableau: TableauExport,
        aujourdhui: LocalDate,
        groupes: Set<GroupeExport> = GroupeExport.BASE_ET_PRESENCES,
    ): String {
        val lignes = ArrayList<String>(tableau.lignes.size + 1)
        val entete = ArrayList<String>()
        if (GroupeExport.IDENTIFIANT in groupes) entete += GroupeExport.IDENTIFIANT.colonnes
        entete += listOf("Nom", "Prénom", "Sexe", "Date de naissance")
        for (groupe in GroupeExport.entries) {
            if (groupe == GroupeExport.IDENTIFIANT || groupe !in groupes) continue
            entete += if (groupe == GroupeExport.PRESENCES) tableau.dimanches.map(FormatsFr::dateCourte) else groupe.colonnes
        }
        lignes += ligne(entete)

        for (enfant in tableau.lignes) {
            val d = enfant.details
            val cellules = ArrayList<String>(entete.size)
            if (GroupeExport.IDENTIFIANT in groupes) cellules += d.id?.toString().orEmpty()
            cellules += listOf(
                enfant.nom,
                enfant.prenom,
                libelleSexe(enfant.sexe),
                FormatsFr.dateCourte(enfant.dateNaissance),
            )
            for (groupe in GroupeExport.entries) {
                if (groupe == GroupeExport.IDENTIFIANT || groupe !in groupes) continue
                cellules += when (groupe) {
                    GroupeExport.IDENTIFIANT -> emptyList()
                    GroupeExport.AGE_STATUT -> listOf(
                        Age.enAnnees(enfant.dateNaissance, aujourdhui).toString(),
                        if (enfant.archive) "Archivé" else "Actif",
                    )
                    GroupeExport.ADRESSE -> listOf(d.adresse.orEmpty())
                    GroupeExport.PARENTS -> listOf(d.nomPere.orEmpty(), ouiNon(d.pereMembre), d.nomMere.orEmpty(), ouiNon(d.mereMembre))
                    GroupeExport.FRATRIE -> listOf(d.nombreFreresSoeurs.toString(), d.nombreFreresSoeursMembres.toString())
                    GroupeExport.EGLISE -> listOf(d.dateArrivee?.let(FormatsFr::dateCourte).orEmpty())
                    GroupeExport.SUIVI -> listOf(
                        d.debutSuivi?.let(FormatsFr::dateCourte).orEmpty(),
                        d.finSuivi?.let(FormatsFr::dateCourte).orEmpty(),
                    )
                    GroupeExport.PRESENCES -> tableau.dimanches.map { dimanche -> libelleStatut(enfant.statuts[dimanche]) }
                    GroupeExport.TOTAUX -> listOf(
                        enfant.compteurs.presences.toString(),
                        enfant.compteurs.retards.toString(),
                        enfant.compteurs.absences.toString(),
                        enfant.compteurs.tauxDixiemes?.let { FormatTaux.format(it) }.orEmpty(),
                    )
                }
            }
            lignes += ligne(cellules)
        }
        return BOM + lignes.joinToString(separator = FIN_DE_LIGNE, postfix = FIN_DE_LIGNE)
    }

    fun libelleSexe(sexe: Sexe): String = if (sexe == Sexe.GARCON) "Garçon" else "Fille"

    /** « Oui », « Non », ou vide si l'information n'est pas renseignée. */
    fun ouiNon(valeur: Boolean?): String = when (valeur) {
        true -> "Oui"
        false -> "Non"
        null -> ""
    }

    /** Une cellule vide : enfant non suivi ce dimanche-là, ou séance encore ouverte sans pointage. */
    private fun libelleStatut(statut: StatutPresence?): String = when (statut) {
        StatutPresence.PRESENT -> "Présent"
        StatutPresence.EN_RETARD -> "En retard"
        StatutPresence.ABSENT -> "Absent"
        StatutPresence.NON_ENREGISTRE, null -> ""
    }

    private fun ligne(champs: List<String>): String = champs.joinToString(SEPARATEUR) { champ(it) }

    /**
     * Protège une cellule : une valeur qui commencerait par = + - @ (ou une tabulation) est précédée
     * d'une apostrophe pour qu'un tableur ne l'exécute jamais comme une formule ; une valeur qui
     * contient un séparateur, un guillemet ou un saut de ligne est mise entre guillemets (RFC 4180).
     */
    internal fun champ(valeur: String): String {
        val protegee = if (valeur.isNotEmpty() && valeur[0] in DEBUTS_DE_FORMULE) "'$valeur" else valeur
        val besoinDeGuillemets = protegee.any { it == ';' || it == '"' || it == '\n' || it == '\r' }
        return if (besoinDeGuillemets) "\"" + protegee.replace("\"", "\"\"") + "\"" else protegee
    }
}
