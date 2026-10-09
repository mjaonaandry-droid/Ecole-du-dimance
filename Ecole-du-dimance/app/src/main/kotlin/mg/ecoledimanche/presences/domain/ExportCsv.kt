package mg.ecoledimanche.presences.domain

import java.time.LocalDate

/** Une ligne de l'export : un enfant (informations de base) et sa présence, dimanche par dimanche. */
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
)

/** Les dimanches (colonnes, du plus ancien au plus récent) et les enfants (lignes) à exporter. */
data class TableauExport(
    val dimanches: List<LocalDate>,
    val lignes: List<LigneExport>,
)

/**
 * Export CSV des enfants : informations de base et présences uniquement (ni parents, ni adresse,
 * ni photo). Le format est pensé pour s'ouvrir directement dans Excel ou LibreOffice en français :
 * séparateur point-virgule, UTF-8 avec BOM (les accents s'affichent correctement), fins de ligne CRLF.
 */
object ExportCsv {
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

    fun construire(tableau: TableauExport, aujourdhui: LocalDate): String {
        val lignes = ArrayList<String>(tableau.lignes.size + 1)
        lignes += ligne(
            listOf("Nom", "Prénom", "Sexe", "Date de naissance", "Âge", "Statut") +
                tableau.dimanches.map(FormatsFr::dateCourte) +
                listOf("Présences", "Retards", "Absences", "Assiduité"),
        )
        for (enfant in tableau.lignes) {
            lignes += ligne(
                listOf(
                    enfant.nom,
                    enfant.prenom,
                    if (enfant.sexe == Sexe.GARCON) "Garçon" else "Fille",
                    FormatsFr.dateCourte(enfant.dateNaissance),
                    Age.enAnnees(enfant.dateNaissance, aujourdhui).toString(),
                    if (enfant.archive) "Archivé" else "Actif",
                ) +
                    tableau.dimanches.map { dimanche -> libelleStatut(enfant.statuts[dimanche]) } +
                    listOf(
                        enfant.compteurs.presences.toString(),
                        enfant.compteurs.retards.toString(),
                        enfant.compteurs.absences.toString(),
                        enfant.compteurs.tauxDixiemes?.let { FormatTaux.format(it) }.orEmpty(),
                    ),
            )
        }
        return BOM + lignes.joinToString(separator = FIN_DE_LIGNE, postfix = FIN_DE_LIGNE)
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
