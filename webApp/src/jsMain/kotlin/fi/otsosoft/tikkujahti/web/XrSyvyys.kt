package fi.otsosoft.tikkujahti.web

import fi.otsosoft.tikkujahti.maailma.Syvyyskartta
import fi.otsosoft.tikkujahti.matikka.V3
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A copy of the browser's latest depth image (WebXR depth sensing, CPU), with the view it was taken from.
 *
 * Which way up the depth buffer is read is not settled: the specification measures normalised view coordinates
 * from the top left, but Chrome's own depth sample feeds them from the bottom up. A depth image read upside down
 * puts the near floor where the far floor is, and figures standing in the open vanish. So the direction is
 * learnt: while the player looks down at the floor the hit test has found, the depth the image gives at the top
 * and the bottom of the view is compared with where the floor really is, read both ways. Until one way has
 * clearly won, the depth is not used at all.
 */
class XrSyvyys : Syvyyskartta {
    private var w = 0
    private var h = 0
    private var metrit: dynamic = null
    private val muunnos = FloatArray(16)
    private val proj = FloatArray(16)
    private val nakyma = FloatArray(16)
    private var aika = -100f
    private var aanet = 0
    private val apu = FloatArray(9)
    private val muunna: dynamic = js("(function (lahde, kohde, k) { for (var i = 0; i < kohde.length; i++) kohde[i] = lahde[i] * k; })")

    /** Null until learnt: whether the depth buffer takes normalised view coordinates from the bottom up. */
    var alhaaltaYlos: Boolean? = null
        private set

    /** The copied view's projection matrix and camera pose matrix (column-major), element by element. */
    fun proj(i: Int) = proj[i]
    fun nakyma(i: Int) = nakyma[i]

    /** Where the camera was, and where it looked, when the copied image was taken. */
    val kamera: V3 get() = V3(nakyma[12], nakyma[13], nakyma[14])
    val eteen: V3 get() = V3(-nakyma[8], -nakyma[9], -nakyma[10])

    /** Copies the depth image [tieto] (an XRCPUDepthInformation in data format [muoto]) of the view [nakymaXr]. */
    fun kopioi(tieto: dynamic, muoto: String?, nakymaXr: dynamic, nyt: Float) {
        val ww = tieto.width as Int
        val hh = tieto.height as Int
        if (ww != w || hh != h || metrit == null) {
            w = ww
            h = hh
            metrit = js("new Float32Array(ww * hh)")
        }
        val data = tieto.data
        val lahde: dynamic = if (muoto == "float32") js("new Float32Array(data)") else js("new Uint16Array(data)")
        muunna(lahde, metrit, tieto.rawValueToMeters)
        val m = tieto.normDepthBufferFromNormView.matrix
        val p = nakymaXr.projectionMatrix
        val v = nakymaXr.transform.matrix
        for (i in 0 until 16) {
            muunnos[i] = (m[i] as Double).toFloat()
            proj[i] = (p[i] as Double).toFloat()
            nakyma[i] = (v[i] as Double).toFloat()
        }
        aika = nyt
    }

    /**
     * Depth from the camera's plane at view point ([u], [v]) (0…1, v from the top), metres, or NaN where nothing
     * was measured: the median of the 3 × 3 pixels there, so one wild pixel decides nothing.
     */
    fun tasoSyvyys(u: Float, v: Float, alhaalta: Boolean = alhaaltaYlos ?: false): Float {
        val luvut = metrit ?: return Float.NaN
        val vv = if (alhaalta) 1f - v else v
        val bu = muunnos[0] * u + muunnos[4] * vv + muunnos[12]
        val bv = muunnos[1] * u + muunnos[5] * vv + muunnos[13]
        val x = floor(bu * w).toInt()
        val y = floor(bv * h).toInt()
        if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1) return Float.NaN
        var n = 0
        for (j in -1..1) for (i in -1..1) {
            val arvo = (luvut[(y + j) * w + x + i] as Number).toFloat()
            if (arvo > 0f) apu[n++] = arvo
        }
        if (n < 5) return Float.NaN
        apu.sort(0, n)
        return apu[n / 2]
    }

    /** Learns which way up the image is read, from the floor height [lattiaY] the hit test found. */
    fun kalibroi(lattiaY: Float) {
        if (alhaaltaYlos != null || metrit == null || eteen.y > -0.45f) return // only while looking down at the floor
        var virheA = 0f
        var virheB = 0f
        var n = 0
        for (v in floatArrayOf(0.15f, 0.3f, 0.7f, 0.85f)) {
            val odotettu = lattianSyvyys(0.5f, v, lattiaY) ?: continue
            val a = tasoSyvyys(0.5f, v, false)
            val b = tasoSyvyys(0.5f, v, true)
            if (a.isNaN() || b.isNaN()) continue
            virheA += abs(a - odotettu)
            virheB += abs(b - odotettu)
            n++
        }
        if (n < 3) return
        if (virheA + 0.1f * n < virheB) aanet++ else if (virheB + 0.1f * n < virheA) aanet--
        if (aanet >= 6) alhaaltaYlos = false else if (aanet <= -6) alhaaltaYlos = true
    }

    /** Depth from the camera's plane at which the ray through view point (u, v from the top) meets the floor. */
    private fun lattianSyvyys(u: Float, v: Float, lattiaY: Float): Float? {
        val x = (2f * u - 1f + proj[8]) / proj[0]
        val y = (1f - 2f * v + proj[9]) / proj[5]
        val alas = nakyma[1] * x + nakyma[5] * y - nakyma[9] // world y of the ray (x, y, -1)
        if (alas > -0.05f) return null
        val s = (lattiaY - nakyma[13]) / alas
        return if (s in 0.4f..3.5f) s else null
    }

    /** This image as the game's depth map, once its direction is known and while it is fresh. */
    fun kartta(nyt: Float): Syvyyskartta? = if (alhaaltaYlos != null && metrit != null && nyt - aika < 1f) this else null

    override fun etaisyys(p: V3): Float? {
        // Into the view's space: the camera pose's rotation transposed
        val dx = p.x - nakyma[12]
        val dy = p.y - nakyma[13]
        val dz = p.z - nakyma[14]
        val x = nakyma[0] * dx + nakyma[1] * dy + nakyma[2] * dz
        val y = nakyma[4] * dx + nakyma[5] * dy + nakyma[6] * dz
        val z = nakyma[8] * dx + nakyma[9] * dy + nakyma[10] * dz
        if (z > -0.1f) return null
        val d = -z
        val ndcX = (proj[0] * x + proj[8] * z) / d
        val ndcY = (proj[5] * y + proj[9] * z) / d
        if (abs(ndcX) > 1f || abs(ndcY) > 1f) return null
        val s = tasoSyvyys((ndcX + 1f) / 2f, (1f - ndcY) / 2f)
        if (s.isNaN()) return null
        // Depth is along the camera's axis; the distance along the ray is longer off the axis
        return s * sqrt(x * x + y * y + z * z) / d
    }

    /** Forgets the image and what was learnt (a new AR session). */
    fun nollaa() {
        metrit = null
        alhaaltaYlos = null
        aanet = 0
        aika = -100f
    }
}
