package fi.otsosoft.tikkujahti.ukko

import fi.otsosoft.tikkujahti.matikka.V3

/**
 * Rag-doll physics for a knocked figure (Verlet integration): the joints are points, the bones are
 * distance constraints, and a few extra constraints keep the torso and head rigid.
 */
class Nukke(alku: Array<V3>, nopeudet: Array<V3>, m: Mitat) {
    val paikat = alku.copyOf()
    private val edelliset = Array(alku.size) { V3.NOLLA }
    private val sade = m.raajaSade * 1.2f
    private var kertyma = 0f
    /** A ceiling right above, if the figure was knocked off one: the doll must not fly up through it. */
    var katto: Float? = null

    private val rajoitteet: List<Triple<Int, Int, Float>>

    init {
        val apu = 1f / 120f
        for (i in alku.indices) edelliset[i] = alku[i] - nopeudet[i] * apu
        val parit = listOf(
            Nivel.LANTIO to Nivel.RINTA, Nivel.RINTA to Nivel.NISKA, Nivel.NISKA to Nivel.PAA,
            Nivel.RINTA to Nivel.PAA,
            Nivel.OLKA_V to Nivel.KYYNARPAA_V, Nivel.KYYNARPAA_V to Nivel.KASI_V,
            Nivel.OLKA_O to Nivel.KYYNARPAA_O, Nivel.KYYNARPAA_O to Nivel.KASI_O,
            Nivel.LONKKA_V to Nivel.POLVI_V, Nivel.POLVI_V to Nivel.NILKKA_V, Nivel.NILKKA_V to Nivel.VARVAS_V,
            Nivel.LONKKA_O to Nivel.POLVI_O, Nivel.POLVI_O to Nivel.NILKKA_O, Nivel.NILKKA_O to Nivel.VARVAS_O,
            Nivel.POLVI_V to Nivel.VARVAS_V, Nivel.POLVI_O to Nivel.VARVAS_O,
            // rigid torso
            Nivel.OLKA_V to Nivel.OLKA_O, Nivel.LONKKA_V to Nivel.LONKKA_O,
            Nivel.OLKA_V to Nivel.RINTA, Nivel.OLKA_O to Nivel.RINTA,
            Nivel.LONKKA_V to Nivel.LANTIO, Nivel.LONKKA_O to Nivel.LANTIO,
            Nivel.OLKA_V to Nivel.LANTIO, Nivel.OLKA_O to Nivel.LANTIO,
            Nivel.LONKKA_V to Nivel.RINTA, Nivel.LONKKA_O to Nivel.RINTA,
            Nivel.OLKA_V to Nivel.LONKKA_O, Nivel.OLKA_O to Nivel.LONKKA_V,
            Nivel.PAA to Nivel.OLKA_V, Nivel.PAA to Nivel.OLKA_O,
        )
        rajoitteet = parit.map { (a, b) -> Triple(a.ordinal, b.ordinal, (alku[a.ordinal] - alku[b.ordinal]).pituus) }
    }

    fun paivita(dt: Float, lattiaY: Float) {
        kertyma += dt
        val askel = 1f / 120f
        var n = 0
        while (kertyma >= askel && n < 8) {
            kertyma -= askel
            n++
            askella(askel, lattiaY)
        }
    }

    private fun askella(dt: Float, lattiaY: Float) {
        val g = V3(0f, -9.81f, 0f) * (dt * dt)
        for (i in paikat.indices) {
            val p = paikat[i]
            val v = (p - edelliset[i]) * 0.995f
            edelliset[i] = p
            paikat[i] = p + v + g
        }
        repeat(6) {
            for ((a, b, l) in rajoitteet) {
                val d = paikat[b] - paikat[a]
                val nyt = d.pituus
                if (nyt < 1e-6f) continue
                val korjaus = d * ((nyt - l) / nyt * 0.5f)
                paikat[a] = paikat[a] + korjaus
                paikat[b] = paikat[b] - korjaus
            }
            for (i in paikat.indices) {
                val p = paikat[i]
                val k = katto
                if (k != null && p.y > k - sade) {
                    paikat[i] = V3(p.x, k - sade, p.z)
                    edelliset[i] = V3(edelliset[i].x, k - sade, edelliset[i].z)
                } else if (p.y < lattiaY + sade) {
                    paikat[i] = V3(p.x, lattiaY + sade, p.z)
                    // friction: the point drags along the floor
                    val e = edelliset[i]
                    edelliset[i] = V3(p.x + (e.x - p.x) * 0.6f, e.y, p.z + (e.z - p.z) * 0.6f)
                }
            }
        }
    }
}
