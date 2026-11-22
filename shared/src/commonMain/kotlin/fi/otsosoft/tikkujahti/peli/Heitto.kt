package fi.otsosoft.tikkujahti.peli

import fi.otsosoft.tikkujahti.matikka.Kehys
import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.matikka.clamp
import fi.otsosoft.tikkujahti.matikka.saturoi
import kotlin.math.sqrt

/**
 * The player's eye: the phone's camera in AR, the virtual camera in the browser room.
 *
 * @param tanPuoliFov tangent of half the vertical field of view
 * @param kuvasuhde width / height of the screen
 */
class Kamera(var paikka: V3, var kehys: Kehys, var tanPuoliFov: Float, var kuvasuhde: Float) {
    /** Direction into the world through a point on the screen: x and y from -1 to 1, y up. */
    fun sade(x: Float, y: Float): V3 =
        (kehys.eteen + kehys.oikealle * (x * tanPuoliFov * kuvasuhde) + kehys.ylos * (y * tanPuoliFov)).suunta()
}

/**
 * A throw read from a swipe.
 *
 * @param x where on the screen the throw aims, -1…1 (y up)
 * @param voima 0 a gentle lob … 1 a fast straight throw
 * @param kaarre -1 curves left … 1 curves right
 */
class Heitto(val x: Float, val y: Float, val voima: Float, val kaarre: Float)

/** Collects the points of a finger swipe and turns the swipe into a [Heitto] when the finger lifts. */
class Pyyhkaisy {
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private val ts = ArrayList<Float>()

    val kaynnissa: Boolean get() = xs.isNotEmpty()

    fun aloita(x: Float, y: Float, t: Float) {
        xs.clear(); ys.clear(); ts.clear()
        lisaa(x, y, t)
    }

    /** A touch point in screen coordinates -1…1 (y up) at time [t] in seconds. */
    fun lisaa(x: Float, y: Float, t: Float) {
        xs += x; ys += y; ts += t
    }

    fun peru() {
        xs.clear(); ys.clear(); ts.clear()
    }

    /** Ends the swipe. Returns null if it was not an upward flick. */
    fun lopeta(): Heitto? {
        val n = xs.size
        if (n < 2) {
            peru(); return null
        }
        // Speed over the last tenth of a second, so a slow drag that ends in a flick still counts
        val loppu = ts[n - 1]
        var i = n - 1
        while (i > 0 && loppu - ts[i - 1] <= 0.1f) i--
        if (i == n - 1) i = n - 2
        val dt = (loppu - ts[i]).coerceAtLeast(0.008f)
        val vx = (xs[n - 1] - xs[i]) / dt
        val vy = (ys[n - 1] - ys[i]) / dt
        val vauhti = sqrt(vx * vx + vy * vy)
        val nousu = ys[n - 1] - ys[0]

        // Curve: how far the path bulges sideways from the straight line between its ends
        val cx = xs[n - 1] - xs[0]
        val cy = ys[n - 1] - ys[0]
        val pituus = sqrt(cx * cx + cy * cy)
        var pullistuma = 0f
        if (pituus > 0.05f) {
            for (k in 1 until n - 1) {
                val d = ((xs[k] - xs[0]) * cy - (ys[k] - ys[0]) * cx) / pituus
                if (kotlin.math.abs(d) > kotlin.math.abs(pullistuma)) pullistuma = d
            }
        }
        val heitto = if (nousu > 0.06f && vy > 0.4f) {
            Heitto(
                x = clamp(xs[n - 1] + vx * 0.07f, -1f, 1f),
                y = clamp(ys[n - 1] + vy * 0.07f, -0.9f, 1f),
                voima = saturoi((vauhti - 1.2f) / 7f),
                // A path that bulges right is thrown out to the right and curves back left
                kaarre = clamp(-pullistuma / pituus.coerceAtLeast(0.2f) * 5f, -1f, 1f),
            )
        } else null
        peru()
        return heitto
    }
}

/** Squared distance between the segments a0–a1 and b0–b1, and how far along a0–a1 the closest point is. */
fun janojenEtaisyys(a0: V3, a1: V3, b0: V3, b1: V3): Pair<Float, Float> {
    val d1 = a1 - a0
    val d2 = b1 - b0
    val r = a0 - b0
    val a = d1 dot d1
    val e = d2 dot d2
    val f = d2 dot r
    var s: Float
    var t: Float
    if (a <= 1e-12f && e <= 1e-12f) {
        return (r dot r) to 0f
    }
    if (a <= 1e-12f) {
        s = 0f
        t = saturoi(f / e)
    } else {
        val c = d1 dot r
        if (e <= 1e-12f) {
            t = 0f
            s = saturoi(-c / a)
        } else {
            val b = d1 dot d2
            val nimittaja = a * e - b * b
            s = if (nimittaja > 1e-12f) saturoi((b * f - c * e) / nimittaja) else 0f
            t = (b * s + f) / e
            if (t < 0f) {
                t = 0f; s = saturoi(-c / a)
            } else if (t > 1f) {
                t = 1f; s = saturoi((b - c) / a)
            }
        }
    }
    val ero = (a0 + d1 * s) - (b0 + d2 * t)
    return (ero dot ero) to s
}
