package fi.otsosoft.tikkujahti.web

import fi.otsosoft.tikkujahti.maailma.Huone
import fi.otsosoft.tikkujahti.maailma.LattianHaku
import fi.otsosoft.tikkujahti.maailma.Tunnistin
import fi.otsosoft.tikkujahti.maailma.PinnanLaji
import fi.otsosoft.tikkujahti.maailma.Pinta
import fi.otsosoft.tikkujahti.matikka.Kehys
import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.matikka.pehmea
import fi.otsosoft.tikkujahti.peli.Aani
import fi.otsosoft.tikkujahti.peli.Albumi
import fi.otsosoft.tikkujahti.peli.Tapahtuma
import fi.otsosoft.tikkujahti.peli.Heitto
import fi.otsosoft.tikkujahti.peli.Kamera
import fi.otsosoft.tikkujahti.peli.Pallo
import fi.otsosoft.tikkujahti.peli.PalloLaji
import fi.otsosoft.tikkujahti.peli.Reppu
import fi.otsosoft.tikkujahti.ukko.Ulkonako
import fi.otsosoft.tikkujahti.ukko.Vari
import fi.otsosoft.tikkujahti.peli.Peli
import fi.otsosoft.tikkujahti.peli.Pyyhkaisy
import fi.otsosoft.tikkujahti.ukko.Tyyppi
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.url.URLSearchParams
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The game page. Two modes share everything but the camera and the floor:
 *  - the room: a virtual room on any device; drag to look around
 *  - AR (WebXR immersive-ar, Android Chrome): the phone's camera is the game camera, and the floor is
 *    found by itself ([LattianHaku])
 *
 * A swipe up from the ball throws it.
 */
class PeliSivu(private val juuri: HTMLElement) {
    private val nakyma = Nakyma(el("div", "nakyma", juuri), 3.3)
    private var peli = Peli(1, Tallennus.lueAlbumi(), Tallennus.lueReppu())
    private val aanet = Aanet()
    private val kamera = Kamera(V3(0f, 0.7f, 1.7f), Kehys.luo(-V3.Z), tan(25.0 * PI / 180).toFloat(), 1f)
    private val piirto = PeliPiirto(nakyma.scene)
    private var tallennettu = 0
    private val huone = Group()
    private val pyyhkaisy = Pyyhkaisy()

    // Looking around in the room
    private var suunta = 0.0
    private var kallistus = -0.28

    // The ball in the player's hand: where it is on the screen (-1…1), and when it comes back after a throw
    private val kasiPallo = PalloPiirto(PalloLaji.NORMI)
    private var kasiX = 0f
    private var kasiY = KASI_LEPO_Y
    private var kasiTauko = 0.0

    // AR
    private var ar: dynamic = null
    private val osumalahteet = ArrayList<dynamic>()
    private var tunnistin = Tunnistin()
    private var pintaAjastin = 0f
    private var seuraavaTaso = 1
    private val aariviivat = Group()
    private val viivat = ArrayList<LineLoop>()
    private val viivaAine = LineBasicMaterial(olio { color = 0xffffff; transparent = true; opacity = 0.35 })
    private var arLattia = false
    private var haku = LattianHaku()
    private val varjotaso = Mesh(PlaneGeometry(8.0, 8.0).rotateX(-PI / 2), ShadowMaterial(olio { opacity = 0.32 }))

    // HUD
    private val hud = el("div", "hud", juuri)
    private val kosketus = el("div", "kosketus", hud)
    private val laskuri = el("div", "laskuri", hud)
    private val putki = el("div", "putki", hud)
    private val vihje = el("div", "vihje", hud)
    private val ilmoitus = el("div", "ilmoitus", hud)
    private val nuolet = el("div", "nuolet", hud)
    private val pallorivi = el("div", "pallorivi", hud)
    private var ilmoitusAjastin = 0
    private val napit = el("div", "ylanapit", hud)
    private var arNappi: HTMLElement? = null
    private var edellinen = -1.0
    private var heittoja = 0

    init {
        rakennaHuone()
        nakyma.scene.add(huone)
        varjotaso.visible = false
        varjotaso.receiveShadow = true
        nakyma.scene.add(varjotaso)
        nakyma.scene.add(aariviivat)
        kasiPallo.mesh.castShadow = false
        nakyma.kamera.add(kasiPallo.mesh)
        peli.maailma.asetaPinnat(Huone.pinnat())
        peli.pyristely = true

        val p = URLSearchParams(window.location.search)
        if (p.get("kuva") != null) {
            kuvatila(p)
        } else {
            rakennaHud()
            ohjaus()
            nakyma.renderoija.setAnimationLoop { aika, ruutu -> ruutu(aika, ruutu) }
        }
    }

    // =====================================================================================================
    // Frame
    // =====================================================================================================

    private fun ruutu(aika: Double, xrRuutu: dynamic) {
        val dt = if (edellinen < 0) 0.016 else ((aika - edellinen) / 1000.0).coerceIn(0.0, 0.05)
        edellinen = aika
        if (ar != null) arKamera(xrRuutu, dt.toFloat()) else huoneKamera()
        paivita(dt.toFloat())
        for (t in peli.tapahtumat) {
            aanet.soita(t, kamera.paikka)
            t.teksti?.let { ilmoita(it) }
            if (t.laji == Aani.AANTELY || (t.laji == Aani.KIMALLUS && t.teksti == null)) vihjenuoli(t)
        }
        naytaPisteet()
        nakyma.piirra()
    }

    /**
     * Something out of view made a sound: an arrow flashes at the edge of the screen towards it. This is how
     * the player "hears" which way to turn when hunting about the house.
     */
    private fun vihjenuoli(t: Tapahtuma) {
        val (x, y, nakyvissa) = kamera.ruudulla(t.paikka)
        if (nakyvissa || nuolet.childElementCount >= 3) return
        val n = el("div", if (t.laji == Aani.KIMALLUS) "nuoli pallo" else "nuoli", nuolet)
        n.style.left = "${(x * 0.5 + 0.5) * 86 + 7}%"
        n.style.top = "${(0.5 - y * 0.5) * 86 + 7}%"
        n.style.transform = "translate(-50%, -50%) rotate(${kotlin.math.atan2(x.toDouble(), y.toDouble()) * 180 / PI}deg)"
        window.setTimeout({ n.asDynamic().remove() }, 1700)
    }

    /** A note that slides in for a few seconds: what was found, why a ball bounced off. */
    private fun ilmoita(teksti: String) {
        ilmoitus.textContent = teksti
        ilmoitus.classList.add("nakyy")
        window.clearTimeout(ilmoitusAjastin)
        ilmoitusAjastin = window.setTimeout({ ilmoitus.classList.remove("nakyy") }, 4200)
    }

    /** The row of balls to choose from: plain ones without end, and the special ones in the bag. */
    private fun naytaPallot() {
        pallorivi.innerHTML = ""
        val omat = PalloLaji.entries.filter { it == PalloLaji.NORMI || peli.reppu[it] > 0 }
        if (omat.size < 2) return
        for (l in omat) {
            val n = el("button", if (l == peli.reppu.valittu) "pallonappi valittu" else "pallonappi", pallorivi)
            n.setAttribute("aria-label", l.nimi)
            n.setAttribute("title", "${l.nimi}: ${l.kuvaus}")
            n.style.setProperty("--a", css(l.a)); n.style.setProperty("--b", css(l.b))
            if (l != PalloLaji.NORMI) el("span", "", n).textContent = "${peli.reppu[l]}"
            n.addEventListener("click", {
                peli.reppu.valitse(l)
                ilmoita("${l.nimi}: ${l.kuvaus}")
            })
            n.addEventListener("beforexrselect", { e: Event -> e.preventDefault() })
        }
    }

    private fun css(v: Vari) = "rgb(${(v.r * 255).toInt()},${(v.g * 255).toInt()},${(v.b * 255).toInt()})"

    private fun naytaPisteet() {
        laskuri.textContent = "${peli.pisteet}"
        // The streak badge shows what the next catch is worth
        val k = peli.kerroin
        putki.textContent = "×$k"
        if (k > 1) putki.classList.add("nakyy") else putki.classList.remove("nakyy")
    }

    private fun paivita(dt: Float) {
        peli.paivita(dt, kamera)

        piirto.paivita(peli)
        if (peli.albumi.muutoksia + peli.reppu.muutoksia != tallennettu) {
            tallennettu = peli.albumi.muutoksia + peli.reppu.muutoksia
            Tallennus.tallenna(peli.albumi, peli.reppu)
            naytaPallot()
        }
        if (kasiPallo.laji != peli.reppu.valittu) {
            kasiPallo.laji = peli.reppu.valittu
            naytaPallot()
        }

        // The ball in the hand: drawn in the camera's own space, so it stays put on the screen
        kasiTauko -= dt
        val nakyy = (ar == null || arLattia) && kasiTauko <= 0.0
        kasiPallo.mesh.visible = nakyy
        if (nakyy) {
            if (!pyyhkaisy.kaynnissa) {
                kasiX += (0f - kasiX) * minOf(1f, dt * 12f)
                kasiY += (KASI_LEPO_Y - kasiY) * minOf(1f, dt * 12f)
            }
            val koko = pehmea((-kasiTauko / 0.2).toFloat())
            val x = kasiX * kamera.tanPuoliFov * kamera.kuvasuhde * KASI_SYVYYS
            val y = kasiY * kamera.tanPuoliFov * KASI_SYVYYS
            kasiPallo.aseta(V3(x, y, -KASI_SYVYYS), Pallo.SADE * koko, V3.X, V3.Y, V3.Z)
        }
    }

    private fun kadenPaikka(): V3 =
        kamera.paikka + (kamera.kehys.eteen +
            kamera.kehys.oikealle * (kasiX * kamera.tanPuoliFov * kamera.kuvasuhde) +
            kamera.kehys.ylos * (kasiY * kamera.tanPuoliFov)) * KASI_SYVYYS

    private fun huoneKamera() {
        val eteen = V3((sin(suunta) * cos(kallistus)).toFloat(), sin(kallistus).toFloat(), (-cos(suunta) * cos(kallistus)).toFloat())
        kamera.kehys = Kehys.luo(eteen)
        // Narrow screens see less to the sides: widen the view a little in portrait
        val pysty = nakyma.korkeus > nakyma.leveys
        nakyma.kamera.fov = if (pysty) 56.0 else 45.0
        nakyma.kamera.updateProjectionMatrix()
        kamera.tanPuoliFov = tan(nakyma.kamera.fov / 2 * PI / 180).toFloat()
        kamera.kuvasuhde = (nakyma.leveys / nakyma.korkeus).toFloat()
        val p = kamera.paikka
        nakyma.kamera.position.set(p.x.toDouble(), p.y.toDouble(), p.z.toDouble())
        nakyma.kamera.lookAt((p.x + eteen.x).toDouble(), (p.y + eteen.y).toDouble(), (p.z + eteen.z).toDouble())
    }

    private fun heita(h: Heitto) {
        peli.heita(h, kamera, kadenPaikka())
        peli.tapahtumat.lastOrNull()?.let { aanet.soita(it, kamera.paikka) }
        kasiTauko = 0.45
        kasiX = 0f
        kasiY = KASI_LEPO_Y - 0.5f
        heittoja++
        if (heittoja == 2) vihje.classList.add("piilo")
    }

    // =====================================================================================================
    // Input
    // =====================================================================================================

    private fun ohjaus() {
        var heittoSormi = -1
        var katseSormi = -1
        var edX = 0.0
        var edY = 0.0
        var alkuX = 0.0
        var alkuY = 0.0
        fun nx(e: dynamic) = ((e.clientX as Double) / window.innerWidth * 2 - 1).toFloat()
        fun ny(e: dynamic) = (1 - (e.clientY as Double) / window.innerHeight * 2).toFloat()
        fun aika(e: dynamic) = ((e.timeStamp as Double) / 1000.0).toFloat()

        kosketus.addEventListener("pointerdown", { ev: Event ->
            val e = ev.asDynamic()
            aanet.herata()
            kosketus.asDynamic().setPointerCapture(e.pointerId)
            alkuX = e.clientX as Double; alkuY = e.clientY as Double
            if (ar != null && !arLattia) return@addEventListener
            // In the room, a touch on the lower part of the screen (where the ball is) throws; elsewhere it looks
            if (ar != null || ny(e) < -0.2f) {
                if (heittoSormi < 0 && kasiTauko <= 0.0) {
                    heittoSormi = e.pointerId as Int
                    pyyhkaisy.aloita(nx(e), ny(e), aika(e))
                }
            } else if (katseSormi < 0) {
                katseSormi = e.pointerId as Int
                edX = e.clientX as Double; edY = e.clientY as Double
            }
        })
        kosketus.addEventListener("pointermove", { ev: Event ->
            val e = ev.asDynamic()
            if (e.pointerId == heittoSormi) {
                pyyhkaisy.lisaa(nx(e), ny(e), aika(e))
                kasiX = nx(e); kasiY = ny(e)
            } else if (e.pointerId == katseSormi) {
                val x = e.clientX as Double
                val y = e.clientY as Double
                suunta = (suunta - (x - edX) * 0.0035).coerceIn(-1.3, 1.3)
                kallistus = (kallistus + (y - edY) * 0.0035).coerceIn(-1.1, 0.5)
                edX = x; edY = y
            }
        })
        val irti: (Event) -> Unit = { ev ->
            val e = ev.asDynamic()
            if (e.pointerId == heittoSormi) {
                heittoSormi = -1
                if (ev.type == "pointerup") {
                    val h = pyyhkaisy.lopeta()
                    if (h != null) heita(h) else peli.napauta(nx(e), ny(e), kamera)
                } else pyyhkaisy.peru()
            } else if (e.pointerId == katseSormi) {
                katseSormi = -1
                // A touch that did not move is a tap, not a look around
                val liike = kotlin.math.hypot((e.clientX as Double) - alkuX, (e.clientY as Double) - alkuY)
                if (ev.type == "pointerup" && liike < 12.0) peli.napauta(nx(e), ny(e), kamera)
            }
        }
        kosketus.addEventListener("pointerup", irti)
        kosketus.addEventListener("pointercancel", irti)
    }

    private fun rakennaHud() {
        laskuri.textContent = "0"
        naytaPallot()
        vihje.textContent = VIHJE_HUONE
        val albumi = el("button", "nappi", napit)
        albumi.textContent = "Albumi"
        albumi.addEventListener("click", { naytaAlbumi() })
        albumi.addEventListener("beforexrselect", { e: Event -> e.preventDefault() })
        val labra = el("a", "nappi", napit)
        labra.textContent = "Ukkolabra"
        labra.setAttribute("href", "labra.html")
        val xr = window.navigator.asDynamic().xr
        if (xr != null && xr != undefined) {
            xr.isSessionSupported("immersive-ar").then { tuettu: Boolean ->
                if (tuettu) {
                    val n = el("button", "nappi ar", napit)
                    n.textContent = "AR-tila"
                    n.addEventListener("click", { if (ar == null) aloitaAr() else ar.end() })
                    // Tapping a button must not also count as a tap on the world
                    n.addEventListener("beforexrselect", { e: Event -> e.preventDefault() })
                    arNappi = n
                }
            }
        }
    }

    /** The album: every kind of figure, with a hint in place of the ones not caught yet. */
    private fun naytaAlbumi() {
        val tausta = el("div", "albumi", hud)
        tausta.addEventListener("beforexrselect", { e: Event -> e.preventDefault() })
        val kortti = el("div", "kortti", tausta)
        val a = peli.albumi
        el("h2", "", kortti).textContent = "Ukkoalbumi ${a.loydetty}/${Tyyppi.entries.size}"
        el("div", "pieni", kortti).textContent = "Napattu yhteensä ${a.yhteensa}. Kierrä taloa ja etsi jokaista lajia mahdollisimman monta."
        for (t in Tyyppi.entries) {
            val rivi = el("div", if (a[t] > 0) "ukkorivi" else "ukkorivi puuttuu", kortti)
            el("div", "kuvake", rivi).style.setProperty("--v", css(albumiVari(t)))
            val teksti = el("div", "", rivi)
            el("b", "", teksti).textContent = if (a[t] > 0) t.nimi else "???"
            el("div", "pieni", teksti).textContent = if (a[t] > 0) "${t.harvinaisuus.nimi} · ${t.pisteet} p · ${t.vihje}" else t.vihje
            el("div", "maara", rivi).textContent = if (a[t] > 0) "${a[t]}" else ""
        }
        val sulje = el("button", "nappi", kortti)
        sulje.textContent = "Sulje"
        sulje.addEventListener("click", { hud.removeChild(tausta) })
    }

    // =====================================================================================================
    // AR (WebXR)
    // =====================================================================================================

    private fun aloitaAr() {
        aanet.herata()
        val xr = window.navigator.asDynamic().xr
        val asetukset = olio {
            requiredFeatures = arrayOf("hit-test")
            optionalFeatures = arrayOf("dom-overlay", "plane-detection")
            domOverlay = olio { root = hud }
        }
        xr.requestSession("immersive-ar", asetukset).then({ istunto: dynamic ->
            ar = istunto
            arLattia = false
            haku = LattianHaku()
            tunnistin = Tunnistin()
            osumalahteet.clear()
            uusiPeli()
            peli.ilmoitaLoydot = true
            // In AR the player walks about the house looking for figures and balls
            peli.etsinta = true
            peli.ukkojaKerralla = 4
            huone.visible = false
            document.body?.classList?.add("ar-tila")
            arNappi?.textContent = "Lopeta AR"
            vihje.textContent = VIHJE_AR
            vihje.classList.remove("piilo")
            nakyma.renderoija.xr.enabled = true
            nakyma.renderoija.xr.setReferenceSpaceType("local")
            nakyma.renderoija.xr.setSession(istunto)
            istunto.requestReferenceSpace("viewer").then { katsoja: dynamic ->
                // A fan of probing rays: straight ahead, and to the sides, up and down (x right, y up, as slopes)
                val sadeLuokka = window.asDynamic().XRRay
                for ((x, y) in MITTAUSSUUNNAT) {
                    val asetus = olio { space = katsoja }
                    if (sadeLuokka != undefined && (x != 0.0 || y != 0.0)) asetus.offsetRay = uusiSade(sadeLuokka, olio { this.x = 0.0; this.y = 0.0; z = 0.0; w = 1.0 }, olio { this.x = x; this.y = y; z = -1.0; w = 0.0 })
                    else if (x != 0.0 || y != 0.0) continue
                    istunto.requestHitTestSource(asetus).then { lahde: dynamic -> osumalahteet.add(lahde) }
                }
            }
            istunto.addEventListener("select", {
                if (arLattia && (istunto.domOverlayState == null || istunto.domOverlayState == undefined)) {
                    // No overlay, so no swipes: a tap throws straight ahead
                    if (kasiTauko <= 0.0) heita(Heitto(0f, 0f, 0.5f, 0f))
                }
            })
            istunto.addEventListener("end", { lopetaAr() })
        }, { _: dynamic ->
            vihje.textContent = "AR-tila ei käynnistynyt tällä laitteella."
            vihje.classList.remove("piilo")
        })
    }

    private fun lopetaAr() {
        ar = null
        osumalahteet.clear()
        piirraAariviivat(emptyList())
        arLattia = false
        varjotaso.visible = false
        huone.visible = true
        document.body?.classList?.remove("ar-tila")
        arNappi?.textContent = "AR-tila"
        vihje.textContent = VIHJE_HUONE
        uusiPeli()
        peli.maailma.asetaPinnat(Huone.pinnat())
        kamera.paikka = V3(0f, 0.7f, 1.7f)
        asetaValo(V3.NOLLA)
        nakyma.sovita()
    }

    private fun uusiSade(luokka: dynamic, alku: dynamic, suunta: dynamic): dynamic = js("new luokka(alku, suunta)")

    private fun uusiPeli() {
        tallennettu = 0
        peli = Peli((js("Date.now()") as Double % 100000.0).toInt(), Tallennus.lueAlbumi(), Tallennus.lueReppu())
        peli.pyristely = true
        heittoja = 0
    }

    /** Reads the phone's pose and lens from WebXR, and keeps looking for the floor. */
    private fun arKamera(xrRuutu: dynamic, dt: Float) {
        if (xrRuutu == null || xrRuutu == undefined) return
        val tila = nakyma.renderoija.xr.getReferenceSpace() ?: return
        val asento = xrRuutu.getViewerPose(tila)
        if (asento != null && asento != undefined) {
            val p = asento.transform.position
            val o = asento.transform.orientation
            apuQ.set(o.x as Double, o.y as Double, o.z as Double, o.w as Double)
            val e = apuV.set(0.0, 0.0, -1.0).applyQuaternion(apuQ)
            val eteen = V3(e.x.toFloat(), e.y.toFloat(), e.z.toFloat())
            val y = apuV.set(0.0, 1.0, 0.0).applyQuaternion(apuQ)
            kamera.paikka = V3((p.x as Double).toFloat(), (p.y as Double).toFloat(), (p.z as Double).toFloat())
            kamera.kehys = Kehys.luo(eteen, V3(y.x.toFloat(), y.y.toFloat(), y.z.toFloat()))
            val proj = asento.views[0].projectionMatrix
            kamera.tanPuoliFov = (1.0 / (proj[5] as Double)).toFloat()
            kamera.kuvasuhde = ((proj[5] as Double) / (proj[0] as Double)).toFloat()
        }
        // The room is probed all the time: a fan of rays through the view, each hit a point on some surface.
        // Upward hits find the floor; all of them feed the recogniser, which grows tables, walls and ceilings.
        var havaittu: Float? = null
        for (lahde in osumalahteet) {
            val osumat = xrRuutu.getHitTestResults(lahde)
            if ((osumat.length as Int) == 0) continue
            val m = osumat[0].getPose(tila)?.transform?.matrix
            if (m == null || m == undefined) continue
            val p = V3((m[12] as Double).toFloat(), (m[13] as Double).toFloat(), (m[14] as Double).toFloat())
            val n = V3((m[4] as Double).toFloat(), (m[5] as Double).toFloat(), (m[6] as Double).toFloat())
            tunnistin.nayte(p, n)
            if (n.y > 0.9f && (havaittu == null || p.y < havaittu)) havaittu = p.y
        }
        haku.ruutu(dt, kamera.paikka, havaittu)
        val lattia = haku.lattia(kamera.paikka) ?: return
        pintaAjastin -= dt
        if (lattia === peli.maailma.lattia && pintaAjastin > 0f) return
        pintaAjastin = 0.4f
        val pinnat = ArrayList<Pinta>()
        pinnat += lattia
        pinnat += selaimenTasot(xrRuutu, tila, lattia.keskus.y)
        // (the recogniser's surfaces where the browser's own plane finder has none)
        for (t in tunnistin.pinnat(lattia.keskus.y)) {
            val keski = t.pisteTasolta(0f, 0f)
            if (pinnat.none { it.laji == t.laji && kotlin.math.abs(it.etaisyys(keski)) < 0.1f && it.sisalla(keski, 0.1f) }) pinnat += t
        }
        peli.maailma.asetaPinnat(pinnat)
        piirraAariviivat(pinnat)
        varjotaso.visible = true
        varjotaso.position.set(lattia.keskus.x.toDouble(), lattia.keskus.y.toDouble(), lattia.keskus.z.toDouble())
        asetaValo(lattia.keskus)
        if (!arLattia) {
            arLattia = true
            vihje.textContent = "Ukkoja ja palloja on ympäri taloa: kulje ja etsi. Pyyhkäise ylöspäin heittääksesi."
        }
    }

    /** The planes the browser itself has detected (WebXR plane detection, where the device offers it). */
    private fun selaimenTasot(xrRuutu: dynamic, tila: dynamic, lattiaY: Float): List<Pinta> {
        val tasot = xrRuutu.detectedPlanes
        if (tasot == null || tasot == undefined) return emptyList()
        val ulos = ArrayList<Pinta>()
        tasot.forEach({ taso: dynamic ->
            val m = xrRuutu.getPose(taso.planeSpace, tila)?.transform?.matrix
            val monikulmio = taso.polygon
            if (m != null && m != undefined && monikulmio != null && (monikulmio.length as Int) >= 4) {
                val keskus = V3((m[12] as Double).toFloat(), (m[13] as Double).toFloat(), (m[14] as Double).toFloat())
                val n = V3((m[4] as Double).toFloat(), (m[5] as Double).toFloat(), (m[6] as Double).toFloat())
                val laji = Tunnistin.luokittele(n, keskus.y, lattiaY)
                if (laji != null) {
                    // A stable name per plane, so that figures stay on their surface as it is refined
                    if (taso.tikkuId == undefined) taso.tikkuId = "xr${seuraavaTaso++}"
                    // The polygon is a closed loop of (x, 0, z) in the plane's space; our second axis is -z
                    val reuna = List((monikulmio.length as Int) - 1) { i -> (monikulmio[i].x as Double).toFloat() to -(monikulmio[i].z as Double).toFloat() }
                    ulos += Pinta(taso.tikkuId as String, laji, keskus, n, V3((m[0] as Double).toFloat(), (m[1] as Double).toFloat(), (m[2] as Double).toFloat()), reuna)
                }
            }
        })
        return ulos
    }

    /** Faint outlines of every recognised surface, so the player sees what the game has understood of the room. */
    private fun piirraAariviivat(pinnat: List<Pinta>) {
        for (v in viivat) {
            aariviivat.remove(v); v.geometry.dispose()
        }
        viivat.clear()
        for (p in pinnat) {
            if (p.laji == PinnanLaji.LATTIA) continue
            val a = FloatArray(p.reuna.size * 3)
            for ((i, kulma) in p.reuna.withIndex()) {
                val w = p.pisteTasolta(kulma.first, kulma.second) + p.normaali * 0.006f
                a[i * 3] = w.x; a[i * 3 + 1] = w.y; a[i * 3 + 2] = w.z
            }
            val g = BufferGeometry()
            g.setAttribute("position", BufferAttribute(a, 3))
            val v = LineLoop(g, viivaAine)
            v.frustumCulled = false
            viivat += v
            aariviivat.add(v)
        }
    }

    private fun asetaValo(kohde: V3) {
        nakyma.aurinko.target.position.set(kohde.x.toDouble(), kohde.y.toDouble(), kohde.z.toDouble())
        nakyma.aurinko.position.set(kohde.x + 0.7, kohde.y + 2.2, kohde.z + 1.1)
    }

    // =====================================================================================================
    // The room
    // =====================================================================================================

    private fun rakennaHuone() {
        val w = Huone.LEVEYS.toDouble()
        val d = Huone.SYVYYS.toDouble()
        val h = Huone.KORKEUS.toDouble()
        fun lisaa(m: Mesh, x: Double, y: Double, z: Double, varjoaa: Boolean = false): Mesh {
            m.position.set(x, y, z)
            m.receiveShadow = true
            m.castShadow = varjoaa
            huone.add(m)
            return m
        }
        fun aine(vari: Int, karheus: Double = 0.9) = MeshStandardMaterial(olio { color = vari; roughness = karheus })

        val lankut = MeshStandardMaterial(olio { map = lankkuTekstuuri(); roughness = 0.75 })
        lisaa(Mesh(PlaneGeometry(w, d).rotateX(-PI / 2), lankut), 0.0, 0.0, 0.0)
        // Walls are thin boxes so that each can be seen from inside and lit like a real wall
        val seina = aine(0xe6ebe4)
        lisaa(Mesh(BoxGeometry(w, h, 0.05), seina), 0.0, h / 2, -d / 2 - 0.025)
        lisaa(Mesh(BoxGeometry(0.05, h, d), aine(0xdde4dc)), -w / 2 - 0.025, h / 2, 0.0)
        lisaa(Mesh(BoxGeometry(0.05, h, d), aine(0xdde4dc)), w / 2 + 0.025, h / 2, 0.0)
        // The ceiling, for the figures that hang from it (it casts no shadow, or the room would be dark)
        lisaa(Mesh(BoxGeometry(w, 0.05, d), aine(0xf4f3ee)), 0.0, h + 0.025, 0.0)
        val lista = aine(0xfaf8f2, 0.6)
        lisaa(Mesh(BoxGeometry(w, 0.07, 0.015), lista), 0.0, 0.035, -d / 2 + 0.0075)
        lisaa(Mesh(BoxGeometry(0.015, 0.07, d), lista), -w / 2 + 0.0075, 0.035, 0.0)
        lisaa(Mesh(BoxGeometry(0.015, 0.07, d), lista), w / 2 - 0.0075, 0.035, 0.0)
        // A rug, to give the floor some depth
        lisaa(Mesh(CircleGeometry(0.95, 48).rotateX(-PI / 2), aine(0x9fc3d6, 1.0)), -0.25, 0.003, -0.35)
        lisaa(Mesh(RingGeometry(0.8, 0.86, 48).rotateX(-PI / 2), aine(0xf5efe2, 1.0)), -0.25, 0.004, -0.35)
        // A window-like picture on the back wall
        lisaa(Mesh(BoxGeometry(0.9, 0.65, 0.03), aine(0xfaf8f2, 0.6)), -0.7, 1.45, -d / 2 + 0.015)
        lisaa(Mesh(PlaneGeometry(0.8, 0.55), aine(0xbfe0f2, 0.5)), -0.7, 1.45, -d / 2 + 0.031)
        lisaa(Mesh(CircleGeometry(0.11, 24), aine(0xffd66b, 0.5)), -0.45, 1.56, -d / 2 + 0.032)
        // The chest of drawers
        val k = Huone.lipastonKeskus
        val lw = Huone.LIPASTO_LEVEYS.toDouble()
        val lh = Huone.LIPASTO_KORKEUS.toDouble()
        val ls = Huone.LIPASTO_SYVYYS.toDouble()
        lisaa(Mesh(BoxGeometry(lw, lh, ls), aine(0xc98f5a, 0.7)), k.x.toDouble(), k.y.toDouble(), k.z.toDouble(), true)
        val etu = k.z + ls / 2
        for (i in 0..1) {
            val y = lh * (0.28 + 0.44 * i)
            lisaa(Mesh(BoxGeometry(lw - 0.08, lh * 0.36, 0.012), aine(0xd9a36c, 0.7)), k.x.toDouble(), y, etu + 0.006)
            lisaa(Mesh(BoxGeometry(0.12, 0.02, 0.02), aine(0x6b4a2e, 0.5)), k.x.toDouble(), y, etu + 0.02)
        }
    }

    /** Floorboards painted on a canvas. */
    private fun lankkuTekstuuri(): CanvasTexture {
        val c = document.createElement("canvas") as HTMLCanvasElement
        c.width = 512; c.height = 512
        val g = c.getContext("2d").asDynamic()
        val rnd = kotlin.random.Random(3)
        val lankkuja = 8
        val lw = 512 / lankkuja
        for (i in 0 until lankkuja) {
            val s = rnd.nextInt(-10, 10)
            g.fillStyle = "rgb(${214 + s},${178 + s},${130 + s})"
            g.fillRect(i * lw, 0, lw, 512)
            // A few grain lines and one butt joint per board
            g.strokeStyle = "rgba(120,85,50,0.10)"
            g.lineWidth = 1
            for (j in 0 until 5) {
                val x = i * lw + 6 + rnd.nextInt(lw - 12)
                g.beginPath(); g.moveTo(x, 0); g.lineTo(x + rnd.nextInt(-4, 4), 512); g.stroke()
            }
            g.fillStyle = "rgba(90,60,35,0.35)"
            g.fillRect(i * lw, 0, 2, 512)
            g.fillRect(i * lw, rnd.nextInt(512), lw, 2)
        }
        val t = CanvasTexture(c)
        t.wrapS = RepeatWrapping; t.wrapT = RepeatWrapping
        t.repeat.set(4, 4)
        t.colorSpace = "srgb"
        t.anisotropy = 8
        return t
    }

    // =====================================================================================================
    // Screenshots: index.html?kuva=1&t=6&heitto=4.5 plays t seconds and throws at a figure at the given time
    // =====================================================================================================

    private fun kuvatila(p: URLSearchParams) {
        peli = Peli(p.get("siemen")?.toIntOrNull() ?: 4, if (p.get("albumi") != null) Albumi.lue(p.get("albumi")) else Albumi())
        p.get("tyyppi")?.let { nimi -> Tyyppi.entries.firstOrNull { it.name == nimi } }?.let { peli.tyypit = listOf(it) }
        peli.maailma.asetaPinnat(Huone.pinnat())
        suunta = p.get("suunta")?.toDoubleOrNull() ?: 0.0
        kallistus = p.get("kallistus")?.toDoubleOrNull() ?: kallistus
        val t = p.get("t")?.toFloatOrNull() ?: 5f
        val heitto = p.get("heitto")?.toFloatOrNull() ?: -1f
        p.get("reppu")?.let { for (l in Reppu.lue(it).let { r -> PalloLaji.entries.filter { l -> l != PalloLaji.NORMI && r[l] > 0 } }) peli.reppu.lisaa(l) }
        p.get("pallo")?.let { nimi -> PalloLaji.entries.firstOrNull { it.name == nimi } }?.let { peli.reppu.lisaa(it); peli.reppu.valitse(it) }
        huoneKamera()
        val dt = 1f / 60f
        var aika = 0f
        while (aika < t) {
            if (heitto >= 0f && aika <= heitto && aika + dt > heitto) {
                peli.ukot.firstOrNull { !it.kiinni }?.let { u ->
                    val kohti = (u.keskipiste - kamera.paikka).suunta()
                    val e = kohti dot kamera.kehys.eteen
                    heita(Heitto(
                        (kohti dot kamera.kehys.oikealle) / e / (kamera.tanPuoliFov * kamera.kuvasuhde),
                        (kohti dot kamera.kehys.ylos) / e / kamera.tanPuoliFov, 0.5f, p.get("kaarre")?.toFloatOrNull() ?: 0f,
                    ))
                }
            }
            paivita(dt)
            aika += dt
        }
        rakennaHud()
        naytaPisteet()
        naytaPallot()
        if (p.get("albumi") != null) naytaAlbumi()
        nakyma.piirra()
        window.asDynamic().kuvaValmis = true
    }

    companion object {
        /** A colour for each kind in the album. */
        fun albumiVari(t: Tyyppi): Vari = when (t) {
            Tyyppi.KULTAINEN -> Ulkonako.KULTA
            Tyyppi.NINJA -> Ulkonako.NINJA
            Tyyppi.PIILO -> Ulkonako.HAALEA
            else -> Ulkonako.VARIT[t.ordinal % Ulkonako.VARIT.size]
        }

        /** Slopes (right, up) of the rays that probe the room in AR. */
        val MITTAUSSUUNNAT = listOf(0.0 to 0.0, -0.22 to 0.0, 0.22 to 0.0, 0.0 to 0.3, 0.0 to -0.3, -0.2 to -0.28, 0.2 to -0.28, -0.2 to 0.28, 0.2 to 0.28)

        const val KASI_LEPO_Y = -0.72f
        const val KASI_SYVYYS = 0.5f
        const val VIHJE_HUONE = "Pyyhkäise pallosta ylöspäin heittääksesi. Vedä ylempää katsellaksesi ympärillesi."
        const val VIHJE_AR = "Katsele puhelimella lattiaa: ukot tulevat kohta esiin."
        private val apuQ = Quaternion()
        private val apuV = Vector3()
    }
}
