package fi.otsosoft.tikkujahti.web

import fi.otsosoft.tikkujahti.maailma.PinnanLaji
import fi.otsosoft.tikkujahti.maailma.Pinta
import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.matikka.clamp
import fi.otsosoft.tikkujahti.matikka.Kehys
import fi.otsosoft.tikkujahti.peli.Heitto
import fi.otsosoft.tikkujahti.peli.Kamera
import fi.otsosoft.tikkujahti.peli.Peli
import fi.otsosoft.tikkujahti.ukko.Tyyppi
import fi.otsosoft.tikkujahti.ukko.Toiminto
import fi.otsosoft.tikkujahti.ukko.Ukko
import fi.otsosoft.tikkujahti.ukko.Ulkonako
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.url.URLSearchParams
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The stick-figure lab: one figure on a round stage, a button for every action, slow motion, colours and
 * sizes. Drag to orbit, pinch or scroll to zoom.
 *
 * For screenshots (tools/kuvat.mjs): `labra.html?kuva=1&toiminto=VILKUTTAA&t=1.2&kulma=25` steps the figure
 * to an exact moment, draws one frame and sets `window.kuvaValmis`.
 */
class Labra(private val juuri: HTMLElement) {
    private val nakyma = Nakyma(el("div", "nakyma", juuri), 0.7)
    private val lava = Pinta(
        "lava", PinnanLaji.LATTIA, V3.NOLLA, V3.Y, V3.X,
        List(24) { i -> (cos(i * PI / 12) * LAVAN_SADE).toFloat() to (sin(i * PI / 12) * LAVAN_SADE).toFloat() },
    )

    private val aanet = Aanet()
    private var variIndeksi = 0
    private var tyyppi = Tyyppi.TAVALLINEN
    private var vaistaa = true
    private var siemen = 7
    private lateinit var ukko: Ukko
    private val koko: Float get() = ukko.m.koko

    // The lab runs a real game with one figure in it, so thrown balls behave exactly as in play
    private val peli = Peli()
    private val piirto = PeliPiirto(nakyma.scene)
    private val kamera = Kamera(V3(0f, 0.3f, 0.5f), Kehys.luo(-V3.Z), kotlin.math.tan(25.0 * PI / 180).toFloat(), 1f)

    // Orbit camera
    private var kulma = 0.45
    private var kallistus = 0.22
    private var etaisyys = 0.52
    private var hidastus = 1.0
    private var edellinen = -1.0
    private val tila = el("div", "tila", juuri)

    init {
        val lattia = Mesh(CircleGeometry(LAVAN_SADE.toDouble() + 0.04, 64), MeshStandardMaterial(olio { color = 0xf3ead8; roughness = 0.9 }))
        lattia.rotateX(-PI / 2)
        lattia.receiveShadow = true
        nakyma.scene.add(lattia)
        val reuna = Mesh(RingGeometry(LAVAN_SADE.toDouble() + 0.04, LAVAN_SADE.toDouble() + 0.055, 64), MeshStandardMaterial(olio { color = 0xd9c9a8; roughness = 0.9 }))
        reuna.rotateX(-PI / 2)
        nakyma.scene.add(reuna)

        peli.ukkojaKerralla = 0
        peli.maailma.asetaPinnat(listOf(lava))
        uusiUkko()
        val p = URLSearchParams(window.location.search)
        if (p.get("kuva") != null) {
            kuvatila(p)
        } else {
            rakennaNapit()
            ohjaus()
            document.addEventListener("pointerdown", { aanet.herata() })
            nakyma.renderoija.setAnimationLoop { aika, _ -> ruutu(aika) }
        }
    }

    private fun uusiUkko() {
        val omatoiminen = peli.ukot.firstOrNull()?.omatoiminen ?: false
        peli.ukot.clear()
        val pohja = Peli.luoUlkonako(tyyppi, Ulkonako.VARIT[variIndeksi], kotlin.random.Random(siemen))
        val ulkonako = pohja.copy(luonne = pohja.luonne.copy(vaistotaito = if (vaistaa) 1f else 0f))
        ukko = Ukko(siemen, ulkonako, V3.NOLLA, 0.3f, siemen++)
        ukko.lattia = lava
        ukko.omatoiminen = omatoiminen
        peli.ukot += ukko
    }

    private var katsoKorkeus = 0.125

    private fun asetaKamera() {
        val korkeus = katsoKorkeus * koko
        // Bigger figures are watched from further away, so every kind fills the stage alike
        val d = etaisyys * koko
        val x = sin(kulma) * cos(kallistus) * d
        val y = korkeus + sin(kallistus) * d
        val z = cos(kulma) * cos(kallistus) * d
        nakyma.kamera.position.set(x, y, z)
        nakyma.kamera.lookAt(0.0, korkeus, 0.0)
        ukko.katsoja = V3(x.toFloat(), y.toFloat(), z.toFloat())
        kamera.paikka = V3(x.toFloat(), y.toFloat(), z.toFloat())
        kamera.kehys = Kehys.luo(V3(0f, korkeus.toFloat(), 0f) - kamera.paikka)
        kamera.kuvasuhde = (nakyma.leveys / nakyma.korkeus).toFloat()
    }

    private fun ruutu(aika: Double) {
        val dt = if (edellinen < 0) 0.016 else clamp(((aika - edellinen) / 1000.0).toFloat(), 0f, 0.05f).toDouble()
        edellinen = aika
        asetaKamera()
        peli.paivita((dt * hidastus).toFloat(), kamera)
        for (t in peli.tapahtumat) aanet.soita(t, kamera.paikka)
        if (peli.ukot.isEmpty() && peli.pallot.isEmpty()) uusiUkko()
        piirto.paivita(peli)
        tila.textContent = ukko.toiminto.name.lowercase().replace('_', ' ')
        nakyma.piirra()
    }

    private fun kuvatila(p: URLSearchParams) {
        juuri.classList.add("kuva") // no panel: the stage fills the picture
        nakyma.sovita()
        p.get("vari")?.toIntOrNull()?.let { variIndeksi = it % Ulkonako.VARIT.size; }
        p.get("tyyppi")?.let { nimi -> Tyyppi.entries.firstOrNull { it.name == nimi } }?.let { tyyppi = it }
        siemen = p.get("siemen")?.toIntOrNull() ?: 7
        uusiUkko()
        kulma = (p.get("kulma")?.toDoubleOrNull() ?: 25.0) * PI / 180
        kallistus = (p.get("kallistus")?.toDoubleOrNull() ?: 12.0) * PI / 180
        etaisyys = p.get("etaisyys")?.toDoubleOrNull() ?: 0.6
        katsoKorkeus = p.get("korkeus")?.toDoubleOrNull() ?: katsoKorkeus
        asetaKamera()
        val dt = 1f / 120f
        val toiminto = p.get("toiminto")?.let { nimi -> Toiminto.entries.firstOrNull { it.name == nimi } }
        if (toiminto != Toiminto.SAAPUU) {
            repeat(360) { ukko.paivita(dt) }
            if (toiminto != null) ukko.komenna(toiminto)
        }
        val t = p.get("t")?.toFloatOrNull() ?: 1f
        repeat((t / dt).toInt()) { ukko.paivita(dt) }
        piirto.paivita(peli)
        nakyma.piirra()
        window.asDynamic().kuvaValmis = true
    }

    private fun rakennaNapit() {
        val paneeli = el("div", "paneeli", juuri)
        val otsikko = el("div", "otsikko", paneeli)
        otsikko.innerHTML = "<b>Ukkolabra</b> <a href=\"index.html\">← peliin</a>"

        val toiminnot = el("div", "napit", paneeli)
        for (t in Toiminto.entries) {
            if (t == Toiminto.SAAPUU) continue
            nappi(toiminnot, NIMET[t] ?: t.name) { ukko.komenna(t) }
        }
        nappi(toiminnot, "Uusi ukko") { uusiUkko() }
        val heita = el("button", "paa", toiminnot)
        heita.textContent = "Heitä pallo"
        heita.addEventListener("click", { heitaPallo() })

        val rivi = el("div", "rivi", paneeli)
        for ((i, v) in Ulkonako.VARIT.withIndex()) {
            val n = el("button", "vari", rivi)
            n.style.background = "rgb(${(v.r * 255).toInt()},${(v.g * 255).toInt()},${(v.b * 255).toInt()})"
            n.setAttribute("aria-label", "Väri ${i + 1}")
            n.addEventListener("click", {
                variIndeksi = i
                if (tyyppi == Tyyppi.KULTAINEN) tyyppi = Tyyppi.TAVALLINEN
                uusiUkko()
            })
        }
        val tyypit = el("div", "rivi", paneeli)
        for (t in Tyyppi.entries) nappi(tyypit, t.nimi) { tyyppi = t; uusiUkko() }

        val saadot = el("div", "rivi", paneeli)
        val omat = el("label", "", saadot)
        val ruksi = document.createElement("input") as HTMLInputElement
        ruksi.type = "checkbox"
        ruksi.addEventListener("change", { ukko.omatoiminen = ruksi.checked; if (ruksi.checked) ukko.komenna(Toiminto.SEISOO) })
        omat.appendChild(ruksi)
        omat.appendChild(document.createTextNode(" Touhuaa itse"))
        val vaisto = el("label", "", saadot)
        val vaistoRuksi = document.createElement("input") as HTMLInputElement
        vaistoRuksi.type = "checkbox"
        vaistoRuksi.checked = true
        vaistoRuksi.addEventListener("change", { vaistaa = vaistoRuksi.checked; uusiUkko() })
        vaisto.appendChild(vaistoRuksi)
        vaisto.appendChild(document.createTextNode(" Väistää"))
        val hid = el("label", "", saadot)
        hid.appendChild(document.createTextNode("Nopeus "))
        val liuku = document.createElement("input") as HTMLInputElement
        liuku.type = "range"; liuku.min = "0.05"; liuku.max = "1"; liuku.step = "0.05"; liuku.value = "1"
        liuku.addEventListener("input", { hidastus = liuku.value.toDouble() })
        hid.appendChild(liuku)
    }

    /** Throws a ball from the camera straight at the figure, as a player would. */
    private fun heitaPallo() {
        if (ukko.kiinni) return
        val kohti = (ukko.keskipiste - kamera.paikka).suunta()
        val e = kohti dot kamera.kehys.eteen
        peli.heita(
            Heitto(
                (kohti dot kamera.kehys.oikealle) / e / (kamera.tanPuoliFov * kamera.kuvasuhde),
                (kohti dot kamera.kehys.ylos) / e / kamera.tanPuoliFov, 0.5f, 0f,
            ),
            kamera,
        )
        peli.tapahtumat.lastOrNull()?.let { aanet.soita(it, kamera.paikka) }
    }

    private fun nappi(isanta: HTMLElement, teksti: String, toiminto: () -> Unit) {
        val n = el("button", "", isanta)
        n.textContent = teksti
        n.addEventListener("click", { toiminto() })
    }

    private fun ohjaus() {
        val c = nakyma.renderoija.domElement
        val sormet = mutableMapOf<Int, Pair<Double, Double>>()
        var nipistys = 0.0
        c.style.asDynamic().touchAction = "none"
        c.addEventListener("pointerdown", { e: Event ->
            val p = e.asDynamic()
            sormet[p.pointerId as Int] = (p.clientX as Double) to (p.clientY as Double)
            c.asDynamic().setPointerCapture(p.pointerId)
            nipistys = 0.0
        })
        c.addEventListener("pointermove", { e: Event ->
            val p = e.asDynamic()
            val id = p.pointerId as Int
            val vanha = sormet[id] ?: return@addEventListener
            val x = p.clientX as Double
            val y = p.clientY as Double
            sormet[id] = x to y
            if (sormet.size == 1) {
                kulma -= (x - vanha.first) * 0.008
                kallistus = (kallistus + (y - vanha.second) * 0.006).coerceIn(-0.05, 1.3)
            } else if (sormet.size == 2) {
                val (a, b) = sormet.values.toList()
                val d = kotlin.math.hypot(a.first - b.first, a.second - b.second)
                if (nipistys > 0) etaisyys = (etaisyys * nipistys / d).coerceIn(0.25, 2.5)
                nipistys = d
            }
        })
        val irti = { e: Event -> sormet.remove(e.asDynamic().pointerId as Int); nipistys = 0.0 }
        c.addEventListener("pointerup", irti)
        c.addEventListener("pointercancel", irti)
        c.addEventListener("wheel", { e: Event ->
            e.preventDefault()
            etaisyys = (etaisyys * (1 + (e.asDynamic().deltaY as Double) * 0.001)).coerceIn(0.25, 2.5)
        }, olio { passive = false })
    }

    companion object {
        const val LAVAN_SADE = 0.33f
        val NIMET = mapOf(
            Toiminto.SEISOO to "Seiso", Toiminto.KAVELEE to "Kävele", Toiminto.JUOKSEE to "Juokse",
            Toiminto.KATSELEE to "Katsele", Toiminto.VILKUTTAA to "Vilkuta", Toiminto.RAAPII_PAATA to "Raavi päätä",
            Toiminto.HAUKOTTELEE to "Haukottele", Toiminto.TUULETTAA to "Tuuleta", Toiminto.SAIKAHTAA to "Säikähdä",
            Toiminto.SUUTTUU to "Suutu", Toiminto.KIUSOITTELEE to "Kiusoittele", Toiminto.VAISTAA_HYPPY to "Väistä hypäten",
            Toiminto.VAISTAA_KYYKKY to "Väistä kyykkyyn", Toiminto.OSUMA to "Osuma", Toiminto.KAPERTYY to "Käperry",
        )
    }
}

fun el(tagi: String, luokka: String, isanta: HTMLElement): HTMLElement {
    val e = document.createElement(tagi) as HTMLElement
    if (luokka.isNotEmpty()) e.className = luokka
    isanta.appendChild(e)
    return e
}
