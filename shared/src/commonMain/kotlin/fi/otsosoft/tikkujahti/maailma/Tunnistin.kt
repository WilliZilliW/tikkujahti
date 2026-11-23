package fi.otsosoft.tikkujahti.maailma

import fi.otsosoft.tikkujahti.matikka.V3
import kotlin.math.abs

/**
 * Builds surfaces out of single measurements. The platform keeps probing the room — a handful of rays through
 * the camera image every moment — and hands each hit here as a point and the direction the surface faces
 * there. Points that lie on the same plane near each other grow into one surface: table tops, shelves, walls,
 * ceilings. So the game learns the room wherever the player looks, also where the platform's own plane
 * finder has found nothing.
 *
 * The floor is not built here: it is one wide disc from [LattianHaku].
 */
class Tunnistin {
    private class Taso(val id: Int, val n: V3, var keskus: V3, val u: V3) {
        val v = n cross u
        val pisteet = ArrayList<V3>()
        var pinta: Pinta? = null
        var lattiaY = Float.NaN
        var muuttunut = true
    }

    private val tasot = ArrayList<Taso>()
    private var seuraava = 1

    private var edKamera: V3? = null
    private var hylkaa = 0

    /**
     * One look at the room: where a small fan of rays from the camera hit — straight ahead ([keski]), a little
     * to each side and above and below, plus any [muut] around them. Null means that ray hit nothing.
     *
     * The direction a surface faces is worked out here from the points themselves, never taken from what the
     * platform reports: for single points the platforms often just say "up", which turned ceilings and walls
     * into tables. If the points lie flat they are added as measurements, and the middle point and the
     * surface's normal (towards the camera) are returned. A fan that falls across an edge or a corner is
     * left out, and so is everything for a moment after the camera's position jumps (tracking lost, as
     * happens when the phone is pointed at a bare ceiling).
     */
    fun katse(kamera: V3, keski: V3?, vasen: V3?, oikea: V3?, ylos: V3?, alas: V3?, muut: List<V3?> = emptyList()): Pair<V3, V3>? {
        val ed = edKamera
        edKamera = kamera
        if (ed != null && (kamera - ed).pituus > HYPPY) hylkaa = 45
        if (hylkaa > 0) {
            hylkaa--
            return null
        }
        if (keski == null || vasen == null || oikea == null || ylos == null || alas == null) return null
        val leveys = (oikea - vasen).pituus
        val korkeus = (ylos - alas).pituus
        if (leveys < 0.04f || korkeus < 0.04f || leveys > 3f || korkeus > 3f) return null
        var n = ((oikea - vasen) cross (ylos - alas)).suunta(V3.NOLLA)
        if (n == V3.NOLLA) return null
        if ((n dot (kamera - keski)) < 0f) n = -n // a surface that is seen faces the one who sees it
        // Flat? Every point has to lie on the plane through the middle one, within what the measuring allows
        val vara = 0.025f + 0.02f * (keski - kamera).pituus
        val kaikki = listOf(vasen, oikea, ylos, alas) + muut.filterNotNull()
        if (kaikki.any { kotlin.math.abs((it - keski) dot n) > vara }) return null
        nayte(keski, n)
        for (p in kaikki) nayte(p, n)
        return keski to n
    }

    /** A measured point [p] on a surface whose normal there is [normaali]. */
    fun nayte(p: V3, normaali: V3) {
        // Only level and upright surfaces: snap the normal, drop anything in between
        val n0 = normaali.suunta()
        val n = when {
            n0.y > 0.85f -> V3.Y
            n0.y < -0.85f -> -V3.Y
            abs(n0.y) < 0.35f -> n0.vaakaan().suunta()
            else -> return
        }
        var paras: Taso? = null
        val it = tasot.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if ((t.n dot n) < 0.94f || abs((p - t.keskus) dot t.n) > PAKSUUS) continue
            if (t.pisteet.none { q -> (q - p).pituus < YHTEYS }) continue
            val eka = paras
            if (eka == null) {
                paras = t
            } else {
                // This point bridges two patches of the same plane: they are one surface
                eka.pisteet += t.pisteet
                eka.muuttunut = true
                it.remove()
            }
        }
        val t = paras ?: run {
            if (tasot.size >= ENINTAAN) return
            val u = if (abs(n.y) > 0.5f) V3.X else (n cross V3.Y).suunta()
            Taso(seuraava++, n, p, u).also { tasot += it }
        }
        // The plane settles on the average of its measurements
        t.keskus += t.n * (((p - t.keskus) dot t.n) * 0.1f)
        if (t.pisteet.any { (it - p).pituus < TIHEYS }) return
        t.pisteet += p
        t.muuttunut = true
        // Only the outline matters: when there are many points, keep the hull
        if (t.pisteet.size > 60) {
            val reuna = kupera(t.pisteet.map { ((it - t.keskus) dot t.u) to ((it - t.keskus) dot t.v) })
            val pidettavat = reuna.map { (a, b) -> t.keskus + t.u * a + t.v * b }
            t.pisteet.clear()
            t.pisteet += pidettavat
        }
    }

    /**
     * The surfaces found so far. The same [Pinta] object is returned for a surface until it changes.
     * [lattiaY] tells tables from floor, and lets walls be continued down to the floor they stand on.
     */
    fun pinnat(lattiaY: Float?): List<Pinta> {
        val ulos = ArrayList<Pinta>()
        for (t in tasot) {
            val ly = lattiaY ?: Float.NaN
            if (t.muuttunut || !(t.lattiaY == ly || (t.lattiaY.isNaN() && ly.isNaN()))) {
                t.muuttunut = false
                t.lattiaY = ly
                t.pinta = rakenna(t, lattiaY)
            }
            t.pinta?.let { ulos += it }
        }
        return ulos
    }

    private fun rakenna(t: Taso, lattiaY: Float?): Pinta? {
        if (t.pisteet.size < VAHINTAAN) return null
        val laji = luokittele(t.n, t.keskus.y, lattiaY) ?: return null
        val pisteet = ArrayList(t.pisteet)
        // A wall seen low enough is taken to reach the floor: that is where climbers get onto it
        if (laji == PinnanLaji.SEINA && lattiaY != null) {
            val alin = pisteet.minOf { it.y }
            if (alin - lattiaY < 1.6f && alin > lattiaY) pisteet += t.pisteet.map { V3(it.x, lattiaY, it.z) }
        }
        var reuna = kupera(pisteet.map { ((it - t.keskus) dot t.u) to ((it - t.keskus) dot t.v) })
        if (reuna.size < 3) return null
        // A little margin: the measurements stop short of the real edges
        val ca = reuna.sumOf { it.first.toDouble() }.toFloat() / reuna.size
        val cb = reuna.sumOf { it.second.toDouble() }.toFloat() / reuna.size
        reuna = reuna.map { (a, b) ->
            val l = kotlin.math.sqrt((a - ca) * (a - ca) + (b - cb) * (b - cb)).coerceAtLeast(1e-4f)
            (a + (a - ca) / l * VARA) to (b + (b - cb) / l * VARA)
        }
        val pinta = Pinta("t${t.id}", laji, t.keskus, t.n, t.u, reuna)
        return if (pinta.ala >= VAHIMMAISALA) pinta else null
    }

    companion object {
        const val ENINTAAN = 24
        /** Points this close to a plane belong to it. */
        const val PAKSUUS = 0.07f
        /** A point joins a surface only if it is this near one of its points: a far-off patch is another surface. */
        const val YHTEYS = 0.8f
        const val TIHEYS = 0.06f
        const val VARA = 0.06f
        const val VAHIMMAISALA = 0.05f
        /** Measurements a surface needs before it counts: one stray patch is not a table. */
        const val VAHINTAAN = 6
        /** A camera that moves further than this between two frames has lost its place, metres. */
        const val HYPPY = 0.25f

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

enum class PaikanLaji(val nimi: String) {
    /** Where two walls meet on the floor. */
    NURKKA("nurkka"),
    /** On the floor under a table or shelf. */
    KOLO("kolo"),
    /** A dark spot (measured from the camera image where the platform can). */
    VARJO("varjoisa paikka"),
    /** On top of something tall. */
    KORKEA("korkea paikka"),
}

/** A spot in the room with some character: figures hide in these and special balls lie in them. */
class Paikka(val laji: PaikanLaji, val piste: V3) {
    /** On the floor (as opposed to up on some surface). */
    val lattialla: Boolean get() = laji != PaikanLaji.KORKEA
}

object Paikat {
    /** Works out the interesting spots of a room from its surfaces, plus the [varjot] the platform measured. */
    fun etsi(m: Maailma, varjot: List<V3>): List<Paikka> {
        val lattia = m.lattia ?: return emptyList()
        val ly = lattia.keskus.y
        val ulos = ArrayList<Paikka>()
        fun lisaa(laji: PaikanLaji, p: V3) {
            if (ulos.none { it.laji == laji && (it.piste - p).pituus < 0.35f }) ulos += Paikka(laji, p)
        }
        for (p in m.pinnat) {
            if (p.laji != PinnanLaji.POYTA || p.reuna.isEmpty()) continue
            val ca = p.reuna.sumOf { it.first.toDouble() }.toFloat() / p.reuna.size
            val cb = p.reuna.sumOf { it.second.toDouble() }.toFloat() / p.reuna.size
            val keski = p.pisteTasolta(ca, cb)
            val korkeus = keski.y - ly
            // High enough to crawl under, low enough to be furniture: there is a nook below
            if (korkeus in 0.5f..1.3f) {
                val alla = V3(keski.x, ly, keski.z)
                if (lattia.sisalla(alla)) lisaa(PaikanLaji.KOLO, alla)
            }
            if (korkeus > 0.8f) lisaa(PaikanLaji.KORKEA, keski)
        }
        // Corners: where two walls that are not parallel both come down to the floor
        val seinat = m.pinnat.filter { it.laji == PinnanLaji.SEINA }
        for (i in seinat.indices) for (j in i + 1 until seinat.size) {
            val a = seinat[i]
            val b = seinat[j]
            val na = a.normaali.vaakaan().suunta(V3.NOLLA)
            val nb = b.normaali.vaakaan().suunta(V3.NOLLA)
            val det = na.x * nb.z - na.z * nb.x
            if (abs(det) < 0.5f) continue
            // The floor point a short way out from both walls
            val da = (a.keskus dot na) + NURKASTA
            val db = (b.keskus dot nb) + NURKASTA
            val p = V3((da * nb.z - db * na.z) / det, ly, (na.x * db - nb.x * da) / det)
            val ylla = p + V3.Y * 0.15f
            if (lattia.sisalla(p) && a.sisalla(ylla, 0.15f) && b.sisalla(ylla, 0.15f)) lisaa(PaikanLaji.NURKKA, p)
        }
        for (v in varjot) {
            val p = V3(v.x, ly, v.z)
            if (abs(v.y - ly) < 0.25f && lattia.sisalla(p)) lisaa(PaikanLaji.VARJO, p)
        }
        return ulos
    }

    const val NURKASTA = 0.14f
}
