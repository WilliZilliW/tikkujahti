package fi.otsosoft.tikkujahti

import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import fi.otsosoft.tikkujahti.maailma.Tunnistin
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
    const val LATTIAN_VAHIMMAISALA = 0.3f

    /**
     * Every tracked plane as a [Pinta]. The floor is the biggest upward plane near the lowest one found,
     * so a table does not become the floor just because it was seen first. Returns the surfaces and the
     * ARCore plane chosen as the floor (or null while none is big enough).
     */
    fun pinnat(session: Session, kamera: V3): Pair<List<Pinta>, Plane?> {
        val tasot = session.getAllTrackables(Plane::class.java).filter {
            it.trackingState == TrackingState.TRACKING && it.subsumedBy == null && it.polygon.remaining() >= 6
        }
        val ylos = tasot.filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
        val alin = ylos.filter { ala(it) >= LATTIAN_VAHIMMAISALA }.minOfOrNull { it.centerPose.ty() }
        val lattia = if (alin == null) null else ylos.filter { it.centerPose.ty() < alin + 0.2f }.maxByOrNull { ala(it) }
        val pinnat = tasot.map { t ->
            val laji = when {
                t === lattia -> PinnanLaji.LATTIA
                t.type == Plane.Type.HORIZONTAL_UPWARD_FACING -> PinnanLaji.POYTA
                t.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING -> PinnanLaji.KATTO
                else -> PinnanLaji.SEINA
            }
            pinta("taso${t.hashCode()}", laji, t, kamera) // the same name every frame, so figures stay on their surface
        }
        return pinnat to lattia
    }

    private fun ala(t: Plane) = t.extentX * t.extentZ

    private fun pinta(id: String, laji: PinnanLaji, t: Plane, kamera: V3): Pinta {
        val pose = t.centerPose
        val x = pose.xAxis
        var y = pose.yAxis // the plane's normal
        // A surface that is seen faces the one who sees it: turn the normal if ARCore gives it the other way
        val kaannetty = y[0] * (kamera.x - pose.tx()) + y[1] * (kamera.y - pose.ty()) + y[2] * (kamera.z - pose.tz()) < 0f
        if (kaannetty) y = floatArrayOf(-y[0], -y[1], -y[2])
        // The polygon is (x, z) pairs in the plane's own frame. Pinta's second axis is normal × u = -z.
        val p = t.polygon
        val reuna = ArrayList<Pair<Float, Float>>(p.remaining() / 2)
        var i = p.position()
        while (i + 1 < p.limit()) {
            reuna += p.get(i) to (if (kaannetty) p.get(i + 1) else -p.get(i + 1))
            i += 2
        }
        return Pinta(id, laji, V3(pose.tx(), pose.ty(), pose.tz()), V3(y[0], y[1], y[2]), V3(x[0], x[1], x[2]), reuna)
    }

    /**
     * Probes the room through a 3×3 grid of points in the middle of the screen and hands the recogniser where
     * the rays hit. Only the positions are used: the recogniser works out which way the surface faces from
     * the points themselves, so a wrong orientation on a single hit cannot mislead it.
     */
    fun mittaa(frame: Frame, leveys: Int, korkeus: Int, kamera: V3, tunnistin: Tunnistin) {
        val p = arrayOfNulls<V3>(9)
        for (iy in 0..2) for (ix in 0..2) {
            val osuma = try {
                frame.hitTest(leveys * (0.5f + (ix - 1) * 0.1f), korkeus * (0.5f + (iy - 1) * 0.07f)).firstOrNull()
            } catch (e: Exception) {
                null
            } ?: continue
            val pose = osuma.hitPose
            p[iy * 3 + ix] = V3(pose.tx(), pose.ty(), pose.tz())
        }
        // Middle, left, right, up (smaller y on screen), down, then the corners
        tunnistin.katse(kamera, p[4], p[3], p[5], p[1], p[7], listOf(p[0], p[2], p[6], p[8]))
    }

    /**
     * Dark spots on the floor, measured from the camera image: the image is split into cells, and the ones
     * much darker than the whole are traced back into the room. Returns points on upward surfaces.
     */
    fun varjot(frame: Frame): List<V3> {
        val kuva = try {
            frame.acquireCameraImage()
        } catch (e: Exception) {
            return emptyList() // no image this frame
        }
        val tummat = ArrayList<FloatArray>()
        try {
            val taso = kuva.planes[0] // luminance
            val puskuri = taso.buffer
            val sarakkeita = 12
            val riveja = 9
            val arvot = FloatArray(sarakkeita * riveja)
            for (r in 0 until riveja) for (s in 0 until sarakkeita) {
                var summa = 0
                for (dy in 0..3) for (dx in 0..3) {
                    val x = ((s + (dx + 0.5f) / 4f) * kuva.width / sarakkeita).toInt()
                    val y = ((r + (dy + 0.5f) / 4f) * kuva.height / riveja).toInt()
                    summa += puskuri.get(y * taso.rowStride + x * taso.pixelStride).toInt() and 0xff
                }
                arvot[r * sarakkeita + s] = summa / 16f
            }
            val keski = arvot.average().toFloat()
            // The few darkest cells, if they really are dark next to the rest of the view
            for (i in arvot.indices.sortedBy { arvot[it] }.take(3)) {
                if (arvot[i] > keski * 0.45f || arvot[i] > 70f) break
                tummat += floatArrayOf((i % sarakkeita + 0.5f) * kuva.width / sarakkeita, (i / sarakkeita + 0.5f) * kuva.height / riveja)
            }
        } catch (e: Exception) {
            return emptyList()
        } finally {
            kuva.close()
        }
        val ulos = ArrayList<V3>()
        val nakyma = FloatArray(2)
        for (px in tummat) {
            try {
                frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, px, Coordinates2d.VIEW, nakyma)
                val osuma = frame.hitTest(nakyma[0], nakyma[1]).firstOrNull { it.hitPose.yAxis[1] > 0.8f } ?: continue
                ulos += V3(osuma.hitPose.tx(), osuma.hitPose.ty(), osuma.hitPose.tz())
            } catch (e: Exception) {
            }
        }
        return ulos
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
