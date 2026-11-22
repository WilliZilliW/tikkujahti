package fi.otsosoft.tikkujahti

import fi.otsosoft.tikkujahti.maailma.PinnanLaji
import fi.otsosoft.tikkujahti.maailma.Pinta
import fi.otsosoft.tikkujahti.matikka.Kehys
import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.maailma.Huone
import fi.otsosoft.tikkujahti.maailma.LattianHaku
import fi.otsosoft.tikkujahti.maailma.PaikanLaji
import fi.otsosoft.tikkujahti.maailma.Paikat
import fi.otsosoft.tikkujahti.maailma.Tunnistin
import fi.otsosoft.tikkujahti.peli.Aani
import fi.otsosoft.tikkujahti.peli.Aarre
import fi.otsosoft.tikkujahti.peli.PalloLaji
import fi.otsosoft.tikkujahti.peli.Reppu
import fi.otsosoft.tikkujahti.peli.Taso
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

    private fun peliYhdella(tyyppi: Tyyppi, vaistotaito: Float? = null, suunta: Float = 0f, pinnat: List<Pinta> = listOf(lattia)): Pair<Peli, Ukko> {
        val peli = Peli()
        peli.maailma.asetaPinnat(pinnat)
        peli.ukkojaKerralla = 0
        peli.aarteita = false
        peli.reppu.rajaton = true
        val pohja = Peli.luoUlkonako(tyyppi, Ulkonako.VARIT[2], kotlin.random.Random(1))
        val u = Ukko(1, if (vaistotaito == null) pohja else pohja.copy(luonne = pohja.luonne.copy(vaistotaito = vaistotaito)), V3(0f, 0f, -0.3f), suunta, 3, alkuKorkeus = 0.2f)
        u.lattia = pinnat[0]
        u.omatoiminen = false
        repeat(150) { u.paivita(dt); u.aanet.clear() } // settles without a player to turn towards
        peli.ukot += u
        return peli to u
    }

    private fun pelaa(peli: Peli, sekunteja: Float): Set<Aani> {
        val nahty = HashSet<Aani>()
        repeat((sekunteja * 60).toInt()) {
            peli.paivita(dt, kamera)
            peli.tapahtumat.forEach { nahty += it.laji }
        }
        return nahty
    }

    @Test
    fun ninjaTorjuuTavallisenPallonMuttaJaaVerkkoon() {
        val (peli, ninja) = peliYhdella(Tyyppi.NINJA)
        peli.paivita(dt, kamera)
        heitaKohti(peli, ninja)
        pelaa(peli, 4f)
        assertEquals(0, peli.keratyt, "ninja jäi kiinni tavalliseen palloon")
        assertTrue(!ninja.kiinni)
        repeat(3) { // the net cannot be dodged, but a hop already under way can carry the ninja clear
            if (peli.keratyt == 0) {
                peli.reppu.valitse(PalloLaji.VERKKO)
                heitaKohti(peli, ninja)
                pelaa(peli, 5f)
            }
        }
        assertEquals(1, peli.keratyt, "verkko ei napannut ninjaa")
    }

    @Test
    fun kilpiTorjuuEdestaMuttaEiTakaa() {
        val (peli, edesta) = peliYhdella(Tyyppi.KILPI)
        peli.paivita(dt, kamera)
        heitaKohti(peli, edesta)
        assertTrue(Aani.TORJUNTA in pelaa(peli, 3f), "kilpi ei torjunut edestä tullutta palloa")
        assertEquals(0, peli.keratyt)

        // Facing away from the player: thrown at before it has turned round
        val (peli2, takaa) = peliYhdella(Tyyppi.KILPI, suunta = kotlin.math.PI.toFloat())
        peli2.paivita(dt, kamera)
        heitaKohti(peli2, takaa)
        pelaa(peli2, 5f)
        assertEquals(1, peli2.keratyt, "selkään osunut pallo ei napannut kilpiukkoa")
    }

    @Test
    fun piiloukonSaaVastaTutkanJalkeen() {
        val (peli, piilo) = peliYhdella(Tyyppi.PIILO, vaistotaito = 0f)
        peli.paivita(dt, kamera)
        assertTrue(piilo.haamu && piilo.asento.haamu)
        heitaKohti(peli, piilo)
        pelaa(peli, 4f)
        assertEquals(0, peli.keratyt, "pallo osui näkymättömään ukkoon")
        peli.reppu.valitse(PalloLaji.TUTKA)
        peli.heita(Heitto(0f, -0.2f, 0.4f, 0f), kamera)
        assertTrue(Aani.TUTKA in pelaa(peli, 1.5f))
        assertTrue(!piilo.haamu, "tutka ei paljastanut piiloukkoa")
        heitaKohti(peli, piilo)
        pelaa(peli, 5f)
        assertEquals(1, peli.keratyt)
    }

    @Test
    fun erikoispallotToimivat() {
        // The multi-ball becomes three
        val (peli, _) = peliYhdella(Tyyppi.TAVALLINEN, vaistotaito = 0f)
        peli.reppu.valitse(PalloLaji.MONI)
        peli.heita(Heitto(0.6f, 0.3f, 0.5f, 0f), kamera)
        pelaa(peli, 0.3f)
        assertEquals(3, peli.pallot.size)

        // The homing ball finds a figure it was not thrown at
        val (peli2, u2) = peliYhdella(Tyyppi.TAVALLINEN, vaistotaito = 0f)
        peli2.paivita(dt, kamera)
        peli2.reppu.valitse(PalloLaji.HAKEUTUVA)
        val kohti = (u2.keskipiste + V3(0.45f, 0.25f, 0f) - kamera.paikka).suunta()
        val e = kohti dot kamera.kehys.eteen
        peli2.heita(Heitto((kohti dot kamera.kehys.oikealle) / e / (kamera.tanPuoliFov * kamera.kuvasuhde), (kohti dot kamera.kehys.ylos) / e / kamera.tanPuoliFov, 0.5f, 0f), kamera)
        pelaa(peli2, 5f)
        assertEquals(1, peli2.keratyt, "hakeutuva pallo ei löytänyt ukkoa")

        // The sticky ball glues, the slow ball slows, and neither catches
        val (peli3, u3) = peliYhdella(Tyyppi.JUOKSIJA)
        peli3.paivita(dt, kamera)
        peli3.reppu.valitse(PalloLaji.HIDASTUS)
        peli3.heita(Heitto(0f, -0.25f, 0.4f, 0f), kamera) // at the floor in front of it
        assertTrue(Aani.HIDASTUS in pelaa(peli3, 1.5f))
        assertTrue(u3.hidastettu > 0f, "hidastuspallo ei hidastanut")
        heitaKohti(peli3, u3)
        pelaa(peli3, 5f)
        assertEquals(1, peli3.keratyt, "hidastettu juoksija ei jäänyt kiinni")

        val (peli4, u4) = peliYhdella(Tyyppi.TAVALLINEN, vaistotaito = 0f)
        peli4.paivita(dt, kamera)
        peli4.reppu.valitse(PalloLaji.TAHMA)
        heitaKohti(peli4, u4)
        assertTrue(Aani.LIIMA in pelaa(peli4, 1f))
        assertTrue(u4.liimattu > 0f && !u4.kiinni)
    }

    @Test
    fun aarteenSaaOsumallaJaReppuSailyy() {
        val (peli, _) = peliYhdella(Tyyppi.TAVALLINEN, vaistotaito = 0f)
        peli.ukot.clear()
        peli.reppu.rajaton = false
        val paikka = V3(0.2f, 0.07f, -0.4f)
        peli.aarteet += Aarre(PalloLaji.VERKKO, paikka)
        val kohti = (paikka - kamera.paikka).suunta()
        val e = kohti dot kamera.kehys.eteen
        peli.heita(Heitto((kohti dot kamera.kehys.oikealle) / e / (kamera.tanPuoliFov * kamera.kuvasuhde), (kohti dot kamera.kehys.ylos) / e / kamera.tanPuoliFov, 0.5f, 0f), kamera)
        assertTrue(Aani.POIMINTA in pelaa(peli, 2f), "pallo ei poiminut aarretta")
        assertEquals(1, peli.reppu[PalloLaji.VERKKO])
        assertTrue(peli.aarteet.isEmpty())
        val luettu = Reppu.lue(peli.reppu.teksti())
        assertEquals(1, luettu[PalloLaji.VERKKO]); assertEquals(0, luettu[PalloLaji.TUTKA])
        // Throwing it uses it up
        peli.reppu.valitse(PalloLaji.VERKKO)
        assertEquals(PalloLaji.VERKKO, peli.heita(Heitto(0f, 0f, 0.5f, 0f), kamera).laji)
        assertEquals(0, peli.reppu[PalloLaji.VERKKO]); assertEquals(PalloLaji.NORMI, peli.reppu.valittu)
        assertEquals(0, Reppu.lue("VERKKO=99;x").let { it.yhteensa - Reppu.KOKO })
    }

    @Test
    fun ukkoKaveleeSeinallaJaKatossa() {
        val seina = Pinta.suorakaide("seina", PinnanLaji.SEINA, V3(0f, 1.25f, -2f), V3.Z, V3.X, 4f, 2.5f)
        val katto = Pinta.suorakaide("katto", PinnanLaji.KATTO, V3(0f, 2.5f, 0f), -V3.Y, V3.X, 4f, 4f)
        for (pinta in listOf(seina, katto)) {
            val u = Ukko(1, Ulkonako(Ulkonako.VARIT[1]), pinta.keskus, 0f, 11)
            u.lattia = pinta
            u.katsoja = V3(0f, 1.3f, 1.5f)
            var kuljettu = 0f
            var ed = u.juuri
            repeat(60 * 40) { ruutu ->
                u.paivita(dt)
                u.aanet.clear()
                kuljettu += (u.juuri - ed).pituus; ed = u.juuri
                if (ruutu < 30) return@repeat // easing onto the surface
                assertTrue(pinta.sisalla(u.juuri, 0.02f) && kotlin.math.abs(pinta.etaisyys(u.juuri)) < 1e-3f, "${pinta.id} ruutu $ruutu: ukko irtosi pinnasta: ${u.juuri}")
                for (n in Nivel.entries) assertTrue(pinta.etaisyys(u.asento[n]) > -0.005f, "${pinta.id} ruutu $ruutu: $n pinnan läpi")
                assertTrue(pinta.etaisyys(u.asento[Nivel.PAA]) > u.m.pituus * 0.35f, "${pinta.id} ruutu $ruutu (${u.toiminto}): pää ei ole irti pinnasta")
            }
            assertTrue(kuljettu > 0.5f, "${pinta.id}: ukko ei liikkunut ($kuljettu m)")
            // Knocked off, it falls to the real floor
            u.maanTaso = 0f
            u.osuma(u.keskipiste, V3(0f, 0f, -2f))
            repeat(25) { u.paivita(dt) }
            assertTrue(u.kiinni)
        }
    }

    @Test
    fun kaikkiTyypitPyorivatHuoneessa() {
        val peli = Peli(7)
        peli.maailma.asetaPinnat(Huone.pinnat())
        peli.ukkojaKerralla = 3
        val k = Kamera(V3(0f, 0.7f, 1.7f), Kehys.luo(V3(0f, -0.28f, -1f)), 0.53f, 0.5f)
        val rnd = kotlin.random.Random(2)
        val nahdyt = HashSet<Tyyppi>()
        repeat(60 * 600) { ruutu ->
            if (ruutu % 100 == 50) {
                PalloLaji.entries.filter { peli.reppu[it] > 0 }.random(rnd).let { peli.reppu.valitse(it) }
                peli.heita(Heitto(rnd.nextFloat() - 0.5f, rnd.nextFloat() * 0.6f - 0.3f, rnd.nextFloat(), rnd.nextFloat() * 2f - 1f), k)
            }
            peli.paivita(dt, k)
            for (u in peli.ukot) {
                nahdyt += u.ulkonako.tyyppi
                val p = u.keskipiste
                assertTrue(p.x.isFinite() && p.y.isFinite() && p.z.isFinite(), "ruutu $ruutu: ${u.ulkonako.tyyppi} ei ole luku")
                assertTrue(kotlin.math.abs(p.x) < 2.6f && kotlin.math.abs(p.z) < 2.6f && p.y > -0.2f && p.y < 2.7f, "ruutu $ruutu: ${u.ulkonako.tyyppi} karkasi huoneesta: $p")
            }
            assertTrue(peli.pallot.size <= 12 && peli.ukot.size <= 9)
        }
        // The rarest may well not turn up in ten minutes, but most kinds must
        assertTrue(nahdyt.size >= 8, "kymmenessä minuutissa nähtiin vain $nahdyt")
        assertTrue(peli.keratyt >= 5, "kerättyjä vain ${peli.keratyt}")
    }

    @Test
    fun kiipeilijaKonttaaSeinallaKadetJaJalatPinnassa() {
        val seina = Pinta.suorakaide("seina", PinnanLaji.SEINA, V3(0f, 1.25f, -2f), V3.Z, V3.X, 4f, 2.5f)
        val u = Ukko(1, Peli.luoUlkonako(Tyyppi.KIIPEILIJA, Ulkonako.VARIT[0], kotlin.random.Random(1)), seina.keskus, 0f, 4, pinta = seina)
        u.omatoiminen = false
        u.katsoja = V3(0f, 1.3f, 1.5f)
        repeat(150) { u.paivita(dt) }
        u.meneKohti(seina.keskus + V3(0.2f, 0.6f, 0f), false)
        var ed = listOf(Nivel.KASI_V, Nivel.KASI_O, Nivel.NILKKA_V, Nivel.NILKKA_O).map { u.asento[it] }
        var liukuma = 0f
        var kuljettu = 0f
        val alku = u.juuri
        repeat(90) { ruutu ->
            u.paivita(dt)
            // The body lies along the wall, not out from it
            assertTrue(seina.etaisyys(u.asento[Nivel.RINTA]) < 0.09f * u.m.koko, "ruutu $ruutu: rinta irti seinästä ${seina.etaisyys(u.asento[Nivel.RINTA])}")
            val nyt = listOf(Nivel.KASI_V, Nivel.KASI_O, Nivel.NILKKA_V, Nivel.NILKKA_O).map { u.asento[it] }
            for (i in nyt.indices) {
                val d = seina.etaisyys(nyt[i])
                assertTrue(d > -0.002f && d < 0.035f, "ruutu $ruutu: raaja $i etäisyydellä $d seinästä")
                // A hand or foot resting on the wall must not slide along it
                val lepo = (if (i < 2) u.m.kasiSade * 0.8f else u.m.nilkka) + 0.0005f
                if (d < lepo && seina.etaisyys(ed[i]) < lepo && ruutu > 5) liukuma = maxOf(liukuma, (nyt[i] - ed[i]).pituus)
            }
            ed = nyt
            kuljettu = (u.juuri - alku).pituus
        }
        assertTrue(kuljettu > 0.15f, "kiipeilijä ei edennyt ($kuljettu m)")
        assertTrue(liukuma < 0.003f, "seinässä oleva raaja liukui ${liukuma * 1000} mm ruudussa")
    }

    @Test
    fun pyristelevaUkkoKarkaaEllEiNapauta() {
        for (napautetaan in listOf(true, false)) {
            // A very rare figure struggles more often than not: find a game where it does
            var nahtiin = false
            for (siemen in 1..12) {
                val peli = Peli(siemen)
                peli.maailma.asetaPinnat(listOf(lattia))
                peli.ukkojaKerralla = 0
                peli.aarteita = false
                peli.pyristely = true
                peli.reppu.rajaton = true
                peli.reppu.valitse(PalloLaji.KULTAINEN)
                val pohja = Peli.luoUlkonako(Tyyppi.KULTAINEN, Ulkonako.VARIT[0], kotlin.random.Random(1))
                val u = Ukko(1, pohja.copy(luonne = pohja.luonne.copy(vaistotaito = 0f)), V3(0f, 0f, -0.3f), 0f, 3, alkuKorkeus = 0.2f)
                u.lattia = lattia
                u.omatoiminen = false
                repeat(150) { u.paivita(dt); u.aanet.clear() }
                peli.ukot += u
                peli.paivita(dt, kamera)
                heitaKohti(peli, u)
                var pyristeli = false
                var karkasi = false
                repeat(60 * 9) {
                    peli.paivita(dt, kamera)
                    val pallo = peli.pallot.firstOrNull { it.tila == PallonTila.PYRISTELEE }
                    if (pallo != null) {
                        pyristeli = true
                        if (napautetaan && pallo.tilaAika > 0.5f) {
                            val kohti = (pallo.p - kamera.paikka).suunta()
                            val e = kohti dot kamera.kehys.eteen
                            assertTrue(peli.napauta((kohti dot kamera.kehys.oikealle) / e / (kamera.tanPuoliFov * kamera.kuvasuhde), (kohti dot kamera.kehys.ylos) / e / kamera.tanPuoliFov, kamera))
                        }
                    }
                    if (peli.tapahtumat.any { it.laji == Aani.KARKASI }) karkasi = true
                }
                if (!pyristeli) continue
                nahtiin = true
                assertEquals(!napautetaan, karkasi)
                assertEquals(if (napautetaan) 1 else 0, peli.keratyt)
                assertTrue(peli.ukot.isEmpty() && peli.pallot.isEmpty(), "karannut ukko tai pallo jäi kentälle")
                assertTrue(!peli.napauta(0f, 0f, kamera))
                break
            }
            assertTrue(nahtiin, "kultainen ukko ei pyristellyt kertaakaan")
        }
    }

    @Test
    fun kiipeilijaNouseeSeinalleJaPalaa() {
        val peli = Peli(3)
        peli.maailma.asetaPinnat(Huone.pinnat())
        peli.tyypit = listOf(Tyyppi.KIIPEILIJA)
        peli.ukkojaKerralla = 1
        peli.aarteita = false
        val k = Kamera(V3(0f, 0.7f, 1.7f), Kehys.luo(V3(0f, -0.28f, -1f)), 0.53f, 0.5f)
        var nousuja = 0
        var laskuja = 0
        var oli = false
        var korkein = 0f
        repeat(60 * 90) {
            peli.paivita(dt, k)
            val u = peli.ukot.firstOrNull() ?: return@repeat
            if (u.seinalla && !oli) nousuja++
            if (!u.seinalla && oli) laskuja++
            oli = u.seinalla
            if (u.seinalla) korkein = maxOf(korkein, u.juuri.y)
        }
        assertTrue(nousuja >= 2 && laskuja >= 1, "puolessatoista minuutissa nousuja $nousuja, laskuja $laskuja")
        assertTrue(korkein > 0.4f, "kiipeilijä nousi vain ${korkein} m")
    }

    @Test
    fun ukkoIstuuPoydanReunalleJalatRoikkuen() {
        val poyta = Pinta.suorakaide("poyta", PinnanLaji.POYTA, V3(0f, 0.5f, 0f), V3.Y, V3.X, 0.8f, 0.5f)
        val u = Ukko(1, Ulkonako(Ulkonako.VARIT[0]), V3(0.1f, 0.5f, 0.05f), 0f, 5, pinta = poyta)
        u.omatoiminen = false
        u.katsoja = V3(0f, 1.2f, 1.5f)
        repeat(150) { u.paivita(dt) }
        val (reuna, ulos) = poyta.lahinReuna(u.juuri)
        assertEquals(0.25f, reuna.z, 1e-4f)
        assertTrue((ulos - V3.Z).pituus < 1e-4f, "reunan suunta $ulos")
        u.istuReunalle(reuna, ulos)
        repeat(60 * 5) { u.paivita(dt) }
        assertEquals(Toiminto.ISTUU, u.toiminto)
        // Seated: pelvis on the table, knees out over the edge, feet below the table top, head well above
        assertTrue(u.asento[Nivel.LANTIO].y < 0.5f + 0.02f && poyta.sisalla(u.asento[Nivel.LANTIO]))
        for (n in listOf(Nivel.POLVI_V, Nivel.POLVI_O)) assertTrue(u.asento[n].z > 0.25f, "$n ei ole reunan yli")
        for (n in listOf(Nivel.NILKKA_V, Nivel.NILKKA_O)) assertTrue(u.asento[n].y < 0.5f - 0.02f && u.asento[n].z > 0.25f, "$n ei roiku: ${u.asento[n]}")
        assertTrue(u.asento[Nivel.PAA].y > 0.5f + u.m.pituus * 0.3f)
        // It gets up again in time, back onto the table
        repeat(60 * 14) { u.paivita(dt) }
        assertTrue(u.toiminto != Toiminto.ISTUU)
        repeat(60) { u.paivita(dt) }
        for (n in Nivel.entries) assertTrue(u.asento[n].y > 0.5f - 0.004f, "$n jäi pöydän alle noustua")
    }

    @Test
    fun lattiaLoytyyItsestaan() {
        val kam = V3(0f, 0.1f, 0f)
        // Nothing seen: after a moment the floor is guessed below the phone
        val h = LattianHaku()
        repeat(60) { h.ruutu(dt, kam, null) }
        assertNull(h.lattia(kam))
        repeat(120) { h.ruutu(dt, kam, null) }
        assertTrue(h.arvattu)
        assertEquals(0.1f - LattianHaku.OLETUSKORKEUS, h.y!!, 1e-4f)
        // A real surface replaces the guess; a table close under the phone does not
        h.ruutu(dt, kam, -0.2f)
        assertTrue(h.arvattu)
        h.ruutu(dt, kam, -0.9f)
        assertTrue(!h.arvattu)
        assertEquals(-0.9f, h.y!!, 1e-4f)
        // A clearly lower surface seen later is the real floor
        h.ruutu(dt, kam, -1.3f)
        assertEquals(-1.3f, h.y!!, 1e-4f)
        // The disc follows the player, but is not rebuilt for nothing
        val a = assertNotNull(h.lattia(kam))
        assertTrue(a === h.lattia(kam + V3(0.5f, 0f, 0f)))
        val b = assertNotNull(h.lattia(kam + V3(1.5f, 0f, 0f)))
        assertTrue(a !== b && b.sisalla(kam + V3(3f, 0f, 0f)))
        // Seen at once: no waiting at all
        val h2 = LattianHaku()
        h2.ruutu(dt, kam, -1.1f)
        assertEquals(-1.1f, assertNotNull(h2.lattia(kam)).keskus.y, 1e-4f)
    }

    @Test
    fun tunnistinKokoaaPinnatMittauksista() {
        val t = Tunnistin()
        val rnd = kotlin.random.Random(4)
        fun kohina() = (rnd.nextFloat() - 0.5f) * 0.02f
        repeat(300) {
            // A table top 0.7 m up, a wall behind it seen from 0.3 to 1.5 m, a ceiling, and a slanted thing to ignore
            t.nayte(V3(rnd.nextFloat() * 1.2f - 0.6f, 0.7f + kohina(), -1f + rnd.nextFloat() * 0.6f), V3(kohina(), 1f, kohina()))
            t.nayte(V3(rnd.nextFloat() * 2f - 1f, 0.3f + rnd.nextFloat() * 1.2f, -2f + kohina()), V3(kohina(), kohina(), 1f))
            t.nayte(V3(rnd.nextFloat() - 0.5f, 2.4f + kohina(), rnd.nextFloat() - 0.5f), V3(0f, -1f, 0f))
            t.nayte(V3(rnd.nextFloat(), 1f, rnd.nextFloat()), V3(0f, 0.7f, 0.7f))
            // The floor itself is not the recogniser's business
            t.nayte(V3(rnd.nextFloat() * 2f - 1f, kohina(), rnd.nextFloat() * 2f - 1f), V3.Y)
        }
        val pinnat = t.pinnat(0f)
        assertEquals(setOf(PinnanLaji.POYTA, PinnanLaji.SEINA, PinnanLaji.KATTO), pinnat.map { it.laji }.toSet())
        assertEquals(3, pinnat.size, "pintoja: " + pinnat.map { it.laji to it.ala })
        val poyta = pinnat.first { it.laji == PinnanLaji.POYTA }
        assertEquals(0.7f, poyta.keskus.y, 0.02f)
        assertTrue(poyta.sisalla(V3(0f, 0.7f, -0.7f)) && !poyta.sisalla(V3(1.2f, 0.7f, -0.7f)) && poyta.ala in 0.5f..1.1f, "pöydän ala ${poyta.ala}")
        val seina = pinnat.first { it.laji == PinnanLaji.SEINA }
        assertTrue((seina.normaali - V3.Z).pituus < 0.05f)
        assertTrue(seina.sisalla(V3(0f, 0.05f, -2f)), "seinä ei ulotu lattiaan")
        assertTrue(!seina.sisalla(V3(0f, 2.2f, -2f)))
        // Unchanged surfaces keep their identity; a new measurement changes only its own surface
        assertTrue(t.pinnat(0f).zip(pinnat).all { (a, b) -> a === b })
        t.nayte(V3(0.9f, 0.7f, -0.7f), V3.Y)
        val uudet = t.pinnat(0f)
        assertTrue(uudet.first { it.laji == PinnanLaji.SEINA } === seina && uudet.first { it.laji == PinnanLaji.POYTA } !== poyta)

        // Places: a nook under the table, and a corner where two walls meet
        val m = fi.otsosoft.tikkujahti.maailma.Maailma()
        m.asetaPinnat(uudet + listOf(
            Pinta.suorakaide("lattia", PinnanLaji.LATTIA, V3.NOLLA, V3.Y, V3.X, 6f, 6f),
            Pinta.suorakaide("sivuseina", PinnanLaji.SEINA, V3(-1.2f, 1f, -1f), V3.X, V3.Z, 2f, 2f),
        ))
        val paikat = Paikat.etsi(m, listOf(V3(1f, 0.02f, 1f), V3(0f, 1.5f, 0f)))
        assertEquals(setOf(PaikanLaji.KOLO, PaikanLaji.NURKKA, PaikanLaji.VARJO), paikat.map { it.laji }.toSet(), "paikat: " + paikat.map { it.laji to it.piste })
        val nurkka = paikat.first { it.laji == PaikanLaji.NURKKA }.piste
        assertTrue((nurkka - V3(-1.2f + Paikat.NURKASTA, 0f, -2f + Paikat.NURKASTA)).pituus < 0.05f, "nurkka $nurkka")
        assertTrue(Paikat.etsi(m, emptyList()).none { it.laji == PaikanLaji.VARJO })
        // The browser room has its corners too
        m.asetaPinnat(Huone.pinnat())
        assertTrue(Paikat.etsi(m, emptyList()).count { it.laji == PaikanLaji.NURKKA } >= 4)
    }

    @Test
    fun ujoUkkoHakeutuuPiiloonJaLoydoistaKerrotaan() {
        val peli = Peli(2)
        peli.maailma.asetaPinnat(Huone.pinnat())
        peli.ukkojaKerralla = 0
        peli.aarteita = false
        peli.ilmoitaLoydot = true
        val k = Kamera(V3(0f, 0.7f, 1.7f), Kehys.luo(V3(0f, -0.28f, -1f)), 0.53f, 0.5f)
        val u = Ukko(1, Ulkonako(Ulkonako.VARIT[0], luonne = Luonne(ujous = 0.95f, vaistotaito = 0f)), V3(-1.2f, 0f, -1.2f), 0f, 3)
        peli.ukot += u
        val tekstit = ArrayList<String>()
        var lahin = Float.MAX_VALUE
        repeat(60 * 60) {
            peli.paivita(dt, k)
            peli.tapahtumat.mapNotNullTo(tekstit) { it.teksti }
            val nurkat = peli.maailma.paikat.filter { it.lattialla }
            if (nurkat.isNotEmpty()) lahin = minOf(lahin, nurkat.minOf { (it.piste - u.juuri).pituus })
        }
        assertTrue(lahin < 0.1f, "ujo ukko ei käynyt piilopaikassa (lähimmillään $lahin m)")
        assertTrue(tekstit.any { "seinän" in it } && tekstit.any { "nurkka" in it }, "ilmoitukset: $tekstit")
        assertEquals(tekstit.size, tekstit.toSet().size, "sama löytö ilmoitettiin kahdesti")
    }

    @Test
    fun ninjaIlmestyyPiilostaTaiYlhaaltaJaKatoaaSavuna() {
        var piilosta = 0
        var ylhaalta = 0
        for (siemen in 1..12) {
            val peli = Peli(siemen)
            peli.maailma.asetaPinnat(Huone.pinnat())
            peli.tyypit = listOf(Tyyppi.NINJA)
            peli.ukkojaKerralla = 1
            peli.aarteita = false
            // Looking into the far left corner of the room
            val k = Kamera(V3(0.5f, 0.7f, 0.5f), Kehys.luo(V3(-0.6f, -0.2f, -1f)), 0.53f, 0.5f)
            var ninja: Ukko? = null
            var savua = false
            repeat(60 * 8) {
                if (ninja != null) return@repeat
                peli.paivita(dt, k)
                ninja = peli.ukot.firstOrNull()
                savua = peli.tehosteet.isNotEmpty()
            }
            val u = assertNotNull(ninja)
            val paikalla = peli.maailma.paikat.any { (it.piste - u.juuri).pituus < 0.1f }
            if (paikalla) {
                piilosta++
                assertTrue(savua, "siemen $siemen: ninja tuli piilosta ilman savua")
                assertTrue(u.keskipiste.y < 0.4f)
            } else {
                ylhaalta++
                assertTrue(u.ilmassa && u.keskipiste.y > 1.2f, "siemen $siemen: ninja ei tullut piilosta eikä ylhäältä: ${u.juuri}, ${u.keskipiste}")
            }
        }
        assertTrue(piilosta >= 3 && ylhaalta >= 2, "piilosta $piilosta, ylhäältä $ylhaalta")

        // An uncaught ninja vanishes in smoke instead of walking off
        val peli = Peli(1)
        peli.maailma.asetaPinnat(Huone.pinnat())
        peli.tyypit = listOf(Tyyppi.NINJA, Tyyppi.TAVALLINEN)
        peli.ukkojaKerralla = 0
        val k = Kamera(V3(0f, 0.7f, 1.7f), Kehys.luo(V3(0f, -0.28f, -1f)), 0.53f, 0.5f)
        val u = Ukko(1, Peli.luoUlkonako(Tyyppi.NINJA, Ulkonako.VARIT[0], kotlin.random.Random(1)), V3(0f, 0f, -0.3f), 0f, 3)
        u.omatoiminen = false
        peli.ukot += u
        var savua = false
        repeat((60 * (Peli.VIERAILU + 3f)).toInt()) {
            peli.paivita(dt, k)
            if (peli.ukot.isEmpty() && peli.tehosteet.isNotEmpty()) savua = true
        }
        assertTrue(peli.ukot.isEmpty() && savua, "ninja ei kadonnut savuna")
    }

    @Test
    fun etsintapelissaUkotOvatYmparillaJaVaihtuvatKunKuljetaan() {
        val peli = Peli(4)
        peli.etsinta = true
        peli.ukkojaKerralla = 4
        peli.aarteita = false
        val haku = LattianHaku()
        var paikka = V3(0f, 1.3f, 0f)
        fun kam() = Kamera(paikka, Kehys.luo(V3(0f, -0.4f, -1f)), 0.6f, 0.5f)
        haku.ruutu(dt, paikka, 0f)
        fun aja(sekunteja: Float) = repeat((sekunteja * 60).toInt()) {
            peli.maailma.asetaPinnat(listOf(assertNotNull(haku.lattia(paikka))))
            peli.paivita(dt, kam())
        }
        aja(25f)
        assertTrue(peli.ukot.size >= 3, "ukkoja vain ${peli.ukot.size}")
        // All around, not lined up in front: some are out of view, and they keep to their own spots
        val poissaNakyvista = peli.ukot.count { !kam().ruudulla(it.keskipiste).third }
        assertTrue(poissaNakyvista >= 1, "kaikki ukot olivat suoraan näkyvissä")
        val kodit = peli.ukot.filter { it.koti != null }
        assertTrue(kodit.isNotEmpty())
        aja(20f)
        for (u in kodit) if (u in peli.ukot && u.ulkonako.tyyppi != Tyyppi.LAUMA) {
            assertTrue((u.juuri - u.koti!!).vaakaan().pituus < 1.6f, "${u.ulkonako.tyyppi} harhaili ${(u.juuri - u.koti!!).pituus} m kotoaan")
        }
        // Walk to another room: the old ones are let go, new ones turn up around the player
        val vanhat = peli.ukot.toSet()
        paikka = V3(12f, 1.3f, 3f)
        aja(25f)
        assertTrue(peli.ukot.none { it in vanhat }, "taakse jääneet ukot eivät lähteneet")
        assertTrue(peli.ukot.size >= 3)
        for (u in peli.ukot) assertTrue((u.juuri - paikka).vaakaan().pituus < Peli.JATTOMATKA, "ukko liian kaukana pelaajasta")

        // The screen-edge hint: in view, off to the right, behind
        val k = Kamera(V3.NOLLA, Kehys.luo(-V3.Z), 0.6f, 0.5f)
        assertTrue(k.ruudulla(V3(0f, 0f, -2f)).third)
        val oikealla = k.ruudulla(V3(3f, 0f, -1f))
        assertTrue(!oikealla.third && oikealla.first == 1f && kotlin.math.abs(oikealla.second) < 0.2f)
        val takana = k.ruudulla(V3(-1f, 0f, 3f))
        assertTrue(!takana.third && takana.first == -1f)
    }

    @Test
    fun tasotVaikeutuvatJaEtenevat() {
        // Each level hides the figures at least as well as the one before
        for (n in 1..14) {
            val a = Taso(n)
            val b = Taso(n + 1)
            assertTrue(b.piilossa >= a.piilossa && b.paikoissa >= a.paikoissa && b.etaisyys >= a.etaisyys && b.nuolia <= a.nuolia && b.vierailu <= a.vierailu && b.tyypit.containsAll(a.tyypit), "taso ${n + 1} on helpompi kuin $n")
        }
        assertEquals(listOf(Tyyppi.TAVALLINEN), Taso(1).tyypit)
        assertEquals(Tyyppi.entries.toSet(), Taso(8).tyypit.toSet())

        // Out of view far more often on a high level than on the first
        fun piilossaOsuus(taso: Int): Float {
            var piilossa = 0
            var kaikki = 0
            for (siemen in 1..40) {
                val peli = Peli(siemen, Albumi.lue("TASO=$taso"))
                peli.tasot = true
                peli.etsinta = true
                peli.aarteita = false
                peli.maailma.asetaPinnat(listOf(Pinta.suorakaide("iso", PinnanLaji.LATTIA, V3.NOLLA, V3.Y, V3.X, 20f, 20f)))
                val k = Kamera(V3(0f, 1.3f, 0f), Kehys.luo(V3(0f, -0.4f, -1f)), 0.6f, 0.5f)
                repeat(60 * 3) { peli.paivita(dt, k) }
                for (u in peli.ukot) {
                    kaikki++
                    if (!k.ruudulla(u.koti ?: u.juuri).third) piilossa++
                    if (taso == 1) assertEquals(Tyyppi.TAVALLINEN, u.ulkonako.tyyppi)
                }
            }
            assertTrue(kaikki >= 30)
            return piilossa.toFloat() / kaikki
        }
        val helppo = piilossaOsuus(1)
        val vaikea = piilossaOsuus(9)
        assertTrue(helppo < 0.35f && vaikea > 0.65f, "näkymän ulkopuolella tasolla 1: $helppo, tasolla 9: $vaikea")

        // Catching the level's quota moves the player up, and that is saved
        val peli = Peli(1)
        peli.tasot = true
        peli.maailma.asetaPinnat(listOf(lattia))
        peli.ukkojaKerralla = 0
        peli.aarteita = false
        var nousu: String? = null
        repeat(Taso(1).tavoite) {
            assertEquals(1, peli.taso.numero)
            val u = ukko(0f)
            peli.ukot += u
            peli.paivita(dt, kamera)
            heitaKohti(peli, u)
            repeat(60 * 6) {
                peli.paivita(dt, kamera)
                peli.tapahtumat.firstOrNull { it.laji == Aani.TASO }?.let { nousu = it.teksti }
            }
        }
        assertEquals(2, peli.taso.numero)
        assertEquals(0, peli.albumi.edistys)
        assertTrue(nousu!!.contains("Taso 2") && nousu!!.contains("nopea juoksija"), "ilmoitus: $nousu")
        val luettu = Albumi.lue(peli.albumi.teksti())
        assertEquals(2, luettu.taso); assertEquals(Taso(1).tavoite, luettu[Tyyppi.TAVALLINEN])
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
