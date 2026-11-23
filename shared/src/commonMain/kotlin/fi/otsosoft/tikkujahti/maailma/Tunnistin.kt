package fi.otsosoft.tikkujahti.maailma

import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.matikka.kulmaValilla
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Learns the room's surfaces — table tops, walls, ceilings — from the phone's depth images.
 *
 * Each depth image arrives as a grid of world points. From it the recogniser:
 *  1. works out which way the surface faces at each point, from the neighbouring points;
 *  2. finds the flat surfaces in this one image: rooms are built level and plumb, so it looks for many
 *     points facing up (or down) at one height, and for many points facing one horizontal direction at one
 *     distance along it (a histogram vote, robust against the noise that single points have);
 *  3. adds each surface it found to the one it already knows there, or starts a new one. A known surface
 *     remembers where it has been seen in 10 cm cells, so its outline follows what was really seen (two
 *     tables at the same height stay two tables), and it lives relative to an [Ankkuri] if the platform gives
 *     anchors, so it moves along when tracking corrects itself;
 *  4. erases cells it now sees through: a ray that measures something beyond a known surface proves that
 *     the surface is not there. Phantoms left by a tracking slip disappear when the place is looked at again.
 *
 * A cell counts once it has been seen in a few separate depth images. The floor is not built here: it is one
 * wide disc from [LattianHaku], which [syvyys] helps by telling where it saw the floor.
 */
class Tunnistin(private val ankkurit: Ankkurit? = null) {
    private enum class Suunta { YLOS, ALAS, PYSTY }

    private class Solu {
        var osumat = 0
        var lapi = 0
        var osumaRuutu = -1
        var lapiRuutu = -1
        var nahty = 0f
        val kelpaa: Boolean get() = lapi * 2 < osumat
    }

    private class Taso(val id: Int, val suunta: Suunta, val ankkuri: Ankkuri?, var varamuunnos: Muunnos, var n: V3, var o: Float, var u: V3) {
        var havaintoja = 0
        val solut = HashMap<Long, Solu>()
        var nahty = 0f
        var muuttunut = true
        var rakennettu = -100f
        var lattiaY = Float.NaN
        var pinnat: List<Pinta> = emptyList()

        // World-space plane for the current image (see paivita)
        var m = varamuunnos
        var nW = n
        var oW = o
        var p0 = V3.NOLLA
        var uW = u
        var vW = n cross u

        fun paivita() {
            m = ankkuri?.muunnos?.also { varamuunnos = it } ?: varamuunnos
            nW = m.suuntaMaailmaan(n)
            uW = m.suuntaMaailmaan(u)
            vW = nW cross uW
            p0 = m.maailmaan(n * o)
            oW = nW dot p0
        }

        fun avain(q: V3): Long {
            val d = q - p0
            return avain(floor((d dot uW) / SOLU).toInt(), floor((d dot vW) / SOLU).toInt())
        }

        fun kelvot() = solut.count { it.value.osumat >= vahvistus(suunta) && it.value.kelpaa }
    }

    private class Havainto(val suunta: Suunta, val n: V3, val o: Float, val pisteet: List<V3>, val etaisyys: Float)

    private val tasot = ArrayList<Taso>()
    private var seuraava = 1
    private var ruutu = 0
    private var aika = 0f
    private var edKamera: V3? = null
    private var edEteen: V3? = null
    private var edAika = 0f
    private var hylkaaAsti = -1f
    private var yhdistetty = 0f
    /** The room's own direction (radians, modulo a right angle) once enough wall has been seen, else null. */
    private var huoneenKulma: Float? = null

    /** Surfaces known so far (for tests and debugging). */
    val tasoja: Int get() = tasot.size

    /** Forgets everything, letting go of the anchors. */
    fun nollaa() {
        for (t in tasot) t.ankkuri?.irrota()
        tasot.clear()
        edKamera = null
    }

    /**
     * One depth image: [pisteet] holds a [leveys] × [korkeus] grid of world points (x, y, z each, row by row,
     * NaN where the image has no depth), seen from [kamera] looking along [eteen] at time [aikaNyt] seconds.
     * Neighbours in the grid have to be neighbours in the image.
     *
     * Returns the height of the floor if this image saw it (a wide, upward surface well below the phone), for
     * [LattianHaku]; otherwise null.
     */
    fun syvyys(aikaNyt: Float, kamera: V3, eteen: V3, pisteet: FloatArray, leveys: Int, korkeus: Int): Float? {
        aika = aikaNyt
        val ed = edKamera
        val edE = edEteen
        val dt = aikaNyt - edAika
        edKamera = kamera
        edEteen = eteen
        edAika = aikaNyt
        if (ed != null && edE != null) {
            // A camera that jumps has lost its place; one that swings or moves fast blurs the depth
            if ((kamera - ed).pituus > HYPPY) hylkaaAsti = aikaNyt + 0.75f
            else if (dt in 1e-3f..0.5f && (kulmaValilla(eteen.suunta(), edE.suunta()) / dt > KAANTO || (kamera - ed).pituus / dt > VAUHTI)) return null
        }
        if (aikaNyt < hylkaaAsti) return null
        ruutu++
        for (t in tasot) t.paivita()

        val havainnot = etsiTasot(kamera, pisteet, leveys, korkeus)
        var lattia: Float? = null
        for (h in havainnot) {
            sovita(h)
            if (h.suunta == Suunta.YLOS && h.pisteet.size >= 30 && kamera.y - h.o > LattianHaku.VAHIMMAISKORKEUS) {
                lattia = min(lattia ?: h.o, h.o)
            }
        }
        for (t in tasot) t.paivita()
        pyyhiLapinakyvat(kamera, pisteet)
        siivoa(kamera)
        return lattia
    }

    // ---------------------------------------------------------------------------------------------------------
    // 1–2: surfaces in one depth image
    // ---------------------------------------------------------------------------------------------------------

    private fun etsiTasot(kamera: V3, p: FloatArray, w: Int, h: Int): List<Havainto> {
        val maara = w * h
        val nx = FloatArray(maara) { Float.NaN }
        val ny = FloatArray(maara)
        val nz = FloatArray(maara)
        val etaisyys = FloatArray(maara) { Float.NaN }
        for (i in 0 until maara) {
            val x = p[i * 3]
            if (x.isNaN()) continue
            val d = sqrt((x - kamera.x).let { it * it } + (p[i * 3 + 1] - kamera.y).let { it * it } + (p[i * 3 + 2] - kamera.z).let { it * it })
            if (d in LAHIN..KAUIMMAINEN) etaisyys[i] = d
        }
        fun piste(i: Int) = V3(p[i * 3], p[i * 3 + 1], p[i * 3 + 2])
        // A neighbour k cells away, unless it is missing or across a depth jump
        fun naapuri(i: Int, x: Int, y: Int, dx: Int, dy: Int): Int {
            for (k in intArrayOf(2, 1)) {
                val xx = x + dx * k
                val yy = y + dy * k
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                val j = yy * w + xx
                val dj = etaisyys[j]
                if (dj.isNaN() || abs(dj - etaisyys[i]) > 0.05f * etaisyys[i] + 0.02f) continue
                return j
            }
            return -1
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (etaisyys[i].isNaN()) continue
            val o = naapuri(i, x, y, 1, 0)
            val v = naapuri(i, x, y, -1, 0)
            val a = naapuri(i, x, y, 0, 1)
            val yl = naapuri(i, x, y, 0, -1)
            if ((o < 0 && v < 0) || (a < 0 && yl < 0)) continue
            val c = piste(i)
            val vaaka = (if (o >= 0) piste(o) else c) - (if (v >= 0) piste(v) else c)
            val pysty = (if (a >= 0) piste(a) else c) - (if (yl >= 0) piste(yl) else c)
            var n = (vaaka cross pysty).suunta(V3.NOLLA)
            if (n == V3.NOLLA) continue
            if ((n dot (kamera - c)) < 0f) n = -n // a surface that is seen faces the one who sees it
            // Only where the neighbourhood really is flat
            val vara = 0.015f + 0.012f * etaisyys[i]
            if (intArrayOf(o, v, a, yl).any { it >= 0 && abs((piste(it) - c) dot n) > vara }) continue
            nx[i] = n.x; ny[i] = n.y; nz[i] = n.z
        }

        val ulos = ArrayList<Havainto>()
        val kaytetty = BooleanArray(maara)

        // Level surfaces: many points facing up (or down) at the same height
        for (suunta in listOf(Suunta.YLOS, Suunta.ALAS)) {
            val ehdokkaat = (0 until maara).filter { i ->
                !nx[i].isNaN() && (if (suunta == Suunta.YLOS) ny[i] > 0.88f else ny[i] < -0.88f && p[i * 3 + 1] > kamera.y)
            }
            repeat(4) {
                val vapaat = ehdokkaat.filter { !kaytetty[it] }
                val huippu = huippu(vapaat.map { p[it * 3 + 1] }, 0.02f) ?: return@repeat
                fun sisalla(i: Int, k: Float) = abs(p[i * 3 + 1] - k) < 0.025f + 0.01f * etaisyys[i]
                var korkeus = huippu
                var mukana = vapaat.filter { sisalla(it, korkeus) }
                if (mukana.size < VAHIN_HAVAINTO) return@repeat
                korkeus = mukana.map { p[it * 3 + 1] }.sorted()[mukana.size / 2]
                mukana = vapaat.filter { sisalla(it, korkeus) }
                mukana.forEach { kaytetty[it] = true }
                if (mukana.size < VAHIN_HAVAINTO) return@repeat
                val n = if (suunta == Suunta.YLOS) V3.Y else -V3.Y
                ulos += Havainto(suunta, n, n dot V3(0f, korkeus, 0f), mukana.map { piste(it) }, mukana.map { etaisyys[it] }.sorted()[mukana.size / 2])
            }
        }

        // Upright surfaces: first a direction many points face, then the distances along it where they gather
        val pystyt = (0 until maara).filter { !nx[it].isNaN() && abs(ny[it]) < 0.3f }
        repeat(4) {
            val vapaat = pystyt.filter { !kaytetty[it] }
            if (vapaat.size < VAHIN_HAVAINTO) return@repeat
            val kulmat = vapaat.map { atan2(nx[it], nz[it]) }
            val lokero = (2f * PI / 36f).toFloat()
            val laskut = IntArray(36)
            for (k in kulmat) laskut[((k + PI.toFloat()) / lokero).toInt().coerceIn(0, 35)]++
            var paras = -1
            var parasMaara = 0
            for (b in 0 until 36) {
                val s = laskut[(b + 35) % 36] + laskut[b] + laskut[(b + 1) % 36]
                if (s > parasMaara) {
                    parasMaara = s; paras = b
                }
            }
            if (parasMaara < VAHIN_HAVAINTO) return@repeat
            val keskiKulma = -PI.toFloat() + (paras + 0.5f) * lokero
            val suuntaa = V3(kotlin.math.sin(keskiKulma), 0f, kotlin.math.cos(keskiKulma))
            val samaan = vapaat.filter { (V3(nx[it], 0f, nz[it]).suunta(V3.NOLLA) dot suuntaa) > 0.966f } // within 15°
            if (samaan.size < VAHIN_HAVAINTO) {
                vapaat.filter { (V3(nx[it], 0f, nz[it]).suunta(V3.NOLLA) dot suuntaa) > 0.9f }.forEach { kaytetty[it] = true }
                return@repeat
            }
            var n = V3.NOLLA
            for (i in samaan) n += V3(nx[i], 0f, nz[i])
            n = n.suunta(suuntaa)
            // Normals of single points are rough, so the direction they agree on is only a start: the wall's
            // direction is then fitted to where its points lie, and the points gathered again with it
            val laajempi = vapaat.filter { (V3(nx[it], 0f, nz[it]).suunta(V3.NOLLA) dot n) > 0.85f } // within 30°
            repeat(3) {
                val jaljella = samaan.filter { !kaytetty[it] }
                val huippu = huippu(jaljella.map { n dot piste(it) }, 0.03f) ?: return@repeat
                var nn = n
                var matka = huippu
                fun sisalla(i: Int) = !kaytetty[i] && abs((nn dot piste(i)) - matka) < 0.03f + 0.012f * etaisyys[i]
                var mukana = jaljella.filter { sisalla(it) }
                var sovitettu = false
                for (kierros in 0 until 3) {
                    if (mukana.size < VAHIN_HAVAINTO) break
                    val (uusiN, uusiMatka) = sovitaSeina(mukana.map { piste(it) }, nn) ?: break
                    nn = uusiN
                    matka = uusiMatka
                    mukana = laajempi.filter { sisalla(it) }
                    sovitettu = true
                }
                if (!sovitettu || mukana.size < VAHIN_HAVAINTO) {
                    jaljella.filter { abs((n dot piste(it)) - huippu) < 0.05f }.forEach { kaytetty[it] = true }
                    return@repeat
                }
                mukana.forEach { kaytetty[it] = true }
                // Rooms are mostly square: a wall within a few degrees of the room's own directions is taken
                // to be exactly along them
                suorista(nn)?.let { suora ->
                    nn = suora
                    matka = mukana.map { nn dot piste(it) }.sorted()[mukana.size / 2]
                }
                ulos += Havainto(Suunta.PYSTY, nn, matka, mukana.map { piste(it) }, mukana.map { etaisyys[it] }.sorted()[mukana.size / 2])
            }
            // Whatever faces this way and was not a surface is done with
            vapaat.filter { (V3(nx[it], 0f, nz[it]).suunta(V3.NOLLA) dot suuntaa) > 0.966f }.forEach { kaytetty[it] = true }
        }
        return ulos
    }

    /** The value most of [arvot] gather around (a histogram with [lokero]-wide bins), or null if too few. */
    private fun huippu(arvot: List<Float>, lokero: Float): Float? {
        if (arvot.size < VAHIN_HAVAINTO) return null
        val laskut = HashMap<Int, Int>()
        for (a in arvot) {
            val b = floor(a / lokero).toInt()
            laskut[b] = (laskut[b] ?: 0) + 1
        }
        var paras = 0
        var parasMaara = 0
        for (b in laskut.keys) {
            val s = (laskut[b - 1] ?: 0) + (laskut[b] ?: 0) + (laskut[b + 1] ?: 0)
            if (s > parasMaara) {
                parasMaara = s; paras = b
            }
        }
        if (parasMaara < VAHIN_HAVAINTO) return null
        var summa = 0.0
        var n = 0
        for (a in arvot) if (abs(floor(a / lokero).toInt() - paras) <= 1) {
            summa += a; n++
        }
        return (summa / n).toFloat()
    }

    /** [n] turned exactly along the room's own directions if it is within [SUORA] of one, else null. */
    private fun suorista(n: V3): V3? {
        val huone = huoneenKulma ?: return null
        val kulma = atan2(n.x, n.z)
        val neljannes = (PI / 2).toFloat()
        val k = kotlin.math.round((kulma - huone) / neljannes)
        val suora = huone + k * neljannes
        if (abs(kulma - suora) > SUORA) return null
        return V3(kotlin.math.sin(suora), 0f, kotlin.math.cos(suora))
    }

    /** Learns the room's directions from the walls seen well: their angles agree modulo a right angle. */
    private fun opiHuoneenKulma() {
        var c = 0.0
        var s = 0.0
        var paino = 0
        for (t in tasot) {
            if (t.suunta != Suunta.PYSTY) continue
            val k = t.kelvot()
            if (k < 20) continue
            val kulma = 4.0 * atan2(t.nW.x, t.nW.z)
            c += k * kotlin.math.cos(kulma); s += k * kotlin.math.sin(kulma)
            paino += k
        }
        // Only when the walls agree: a room of odd angles keeps its walls as they are
        huoneenKulma = if (paino >= 60 && sqrt(c * c + s * s) / paino > 0.8) (kotlin.math.atan2(s, c) / 4.0).toFloat() else null
    }

    /**
     * The upright plane through [pisteet], seen from above as the line they lie along (principal axis of their
     * x, z), facing the same side as [vihje]: its normal and distance along it. Null if the points do not
     * span enough of a wall to tell its direction, or if they turn out to face somewhere else altogether.
     */
    private fun sovitaSeina(pisteet: List<V3>, vihje: V3): Pair<V3, Float>? {
        var mx = 0.0
        var mz = 0.0
        for (q in pisteet) {
            mx += q.x; mz += q.z
        }
        mx /= pisteet.size; mz /= pisteet.size
        var sxx = 0.0
        var sxz = 0.0
        var szz = 0.0
        for (q in pisteet) {
            val dx = q.x - mx
            val dz = q.z - mz
            sxx += dx * dx; sxz += dx * dz; szz += dz * dz
        }
        val kulma = 0.5 * kotlin.math.atan2(2.0 * sxz, sxx - szz)
        val pitkin = V3(kotlin.math.cos(kulma).toFloat(), 0f, kotlin.math.sin(kulma).toFloat())
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (q in pisteet) {
            val s = q.x * pitkin.x + q.z * pitkin.z
            lo = min(lo, s); hi = max(hi, s)
        }
        if (hi - lo < 0.3f) return null
        var n = V3(-pitkin.z, 0f, pitkin.x)
        if ((n dot vihje) < 0f) n = -n
        if ((n dot vihje) < 0.85f) return null
        return n to (n.x * mx + n.z * mz).toFloat()
    }

    // ---------------------------------------------------------------------------------------------------------
    // 3: adding what was seen to what is known
    // ---------------------------------------------------------------------------------------------------------

    private fun sovita(h: Havainto) {
        var paras: Taso? = null
        var parasOsuus = 0f
        val otos = if (h.pisteet.size <= 40) h.pisteet else List(40) { h.pisteet[it * h.pisteet.size / 40] }
        for (t in tasot) {
            if (t.suunta != h.suunta || (t.nW dot h.n) < 0.985f) continue // within 10°
            if (abs(t.oW - h.o) > 0.05f + 0.015f * h.etaisyys) continue
            // Same plane — but the same surface only if what was seen is on it or right next to it
            var lahella = 0
            for (q in otos) if (lahella(t, q, 3)) lahella++
            val osuus = lahella / otos.size.toFloat()
            if (osuus > parasOsuus) {
                parasOsuus = osuus; paras = t
            }
        }
        val t = if (paras != null && parasOsuus >= 0.15f) paras else uusiTaso(h) ?: return
        t.havaintoja++
        t.nahty = aika
        // The plane settles on what it has been seen at, a little more slowly the more it has been seen
        val havaittu = h.pisteet.map { t.nW dot it }.sorted()[h.pisteet.size / 2]
        val paino = 1f / (1f + min(t.havaintoja, 20))
        t.o += (havaittu - t.oW) * paino
        if (h.suunta == Suunta.PYSTY) {
            t.n = (t.n + t.m.suuntaPaikalliseksi(h.n) * (paino * 0.5f)).suunta(t.n)
            t.u = t.u.tasolle(t.n).suunta(t.u)
        }
        t.paivita()
        for (q in h.pisteet) {
            val s = t.solut.getOrPut(t.avain(q)) { Solu() }
            if (s.osumaRuutu != ruutu) {
                s.osumaRuutu = ruutu
                s.osumat = min(s.osumat + 1, 60)
                s.nahty = aika
            }
        }
        t.muuttunut = true
    }

    private fun lahella(t: Taso, q: V3, soluja: Int): Boolean {
        val d = q - t.p0
        val a = floor((d dot t.uW) / SOLU).toInt()
        val b = floor((d dot t.vW) / SOLU).toInt()
        for (i in -soluja..soluja) for (j in -soluja..soluja) if (t.solut.containsKey(avain(a + i, b + j))) return true
        return false
    }

    private fun uusiTaso(h: Havainto): Taso? {
        if (tasot.size >= ENINTAAN) {
            // Make room: let go of the surface that has least to show for itself and was seen longest ago
            val heikoin = tasot.minByOrNull { it.kelvot() * 10f + it.nahty } ?: return null
            heikoin.ankkuri?.irrota()
            tasot.remove(heikoin)
        }
        var keski = V3.NOLLA
        for (q in h.pisteet) keski += q
        keski /= h.pisteet.size.toFloat()
        val ankkuri = ankkurit?.luo(keski)
        val m = ankkuri?.muunnos ?: Muunnos.siirto(keski)
        val n = m.suuntaPaikalliseksi(h.n)
        val o = n dot m.paikalliseksi(keski)
        val u = m.suuntaPaikalliseksi(if (h.suunta == Suunta.PYSTY) (h.n cross V3.Y).suunta(V3.X) else V3.X)
        return Taso(seuraava++, h.suunta, ankkuri, m, n, o, u).also {
            it.paivita()
            tasot += it
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // 4: erasing what has been seen through, and tidying up
    // ---------------------------------------------------------------------------------------------------------

    private fun pyyhiLapinakyvat(kamera: V3, p: FloatArray) {
        if (tasot.isEmpty()) return
        var i = 0
        val maara = p.size / 3
        while (i < maara) {
            val x = p[i * 3]
            if (!x.isNaN()) {
                val q = V3(x, p[i * 3 + 1], p[i * 3 + 2])
                val ero = q - kamera
                val l = ero.pituus
                if (l in LAHIN..KAUIMMAINEN) {
                    val suunta = ero / l
                    for (t in tasot) {
                        val nimittaja = t.nW dot suunta
                        if (abs(nimittaja) < 0.2f) continue
                        val s = (t.oW - (t.nW dot kamera)) / nimittaja
                        if (s < 0.3f || s > l - (0.08f + 0.04f * l)) continue
                        val avain = t.avain(kamera + suunta * s)
                        val solu = t.solut[avain] ?: continue
                        if (solu.lapiRuutu == ruutu) continue
                        solu.lapiRuutu = ruutu
                        solu.lapi++
                        t.muuttunut = true
                        if (solu.lapi >= 3 && !solu.kelpaa) t.solut.remove(avain)
                    }
                }
            }
            i += 2
        }
    }

    private fun siivoa(kamera: V3) {
        val it = tasot.iterator()
        while (it.hasNext()) {
            val t = it.next()
            val unohdettu = aika - t.nahty > UNOHDUS && (t.p0 - kamera).pituus > 8f
            if (t.solut.isEmpty() || unohdettu) {
                t.ankkuri?.irrota()
                it.remove()
            }
        }
        if (aika - yhdistetty < 1f) return
        yhdistetty = aika
        opiHuoneenKulma()
        // Two surfaces that have grown into each other are one
        var kierroksia = 0
        while (kierroksia++ < 8) {
            var pari: Pair<Taso, Taso>? = null
            loop@ for (i in tasot.indices) for (j in i + 1 until tasot.size) {
                val a = tasot[i]
                val b = tasot[j]
                if (a.suunta == b.suunta && (a.nW dot b.nW) > 0.995f && abs(a.oW - b.oW) < YHDISTYS && koskettaa(a, b)) {
                    pari = a to b
                    break@loop
                }
            }
            val (a, b) = pari ?: break
            val (iso, pieni) = if (a.solut.size >= b.solut.size) a to b else b to a
            // The merged plane lies between the two, weighted by how much each was seen
            val yhteinen = (iso.oW * iso.havaintoja + pieni.oW * pieni.havaintoja) / (iso.havaintoja + pieni.havaintoja).coerceAtLeast(1)
            iso.o += yhteinen - iso.oW
            iso.paivita()
            for ((k, s) in pieni.solut) {
                val uusi = iso.solut.getOrPut(iso.avain(solunKeskus(pieni, k))) { Solu() }
                uusi.osumat = max(uusi.osumat, s.osumat)
                uusi.lapi = max(uusi.lapi, s.lapi)
                uusi.nahty = max(uusi.nahty, s.nahty)
            }
            iso.havaintoja += pieni.havaintoja
            iso.nahty = max(iso.nahty, pieni.nahty)
            iso.muuttunut = true
            pieni.ankkuri?.irrota()
            tasot.remove(pieni)
        }
    }

    private fun koskettaa(a: Taso, b: Taso): Boolean {
        val (iso, pieni) = if (a.solut.size >= b.solut.size) a to b else b to a
        return pieni.solut.keys.any { lahella(iso, solunKeskus(pieni, it), 2) }
    }

    private fun solunKeskus(t: Taso, k: Long): V3 {
        val a = (k shr 32).toInt()
        val b = k.toInt()
        return t.p0 + t.uW * ((a + 0.5f) * SOLU) + t.vW * ((b + 0.5f) * SOLU)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Surfaces for the game
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The surfaces found so far, given the floor's height [lattiaY] (which tells tables from floor, and lets
     * walls be continued down to the floor). A surface keeps its [Pinta.id] for as long as it exists.
     */
    fun pinnat(lattiaY: Float?): List<Pinta> {
        val ulos = ArrayList<Pinta>()
        for (t in tasot) {
            val ly = lattiaY ?: Float.NaN
            val lattiaMuuttui = !(abs(t.lattiaY - ly) < 0.02f || (t.lattiaY.isNaN() && ly.isNaN()))
            if (lattiaMuuttui || (t.muuttunut && aika - t.rakennettu >= 0.5f)) {
                t.paivita()
                t.pinnat = rakenna(t, lattiaY)
                t.muuttunut = false
                t.rakennettu = aika
                t.lattiaY = ly
            }
            ulos += t.pinnat
        }
        return ulos
    }

    /** Height of the ceiling, if one has been seen well enough. */
    fun kattoY(lattiaY: Float?): Float? = tasot
        .filter { it.suunta == Suunta.ALAS && (lattiaY == null || -it.oW > lattiaY + 1.7f) && it.kelvot() >= 8 }
        .maxByOrNull { it.kelvot() }?.let { -it.oW }

    private fun rakenna(t: Taso, lattiaY: Float?): List<Pinta> {
        val vahvat = t.solut.filter { it.value.osumat >= vahvistus(t.suunta) && it.value.kelpaa }.keys
        if (vahvat.isEmpty()) return emptyList()
        val n = when (t.suunta) {
            Suunta.YLOS -> V3.Y
            Suunta.ALAS -> -V3.Y
            Suunta.PYSTY -> t.nW.vaakaan().suunta(t.nW)
        }
        val keskus = when (t.suunta) {
            Suunta.YLOS -> V3(t.p0.x, t.oW, t.p0.z)
            Suunta.ALAS -> V3(t.p0.x, -t.oW, t.p0.z)
            Suunta.PYSTY -> t.p0
        }
        val laji = luokittele(n, keskus.y, lattiaY) ?: return emptyList()
        val u = t.uW.tasolle(n).suunta(t.uW)
        // Connected groups of cells, each its own surface
        val jaljella = vahvat.toMutableSet()
        val ryhmat = ArrayList<List<Long>>()
        while (jaljella.isNotEmpty()) {
            val alku = jaljella.first()
            jaljella.remove(alku)
            val ryhma = ArrayList<Long>()
            val jono = ArrayDeque(listOf(alku))
            while (jono.isNotEmpty()) {
                val k = jono.removeFirst()
                ryhma += k
                val a = (k shr 32).toInt()
                val b = k.toInt()
                for (i in -1..1) for (j in -1..1) {
                    val nk = avain(a + i, b + j)
                    if (jaljella.remove(nk)) jono.addLast(nk)
                }
            }
            ryhmat += ryhma
        }
        ryhmat.sortByDescending { it.size }
        val ulos = ArrayList<Pinta>()
        for ((indeksi, ryhma) in ryhmat.withIndex()) {
            if (ryhma.size < vahimmillaan(laji)) continue
            val kulmat = ArrayList<Pair<Float, Float>>()
            for (k in ryhma) {
                val a = (k shr 32).toInt() * SOLU
                val b = k.toInt() * SOLU
                kulmat += a to b; kulmat += (a + SOLU) to b; kulmat += a to (b + SOLU); kulmat += (a + SOLU) to (b + SOLU)
            }
            // The cell grid lies on the plane through p0; Pinta measures from keskus along u and n × u
            val siirtoA = (t.p0 - keskus) dot u
            val v = n cross u
            val siirtoB = (t.p0 - keskus) dot v
            val samaV = (t.vW dot v) > 0f
            var tasolla = kulmat.map { (a, b) -> (a + siirtoA) to ((if (samaV) b else -b) + siirtoB) }
            // A wall seen low enough is taken to reach the floor: that is where climbers get onto it
            if (laji == PinnanLaji.SEINA && lattiaY != null) {
                val ylin = tasolla.maxOf { (_, b) -> (keskus + v * b).y }
                val alin = tasolla.minOf { (_, b) -> (keskus + v * b).y }
                if (alin > lattiaY && alin - lattiaY < 0.9f && ylin > alin) {
                    val bLattia = (lattiaY - keskus.y) / v.y
                    tasolla = tasolla + tasolla.map { (a, _) -> a to bLattia }
                }
            }
            val reuna = kupera(tasolla)
            if (reuna.size < 3) continue
            val id = if (indeksi == 0) "t${t.id}" else "t${t.id}-$indeksi"
            ulos += Pinta(id, laji, keskus, n, u, reuna)
        }
        // Outlines are convex, so the pieces of an L-shaped patch can overlap: overlapping pieces are one
        var yhdistyi = true
        while (yhdistyi) {
            yhdistyi = false
            loop@ for (i in ulos.indices) for (j in i + 1 until ulos.size) {
                val a = ulos[i]
                val b = ulos[j]
                if (b.reuna.any { (x, y) -> a.sisalla(a.pisteTasolta(x, y)) } || a.reuna.any { (x, y) -> b.sisalla(b.pisteTasolta(x, y)) }) {
                    ulos[i] = Pinta(a.id, laji, keskus, n, u, kupera(a.reuna + b.reuna))
                    ulos.removeAt(j)
                    yhdistyi = true
                    break@loop
                }
            }
        }
        return ulos
    }

    companion object {
        /** Cell size of a surface's outline, metres. */
        const val SOLU = 0.1f
        const val ENINTAAN = 48
        /** Points a surface needs in one depth image before it counts as seen. */
        const val VAHIN_HAVAINTO = 15
        /** Depth is measured well only this near and this far, metres. */
        const val LAHIN = 0.25f
        const val KAUIMMAINEN = 4.5f
        /** A camera that moves further than this between two images has lost its place, metres. */
        const val HYPPY = 0.25f
        /** Faster turning (rad/s) or moving (m/s) than this smears the depth. */
        const val KAANTO = 1.2f
        const val VAUHTI = 0.9f
        /** Parallel surfaces this close (m) whose cells touch are one surface measured twice. */
        const val YHDISTYS = 0.12f
        /** A wall this close to the room's own directions (radians) is straightened to them. */
        const val SUORA = 0.14f
        /** A surface not seen for this long (s), far from the player, is forgotten. */
        const val UNOHDUS = 600f

        private fun avain(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

        /** In how many separate depth images a cell has to be seen before it counts. */
        private fun vahvistus(s: Suunta) = if (s == Suunta.PYSTY) 3 else 2

        /** Cells a surface needs to be of any use. */
        private fun vahimmillaan(laji: PinnanLaji) = when (laji) {
            PinnanLaji.POYTA -> 6
            PinnanLaji.SEINA -> 20
            PinnanLaji.KATTO -> 15
            PinnanLaji.LATTIA -> 20
        }

        /**
         * A plane the platform itself has detected, as a [Pinta], or null if it is the floor or of no use.
         * The platform gives the plane's own frame — [x] and [normaali] axes at [keskus] — and its outline as
         * (x, z) pairs in that frame. The normal is turned to face [kamera] first: platforms do not agree on
         * which way a ceiling's normal points.
         */
        fun tasosta(id: String, keskus: V3, x: V3, normaali: V3, reunaXZ: List<Pair<Float, Float>>, kamera: V3, lattiaY: Float?): Pinta? {
            val kaannetty = (normaali dot (kamera - keskus)) < 0f
            val n = if (kaannetty) -normaali else normaali
            val laji = luokittele(n.suunta(), keskus.y, lattiaY) ?: return null
            // Pinta's second axis is normal × x: that is -z of the plane's frame, or +z once the normal is turned
            return Pinta(id, laji, keskus, n, x, reunaXZ.map { (a, z) -> a to (if (kaannetty) z else -z) })
        }

        /** What a surface facing [n] at height [y] is, or null if it is the floor itself or of no use. */
        fun luokittele(n: V3, y: Float, lattiaY: Float?): PinnanLaji? = when {
            n.y > 0.85f -> when {
                lattiaY == null -> null
                y > lattiaY + 0.12f && y < lattiaY + 2.3f -> PinnanLaji.POYTA
                else -> null // the floor (or something below it or absurdly high: noise)
            }
            // What faces down low in the room is the underside of something, not a ceiling to hang from
            n.y < -0.85f -> if (lattiaY == null || y > lattiaY + 1.7f) PinnanLaji.KATTO else null
            abs(n.y) < 0.35f -> PinnanLaji.SEINA
            else -> null
        }

        /** Convex hull of plane points (Andrew's monotone chain), counter-clockwise. */
        fun kupera(pisteet: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
            val p = pisteet.distinct().sortedWith(compareBy({ it.first }, { it.second }))
            if (p.size < 3) return p
            fun risti(o: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>) =
                (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
            val h = ArrayList<Pair<Float, Float>>()
            for (q in p) {
                while (h.size >= 2 && risti(h[h.size - 2], h[h.size - 1], q) <= 0f) h.removeAt(h.size - 1)
                h += q
            }
            val ala = h.size + 1
            for (q in p.reversed().drop(1)) {
                while (h.size >= ala && risti(h[h.size - 2], h[h.size - 1], q) <= 0f) h.removeAt(h.size - 1)
                h += q
            }
            h.removeAt(h.size - 1)
            return h
        }
    }
}
