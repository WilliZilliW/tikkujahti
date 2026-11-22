package fi.otsosoft.tikkujahti.peli

import fi.otsosoft.tikkujahti.maailma.Pinta
import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.matikka.kierra
import fi.otsosoft.tikkujahti.ukko.Ukko

enum class PallonTila {
    /** Flying, bouncing and rolling. */
    LENTAA,
    /** Hit a figure: hangs in the air while the figure curls into it. */
    VANGITSEE,
    /** Carries the caught figure back to the player. */
    PALAA,
    /** A miss that has come to rest and is fading away. */
    HAIPUU,
    POIS,
}

class Pallo(val id: Int, var p: V3, var v: V3, val sade: Float) {
    var tila = PallonTila.LENTAA
    /** Sideways acceleration from a curved swipe; ends at the first bounce. */
    var kaarre = V3.NOLLA
    var ika = 0f
    var tilaAika = 0f
    var pomppuja = 0
    /** The figure this ball caught. */
    var ukko: Ukko? = null
    /** Drawn size, 0…1 and a little over: balls pop when they catch and shrink when they fade. */
    var koko = 1f

    // Orientation, for the spin
    var x = V3.X
        private set
    var y = V3.Y
        private set
    var z = V3.Z
        private set

    fun vaihda(t: PallonTila) {
        tila = t
        tilaAika = 0f
    }

    fun pyorita(akseli: V3, kulma: Float) {
        x = kierra(x, akseli, kulma)
        y = kierra(y, akseli, kulma).tasolle(x).suunta(V3.Y)
        z = x cross y
    }

    /**
     * One physics step. Returns the speed of the hardest bounce during it, or 0.
     * Surfaces are one-sided: a ball only bounces off the side the normal points to.
     */
    fun askel(dt: Float, pinnat: List<Pinta>): Float {
        v += (PAINOVOIMA + kaarre) * dt
        val ed = p
        p += v * dt
        var isku = 0f
        for (pinta in pinnat) {
            val d = pinta.etaisyys(p)
            if (d >= sade || d < -sade * 2f - 0.02f) continue
            val vn = v dot pinta.normaali
            if (vn >= 0f || pinta.etaisyys(ed) < d) continue
            if (!pinta.sisalla(p, sade * 0.5f)) continue
            p += pinta.normaali * (sade - d)
            val pysty = pinta.normaali * vn
            val sivu = v - pysty
            // Lively off the first bounce, then quickly calmer; slow contacts just roll
            val kimmoisuus = if (-vn < 0.35f) 0f else KIMMOISUUS
            v = sivu * (if (kimmoisuus == 0f) 1f - 2.5f * dt else 0.82f) - pysty * kimmoisuus
            if (-vn > isku) isku = -vn
            if (kimmoisuus > 0f) {
                pomppuja++
                kaarre = V3.NOLLA
            }
        }
        // Spin as if rolling in the direction of travel
        val vaaka = v.vaakaan()
        val vauhti = vaaka.pituus
        if (vauhti > 0.01f) pyorita((V3.Y cross vaaka).suunta(V3.X), vauhti / sade * dt * 0.6f)
        return isku
    }

    companion object {
        val PAINOVOIMA = V3(0f, -9.81f, 0f)
        const val KIMMOISUUS = 0.62f
        /** A ball the size of a ping-pong ball suits figures 25 cm tall. */
        const val SADE = 0.024f
    }
}
