package fi.otsosoft.tikkujahti.peli

import fi.otsosoft.tikkujahti.ukko.Tyyppi

/** How many figures of each kind the player has caught. The platform saves [teksti] and restores it with [lue]. */
class Albumi {
    private val maarat = IntArray(Tyyppi.entries.size)
    /** Grows with every change, so the platform knows when to save. */
    var muutoksia = 0
        private set

    operator fun get(t: Tyyppi) = maarat[t.ordinal]
    val yhteensa: Int get() = maarat.sum()
    val loydetty: Int get() = maarat.count { it > 0 }

    fun lisaa(t: Tyyppi) {
        maarat[t.ordinal]++
        muutoksia++
    }

    /** Saved form, e.g. "TAVALLINEN=12;ISO=1". Kinds are stored by name, so new kinds can be added freely. */
    fun teksti(): String = Tyyppi.entries.filter { this[it] > 0 }.joinToString(";") { "${it.name}=${this[it]}" }

    companion object {
        fun lue(teksti: String?): Albumi {
            val a = Albumi()
            for (osa in teksti.orEmpty().split(';')) {
                val nimi = osa.substringBefore('=')
                val maara = osa.substringAfter('=', "").toIntOrNull() ?: continue
                val t = Tyyppi.entries.firstOrNull { it.name == nimi } ?: continue
                a.maarat[t.ordinal] = maara.coerceAtLeast(0)
            }
            return a
        }
    }
}
