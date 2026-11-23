package fi.otsosoft.tikkujahti.web

import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.mesh.PalloVerkko
import fi.otsosoft.tikkujahti.mesh.UkkoVerkko
import fi.otsosoft.tikkujahti.mesh.Verkko
import fi.otsosoft.tikkujahti.matikka.pehmea
import fi.otsosoft.tikkujahti.peli.Aarre
import fi.otsosoft.tikkujahti.peli.Albumi
import fi.otsosoft.tikkujahti.peli.PalloLaji
import fi.otsosoft.tikkujahti.peli.Reppu
import fi.otsosoft.tikkujahti.peli.Tehoste
import kotlin.math.cos
import kotlin.math.sin
import fi.otsosoft.tikkujahti.peli.Pallo
import fi.otsosoft.tikkujahti.peli.Peli
import fi.otsosoft.tikkujahti.ukko.Tyyppi
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
    private val tyyppi = ukko.ulkonako.tyyppi
    private val verkko = UkkoVerkko(ukko.m, tyyppi)
    private val geo = VerkkoGeometria(verkko, true)
    // The golden figure gleams: smoother, and it glows a little so it stands out in any light
    private val keho = if (tyyppi == Tyyppi.KULTAINEN) {
        MeshStandardMaterial(olio { color = vari(ukko.ulkonako.vari); roughness = 0.22; metalness = 0.25; emissive = vari(ukko.ulkonako.vari); emissiveIntensity = 0.22 })
    } else materiaali(ukko.ulkonako.vari, 0.55)
    private val varuste = materiaali(Ulkonako.varusteVari(tyyppi), 0.5)
    val mesh = Mesh(
        geo.geometria,
        arrayOf(
            keho,
            materiaali(Ulkonako.SILMAVALKO, 0.25),
            materiaali(Ulkonako.TUMMA, 0.3),
            materiaali(Ulkonako.SUU, 0.6),
            materiaali(Ulkonako.KIELI, 0.45),
            varuste,
        ),
    )

    init {
        mesh.castShadow = true
        mesh.frustumCulled = false // the vertices are already in world space and move every frame
    }

    fun paivita() {
        val a = ukko.asento
        // Behind something real (Peli.paivitaPeitot): not drawn
        mesh.visible = a.nakyvissa && !ukko.peitossa
        if (!mesh.visible) return
        // An invisible figure shows only its face
        keho.visible = !a.haamu
        varuste.visible = !a.haamu
        mesh.castShadow = !a.haamu
        verkko.paivita(a)
        geo.paivitetty()
    }

    fun vapauta() {
        geo.geometria.dispose()
    }
}

/** Draws a ball of some kind. All balls share one geometry. */
class PalloPiirto(laji: PalloLaji) {
    private val a = materiaali(laji.a, 0.4)
    private val b = materiaali(laji.b, 0.4)
    val mesh = Mesh(yhteinen.geometria, arrayOf(a, b))
    var laji = laji
        set(l) {
            field = l
            a.color.setRGB(l.a.r.toDouble(), l.a.g.toDouble(), l.a.b.toDouble(), "srgb")
            b.color.setRGB(l.b.r.toDouble(), l.b.g.toDouble(), l.b.b.toDouble(), "srgb")
        }

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
        val yhteinen by lazy { VerkkoGeometria(PalloVerkko(), false) }
        private val apu = Matrix4()
    }
}

/** A see-through glowing sphere: the glitter round a ball lying in the room, the burst of a special ball. */
class Kupla(v: Vari) {
    private val aine = MeshBasicMaterial(olio { color = vari(v); transparent = true; opacity = 0.2; depthWrite = false })
    val mesh = Mesh(PalloPiirto.yhteinen.geometria, arrayOf(aine, aine))

    fun aseta(p: V3, sade: Float, nakyvyys: Float) {
        mesh.position.set(p.x.toDouble(), p.y.toDouble(), p.z.toDouble())
        mesh.scale.set(sade.toDouble(), sade.toDouble(), sade.toDouble())
        aine.opacity = nakyvyys.toDouble()
    }
}

/** Keeps the scene's figures, balls and effects in step with a game. */
class PeliPiirto(private val scene: Scene) {
    private val ukot = HashMap<Ukko, UkkoPiirto>()
    private val pallot = HashMap<Pallo, PalloPiirto>()
    private val aarteet = HashMap<Aarre, Pair<PalloPiirto, Kupla>>()
    private val tehosteet = HashMap<Tehoste, Kupla>()

    fun paivita(peli: Peli) {
        for (u in peli.ukot) ukot.getOrPut(u) { UkkoPiirto(u).also { scene.add(it.mesh) } }.paivita()
        val ukkoIt = ukot.entries.iterator()
        while (ukkoIt.hasNext()) {
            val e = ukkoIt.next()
            if (e.key !in peli.ukot) {
                scene.remove(e.value.mesh); e.value.vapauta(); ukkoIt.remove()
            }
        }
        for (b in peli.pallot) {
            pallot.getOrPut(b) { PalloPiirto(b.laji).also { scene.add(it.mesh) } }.aseta(b.p, b.sade * b.koko, b.x, b.y, b.z)
        }
        val palloIt = pallot.entries.iterator()
        while (palloIt.hasNext()) {
            val e = palloIt.next()
            if (e.key !in peli.pallot) {
                scene.remove(e.value.mesh); palloIt.remove()
            }
        }
        // Special balls lying about: bobbing, turning, with a pulsing glow so they catch the eye
        for (a in peli.aarteet) {
            val (pallo, hehku) = aarteet.getOrPut(a) {
                (PalloPiirto(a.laji) to Kupla(a.laji.a)).also { scene.add(it.first.mesh); scene.add(it.second.mesh) }
            }
            val esiin = pehmea(a.ika / 0.4f) * (1f - pehmea((a.ika - Peli.AARTEEN_IKA + 1f)))
            val p = a.paikka + V3.Y * (0.015f * sin(a.ika * 2.5f))
            val kulma = a.ika * 1.6f
            pallo.aseta(p, Pallo.SADE * 1.15f * esiin, V3(cos(kulma), 0f, -sin(kulma)), V3.Y, V3(sin(kulma), 0f, cos(kulma)))
            hehku.aseta(p, (0.055f + 0.012f * sin(a.ika * 5f)) * esiin, 0.22f + 0.1f * sin(a.ika * 5f))
        }
        val aarreIt = aarteet.entries.iterator()
        while (aarreIt.hasNext()) {
            val e = aarreIt.next()
            if (e.key !in peli.aarteet) {
                scene.remove(e.value.first.mesh); scene.remove(e.value.second.mesh); aarreIt.remove()
            }
        }
        for (t in peli.tehosteet) {
            tehosteet.getOrPut(t) { Kupla(t.vari).also { scene.add(it.mesh) } }.aseta(t.paikka, t.koko, 0.3f * t.nakyvyys)
        }
        val tehosteIt = tehosteet.entries.iterator()
        while (tehosteIt.hasNext()) {
            val e = tehosteIt.next()
            if (e.key !in peli.tehosteet) {
                scene.remove(e.value.mesh); tehosteIt.remove()
            }
        }
    }
}

/** The album saved in the browser, shared by the game and the lab. */
object Tallennus {
    private const val AVAIN = "tikkujahti.albumi"
    private const val REPPU = "tikkujahti.reppu"

    fun lueAlbumi(): Albumi = try {
        Albumi.lue(window.localStorage.getItem(AVAIN))
    } catch (e: Throwable) {
        Albumi() // storage can be blocked; then the album just lasts for this visit
    }

    fun lueReppu(): Reppu = try {
        Reppu.lue(window.localStorage.getItem(REPPU))
    } catch (e: Throwable) {
        Reppu()
    }

    fun tallenna(a: Albumi, r: Reppu) {
        try {
            window.localStorage.setItem(AVAIN, a.teksti())
            window.localStorage.setItem(REPPU, r.teksti())
        } catch (e: Throwable) {
        }
    }
}

/** The renderer, a scene with soft daylight and a shadow, and a camera, filling [isanta]. */
class Nakyma(private val isanta: HTMLElement, varjoAlue: Double) {
    val renderoija = WebGLRenderer(olio { antialias = true; alpha = true })
    val scene = Scene()
    val kamera = PerspectiveCamera(50.0, 1.0, 0.02, 50.0)
    val taivas = HemisphereLight(0xffffff, 0xa39c92, 1.35)
    val aurinko = DirectionalLight(0xfff4e0, 2.0)

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
