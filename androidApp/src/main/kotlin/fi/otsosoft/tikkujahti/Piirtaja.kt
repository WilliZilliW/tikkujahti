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
import fi.otsosoft.tikkujahti.matikka.pehmea
import fi.otsosoft.tikkujahti.peli.Aarre
import fi.otsosoft.tikkujahti.peli.Pallo
import fi.otsosoft.tikkujahti.peli.PalloLaji
import fi.otsosoft.tikkujahti.peli.Tehoste
import kotlin.math.cos
import kotlin.math.sin
import fi.otsosoft.tikkujahti.peli.Peli
import fi.otsosoft.tikkujahti.ukko.Tyyppi
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

/** Draws the game's figures, balls and effects as Filament renderables added straight to the AR scene. */
class Piirtaja(private val engine: Engine, private val scene: Scene, private val materiaalit: MaterialLoader) {
    private class UkkoKuva(val verkko: UkkoVerkko, val puskurit: VerkkoPuskurit, val entiteetti: Int, val varjo: Int) {
        var haamu = false
    }

    private val ukot = HashMap<Ukko, UkkoKuva>()
    private val pallot = HashMap<Pallo, Int>()
    private val aarteet = HashMap<Aarre, Pair<Int, Int>>()
    private val tehosteet = HashMap<Tehoste, Int>()
    private val palloVerkko = PalloVerkko()
    private val palloPuskurit = VerkkoPuskurit(engine, palloVerkko, false)

    private fun aine(v: Vari, karheus: Float) = materiaalit.createColorInstance(v.compose(), 0f, karheus, 0.4f)

    private val valkoinen = aine(Ulkonako.SILMAVALKO, 0.25f)
    private val tumma = aine(Ulkonako.TUMMA, 0.3f)
    private val suu = aine(Ulkonako.SUU, 0.6f)
    private val kieli = aine(Ulkonako.KIELI, 0.45f)
    // Materials per kind of ball, made when first needed: the two colours, and a see-through glow
    private val palloAineet = HashMap<PalloLaji, Pair<MaterialInstance, MaterialInstance>>()
    private val hehkut = HashMap<Vari, MaterialInstance>()
    private var kasiLaji = PalloLaji.NORMI
    private var kasiPallo = luoPallo(PalloLaji.NORMI)
    private val muunnos = FloatArray(16)


    private fun palloAine(l: PalloLaji) = palloAineet.getOrPut(l) { aine(l.a, 0.4f) to aine(l.b, 0.4f) }
    private fun hehku(v: Vari) = hehkut.getOrPut(v) { materiaalit.createColorInstance(Color(v.r, v.g, v.b, 0.22f), 0f, 1f, 0f) }

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
        // Room for the head, and for a parachute above it
        val vara = u.m.paaSade * (if (u.ulkonako.tyyppi == Tyyppi.LASKUVARJO) 5f else 1.6f)
        val rm = engine.renderableManager
        rm.setAxisAlignedBoundingBox(
            rm.getInstance(k.entiteetti),
            Box((x0 + x1) / 2f, (y0 + y1) / 2f, (z0 + z1) / 2f, (x1 - x0) / 2f + vara, (y1 - y0) / 2f + vara, (z1 - z0) / 2f + vara),
        )
    }

    private fun luoUkko(u: Ukko): UkkoKuva {
        val tyyppi = u.ulkonako.tyyppi
        val verkko = UkkoVerkko(u.m, tyyppi)
        val puskurit = VerkkoPuskurit(engine, verkko, true)
        // The golden figure gleams
        // Colours are kept for the whole game and shared: a material instance destroyed while the game runs
        // let Filament hand its handle to something else, and SceneView's floor plane once showed up painted
        // in a caught figure's colour
        val keho = kehoAineet.getOrPut(u.ulkonako.vari to (tyyppi == Tyyppi.KULTAINEN)) {
            if (tyyppi == Tyyppi.KULTAINEN) materiaalit.createColorInstance(u.ulkonako.vari.compose(), 0.35f, 0.22f, 0.6f) else aine(u.ulkonako.vari, 0.55f)
        }
        val varuste = kehoAineet.getOrPut(Ulkonako.varusteVari(tyyppi) to false) { aine(Ulkonako.varusteVari(tyyppi), 0.5f) }
        val aineet = arrayOf(keho, valkoinen, tumma, suu, kieli, varuste)
        val e = EntityManager.get().create()
        val b = RenderableManager.Builder(verkko.osia).boundingBox(laatikko).culling(false).castShadows(true).receiveShadows(false)
        for (i in 0 until verkko.osia) {
            b.geometry(i, RenderableManager.PrimitiveType.TRIANGLES, puskurit.pisteet, puskurit.indeksit, verkko.osaAlku[i], verkko.osaMaara[i])
            b.material(i, aineet[i])
        }
        b.build(engine, e)
        scene.addEntity(e)
        return UkkoKuva(verkko, puskurit, e, luoVarjo())
    }

    private val kehoAineet = HashMap<Pair<Vari, Boolean>, MaterialInstance>()

    // A soft round shadow under each figure: three see-through discs, darker towards the middle
    private val varjoAine = materiaalit.createUnlitColorInstance(Color(0f, 0f, 0f, 0.16f))
    private val varjoPisteet: VertexBuffer
    private val varjoIndeksit: IndexBuffer

    init {
        val sektoreita = 20
        val renkaat = floatArrayOf(1f, 0.72f, 0.45f)
        val paikat = FloatArray(renkaat.size * (sektoreita + 1) * 3)
        val kolmiot = ShortArray(renkaat.size * sektoreita * 3)
        var p = 0
        var k = 0
        for ((r, sade) in renkaat.withIndex()) {
            val keski = r * (sektoreita + 1)
            paikat[p++] = 0f; paikat[p++] = r * 0.0005f; paikat[p++] = 0f
            for (i in 0 until sektoreita) {
                val a = i * 2.0 * Math.PI / sektoreita
                paikat[p++] = (cos(a) * sade).toFloat(); paikat[p++] = r * 0.0005f; paikat[p++] = (sin(a) * sade).toFloat()
            }
            for (i in 0 until sektoreita) {
                kolmiot[k++] = keski.toShort(); kolmiot[k++] = (keski + 1 + (i + 1) % sektoreita).toShort(); kolmiot[k++] = (keski + 1 + i).toShort()
            }
        }
        varjoPisteet = VertexBuffer.Builder().vertexCount(paikat.size / 3).bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .build(engine)
        varjoPisteet.setBufferAt(engine, 0, floatPuskuri(paikat.size).apply { put(paikat); flip() })
        varjoIndeksit = IndexBuffer.Builder().indexCount(kolmiot.size).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
        val ind = ByteBuffer.allocateDirect(kolmiot.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        ind.put(kolmiot); ind.flip()
        varjoIndeksit.setBuffer(engine, ind)
    }

    private fun luoVarjo(): Int {
        val e = EntityManager.get().create()
        RenderableManager.Builder(1).boundingBox(Box(0f, 0f, 0f, 1f, 0.01f, 1f)).culling(false).castShadows(false).receiveShadows(false)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, varjoPisteet, varjoIndeksit)
            .material(0, varjoAine)
            .build(engine, e)
        engine.transformManager.create(e)
        scene.addEntity(e)
        return e
    }

    /** Lays the shadow on the surface the figure stands on, under its hips, smaller the higher it is. */
    private fun asetaVarjo(k: UkkoKuva, u: Ukko, nakyy: Boolean) {
        val pinta = u.lattia
        val lantio = u.asento[fi.otsosoft.tikkujahti.ukko.Nivel.LANTIO]
        if (!nakyy || pinta == null || u.asento.haamu) {
            nayta(k.varjo, false)
            return
        }
        val n = pinta.normaali
        val korkeus = pinta.etaisyys(lantio)
        val kohta = lantio - n * korkeus + n * 0.004f
        val koko = u.m.lantioSeisten * 0.75f * (1f - (korkeus / (u.m.pituus * 3f)).coerceIn(0f, 0.7f))
        if (korkeus < -0.05f || korkeus > u.m.pituus * 3f) {
            nayta(k.varjo, false)
            return
        }
        nayta(k.varjo, true)
        val x = pinta.u
        val z = x cross n
        asetaPallo(k.varjo, kohta, koko, x, n, z)
    }

    /** An invisible figure shows only its face: the body and what it carries are drawn with no triangles. */
    private fun asetaHaamu(k: UkkoKuva, haamu: Boolean) {
        if (k.haamu == haamu) return
        k.haamu = haamu
        val rm = engine.renderableManager
        val i = rm.getInstance(k.entiteetti)
        for (osa in intArrayOf(UkkoVerkko.KEHO, UkkoVerkko.VARUSTE)) {
            rm.setGeometryAt(i, osa, RenderableManager.PrimitiveType.TRIANGLES, k.puskurit.pisteet, k.puskurit.indeksit, k.verkko.osaAlku[osa], if (haamu) 0 else k.verkko.osaMaara[osa])
        }
        rm.setCastShadows(i, !haamu)
    }

    private fun luoPallo(a: MaterialInstance, b: MaterialInstance, varjo: Boolean): Int {
        val e = EntityManager.get().create()
        val rb = RenderableManager.Builder(2).boundingBox(Box(0f, 0f, 0f, 1f, 1f, 1f)).culling(false).castShadows(varjo).receiveShadows(false)
        for (i in 0..1) {
            rb.geometry(i, RenderableManager.PrimitiveType.TRIANGLES, palloPuskurit.pisteet, palloPuskurit.indeksit, palloVerkko.osaAlku[i], palloVerkko.osaMaara[i])
            rb.material(i, if (i == 0) a else b)
        }
        rb.build(engine, e)
        engine.transformManager.create(e)
        scene.addEntity(e)
        return e
    }

    private fun luoPallo(l: PalloLaji) = palloAine(l).let { luoPallo(it.first, it.second, true) }
    private fun luoKupla(v: Vari) = hehku(v).let { luoPallo(it, it, false) }

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
            // Behind something real (Peli.paivitaPeitot): not drawn
            nayta(k.entiteetti, u.asento.nakyvissa && !u.peitossa)
            asetaVarjo(k, u, u.asento.nakyvissa && !u.peitossa)
            if (u.asento.nakyvissa) {
                asetaHaamu(k, u.asento.haamu)
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
                poista(e.value.varjo)
                ukkoIt.remove()
            }
        }
        for (b in peli.pallot) {
            asetaPallo(pallot.getOrPut(b) { luoPallo(b.laji) }, b.p, b.sade * b.koko, b.x, b.y, b.z)
        }
        val palloIt = pallot.entries.iterator()
        while (palloIt.hasNext()) {
            val e = palloIt.next()
            if (e.key !in peli.pallot) {
                poista(e.value)
                palloIt.remove()
            }
        }
        // Special balls lying about: bobbing, turning, with a pulsing glow so they catch the eye
        for (a in peli.aarteet) {
            val (pallo, kupla) = aarteet.getOrPut(a) { luoPallo(a.laji) to luoKupla(a.laji.a) }
            val esiin = pehmea(a.ika / 0.4f) * (1f - pehmea(a.ika - Peli.AARTEEN_IKA + 1f))
            val p = a.paikka + V3.Y * (0.015f * sin(a.ika * 2.5f))
            val kulma = a.ika * 1.6f
            asetaPallo(pallo, p, Pallo.SADE * 1.15f * esiin, V3(cos(kulma), 0f, -sin(kulma)), V3.Y, V3(sin(kulma), 0f, cos(kulma)))
            asetaPallo(kupla, p, (0.055f + 0.012f * sin(a.ika * 5f)) * esiin, V3.X, V3.Y, V3.Z)
        }
        val aarreIt = aarteet.entries.iterator()
        while (aarreIt.hasNext()) {
            val e = aarreIt.next()
            if (e.key !in peli.aarteet) {
                poista(e.value.first); poista(e.value.second)
                aarreIt.remove()
            }
        }
        for (t in peli.tehosteet) asetaPallo(tehosteet.getOrPut(t) { luoKupla(t.vari) }, t.paikka, t.koko, V3.X, V3.Y, V3.Z)
        val tehosteIt = tehosteet.entries.iterator()
        while (tehosteIt.hasNext()) {
            val e = tehosteIt.next()
            if (e.key !in peli.tehosteet) {
                poista(e.value)
                tehosteIt.remove()
            }
        }
        // The ball in the hand is the kind chosen for the next throw
        if (kasiLaji != peli.reppu.valittu) {
            poista(kasiPallo)
            kasiLaji = peli.reppu.valittu
            kasiPallo = luoPallo(kasiLaji)
        }
        nayta(kasiPallo, kasi != null)
        if (kasi != null) asetaPallo(kasiPallo, kasi, Pallo.SADE * kadenKoko, V3.X, V3.Y, V3.Z)
    }

    /** Frees everything. Call before the engine is destroyed. */
    fun tuhoa() {
        for (k in ukot.values) {
            poista(k.entiteetti); poista(k.varjo); k.puskurit.tuhoa()
        }
        ukot.clear()
        for (e in pallot.values + tehosteet.values + aarteet.values.flatMap { listOf(it.first, it.second) }) poista(e)
        pallot.clear(); tehosteet.clear(); aarteet.clear()
        poista(kasiPallo)
        palloPuskurit.tuhoa()

        engine.destroyVertexBuffer(varjoPisteet)
        engine.destroyIndexBuffer(varjoIndeksit)
        for (m in listOf(valkoinen, tumma, suu, kieli, varjoAine) + kehoAineet.values + hehkut.values + palloAineet.values.flatMap { listOf(it.first, it.second) }) engine.destroyMaterialInstance(m)
        kehoAineet.clear()
    }
}
