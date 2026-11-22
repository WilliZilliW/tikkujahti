package fi.otsosoft.tikkujahti.matikka

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A 3D vector in metres. The world is y-up, as in ARCore, WebXR and three.js. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    operator fun div(s: Float) = V3(x / s, y / s, z / s)
    operator fun unaryMinus() = V3(-x, -y, -z)

    infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    val pituus: Float get() = sqrt(x * x + y * y + z * z)
    val pituus2: Float get() = x * x + y * y + z * z

    /** Unit vector in the same direction, or [varalla] for a (near) zero vector. */
    fun suunta(varalla: V3 = Y): V3 {
        val l = pituus
        return if (l < 1e-7f) varalla else this / l
    }

    /** The part of this vector perpendicular to the unit vector [n]. */
    fun tasolle(n: V3) = this - n * (this dot n)

    fun vaakaan() = V3(x, 0f, z)

    fun etaisyys(o: V3) = (this - o).pituus

    companion object {
        val NOLLA = V3(0f, 0f, 0f)
        val X = V3(1f, 0f, 0f)
        val Y = V3(0f, 1f, 0f)
        val Z = V3(0f, 0f, 1f)

        /** Horizontal unit vector for a heading angle: 0 points to +z, positive turns towards +x. */
        fun suunnasta(kulma: Float) = V3(sin(kulma), 0f, cos(kulma))
    }
}

operator fun Float.times(v: V3) = v * this

fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
fun lerp(a: V3, b: V3, t: Float) = a + (b - a) * t
fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
fun saturoi(v: Float) = clamp(v, 0f, 1f)

/** Smooth 0→1 ease (smoothstep). */
fun pehmea(t: Float): Float {
    val x = saturoi(t)
    return x * x * (3f - 2f * x)
}

/** 0→1→0 bump over t∈[0,1]. */
fun kumpu(t: Float) = sin(PI.toFloat() * saturoi(t))

/** Heading angle of a horizontal direction (inverse of [V3.suunnasta]). */
fun suuntakulma(v: V3) = atan2(v.x, v.z)

/** Shortest signed difference b - a between two angles, in (-π, π]. */
fun kulmaero(a: Float, b: Float): Float {
    var d = (b - a) % (2f * PI.toFloat())
    if (d > PI) d -= 2f * PI.toFloat()
    if (d <= -PI) d += 2f * PI.toFloat()
    return d
}

/** Rotates [v] around the unit axis [akseli] by [kulma] radians (Rodrigues). */
fun kierra(v: V3, akseli: V3, kulma: Float): V3 {
    val c = cos(kulma)
    val s = sin(kulma)
    return v * c + (akseli cross v) * s + akseli * ((akseli dot v) * (1f - c))
}

/** Angle between two unit vectors. */
fun kulmaValilla(a: V3, b: V3) = acos(clamp(a dot b, -1f, 1f))

/** Any unit vector perpendicular to the unit vector [n]. */
fun kohtisuora(n: V3): V3 = (if (abs(n.y) < 0.9f) n cross V3.Y else n cross V3.X).suunta()

/** An orthonormal right-handed frame: [eteen] (forward), [ylos] (up) and [oikealle] (right = forward × up). */
data class Kehys(val eteen: V3, val ylos: V3, val oikealle: V3) {
    /** World direction of a vector given in this frame (x = right, y = up, z = forward). */
    fun maailmaan(x: Float, y: Float, z: Float) = oikealle * x + ylos * y + eteen * z

    companion object {
        /** Frame looking along [eteen] with [ylosVihje] as the approximate up. */
        fun luo(eteen: V3, ylosVihje: V3 = V3.Y): Kehys {
            val f = eteen.suunta(V3.Z)
            var r = f cross ylosVihje
            if (r.pituus2 < 1e-8f) r = kohtisuora(f)
            r = r.suunta()
            val u = r cross f
            return Kehys(f, u, r)
        }

        /** Frame whose up is exactly [ylos], facing as close to [eteenVihje] as possible. */
        fun ylosta(ylos: V3, eteenVihje: V3): Kehys {
            val u = ylos.suunta()
            var r = eteenVihje cross u
            if (r.pituus2 < 1e-8f) r = kohtisuora(u)
            r = r.suunta()
            return Kehys(u cross r, u, r)
        }
    }
}
