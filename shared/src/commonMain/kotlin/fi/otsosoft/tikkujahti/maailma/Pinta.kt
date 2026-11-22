package fi.otsosoft.tikkujahti.maailma

import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.matikka.kohtisuora
import kotlin.math.abs
import kotlin.random.Random

enum class PinnanLaji { LATTIA, POYTA, SEINA, KATTO }

/**
 * A flat real-world surface: a floor, table top, wall or ceiling found by ARCore or WebXR, or a part of
 * the virtual room. Its shape is a convex or concave polygon in the surface's own plane.
 *
 * @param keskus a point on the plane
 * @param normaali unit normal pointing out of the surface (up for floors)
 * @param uAkseli unit vector in the plane; v = normal × u
 * @param reuna polygon corners as (u, v) pairs relative to [keskus]
 */
class Pinta(
    val id: String,
    val laji: PinnanLaji,
    val keskus: V3,
    val normaali: V3,
    uAkseli: V3 = kohtisuora(normaali),
    val reuna: List<Pair<Float, Float>>,
) {
    val u: V3 = uAkseli.tasolle(normaali).suunta(kohtisuora(normaali))
    val v: V3 = normaali cross u

    val ala: Float by lazy {
        var a = 0f
        for (i in reuna.indices) {
            val (x1, y1) = reuna[i]
            val (x2, y2) = reuna[(i + 1) % reuna.size]
            a += x1 * y2 - x2 * y1
        }
        abs(a) / 2f
    }

    val onVaaka: Boolean get() = abs(normaali.y) > 0.9f

    /** Signed distance from the plane, positive on the normal's side. */
    fun etaisyys(p: V3) = (p - keskus) dot normaali

    fun tasoKoordinaatit(p: V3): Pair<Float, Float> {
        val d = p - keskus
        return (d dot u) to (d dot v)
    }

    fun pisteTasolta(a: Float, b: Float) = keskus + u * a + v * b

    /** Whether the point's projection onto the plane falls inside the polygon, grown by [marginaali]. */
    fun sisalla(p: V3, marginaali: Float = 0f): Boolean {
        val (a, b) = tasoKoordinaatit(p)
        if (pisteMonikulmiossa(a, b)) return true
        if (marginaali <= 0f) return false
        return etaisyysReunaan(a, b) <= marginaali
    }

    /** Distance from the plane point (a, b) to the polygon's edge (positive whether inside or out). */
    fun etaisyysReunaan(a: Float, b: Float): Float {
        var paras = Float.MAX_VALUE
        for (i in reuna.indices) {
            val (x1, y1) = reuna[i]
            val (x2, y2) = reuna[(i + 1) % reuna.size]
            val dx = x2 - x1
            val dy = y2 - y1
            val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-12f) 0f else (((a - x1) * dx + (b - y1) * dy) / l2).coerceIn(0f, 1f)
            val ex = x1 + dx * t - a
            val ey = y1 + dy * t - b
            val d = kotlin.math.sqrt(ex * ex + ey * ey)
            if (d < paras) paras = d
        }
        return paras
    }

    /**
     * The point on the polygon's edge nearest to [p], and the direction straight out over the edge there
     * (in the surface's plane, away from the surface).
     */
    fun lahinReuna(p: V3): Pair<V3, V3> {
        val (a, b) = tasoKoordinaatit(p)
        var paras = Float.MAX_VALUE
        var pa = 0f; var pb = 0f; var na = 1f; var nb = 0f
        for (i in reuna.indices) {
            val (x1, y1) = reuna[i]
            val (x2, y2) = reuna[(i + 1) % reuna.size]
            val dx = x2 - x1
            val dy = y2 - y1
            val l2 = dx * dx + dy * dy
            if (l2 < 1e-10f) continue
            val t = (((a - x1) * dx + (b - y1) * dy) / l2).coerceIn(0f, 1f)
            val ex = x1 + dx * t
            val ey = y1 + dy * t
            val d = (ex - a) * (ex - a) + (ey - b) * (ey - b)
            if (d < paras) {
                paras = d; pa = ex; pb = ey
                // Perpendicular to the edge, on the side that is outside the polygon
                val l = kotlin.math.sqrt(l2)
                na = dy / l; nb = -dx / l
                if (pisteMonikulmiossa(ex + na * 0.01f, ey + nb * 0.01f)) {
                    na = -na; nb = -nb
                }
            }
        }
        return pisteTasolta(pa, pb) to (u * na + v * nb)
    }

    private fun pisteMonikulmiossa(a: Float, b: Float): Boolean {
        var sisalla = false
        var j = reuna.size - 1
        for (i in reuna.indices) {
            val (xi, yi) = reuna[i]
            val (xj, yj) = reuna[j]
            if ((yi > b) != (yj > b) && a < (xj - xi) * (b - yi) / (yj - yi) + xi) sisalla = !sisalla
            j = i
        }
        return sisalla
    }

    /** A random point on the surface at least [reunasta] from its edge, or null if none was found. */
    fun satunnainenPiste(rnd: Random, reunasta: Float = 0f): V3? {
        if (reuna.isEmpty()) return null
        val minA = reuna.minOf { it.first }
        val maxA = reuna.maxOf { it.first }
        val minB = reuna.minOf { it.second }
        val maxB = reuna.maxOf { it.second }
        repeat(40) {
            val a = minA + rnd.nextFloat() * (maxA - minA)
            val b = minB + rnd.nextFloat() * (maxB - minB)
            if (pisteMonikulmiossa(a, b) && etaisyysReunaan(a, b) >= reunasta) return pisteTasolta(a, b)
        }
        return null
    }

    /** The point on the surface nearest to [p]'s projection, pulled [reunasta] inside the edge if possible. */
    fun lahinPiste(p: V3, reunasta: Float = 0f): V3 {
        val (a, b) = tasoKoordinaatit(p)
        if (pisteMonikulmiossa(a, b) && etaisyysReunaan(a, b) >= reunasta) return pisteTasolta(a, b)
        // Move towards the polygon's centroid until far enough inside
        val ca = reuna.sumOf { it.first.toDouble() }.toFloat() / reuna.size
        val cb = reuna.sumOf { it.second.toDouble() }.toFloat() / reuna.size
        var lo = 0f
        var hi = 1f
        repeat(16) {
            val t = (lo + hi) / 2f
            val x = a + (ca - a) * t
            val y = b + (cb - b) * t
            if (pisteMonikulmiossa(x, y) && etaisyysReunaan(x, y) >= reunasta) hi = t else lo = t
        }
        return pisteTasolta(a + (ca - a) * hi, b + (cb - b) * hi)
    }

    companion object {
        /** A rectangle centred on [keskus]. */
        fun suorakaide(id: String, laji: PinnanLaji, keskus: V3, normaali: V3, u: V3, leveys: Float, syvyys: Float): Pinta {
            val a = leveys / 2f
            val b = syvyys / 2f
            return Pinta(id, laji, keskus, normaali, u, listOf(-a to -b, a to -b, a to b, -a to b))
        }
    }
}

/** All the surfaces known so far. AR platforms replace them as tracking improves. */
class Maailma {
    var pinnat: List<Pinta> = emptyList()
        private set

    fun asetaPinnat(uudet: List<Pinta>) {
        pinnat = uudet
    }

    /** Dark spots the platform has measured from the camera image (points on or near the floor). */
    var varjot: List<V3> = emptyList()
    /** The interesting spots of the room, worked out from the surfaces by the game every now and then. */
    var paikat: List<Paikka> = emptyList()

    /** Floors and table tops. */
    val vaakapinnat: List<Pinta> get() = pinnat.filter { it.onVaaka && it.normaali.y > 0f }

    /** The biggest floor-like surface: the one the stick figures live on. */
    val lattia: Pinta?
        get() = pinnat.filter { it.laji == PinnanLaji.LATTIA }.maxByOrNull { it.ala }
            ?: vaakapinnat.maxByOrNull { it.ala }

    /** The highest upward surface under [p] (within [marginaali] of its edge), or null. */
    fun pintaAlla(p: V3, marginaali: Float = 0f): Pinta? =
        vaakapinnat.filter { it.etaisyys(p) >= -0.05f && it.sisalla(p, marginaali) }.maxByOrNull { it.keskus.y }
}
