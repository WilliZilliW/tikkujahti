package fi.otsosoft.tikkujahti.peli

import fi.otsosoft.tikkujahti.ukko.Tyyppi

/**
 * A level of the game. Catching [tavoite] figures finishes it. The higher the level, the better the figures
 * are hidden: further off, more often out of sight and in the room's corners, nooks and shadows, quicker to
 * slip away, with fewer hints of where they are. New kinds of figure join in along the way.
 */
class Taso(val numero: Int) {
    private val n = (numero - 1).coerceAtLeast(0)

    /** Catches needed to finish the level. */
    val tavoite = (3 + 2 * numero).coerceAtMost(15)
    /** How often a figure turns up where the player is not looking. */
    val piilossa = (0.1f + 0.13f * n).coerceAtMost(0.9f)
    /** How often it turns up in one of the room's hiding places, when there are any. */
    val paikoissa = (0.1f + 0.1f * n).coerceAtMost(0.8f)
    /** The furthest from the player a figure turns up, metres. */
    val etaisyys = (2.2f + 0.3f * n).coerceAtMost(4f)
    /** How many figures (or herds) are out at once in the searching game. */
    val ukkoja = (2 + numero / 2).coerceAtMost(5)
    /** How often a new figure is a special kind. */
    val erikoisia = (0.15f + 0.06f * n).coerceAtMost(0.6f)
    /** How often a sound out of view gets an arrow at the edge of the screen. */
    val nuolia = (1f - 0.12f * n).coerceAtLeast(0.25f)
    /** Added to every figure's skill at dodging. */
    val vaistolisa = (0.04f * n).coerceAtMost(0.3f)
    /** How long a special figure stays if nobody catches it, seconds. */
    val vierailu = (46f - 3f * numero).coerceAtLeast(22f)

    /** The kinds of figure met on this level. */
    val tyypit: List<Tyyppi> = AVAUTUU.filter { it.value <= numero }.keys.toList()
    /** The kinds that appear for the first time on this level. */
    val uudet: List<Tyyppi> = AVAUTUU.filter { it.value == numero }.keys.toList()

    companion object {
        /** The level each kind of figure first appears on. */
        val AVAUTUU = mapOf(
            Tyyppi.TAVALLINEN to 1,
            Tyyppi.JUOKSIJA to 2, Tyyppi.LASKUVARJO to 2,
            Tyyppi.ISO to 3, Tyyppi.KILPI to 3,
            Tyyppi.KIIPEILIJA to 4, Tyyppi.LAUMA to 4,
            Tyyppi.KATTO to 5,
            Tyyppi.NINJA to 6, Tyyppi.PIILO to 6,
            Tyyppi.KULTAINEN to 8,
        )
    }
}
