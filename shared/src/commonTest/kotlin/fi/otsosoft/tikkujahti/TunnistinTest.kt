package fi.otsosoft.tikkujahti

import fi.otsosoft.tikkujahti.maailma.Ankkuri
import fi.otsosoft.tikkujahti.maailma.Ankkurit
import fi.otsosoft.tikkujahti.maailma.LattianHaku
import fi.otsosoft.tikkujahti.maailma.Muunnos
import fi.otsosoft.tikkujahti.maailma.PinnanLaji
import fi.otsosoft.tikkujahti.maailma.Pinta
import fi.otsosoft.tikkujahti.maailma.Tunnistin
import fi.otsosoft.tikkujahti.matikka.Kehys
import fi.otsosoft.tikkujahti.matikka.V3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The recogniser in a simulated room: a phone walks about a living room and looks around, and its depth
 * images are made by casting rays at the room — with the noise, holes and stray values real depth has, and
 * with tracking that drifts and then corrects itself with a jump, as ARCore's does.
 */
class TunnistinTest {
    /** A box of furniture. */
    private class Laatikko(val min: V3, val max: V3)

    private class Huone(seed: Int) {
        val rnd = Random(seed)
        val xMin = -2f; val xMax = 2.2f; val zMin = -2.5f; val zMax = 2f; val katto = 2.5f
        val poyta = Laatikko(V3(0.3f, 0.72f, -2.0f), V3(1.5f, 0.75f, -1.2f))
        val kaappi = Laatikko(V3(-2f, 0f, 0.2f), V3(-1.3f, 0.5f, 1.6f))
        val laatikot = listOf(poyta, kaappi)

        /** Distance along the unit ray to what it hits, and whether that is a wall (plain walls give poor depth). */
        fun sade(o: V3, d: V3): Pair<Float, Boolean> {
            var paras = Float.MAX_VALUE
            var seina = false
            fun ulos(p: Float, dd: Float, lo: Float, hi: Float, onSeina: Boolean) {
                if (abs(dd) < 1e-6f) return
                val t = ((if (dd > 0f) hi else lo) - p) / dd
                if (t > 0f && t < paras) {
                    paras = t; seina = onSeina
                }
            }
            ulos(o.x, d.x, xMin, xMax, true)
            ulos(o.y, d.y, 0f, katto, false)
            ulos(o.z, d.z, zMin, zMax, true)
            for (b in laatikot) {
                var t0 = -Float.MAX_VALUE
                var t1 = Float.MAX_VALUE
                for ((p, dd, lo, hi) in listOf(Nelja(o.x, d.x, b.min.x, b.max.x), Nelja(o.y, d.y, b.min.y, b.max.y), Nelja(o.z, d.z, b.min.z, b.max.z))) {
                    if (abs(dd) < 1e-6f) {
                        if (p < lo || p > hi) t0 = Float.MAX_VALUE
                        continue
                    }
                    val a = (lo - p) / dd
                    val c = (hi - p) / dd
                    t0 = max(t0, min(a, c))
                    t1 = min(t1, max(a, c))
                }
                if (t0 <= t1 && t0 > 0f && t0 < paras) {
                    paras = t0; seina = false
                }
            }
            return paras to seina
        }

        fun gauss(): Float {
            val u = rnd.nextFloat().coerceAtLeast(1e-6f)
            val v = rnd.nextFloat()
            return (sqrt(-2f * ln(u)) * cos(2f * PI.toFloat() * v))
        }
    }

    private data class Nelja(val a: Float, val b: Float, val c: Float, val d: Float)

    /** Tracking drift: what the phone believes = rotate about y by [kulma], then shift by [siirto]. */
    private class Ryominta(val kulma: Float, val siirto: V3) {
        fun maailmaan(p: V3) = V3(cos(kulma) * p.x + sin(kulma) * p.z, p.y, -sin(kulma) * p.x + cos(kulma) * p.z) + siirto
        fun suunta(d: V3) = V3(cos(kulma) * d.x + sin(kulma) * d.z, d.y, -sin(kulma) * d.x + cos(kulma) * d.z)
        fun takaisin(p: V3): V3 {
            val q = p - siirto
            return V3(cos(kulma) * q.x - sin(kulma) * q.z, q.y, sin(kulma) * q.x + cos(kulma) * q.z)
        }
        fun suuntaTakaisin(d: V3) = V3(cos(kulma) * d.x - sin(kulma) * d.z, d.y, sin(kulma) * d.x + cos(kulma) * d.z)
    }

    /** Anchors as ARCore keeps them: fixed to the real room, so they move along when the drift changes. */
    private class SimAnkkurit(val ryominta: () -> Ryominta) : Ankkurit {
        val elossa = ArrayList<Ankkuri>()
        override fun luo(paikka: V3): Ankkuri {
            val r0 = ryominta()
            val oikea = r0.takaisin(paikka)
            val akselit = listOf(V3.X, V3.Y, V3.Z).map { r0.suuntaTakaisin(it) } // the anchor's axes in the real room
            return object : Ankkuri {
                override val muunnos: Muunnos
                    get() {
                        val r = ryominta()
                        return Muunnos(r.maailmaan(oikea), r.suunta(akselit[0]), r.suunta(akselit[1]), r.suunta(akselit[2]))
                    }
                override fun irrota() {
                    elossa.remove(this)
                }
            }.also { elossa += it }
        }
    }

    private class Tulos(val pinnat: List<Pinta>, val lattiaY: Float?, val kattoY: Float?, val ryominta: Ryominta, val ankkureita: Int)

    /**
     * Walks the phone about the room for [kesto] seconds and returns what was recognised, turned back into the
     * real room's coordinates. [ryomii] makes tracking drift and then correct itself halfway.
     */
    private fun kavele(ryomii: Boolean, ankkureilla: Boolean, kesto: Float = 70f, seed: Int = 1): Tulos {
        val huone = Huone(seed)
        var ryominta = Ryominta(0f, V3.NOLLA)
        val ankkurit = SimAnkkurit { ryominta }
        val tunnistin = Tunnistin(if (ankkureilla) ankkurit else null)
        val haku = LattianHaku()
        val w = 48
        val h = 36
        val tanX = kotlin.math.tan(32f * PI.toFloat() / 180f)
        val tanY = kotlin.math.tan(25f * PI.toFloat() / 180f)
        val pisteet = FloatArray(w * h * 3)
        val dt = 1f / 15f
        var t = 0f
        while (t < kesto) {
            if (ryomii) {
                // Drift grows slowly; at 40 s tracking recognises where it started and snaps most of it back
                val k = if (t < 40f) t / 60f else 0.25f * t / 60f
                ryominta = Ryominta(0.05f * k, V3(0.06f, 0.01f, -0.05f) * k)
            }
            // A slow walk round a small circle, turning round and round, looking mostly down at the room and,
            // for a while, up at the ceiling
            val paikka = V3(0.1f + 0.5f * cos(t * 0.09f), 1.4f, -0.2f + 0.5f * sin(t * 0.09f))
            val suunta = t * 0.17f
            val ylos = if (t in 22f..30f) 1.0f * sinPehmea((t - 22f) / 1.8f) * sinPehmea((30f - t) / 1.8f) else 0f
            val nyokkays = -0.45f + 0.3f * sin(t * 0.4f) + ylos * 1.6f
            val eteen = V3(sin(suunta) * cos(nyokkays), sin(nyokkays), cos(suunta) * cos(nyokkays))
            val k = Kehys.luo(eteen)
            for (j in 0 until h) for (i in 0 until w) {
                val d = (k.eteen + k.oikealle * (tanX * (2f * (i + 0.5f) / w - 1f)) + k.ylos * (tanY * (1f - 2f * (j + 0.5f) / h))).suunta()
                val (l, seina) = huone.sade(paikka, d)
                val o = (j * w + i) * 3
                val puuttuu = huone.rnd.nextFloat() < (if (seina) 0.45f else 0.2f)
                if (l > 5f || puuttuu) {
                    pisteet[o] = Float.NaN; pisteet[o + 1] = Float.NaN; pisteet[o + 2] = Float.NaN
                    continue
                }
                var mitattu = l * (1f + 0.012f * huone.gauss()) + 0.004f * huone.gauss()
                if (huone.rnd.nextFloat() < 0.01f) mitattu *= 0.6f + huone.rnd.nextFloat() * 0.8f
                val q = ryominta.maailmaan(paikka + d * mitattu)
                pisteet[o] = q.x; pisteet[o + 1] = q.y; pisteet[o + 2] = q.z
            }
            val kamera = ryominta.maailmaan(paikka)
            val lattia = tunnistin.syvyys(t, kamera, ryominta.suunta(eteen), pisteet, w, h)
            haku.ruutu(dt, kamera, lattia)
            t += dt
        }
        val ly = haku.y
        val pinnat = tunnistin.pinnat(ly)
        // Back into the real room's coordinates
        val r = ryominta
        val oikeat = pinnat.map { p -> Pinta(p.id, p.laji, r.takaisin(p.keskus), r.suuntaTakaisin(p.normaali), r.suuntaTakaisin(p.u), p.reuna) }
        return Tulos(oikeat, ly?.let { it - r.siirto.y }, tunnistin.kattoY(ly)?.let { it - r.siirto.y }, r, ankkurit.elossa.size)
    }

    private fun sinPehmea(x: Float) = if (x <= 0f) 0f else if (x >= 1f) 1f else (1f - cos(PI.toFloat() * x)) / 2f

    /** Real upright faces of the room as (normal, distance along it). */
    private val oikeatSeinat = listOf(
        V3.X to -2f, -V3.X to -2.2f, V3.Z to -2.5f, -V3.Z to -2f, // the room's walls
        V3.X to -1.3f, V3.Z to 1.6f, -V3.Z to -0.2f, // the cabinet's front and ends
    )

    private fun tarkista(t: Tulos, nimi: String) {
        val kuvaus = "$nimi: " + t.pinnat.joinToString { "${it.id} ${it.laji} n=${it.normaali} y=${it.keskus.y} ala=${it.ala}" }
        assertEquals(0f, assertNotNull(t.lattiaY, "$nimi: lattiaa ei löytynyt"), 0.04f, "$nimi: lattian korkeus")
        assertEquals(2.5f, assertNotNull(t.kattoY, "$nimi: kattoa ei löytynyt. $kuvaus"), 0.08f, "$nimi: katon korkeus")

        // Tables: the table and the cabinet top, where they really are, and no table anywhere else
        val poydat = t.pinnat.filter { it.laji == PinnanLaji.POYTA }
        val poyta = poydat.firstOrNull { it.sisalla(V3(0.9f, 0.75f, -1.6f)) && abs(it.keskus.y - 0.75f) < 0.04f }
        assertNotNull(poyta, "$nimi: pöytä puuttuu. $kuvaus")
        assertTrue(poyta.ala in 0.5f..1.5f, "$nimi: pöydän ala ${poyta.ala}")
        assertTrue(!poyta.sisalla(V3(0.9f, 0.75f, -0.7f)) && !poyta.sisalla(V3(2.0f, 0.75f, -1.6f)), "$nimi: pöytä ulottuu liian kauas")
        assertNotNull(poydat.firstOrNull { it.sisalla(V3(-1.65f, 0.5f, 0.9f)) && abs(it.keskus.y - 0.5f) < 0.04f }, "$nimi: kaapin kansi puuttuu. $kuvaus")
        for (p in poydat) {
            val oikea = (abs(p.keskus.y - 0.75f) < 0.05f) || (abs(p.keskus.y - 0.5f) < 0.05f)
            assertTrue(oikea, "$nimi: haamupöytä ${p.id} korkeudella ${p.keskus.y}. $kuvaus")
        }

        // Walls: every room wall found once, and nothing upright where nothing is
        val seinat = t.pinnat.filter { it.laji == PinnanLaji.SEINA }
        for ((n, o) in oikeatSeinat.take(4)) {
            assertTrue(seinat.any { (it.normaali dot n) > 0.99f && abs((it.normaali dot it.keskus) - o) < 0.07f }, "$nimi: seinä $n $o puuttuu. $kuvaus")
        }
        for (s in seinat) {
            val vastaa = oikeatSeinat.any { (n, o) -> (s.normaali dot n) > 0.985f && abs((s.normaali dot s.keskus) - o) < 0.08f }
            assertTrue(vastaa, "$nimi: haamuseinä ${s.id} n=${s.normaali} d=${s.normaali dot s.keskus}. $kuvaus")
        }
        for (i in seinat.indices) for (j in i + 1 until seinat.size) {
            val a = seinat[i]
            val b = seinat[j]
            val paallekkain = (a.normaali dot b.normaali) > 0.98f && abs((a.normaali dot a.keskus) - (b.normaali dot b.keskus)) < 0.2f &&
                b.reuna.any { (u, v) -> a.sisalla(b.pisteTasolta(u, v), -0.15f) }
            assertTrue(!paallekkain, "$nimi: seinä ${a.id} ja ${b.id} ovat päällekkäin. $kuvaus")
        }
        // Walls reach the floor, so that climbers get onto them
        assertTrue(seinat.any { s -> oikeatSeinat.take(4).any { (n, _) -> (s.normaali dot n) > 0.99f } && s.reuna.any { (u, v) -> s.pisteTasolta(u, v).y < 0.05f } }, "$nimi: mikään seinä ei ulotu lattiaan")
    }

    @Test
    fun tunnistaaHuoneenTarkallaSeurannalla() {
        tarkista(kavele(ryomii = false, ankkureilla = false), "tarkka seuranta")
    }

    @Test
    fun tunnistaaHuoneenKunSeurantaRyomiiJaKorjautuu() {
        val t = kavele(ryomii = true, ankkureilla = true)
        tarkista(t, "ryöminta ja ankkurit")
        assertTrue(t.ankkureita <= Tunnistin.ENINTAAN, "ankkureita jäi irrottamatta: ${t.ankkureita}")
    }

    @Test
    fun ilmanAnkkureitaKorjautuvaRyomintaEiJataHaamuja() {
        // Browsers without anchors: the surfaces stay where they were measured, but seeing the room again
        // has to clean up what the correction left behind
        tarkista(kavele(ryomii = true, ankkureilla = false, kesto = 90f), "ryöminta ilman ankkureita")
    }

    @Test
    fun tunnistusKestaaEriKavelyt() {
        // Other random rooms' worth of noise and holes, with drift
        for (siemen in 2..6) tarkista(kavele(ryomii = true, ankkureilla = siemen % 2 == 0, seed = siemen), "siemen $siemen")
    }

    @Test
    fun nopeaHeilautusJaHyppyEivatTuotaMittauksia() {
        val tunnistin = Tunnistin()
        val w = 6
        val h = 6
        // A flat patch of table under the camera, at height [y]
        fun laikku(y: Float) = FloatArray(w * h * 3) { i -> when (i % 3) { 0 -> (i / 3 % w) * 0.05f; 1 -> y; else -> -1f - (i / 3 / w) * 0.05f } }
        tunnistin.syvyys(0f, V3(0f, 1.4f, 0f), V3(0f, -1f, -1f), laikku(0.7f), w, h)
        assertEquals(1, tunnistin.tasoja)
        // Swinging round fast: left out
        tunnistin.syvyys(0.05f, V3(0f, 1.4f, 0f), V3(1f, 0f, 0f), laikku(0.3f), w, h)
        assertEquals(1, tunnistin.tasoja)
        // A jump in the camera's position: nothing for a while
        tunnistin.syvyys(0.1f, V3(1f, 1.4f, 0f), V3(1f, 0f, 0f), laikku(0.3f), w, h)
        tunnistin.syvyys(0.3f, V3(1f, 1.4f, 0f), V3(1f, 0f, 0f), laikku(0.3f), w, h)
        assertEquals(1, tunnistin.tasoja)
        tunnistin.syvyys(1.0f, V3(1f, 1.4f, 0f), V3(1f, 0f, 0f), laikku(0.3f), w, h)
        assertEquals(2, tunnistin.tasoja)
    }
}
