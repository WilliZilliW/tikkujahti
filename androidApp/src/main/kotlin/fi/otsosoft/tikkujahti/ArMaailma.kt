package fi.otsosoft.tikkujahti

import com.google.ar.core.Camera
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import fi.otsosoft.tikkujahti.maailma.PinnanLaji
import fi.otsosoft.tikkujahti.maailma.Pinta
import fi.otsosoft.tikkujahti.matikka.Kehys
import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.peli.Kamera

/** ARCore's view of the room turned into the game's own terms. */
object ArMaailma {
    /** A plane has to be at least this big (m²) before figures are let loose on it. */
    const val LATTIAN_VAHIMMAISALA = 0.5f

    /**
     * Every tracked plane as a [Pinta]. The floor is the biggest upward plane near the lowest one found,
     * so a table does not become the floor just because it was seen first. Returns the surfaces and the
     * ARCore plane chosen as the floor (or null while none is big enough).
     */
    fun pinnat(session: Session): Pair<List<Pinta>, Plane?> {
        val tasot = session.getAllTrackables(Plane::class.java).filter {
            it.trackingState == TrackingState.TRACKING && it.subsumedBy == null && it.polygon.remaining() >= 6
        }
        val ylos = tasot.filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
        val alin = ylos.filter { ala(it) >= LATTIAN_VAHIMMAISALA }.minOfOrNull { it.centerPose.ty() }
        val lattia = if (alin == null) null else ylos.filter { it.centerPose.ty() < alin + 0.2f }.maxByOrNull { ala(it) }
        val pinnat = tasot.mapIndexed { i, t ->
            val laji = when {
                t === lattia -> PinnanLaji.LATTIA
                t.type == Plane.Type.HORIZONTAL_UPWARD_FACING -> PinnanLaji.POYTA
                t.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING -> PinnanLaji.KATTO
                else -> PinnanLaji.SEINA
            }
            pinta("taso$i", laji, t)
        }
        return pinnat to lattia
    }

    private fun ala(t: Plane) = t.extentX * t.extentZ

    private fun pinta(id: String, laji: PinnanLaji, t: Plane): Pinta {
        val pose = t.centerPose
        val x = pose.xAxis
        val y = pose.yAxis // the plane's normal
        // The polygon is (x, z) pairs in the plane's own frame. Pinta's second axis is normal × u = -z.
        val p = t.polygon
        val reuna = ArrayList<Pair<Float, Float>>(p.remaining() / 2)
        var i = p.position()
        while (i + 1 < p.limit()) {
            reuna += p.get(i) to -p.get(i + 1)
            i += 2
        }
        return Pinta(id, laji, V3(pose.tx(), pose.ty(), pose.tz()), V3(y[0], y[1], y[2]), V3(x[0], x[1], x[2]), reuna)
    }

    private val projektio = FloatArray(16)

    /** Copies the phone camera's pose and lens into the game camera. Returns false while tracking is lost. */
    fun kamera(c: Camera, kamera: Kamera): Boolean {
        if (c.trackingState != TrackingState.TRACKING) return false
        // The display-oriented pose has x to the right and y up on the screen, whichever way the sensor sits
        val pose = c.displayOrientedPose
        val z = pose.zAxis
        val y = pose.yAxis
        kamera.paikka = V3(pose.tx(), pose.ty(), pose.tz())
        kamera.kehys = Kehys.luo(V3(-z[0], -z[1], -z[2]), V3(y[0], y[1], y[2]))
        c.getProjectionMatrix(projektio, 0, 0.05f, 30f)
        kamera.tanPuoliFov = 1f / projektio[5]
        kamera.kuvasuhde = projektio[5] / projektio[0]
        return true
    }
}
