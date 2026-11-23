package fi.otsosoft.tikkujahti

import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import fi.otsosoft.tikkujahti.maailma.Tunnistin
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import fi.otsosoft.tikkujahti.maailma.Ankkuri
import fi.otsosoft.tikkujahti.maailma.Ankkurit
import fi.otsosoft.tikkujahti.maailma.Muunnos
import fi.otsosoft.tikkujahti.maailma.PinnanLaji
import fi.otsosoft.tikkujahti.maailma.Pinta
import java.nio.ByteOrder
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
    fun pinnat(session: Session, kamera: V3, lattiaArvio: Float?): Pair<List<Pinta>, Plane?> {
        val tasot = session.getAllTrackables(Plane::class.java).filter {
            it.trackingState == TrackingState.TRACKING && it.subsumedBy == null && it.polygon.remaining() >= 6
        }
        val ylos = tasot.filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
        val alin = ylos.filter { ala(it) >= LATTIAN_VAHIMMAISALA }.minOfOrNull { it.centerPose.ty() }
        val lattia = if (alin == null) null else ylos.filter { it.centerPose.ty() < alin + 0.2f }.maxByOrNull { ala(it) }
        // Tables and ceilings by their height above the floor: a second piece of floor ARCore has not yet joined
        // to the first is floor, not a table, and the underside of a table is no ceiling
        val lattiaY = lattia?.centerPose?.ty() ?: lattiaArvio
        val pinnat = tasot.mapNotNull { t ->
            val y = t.centerPose.ty()
            val laji = when {
                t === lattia -> PinnanLaji.LATTIA
                t.type == Plane.Type.HORIZONTAL_UPWARD_FACING -> Tunnistin.luokittele(V3.Y, y, lattiaY) ?: PinnanLaji.LATTIA
                t.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING -> Tunnistin.luokittele(-V3.Y, y, lattiaY) ?: return@mapNotNull null
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

    private var edellinenSyvyys = 0L
    private var alkuAika = 0L
    private var ruudukko = FloatArray(0)
    private val malli = FloatArray(16)

    /** Grid columns the depth image is sampled into for the recogniser. */
    private const val SARAKKEITA = 48

    /** Raw depth below this confidence (0–255) is left out. */
    private const val LUOTTAMUS = 100

    /**
     * Hands the newest depth image to the recogniser as a grid of world points, a few times a second. Raw depth
     * (with its confidence) is preferred: it is sparse but measured, where the smoothed depth also fills in
     * guesses. Returns the floor height the recogniser saw in it, if any.
     */
    fun syvyys(frame: Frame, kamera: Kamera, tunnistin: Tunnistin): Float? {
        var luotettavuus: android.media.Image? = null
        val kuva = try {
            frame.acquireRawDepthImage16Bits().also {
                luotettavuus = try { frame.acquireRawDepthConfidenceImage() } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            try {
                frame.acquireDepthImage16Bits()
            } catch (e2: Exception) {
                return null // no depth on this phone, or not yet
            }
        }
        try {
            val t = kuva.timestamp
            if (alkuAika == 0L) alkuAika = t
            if (t == edellinenSyvyys || t - edellinenSyvyys < 120_000_000L) return null
            edellinenSyvyys = t
            val w = kuva.width
            val h = kuva.height
            // The depth image shares the camera image's lens, scaled to its own size (ARCore Raw Depth codelab)
            val intr = frame.camera.textureIntrinsics
            val koko = intr.imageDimensions
            val fx = intr.focalLength[0] * w / koko[0]
            val fy = intr.focalLength[1] * h / koko[1]
            val cx = intr.principalPoint[0] * w / koko[0]
            val cy = intr.principalPoint[1] * h / koko[1]
            val askel = kotlin.math.max(1, (w + SARAKKEITA - 1) / SARAKKEITA)
            val gw = w / askel
            val gh = h / askel
            if (ruudukko.size != gw * gh * 3) ruudukko = FloatArray(gw * gh * 3)
            frame.camera.pose.toMatrix(malli, 0) // the sensor's pose: the depth image is in sensor orientation
            val taso = kuva.planes[0]
            val syv = taso.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val ltaso = luotettavuus?.planes?.get(0)
            val lpuskuri = ltaso?.buffer
            for (gy in 0 until gh) for (gx in 0 until gw) {
                val x = gx * askel + askel / 2
                val y = gy * askel + askel / 2
                val o = (gy * gw + gx) * 3
                val mm = syv.getShort(y * taso.rowStride + x * taso.pixelStride).toInt() and 0xffff
                val luottaa = lpuskuri == null || (lpuskuri.get(y * ltaso.rowStride + x * ltaso.pixelStride).toInt() and 0xff) >= LUOTTAMUS
                if (mm == 0 || !luottaa) {
                    ruudukko[o] = Float.NaN; ruudukko[o + 1] = Float.NaN; ruudukko[o + 2] = Float.NaN
                    continue
                }
                val d = mm / 1000f
                // Camera space: x right, y up, looking down -z
                val px = d * (x - cx) / fx
                val py = d * (cy - y) / fy
                val pz = -d
                ruudukko[o] = malli[0] * px + malli[4] * py + malli[8] * pz + malli[12]
                ruudukko[o + 1] = malli[1] * px + malli[5] * py + malli[9] * pz + malli[13]
                ruudukko[o + 2] = malli[2] * px + malli[6] * py + malli[10] * pz + malli[14]
            }
            return tunnistin.syvyys((t - alkuAika) / 1e9f, kamera.paikka, kamera.kehys.eteen, ruudukko, gw, gh)
        } catch (e: Exception) {
            return null
        } finally {
            kuva.close()
            luotettavuus?.close()
        }
    }

    /** ARCore anchors for the recogniser: its surfaces move along when tracking corrects itself. */
    fun ankkurit(session: Session) = Ankkurit { p ->
        try {
            val a = session.createAnchor(Pose.makeTranslation(p.x, p.y, p.z))
            object : Ankkuri {
                private var viimeisin: Muunnos? = null
                override val muunnos: Muunnos?
                    get() {
                        if (a.trackingState == TrackingState.TRACKING) {
                            val q = a.pose
                            viimeisin = Muunnos(V3(q.tx(), q.ty(), q.tz()), akseli(q.xAxis), akseli(q.yAxis), akseli(q.zAxis))
                        }
                        return viimeisin
                    }

                override fun irrota() = a.detach()
            }
        } catch (e: Exception) {
            null // e.g. not tracking just now
        }
    }

    private fun akseli(a: FloatArray) = V3(a[0], a[1], a[2])

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
