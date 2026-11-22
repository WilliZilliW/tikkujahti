package fi.otsosoft.tikkujahti.web

import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.mesh.PalloVerkko
import fi.otsosoft.tikkujahti.mesh.UkkoVerkko
import fi.otsosoft.tikkujahti.mesh.Verkko
import fi.otsosoft.tikkujahti.ukko.Ukko
import fi.otsosoft.tikkujahti.ukko.Ulkonako
import fi.otsosoft.tikkujahti.ukko.Vari
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/** A JavaScript object literal. */
fun olio(alusta: dynamic.() -> Unit): dynamic {
    val o = js("({})")
    alusta(o)
    return o
}

fun vari(v: Vari) = Color().setRGB(v.r.toDouble(), v.g.toDouble(), v.b.toDouble(), "srgb")

fun materiaali(v: Vari, karheus: Double): MeshStandardMaterial =
    MeshStandardMaterial(olio { color = vari(v); roughness = karheus; metalness = 0.0 })

/** Geometry that shares its arrays with a [Verkko]: one group per part. */
class VerkkoGeometria(private val verkko: Verkko, muuttuva: Boolean) {
    val geometria = BufferGeometry()
    // Kotlin's FloatArray is a Float32Array, so three.js reads the mesh's own arrays without copying
    private val paikat = BufferAttribute(verkko.paikat, 3)
    private val normaalit = BufferAttribute(verkko.normaalit, 3)

    init {
        if (muuttuva) {
            paikat.setUsage(DynamicDrawUsage)
            normaalit.setUsage(DynamicDrawUsage)
        }
        geometria.setAttribute("position", paikat)
        geometria.setAttribute("normal", normaalit)
        val ind = verkko.indeksit
        geometria.setIndex(BufferAttribute(js("new Uint16Array(ind)"), 1))
        for (i in 0 until verkko.osia) geometria.addGroup(verkko.osaAlku[i], verkko.osaMaara[i], i)
    }

    fun paivitetty() {
        paikat.needsUpdate = true
        normaalit.needsUpdate = true
    }
}

/** Draws one stick figure: its mesh is rebuilt from the pose every frame. */
class UkkoPiirto(val ukko: Ukko) {
    private val verkko = UkkoVerkko(ukko.m)
    private val geo = VerkkoGeometria(verkko, true)
    private val keho = materiaali(ukko.ulkonako.vari, 0.55)
    val mesh = Mesh(
        geo.geometria,
        arrayOf(
            keho,
            materiaali(Ulkonako.SILMAVALKO, 0.25),
            materiaali(Ulkonako.TUMMA, 0.3),
            materiaali(Ulkonako.SUU, 0.6),
            materiaali(Ulkonako.KIELI, 0.45),
        ),
    )

    init {
        mesh.castShadow = true
        mesh.frustumCulled = false // the vertices are already in world space and move every frame
    }

    fun asetaVari(v: Vari) {
        keho.color.setRGB(v.r.toDouble(), v.g.toDouble(), v.b.toDouble(), "srgb")
    }

    fun paivita() {
        mesh.visible = ukko.asento.nakyvissa
        if (!mesh.visible) return
        verkko.paivita(ukko.asento)
        geo.paivitetty()
    }

    fun vapauta() {
        geo.geometria.dispose()
    }
}

/** Draws a ball. All balls share one geometry. */
class PalloPiirto(a: Vari, b: Vari) {
    val mesh = Mesh(yhteinen.geometria, arrayOf(materiaali(a, 0.4), materiaali(b, 0.4)))

    init {
        mesh.castShadow = true
    }

    fun aseta(p: V3, sade: Float, x: V3, y: V3, z: V3) {
        mesh.position.set(p.x.toDouble(), p.y.toDouble(), p.z.toDouble())
        mesh.scale.set(sade.toDouble(), sade.toDouble(), sade.toDouble())
        val m = apu.elements
        m[0] = x.x.toDouble(); m[1] = x.y.toDouble(); m[2] = x.z.toDouble(); m[3] = 0.0
        m[4] = y.x.toDouble(); m[5] = y.y.toDouble(); m[6] = y.z.toDouble(); m[7] = 0.0
        m[8] = z.x.toDouble(); m[9] = z.y.toDouble(); m[10] = z.z.toDouble(); m[11] = 0.0
        m[12] = 0.0; m[13] = 0.0; m[14] = 0.0; m[15] = 1.0
        mesh.quaternion.setFromRotationMatrix(apu)
    }

    companion object {
        private val yhteinen by lazy { VerkkoGeometria(PalloVerkko(), false) }
        private val apu = Matrix4()
    }
}

/** The renderer, a scene with soft daylight and a shadow, and a camera, filling [isanta]. */
class Nakyma(private val isanta: HTMLElement, varjoAlue: Double) {
    val renderoija = WebGLRenderer(olio { antialias = true; alpha = true })
    val scene = Scene()
    val kamera = PerspectiveCamera(50.0, 1.0, 0.02, 50.0)
    val taivas = HemisphereLight(0xffffff, 0x9a938a, 1.5)
    val aurinko = DirectionalLight(0xfff4e0, 2.4)

    init {
        renderoija.setPixelRatio(minOf(window.devicePixelRatio, 2.0))
        renderoija.shadowMap.enabled = true
        isanta.appendChild(renderoija.domElement)
        scene.add(taivas)
        aurinko.position.set(0.7, 2.2, 1.1)
        aurinko.castShadow = true
        val v = aurinko.shadow
        v.mapSize.set(2048, 2048)
        v.camera.left = -varjoAlue; v.camera.right = varjoAlue
        v.camera.top = varjoAlue; v.camera.bottom = -varjoAlue
        v.camera.near = 0.1; v.camera.far = 8.0
        v.bias = -0.0004
        v.normalBias = 0.002
        v.radius = 4
        scene.add(aurinko)
        scene.add(aurinko.target)
        scene.add(kamera)
        window.addEventListener("resize", { sovita() })
        sovita()
    }

    val leveys: Double get() = isanta.clientWidth.toDouble().coerceAtLeast(1.0)
    val korkeus: Double get() = isanta.clientHeight.toDouble().coerceAtLeast(1.0)

    fun sovita() {
        if (renderoija.xr.isPresenting == true) return
        renderoija.setSize(leveys, korkeus)
        kamera.aspect = leveys / korkeus
        kamera.updateProjectionMatrix()
    }

    fun piirra() = renderoija.render(scene, kamera)
}
