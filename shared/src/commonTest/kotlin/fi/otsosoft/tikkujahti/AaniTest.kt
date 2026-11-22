package fi.otsosoft.tikkujahti

import fi.otsosoft.tikkujahti.aani.Syntikka
import fi.otsosoft.tikkujahti.ukko.Aantely
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class AaniTest {
    private fun tarkista(nimi: String, a: FloatArray) {
        val kesto = a.size / Syntikka.TAAJUUS.toFloat()
        assertTrue(kesto in 0.05f..2.5f, "$nimi kestää $kesto s")
        val huippu = a.maxOf { abs(it) }
        assertTrue(a.all { it.isFinite() }, "$nimi: ei-luku")
        assertTrue(huippu in 0.1f..1f, "$nimi: huippu $huippu")
        // No click at either end
        assertTrue(abs(a.first()) < 0.2f && abs(a.last()) < 0.05f, "$nimi naksahtaa: ${a.first()} … ${a.last()}")
    }

    @Test
    fun kaikkiAanetOvatKelvollisia() {
        for (a in Aantely.entries) for (korkeus in listOf(0.8f, 1f, 1.3f)) tarkista("$a x$korkeus", Syntikka.aantely(a, korkeus, 3))
        tarkista("heitto", Syntikka.heitto(0f)); tarkista("heitto", Syntikka.heitto(1f))
        tarkista("pomppu", Syntikka.pomppu(1f))
        tarkista("osuma", Syntikka.osuma())
        tarkista("kerays", Syntikka.kerays())
        tarkista("torjunta", Syntikka.torjunta()); tarkista("kimallus", Syntikka.kimallus()); tarkista("poiminta", Syntikka.poiminta())
        tarkista("pyristely", Syntikka.pyristely()); tarkista("karkasi", Syntikka.karkasi())
        tarkista("taso", Syntikka.taso())
        tarkista("tutka", Syntikka.tutka()); tarkista("hidastus", Syntikka.hidastus()); tarkista("liima", Syntikka.liima())
    }
}
