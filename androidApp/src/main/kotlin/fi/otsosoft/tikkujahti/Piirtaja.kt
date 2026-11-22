package fi.otsosoft.tikkujahti

import androidx.compose.ui.graphics.Color
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.VertexBuffer
import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.mesh.PalloVerkko
import fi.otsosoft.tikkujahti.mesh.UkkoVerkko
import fi.otsosoft.tikkujahti.mesh.Verkko
import fi.otsosoft.tikkujahti.peli.Pallo
import fi.otsosoft.tikkujahti.peli.Peli
import fi.otsosoft.tikkujahti.ukko.Ukko
import fi.otsosoft.tikkujahti.ukko.Ulkonako
import fi.otsosoft.tikkujahti.ukko.Vari
import io.github.sceneview.loaders.MaterialLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

private fun Vari.compose() = Color(r, g, b)

/** A direct buffer in native byte order holding [n] floats. */
private fun floatPuskuri(n: Int): FloatBuffer =
    ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

/**
 * A [Verkko] as Filament buffers. Filament wants tangent-frame quaternions instead of normals, and reads
 * the buffers we hand over on its own thread a little later, so updates rotate through a few buffers.
 */
private class VerkkoPuskurit(private val engine: Engine, private val verkko: Verkko, muuttuva: Boolean) {
    val pisteet: VertexBuffer = VertexBuffer.Builder()
        .vertexCount(verkko.pisteita)
        .bufferCount(2)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
        .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4, 0, 16)
        .build(engine)
    val indeksit: IndexBuffer = IndexBuffer.Builder()
        .indexCount(verkko.indeksit.size)
        .bufferType(IndexBuffer.Builder.IndexType.USHORT)
        .build(engine)

    private val kierros = if (muuttuva) 4 else 1
    private val paikat = Array(kierros) { floatPuskuri(verkko.pisteita * 3) }
    private val kehykset = Array(kierros) { floatPuskuri(verkko.pisteita * 4) }
    private val apu = FloatArray(verkko.pisteita * 4)
    private var vuoro = 0

    init {
        val ind = ByteBuffer.allocateDirect(verkko.indeksit.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (i in verkko.indeksit) ind.put(i.toShort())
        ind.flip()
        indeksit.setBuffer(engine, ind)
        laheta()
    }

    /** Sends the mesh's current positions and normals to Filament. */
    fun laheta() {
        vuoro = (vuoro + 1) % kierros
        val n = verkko.pisteita
        val p = paikat[vuoro]
        p.clear(); p.put(verkko.paikat, 0, n * 3); p.flip()
        // The quaternion that turns +z to the normal: enough of a tangent frame for untextured materials
        val nor = verkko.normaalit
        for (i in 0 until n) {
            val nx = nor[i * 3]
            val ny = nor[i * 3 + 1]
            val nz = nor[i * 3 + 2]
            val w = 1f + nz
            val o = i * 4
            if (w < 1e-4f) {
                apu[o] = 1f; apu[o + 1] = 0f; apu[o + 2] = 0f; apu[o + 3] = 0f
            } else {
                val l = 1f / sqrt(nx * nx + ny * ny + w * w)
                apu[o] = -ny * l; apu[o + 1] = nx * l; apu[o + 2] = 0f; apu[o + 3] = w * l
            }
        }
        val k = kehykset[vuoro]
        k.clear(); k.put(apu, 0, n * 4); k.flip()
        pisteet.setBufferAt(engine, 0, p)
        pisteet.setBufferAt(engine, 1, k)
    }

    fun tuhoa() {
        engine.destroyVertexBuffer(pisteet)
        engine.destroyIndexBuffer(indeksit)
    }
}

/** Draws the game's figures and balls as Filament renderables added straight to the AR scene. */
class Piirtaja(private val engine: Engine, private val scene: Scene, private val materiaalit: MaterialLoader) {
    private class UkkoKuva(val verkko: UkkoVerkko, val puskurit: VerkkoPuskurit, val entiteetti: Int, val keho: MaterialInstance)

    private val ukot = HashMap<Ukko, UkkoKuva>()
    private val pallot = HashMap<Pallo, Int>()
    private val palloPuskurit = VerkkoPuskurit(engine, PalloVerkko(), false)
    private val palloVerkko = PalloVerkko()

    private fun aine(v: Vari, karheus: Float) = materiaalit.createColorInstance(v.compose(), 0f, karheus, 0.4f)

    private val valkoinen = aine(Ulkonako.SILMAVALKO, 0.25f)
    private val tumma = aine(Ulkonako.TUMMA, 0.3f)
    private val suu = aine(Ulkonako.SUU, 0.6f)
    private val kieli = aine(Ulkonako.KIELI, 0.45f)
    private val palloA = aine(PALLO_A, 0.4f)
    private val palloB = aine(PALLO_B, 0.4f)
    private val kasiPallo = luoPallo()
    private val muunnos = FloatArray(16)

    // A figure's vertices are already in world space, so its bounding box is set from its joints every frame:
    // the shadow map is fitted to the casters' boxes, and a loose box would smear the shadows.
    private val laatikko = Box(0f, 0f, 0f, 0.3f, 0.3f, 0.3f)

    private fun rajaa(k: UkkoKuva, u: Ukko) {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (p in u.asento.nivelet) {
            if (p.x < x0) x0 = p.x; if (p.y < y0) y0 = p.y; if (p.z < z0) z0 = p.z
            if (p.x > x1) x1 = p.x; if (p.y > y1) y1 = p.y; if (p.z > z1) z1 = p.z
        }
        val vara = u.m.paaSade * 1.6f
        val rm = engine.renderableManager
        rm.setAxisAlignedBoundingBox(
            rm.getInstance(k.entiteetti),
            Box((x0 + x1) / 2f, (y0 + y1) / 2f, (z0 + z1) / 2f, (x1 - x0) / 2f + vara, (y1 - y0) / 2f + vara, (z1 - z0) / 2f + vara),
        )
    }

    private fun luoUkko(u: Ukko): UkkoKuva {
        val verkko = UkkoVerkko(u.m)
        val puskurit = VerkkoPuskurit(engine, verkko, true)
        val keho = aine(u.ulkonako.vari, 0.55f)
        val aineet = arrayOf(keho, valkoinen, tumma, suu, kieli)
        val e = EntityManager.get().create()
        val b = RenderableManager.Builder(verkko.osia).boundingBox(laatikko).culling(false).castShadows(true).receiveShadows(false)
        for (i in 0 until verkko.osia) {
            b.geometry(i, RenderableManager.PrimitiveType.TRIANGLES, puskurit.pisteet, puskurit.indeksit, verkko.osaAlku[i], verkko.osaMaara[i])
            b.material(i, aineet[i])
        }
        b.build(engine, e)
        scene.addEntity(e)
        return UkkoKuva(verkko, puskurit, e, keho)
    }

    private fun luoPallo(): Int {
        val e = EntityManager.get().create()
        val b = RenderableManager.Builder(2).boundingBox(Box(0f, 0f, 0f, 1f, 1f, 1f)).culling(false).castShadows(true).receiveShadows(false)
        for (i in 0..1) {
            b.geometry(i, RenderableManager.PrimitiveType.TRIANGLES, palloPuskurit.pisteet, palloPuskurit.indeksit, palloVerkko.osaAlku[i], palloVerkko.osaMaara[i])
            b.material(i, if (i == 0) palloA else palloB)
        }
        b.build(engine, e)
        engine.transformManager.create(e)
        scene.addEntity(e)
        return e
    }

    private fun asetaPallo(e: Int, p: V3, sade: Float, x: V3, y: V3, z: V3) {
        val m = muunnos
        m[0] = x.x * sade; m[1] = x.y * sade; m[2] = x.z * sade; m[3] = 0f
        m[4] = y.x * sade; m[5] = y.y * sade; m[6] = y.z * sade; m[7] = 0f
        m[8] = z.x * sade; m[9] = z.y * sade; m[10] = z.z * sade; m[11] = 0f
        m[12] = p.x; m[13] = p.y; m[14] = p.z; m[15] = 1f
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(e), m)
    }

    private fun nayta(e: Int, nakyy: Boolean) {
        // Layer bit 0 is the one the view draws
        val rm = engine.renderableManager
        rm.setLayerMask(rm.getInstance(e), 0xff, if (nakyy) 0xff else 0)
    }

    private fun poista(e: Int) {
        scene.removeEntities(intArrayOf(e))
        engine.destroyEntity(e)
        EntityManager.get().destroy(e)
    }

    /** Brings the scene up to date with the game. [kasi] is where the ball in the player's hand is, or null. */
    fun paivita(peli: Peli, kasi: V3?, kadenKoko: Float) {
        for (u in peli.ukot) {
            val k = ukot.getOrPut(u) { luoUkko(u) }
            nayta(k.entiteetti, u.asento.nakyvissa)
            if (u.asento.nakyvissa) {
                k.verkko.paivita(u.asento)
                k.puskurit.laheta()
                rajaa(k, u)
            }
        }
        val ukkoIt = ukot.entries.iterator()
        while (ukkoIt.hasNext()) {
            val e = ukkoIt.next()
            if (e.key !in peli.ukot) {
                poista(e.value.entiteetti)
                e.value.puskurit.tuhoa()
                engine.destroyMaterialInstance(e.value.keho)
                ukkoIt.remove()
            }
        }
        for (b in peli.pallot) {
            asetaPallo(pallot.getOrPut(b) { luoPallo() }, b.p, b.sade * b.koko, b.x, b.y, b.z)
        }
        val palloIt = pallot.entries.iterator()
        while (palloIt.hasNext()) {
            val e = palloIt.next()
            if (e.key !in peli.pallot) {
                poista(e.value)
                palloIt.remove()
            }
        }
        nayta(kasiPallo, kasi != null)
        if (kasi != null) asetaPallo(kasiPallo, kasi, Pallo.SADE * kadenKoko, V3.X, V3.Y, V3.Z)
    }

    /** Frees everything. Call before the engine is destroyed. */
    fun tuhoa() {
        for (k in ukot.values) {
            poista(k.entiteetti); k.puskurit.tuhoa(); engine.destroyMaterialInstance(k.keho)
        }
        ukot.clear()
        for (e in pallot.values) poista(e)
        pallot.clear()
        poista(kasiPallo)
        palloPuskurit.tuhoa()
        for (m in listOf(valkoinen, tumma, suu, kieli, palloA, palloB)) engine.destroyMaterialInstance(m)
    }

    companion object {
        val PALLO_A = Vari.hex(0xFFC93C)
        val PALLO_B = Vari.hex(0xFFF7E6)
    }
}
