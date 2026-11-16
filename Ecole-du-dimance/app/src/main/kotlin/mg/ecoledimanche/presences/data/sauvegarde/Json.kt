package mg.ecoledimanche.presences.data.sauvegarde

/**
 * JSON minimal pour la sauvegarde complète : objets, tableaux, textes, nombres entiers, booléens et nul.
 * Volontairement sans dépendance (ni bibliothèque, ni `org.json` d'Android, absent des tests JVM).
 * Les objets sont des `Map<String, Any?>` (ordre conservé), les tableaux des `List<Any?>`,
 * les nombres des `Long`.
 */
object Json {
    fun ecrire(valeur: Any?): String = StringBuilder().also { ecrire(valeur, it) }.toString()

    private fun ecrire(valeur: Any?, sortie: StringBuilder) {
        when (valeur) {
            null -> sortie.append("null")
            is Boolean -> sortie.append(valeur)
            is Int, is Long -> sortie.append(valeur)
            is String -> texte(valeur, sortie)
            is Map<*, *> -> {
                sortie.append('{')
                var premier = true
                for ((cle, element) in valeur) {
                    if (!premier) sortie.append(',')
                    premier = false
                    texte(cle as String, sortie)
                    sortie.append(':')
                    ecrire(element, sortie)
                }
                sortie.append('}')
            }
            is List<*> -> {
                sortie.append('[')
                valeur.forEachIndexed { i, element ->
                    if (i > 0) sortie.append(',')
                    ecrire(element, sortie)
                }
                sortie.append(']')
            }
            else -> throw IllegalArgumentException("Type non pris en charge : ${valeur::class}")
        }
    }

    private fun texte(valeur: String, sortie: StringBuilder) {
        sortie.append('"')
        for (c in valeur) {
            when {
                c == '"' -> sortie.append("\\\"")
                c == '\\' -> sortie.append("\\\\")
                c == '\n' -> sortie.append("\\n")
                c == '\r' -> sortie.append("\\r")
                c == '\t' -> sortie.append("\\t")
                c < ' ' -> sortie.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sortie.append(c)
            }
        }
        sortie.append('"')
    }

    /** Lève [FormatInvalideException] si le texte n'est pas un JSON valide (de ce sous-ensemble). */
    fun lire(texte: String): Any? {
        val lecteur = Lecteur(texte)
        val valeur = lecteur.valeur()
        lecteur.espaces()
        if (!lecteur.fini()) throw FormatInvalideException("Caractères en trop après le JSON")
        return valeur
    }

    private class Lecteur(private val t: String) {
        private var i = 0

        fun fini() = i >= t.length

        fun espaces() {
            while (i < t.length && t[i].isWhitespace()) i++
        }

        private fun attendre(c: Char) {
            espaces()
            if (i >= t.length || t[i] != c) throw FormatInvalideException("« $c » attendu à la position $i")
            i++
        }

        fun valeur(): Any? {
            espaces()
            if (i >= t.length) throw FormatInvalideException("Fin de texte inattendue")
            return when (val c = t[i]) {
                '{' -> objet()
                '[' -> tableau()
                '"' -> texte()
                't' -> motCle("true", true)
                'f' -> motCle("false", false)
                'n' -> motCle("null", null)
                else -> if (c == '-' || c.isDigit()) nombre() else throw FormatInvalideException("Valeur inattendue à la position $i")
            }
        }

        private fun motCle(mot: String, valeur: Any?): Any? {
            if (!t.startsWith(mot, i)) throw FormatInvalideException("« $mot » attendu à la position $i")
            i += mot.length
            return valeur
        }

        private fun nombre(): Long {
            val debut = i
            if (t[i] == '-') i++
            while (i < t.length && t[i].isDigit()) i++
            return t.substring(debut, i).toLongOrNull() ?: throw FormatInvalideException("Nombre invalide à la position $debut")
        }

        private fun texte(): String {
            attendre('"')
            val sortie = StringBuilder()
            while (true) {
                if (i >= t.length) throw FormatInvalideException("Texte non terminé")
                val c = t[i++]
                when (c) {
                    '"' -> return sortie.toString()
                    '\\' -> {
                        if (i >= t.length) throw FormatInvalideException("Échappement incomplet")
                        when (val e = t[i++]) {
                            '"' -> sortie.append('"')
                            '\\' -> sortie.append('\\')
                            '/' -> sortie.append('/')
                            'n' -> sortie.append('\n')
                            'r' -> sortie.append('\r')
                            't' -> sortie.append('\t')
                            'b' -> sortie.append('\b')
                            'f' -> sortie.append('\u000C')
                            'u' -> {
                                if (i + 4 > t.length) throw FormatInvalideException("Échappement \\u incomplet")
                                val code = t.substring(i, i + 4).toIntOrNull(16) ?: throw FormatInvalideException("Échappement \\u invalide")
                                sortie.append(code.toChar())
                                i += 4
                            }
                            else -> throw FormatInvalideException("Échappement \\$e inconnu")
                        }
                    }
                    else -> sortie.append(c)
                }
            }
        }

        private fun tableau(): List<Any?> {
            attendre('[')
            val liste = ArrayList<Any?>()
            espaces()
            if (i < t.length && t[i] == ']') {
                i++
                return liste
            }
            while (true) {
                liste += valeur()
                espaces()
                if (i < t.length && t[i] == ',') {
                    i++
                    continue
                }
                attendre(']')
                return liste
            }
        }

        private fun objet(): Map<String, Any?> {
            attendre('{')
            val carte = LinkedHashMap<String, Any?>()
            espaces()
            if (i < t.length && t[i] == '}') {
                i++
                return carte
            }
            while (true) {
                espaces()
                val cle = texte()
                attendre(':')
                carte[cle] = valeur()
                espaces()
                if (i < t.length && t[i] == ',') {
                    i++
                    continue
                }
                attendre('}')
                return carte
            }
        }
    }
}

/** Fichier de sauvegarde illisible ou incohérent. */
class FormatInvalideException(message: String) : Exception(message)
