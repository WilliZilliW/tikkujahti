package fi.otsosoft.tikkujahti.peli

import fi.otsosoft.tikkujahti.ukko.Tyyppi

/** How many figures of each kind the player has caught, and the level reached. The platform saves [teksti] and restores it with [lue]. */
class Albumi {
    private val maarat = IntArray(Tyyppi.entries.size)
    /** The level the player is on, and how many figures have been caught on it so far. */
    var taso = 1
        private set
    var edistys = 0
        private set

    /** Counts a catch towards the level. Returns true if that finished the level. */
    fun etene(tavoite: Int): Boolean {
        edistys++
        muutoksia++
        if (edistys < tavoite) return false
        taso++
        edistys = 0
        return true
    }

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
    fun teksti(): String = (Tyyppi.entries.filter { this[it] > 0 }.map { "${it.name}=${this[it]}" } + listOf("TASO=$taso", "EDISTYS=$edistys")).joinToString(";")

    companion object {
        fun lue(teksti: String?): Albumi {
            val a = Albumi()
            for (osa in teksti.orEmpty().split(';')) {
                val nimi = osa.substringBefore('=')
                val maara = osa.substringAfter('=', "").toIntOrNull() ?: continue
                if (nimi == "TASO") a.taso = maara.coerceAtLeast(1)
                if (nimi == "EDISTYS") a.edistys = maara.coerceAtLeast(0)
                val t = Tyyppi.entries.firstOrNull { it.name == nimi } ?: continue
                a.maarat[t.ordinal] = maara.coerceAtLeast(0)
            }
            return a
        }
    }
}
