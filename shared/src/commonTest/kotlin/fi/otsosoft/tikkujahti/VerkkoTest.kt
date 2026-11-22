package fi.otsosoft.tikkujahti

import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.mesh.PalloVerkko
import fi.otsosoft.tikkujahti.mesh.UkkoVerkko
import fi.otsosoft.tikkujahti.mesh.Verkko
import fi.otsosoft.tikkujahti.ukko.Toiminto
import fi.otsosoft.tikkujahti.ukko.Ukko
import fi.otsosoft.tikkujahti.ukko.Ulkonako
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerkkoTest {
    private fun p(v: Verkko, i: Int) = V3(v.paikat[i * 3], v.paikat[i * 3 + 1], v.paikat[i * 3 + 2])
    private fun n(v: Verkko, i: Int) = V3(v.normaalit[i * 3], v.normaalit[i * 3 + 1], v.normaalit[i * 3 + 2])

    /** Share of each part's area whose triangles wind against their vertex normals. */
    private fun vaarinPain(v: Verkko): List<Float> = (0 until v.osia).map { osa ->
        var vaarin = 0f
        var kaikki = 1e-12f
        for (t in 0 until v.osaMaara[osa] / 3) {
            val o = v.osaAlku[osa] + t * 3
            val a = v.indeksit[o]; val b = v.indeksit[o + 1]; val c = v.indeksit[o + 2]
            val g = (p(v, b) - p(v, a)) cross (p(v, c) - p(v, a))
            kaikki += g.pituus
            if ((g dot (n(v, a) + n(v, b) + n(v, c))) < 0f) vaarin += g.pituus
        }
        vaarin / kaikki
    }

    @Test
    fun pallonKolmiotOsoittavatUlos() {
        val v = PalloVerkko()
        assertEquals(listOf(0f, 0f), vaarinPain(v))
        assertTrue(v.osaMaara[0] > 0 && v.osaMaara[1] > 0)
    }

    @Test
    fun ukonVerkkoPysyyEhjanaKaikissaToiminnoissa() {
        for (t in Toiminto.entries) {
            val u = Ukko(1, Ulkonako(Ulkonako.VARIT[0]), V3(0.3f, 0f, -1f), 0.7f, 5)
            u.omatoiminen = false
            u.katsoja = V3(0f, 1.4f, 0.5f)
            val v = UkkoVerkko(u.m)
            val indeksit = v.indeksit.copyOf()
            val pisteita = v.pisteita
            repeat(90) { u.paivita(1f / 60f) }
            u.komenna(t)
            repeat(150) { ruutu ->
                u.paivita(1f / 60f)
                v.paivita(u.asento)
                assertEquals(pisteita, v.pisteita)
                for (i in 0 until v.pisteita * 3) {
                    assertTrue(v.paikat[i].isFinite() && v.normaalit[i].isFinite(), "$t ruutu $ruutu: ei-luku verkossa")
                }
                if (!u.kiinni && u.asento.kasvot.aukiV > 0.9f && ruutu % 5 == 0) {
                    val vaarin = vaarinPain(v)
                    // Tubes fold a little inside sharply bent joints, and lids squeeze the pupils into slivers
                    for (osa in 0 until UkkoVerkko.KIELI) { // not the tongue: it is squeezed flat between the lips
                        assertTrue(vaarin[osa] < 0.04f, "$t ruutu $ruutu: osasta $osa on nurinpäin ${vaarin[osa] * 100} %")
                    }
                }
            }
            assertTrue(indeksit.contentEquals(v.indeksit))
        }
    }
}
