package fi.otsosoft.tikkujahti.maailma

import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.matikka.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Finds the floor in AR without asking the player anything, so that figures simply start turning up.
 *
 * Feed it the height of whatever upward surface the platform sees under the middle of the screen. The first
 * one well below the phone becomes the floor; a clearly lower one seen later replaces it (the first was a
 * table). If nothing is seen for a moment, the floor is guessed from how high people hold a phone, and the
 * guess is corrected as soon as a real surface is seen.
 */
class LattianHaku {
    /** Height of the floor, once there is one. */
    var y: Float? = null
        private set
    /** The floor is only a guess so far. */
    var arvattu = false
        private set
    private var aika = 0f
    private var ehdokas: Float? = null
    private var ehdokkaita = 0
    private var keskus = V3.NOLLA
    private var pinta: Pinta? = null

    fun ruutu(dt: Float, kamera: V3, havaittuY: Float?) {
        aika += dt
        val nyt = y
        if (havaittuY != null) {
            val alla = kamera.y - havaittuY
            when {
                nyt == null || arvattu -> if (alla > VAHIMMAISKORKEUS) {
                    y = havaittuY
                    arvattu = false
                }
                // Clearly lower: the real floor, if it keeps being seen there (one odd measurement moves nothing)
                havaittuY < nyt - 0.2f -> if (alla < 2.5f) {
                    val e = ehdokas
                    if (e != null && abs(e - havaittuY) < 0.06f) {
                        ehdokas = lerp(e, havaittuY, 0.2f)
                        if (++ehdokkaita >= VAHVISTUS) {
                            y = ehdokas
                            ehdokas = null
                        }
                    } else {
                        ehdokas = havaittuY
                        ehdokkaita = 1
                    }
                }
                abs(havaittuY - nyt) < 0.2f -> y = lerp(nyt, havaittuY, 0.1f) // the same floor, measured better
            }
        }
        if (y == null && aika > ODOTUS) {
            y = kamera.y - OLETUSKORKEUS
            arvattu = true
        }
    }

    /**
     * The floor as a wide disc that follows the player about, or null while there is none. The same object
     * is returned until the floor moves.
     */
    fun lattia(kamera: V3): Pinta? {
        val korkeus = y ?: return null
        val vanha = pinta
        if (vanha != null && abs(vanha.keskus.y - korkeus) < 0.004f && (kamera - keskus).vaakaan().pituus < 1f) return vanha
        keskus = V3(kamera.x, korkeus, kamera.z)
        return Pinta("ar-lattia", PinnanLaji.LATTIA, keskus, V3.Y, V3.X, List(20) { i ->
            (cos(i * PI / 10) * SADE).toFloat() to (sin(i * PI / 10) * SADE).toFloat()
        }).also { pinta = it }
    }

    companion object {
        /** Seconds to look for a real surface before guessing. */
        const val ODOTUS = 2.5f
        /** How far below the phone the guessed floor is: a phone held by someone standing. */
        const val OLETUSKORKEUS = 1.25f
        /** A surface nearer than this below the phone is taken for a table, not the floor. */
        const val VAHIMMAISKORKEUS = 0.45f
        /** How many times a lower floor has to be seen at the same height before the floor moves there. */
        const val VAHVISTUS = 8
        /** Wide enough that figures a few rooms' corners away still have floor under them. */
        const val SADE = 7.0
    }
}
