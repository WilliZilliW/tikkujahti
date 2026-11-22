package fi.otsosoft.tikkujahti

import fi.otsosoft.tikkujahti.maailma.PinnanLaji
import fi.otsosoft.tikkujahti.maailma.Pinta
import fi.otsosoft.tikkujahti.matikka.Kehys
import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.peli.Aani
import fi.otsosoft.tikkujahti.peli.Albumi
import fi.otsosoft.tikkujahti.peli.Heitto
import fi.otsosoft.tikkujahti.peli.Kamera
import fi.otsosoft.tikkujahti.peli.Pallo
import fi.otsosoft.tikkujahti.peli.PallonTila
import fi.otsosoft.tikkujahti.peli.Peli
import fi.otsosoft.tikkujahti.peli.Pyyhkaisy
import fi.otsosoft.tikkujahti.ukko.Luonne
import fi.otsosoft.tikkujahti.ukko.Nivel
import fi.otsosoft.tikkujahti.ukko.Toiminto
import fi.otsosoft.tikkujahti.ukko.Tyyppi
import fi.otsosoft.tikkujahti.ukko.Ukko
import fi.otsosoft.tikkujahti.ukko.Ulkonako
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeliTest {
    private val dt = 1f / 60f
    private val lattia = Pinta.suorakaide("lattia", PinnanLaji.LATTIA, V3.NOLLA, V3.Y, V3.X, 4f, 4f)
    private val kamera = Kamera(V3(0f, 1.3f, 1.5f), Kehys.luo(V3(0f, -0.55f, -1f)), 0.6f, 0.5f)

    private fun ukko(vaistotaito: Float, siemen: Int = 3): Ukko {
        val u = Ukko(1, Ulkonako(Ulkonako.VARIT[0], luonne = Luonne(vaistotaito = vaistotaito)), V3(0f, 0f, -0.3f), 0f, siemen)
        u.lattia = lattia
        u.katsoja = kamera.paikka
        u.omatoiminen = false
        repeat(150) { u.paivita(dt) } // arrive and settle
        assertEquals(Toiminto.SEISOO, u.toiminto)
        return u
    }

    @Test
    fun kavellessaMaassaOlevaJalkaEiLiu() {
        for (toiminto in listOf(Toiminto.KAVELEE, Toiminto.JUOKSEE)) {
            val u = ukko(0f)
            u.komenna(toiminto)
            var ed = listOf(u.asento[Nivel.NILKKA_V], u.asento[Nivel.NILKKA_O])
            var kuljettu = 0f
            var liukuma = 0f
            val alku = u.juuri
            repeat(120) {
                u.paivita(dt)
                val nyt = listOf(u.asento[Nivel.NILKKA_V], u.asento[Nivel.NILKKA_O])
                for (i in 0..1) {
                    // A foot resting at ankle height is planted: it must not move along the floor
                    val maassa = nyt[i].y < u.m.nilkka + 0.0015f && ed[i].y < u.m.nilkka + 0.0015f
                    if (maassa) liukuma = maxOf(liukuma, (nyt[i] - ed[i]).vaakaan().pituus)
                }
                ed = nyt
                kuljettu = (u.juuri - alku).pituus
            }
            assertTrue(kuljettu > 0.1f, "$toiminto: ukko ei liikkunut ($kuljettu m)")
            assertTrue(liukuma < 0.002f, "$toiminto: maassa oleva jalka liukui ${liukuma * 1000} mm ruudussa")
        }
    }

    @Test
    fun ukkoPysyyLattiallaJaPystyssa() {
        val pieni = Pinta.suorakaide("matto", PinnanLaji.LATTIA, V3(0f, 0.4f, 0f), V3.Y, V3.X, 0.8f, 0.6f)
        val u = Ukko(1, Ulkonako(Ulkonako.VARIT[1]), V3(0f, 0.4f, 0f), 0f, 11)
        u.lattia = pieni
        u.katsoja = V3(0f, 1.4f, 1f)
        repeat(60 * 60) { ruutu ->
            u.paivita(dt)
            assertTrue(pieni.sisalla(u.juuri, 0.01f), "ruutu $ruutu: ukko käveli reunan yli: ${u.juuri}")
            assertEquals(0.4f, u.juuri.y, 1e-4f)
            for (n in Nivel.entries) {
                val p = u.asento[n]
                assertTrue(p.x.isFinite() && p.y.isFinite() && p.z.isFinite(), "ruutu $ruutu: $n ei ole luku")
                assertTrue(p.y > 0.4f - 0.004f, "ruutu $ruutu (${u.toiminto}): $n lattian alla: ${p.y - 0.4f}")
            }
            assertTrue(u.asento[Nivel.PAA].y > 0.4f + u.m.pituus * 0.35f, "ruutu $ruutu (${u.toiminto}): pää liian alhaalla")
            u.aanet.clear()
        }
    }

    @Test
    fun taitavaUkkoVaistaaJaTaitamatonEi() {
        for ((taito, odotettu) in listOf(1f to true, 0f to false)) {
            val u = ukko(taito)
            // A ball flying straight at its chest, half a second away
            val p = u.keskipiste + V3(0f, 0.3f, 1.5f)
            val v = (u.keskipiste - p) / 0.5f - Pallo.PAINOVOIMA * 0.25f
            u.havaitsePallo(7, p, v, Pallo.PAINOVOIMA, Pallo.SADE)
            var vaisti = false
            repeat(30) {
                u.paivita(dt)
                if (u.toiminto == Toiminto.VAISTAA_HYPPY || u.toiminto == Toiminto.VAISTAA_KYYKKY) vaisti = true
            }
            assertEquals(odotettu, vaisti, "väistötaito $taito")
        }
    }

    @Test
    fun pyyhkaisystaTuleeHeitto() {
        val p = Pyyhkaisy()
        p.aloita(0f, -0.8f, 0f)
        for (i in 1..8) p.lisaa(0.02f * i, -0.8f + 0.1f * i, i * 0.016f)
        val h = assertNotNull(p.lopeta())
        assertTrue(h.y > 0f && h.x > 0.1f, "tähtäys ${h.x}, ${h.y}")
        assertTrue(h.voima > 0.3f, "voima ${h.voima}")
        assertTrue(kotlin.math.abs(h.kaarre) < 0.2f, "suora pyyhkäisy ei kaarra: ${h.kaarre}")

        // A tap or a downward drag is not a throw
        p.aloita(0f, 0f, 0f); p.lisaa(0f, 0.01f, 0.1f)
        assertNull(p.lopeta())
        p.aloita(0f, 0.5f, 0f); p.lisaa(0f, 0f, 0.1f)
        assertNull(p.lopeta())
    }

    @Test
    fun palloPomppiiLattiastaJaPysahtyy() {
        val peli = Peli()
        peli.maailma.asetaPinnat(listOf(Pinta.suorakaide("iso", PinnanLaji.LATTIA, V3.NOLLA, V3.Y, V3.X, 20f, 20f)))
        peli.ukkojaKerralla = 0
        val pallo = peli.heita(Heitto(0f, 0f, 0.5f, 0f), kamera)
        var pomput = 0
        var ruutuja = 0
        while (peli.pallot.isNotEmpty() && ruutuja < 60 * 10) {
            peli.paivita(dt, kamera)
            pomput += peli.tapahtumat.count { it.laji == Aani.POMPPU }
            assertTrue(pallo.p.y > -0.01f, "pallo putosi lattian läpi: ${pallo.p}")
            ruutuja++
        }
        assertTrue(pomput >= 2, "pomppuja $pomput")
        assertTrue(peli.pallot.isEmpty(), "pallo ei kadonnut")
    }

    @Test
    fun osumaKeraaUkon() {
        val peli = Peli()
        peli.maailma.asetaPinnat(listOf(lattia))
        peli.ukkojaKerralla = 0
        val u = ukko(0f)
        peli.ukot += u
        peli.paivita(dt, kamera)
        // Aim a little off the figure: the aim assist should still land it
        val kohti = (u.keskipiste + V3(0.05f, 0f, 0f) - kamera.paikka).suunta()
        val x = (kohti dot kamera.kehys.oikealle) / (kohti dot kamera.kehys.eteen) / (kamera.tanPuoliFov * kamera.kuvasuhde)
        val y = (kohti dot kamera.kehys.ylos) / (kohti dot kamera.kehys.eteen) / kamera.tanPuoliFov
        val pallo = peli.heita(Heitto(x, y, 0.6f, 0f), kamera)
        val nahty = HashSet<Aani>()
        var kapertyi = false
        repeat(60 * 6) {
            peli.paivita(dt, kamera)
            peli.tapahtumat.forEach { nahty += it.laji }
            if (u.toiminto == Toiminto.KAPERTYY && !u.valmis) {
                kapertyi = true
                assertEquals(PallonTila.VANGITSEE, pallo.tila)
            }
        }
        assertTrue(Aani.OSUMA in nahty, "pallo ei osunut")
        assertTrue(kapertyi, "ukko ei käpertynyt palloon")
        assertTrue(Aani.KERAYS in nahty, "palloa ei kerätty")
        assertEquals(1, peli.keratyt)
        assertTrue(peli.ukot.isEmpty() && peli.pallot.isEmpty())
    }

    private fun heitaKohti(peli: Peli, u: Ukko) {
        val kohti = (u.keskipiste - kamera.paikka).suunta()
        val e = kohti dot kamera.kehys.eteen
        peli.heita(Heitto((kohti dot kamera.kehys.oikealle) / e / (kamera.tanPuoliFov * kamera.kuvasuhde), (kohti dot kamera.kehys.ylos) / e / kamera.tanPuoliFov, 0.6f, 0f), kamera)
    }

    @Test
    fun isoUkkoVaatiiKolmeOsumaaJaPutkiKasvattaaPisteita() {
        val peli = Peli()
        peli.maailma.asetaPinnat(listOf(lattia))
        peli.ukkojaKerralla = 0
        val iso = Ukko(1, Peli.luoUlkonako(Tyyppi.ISO, Ulkonako.VARIT[2], kotlin.random.Random(1)).let { it.copy(luonne = it.luonne.copy(vaistotaito = 0f)) }, V3(0f, 0f, -0.3f), 0f, 3)
        iso.omatoiminen = false
        peli.ukot += iso
        repeat(150) { peli.paivita(dt, kamera) }
        for (heitto in 1..3) {
            assertEquals(4 - heitto, iso.osumiaJaljella)
            heitaKohti(peli, iso)
            var suuttui = false
            repeat(60 * 5) {
                peli.paivita(dt, kamera)
                if (iso.toiminto == Toiminto.SUUTTUU) suuttui = true
                assertTrue(iso.toiminto != Toiminto.SAIKAHTAA, "osuman saanut ukko ei säikähdä ohi mennyttä palloa")
            }
            if (heitto < 3) {
                assertTrue(suuttui, "iso ukko ei suuttunut osumasta $heitto")
                assertEquals(0, peli.keratyt, "iso ukko jäi kiinni jo osumalla $heitto")
                assertTrue(!iso.kiinni && iso.asento[Nivel.PAA].y > iso.m.pituus * 0.5f, "iso ukko ei pysynyt pystyssä")
            }
        }
        assertEquals(1, peli.keratyt)
        assertEquals(Tyyppi.ISO.pisteet, peli.pisteet)
        assertEquals(1, peli.putki)
        assertEquals(1, peli.albumi[Tyyppi.ISO])

        // The next catch in a row scores double; a miss ends the streak
        val u = ukko(0f)
        peli.ukot += u
        peli.paivita(dt, kamera)
        heitaKohti(peli, u)
        repeat(60 * 6) { peli.paivita(dt, kamera) }
        assertEquals(Tyyppi.ISO.pisteet + 2, peli.pisteet)
        peli.heita(Heitto(0.9f, 0.5f, 0.5f, 0f), kamera)
        repeat(60 * 8) { peli.paivita(dt, kamera) }
        assertEquals(0, peli.putki)
    }

    @Test
    fun albumiSailyyTallennuksessa() {
        val a = Albumi()
        a.lisaa(Tyyppi.ISO); a.lisaa(Tyyppi.TAVALLINEN); a.lisaa(Tyyppi.TAVALLINEN)
        val b = Albumi.lue(a.teksti())
        assertEquals(2, b[Tyyppi.TAVALLINEN]); assertEquals(1, b[Tyyppi.ISO]); assertEquals(0, b[Tyyppi.KULTAINEN])
        assertEquals(2, b.loydetty)
        assertEquals(0, Albumi.lue("roskaa;X=2;ISO=abc").yhteensa)
        assertEquals(0, Albumi.lue(null).yhteensa)
    }

    @Test
    fun peliTuoUkkojaJaPyoriiPitkaan() {
        val peli = Peli(5)
        peli.maailma.asetaPinnat(listOf(lattia))
        var enimmillaan = 0
        val rnd = kotlin.random.Random(2)
        repeat(60 * 120) { ruutu ->
            if (ruutu % 90 == 45) {
                peli.heita(Heitto(rnd.nextFloat() - 0.5f, rnd.nextFloat() * 0.6f - 0.3f, rnd.nextFloat(), rnd.nextFloat() * 2f - 1f), kamera)
            }
            peli.paivita(dt, kamera)
            enimmillaan = maxOf(enimmillaan, peli.ukot.size)
            for (u in peli.ukot) assertTrue(u.juuri.x.isFinite() && u.keskipiste.y.isFinite())
            assertTrue(peli.pallot.size <= 6)
        }
        assertTrue(enimmillaan >= 2, "ukkoja oli enimmillään $enimmillaan")
        assertTrue(peli.keratyt >= 1, "kahdessa minuutissa ei osunut yksikään 80 heitosta")
    }
}
