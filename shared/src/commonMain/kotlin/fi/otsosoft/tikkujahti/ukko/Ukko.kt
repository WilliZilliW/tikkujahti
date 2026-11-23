package fi.otsosoft.tikkujahti.ukko

import fi.otsosoft.tikkujahti.maailma.Pinta
import fi.otsosoft.tikkujahti.matikka.Jousi
import fi.otsosoft.tikkujahti.matikka.Jousi3
import fi.otsosoft.tikkujahti.matikka.Kehys
import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.matikka.clamp
import fi.otsosoft.tikkujahti.matikka.kierra
import fi.otsosoft.tikkujahti.matikka.kulmaero
import fi.otsosoft.tikkujahti.matikka.kumpu
import fi.otsosoft.tikkujahti.matikka.lerp
import fi.otsosoft.tikkujahti.matikka.pehmea
import fi.otsosoft.tikkujahti.matikka.saturoi
import fi.otsosoft.tikkujahti.matikka.suuntakulma
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class Tunne { NEUTRAALI, ILOINEN, PELOKAS, VIHAINEN, KIUSOITTELEVA, YLLATTYNYT, UNINEN, MIETTELIAS }

enum class Toiminto {
    SAAPUU, SEISOO, KAVELEE, JUOKSEE, KATSELEE, VILKUTTAA, RAAPII_PAATA, HAUKOTTELEE, TUULETTAA,
    SAIKAHTAA, SUUTTUU, KIUSOITTELEE, VAISTAA_HYPPY, VAISTAA_KYYKKY, OSUMA, KAPERTYY,
    /** Sitting on an edge, legs dangling over it. */
    ISTUU,
}

/** Little voice sounds and noises a figure makes; the game turns them into audio. */
enum class Aantely { HEI, HIH, JEE, IIK, AH, NANANA, HAUKOTUS, HMM, HUPS, TOMPS, HYPPY, ALASTULO }

/**
 * One stick figure: its brain, how it walks, and its body language, recomputed into [asento] every frame.
 *
 * The figure stands on [lattia]. The game tells it where the player's camera is ([katsoja]) and which ball
 * is flying nearby ([pallo]), so it can look at them, and warns it about balls heading its way
 * ([havaitsePallo]) so it can dodge.
 */
class Ukko(
    val id: Int,
    val ulkonako: Ulkonako,
    alku: V3,
    alkuSuunta: Float,
    siemen: Int,
    /** How high above the surface the figure appears; the parachutist starts high up. */
    alkuKorkeus: Float? = null,
    /** The surface to appear on, when it is not a floor: [alku] is then a point on it. */
    pinta: Pinta? = null,
) {
    val m = ulkonako.mitat
    private val luonne = ulkonako.luonne
    private val tyyppi = ulkonako.tyyppi
    /** Hits still needed to catch this figure. */
    var osumiaJaljella = ulkonako.tyyppi.osumia
        private set
    private val rnd = Random(siemen)
    /** The pose in the world, for drawing and hit tests. */
    val asento = Asento()
    /** The pose in the figure's own space, where the surface it stands on is always the floor (y up). */
    private val oma = Asento()

    // ---- Inputs from the game ----
    var katsoja: V3? = null
    /** Where the player is looking, if known: the figure prefers to stay in view. */
    var katsojanSuunta: V3? = null
    var pallo: V3? = null
    /**
     * The surface the figure stands on: a floor or table top, but also a wall or a ceiling. On anything but
     * an upward surface the figure lives in its own space where that surface is the floor, and only the
     * finished pose is turned into the world: so everything it can do on a floor it can do on a wall.
     */
    var lattia: Pinta?
        get() = pintaM
        set(p) = asetaPinta(p)
    /**
     * The figure's own spot in the world. With one, it keeps to its neighbourhood for the player to come and
     * find, instead of following the player about.
     */
    var koti: V3? = null
    /**
     * Whether the way between two world points is clear of known walls (the game gives this). Figures do not
     * choose to walk through a wall: a figure behind one would vanish behind its stand-in.
     */
    var esteeton: ((V3, V3) -> Boolean)? = null
    /** Behind something real as seen from the player (the game decides this, see Peli.paivitaPeitot): not drawn. */
    var peitossa = false
    /** World height of the real floor, where a knocked figure falls to. */
    var maanTaso: Float? = null
    private var pintaM: Pinta? = null
    private var pintaL: Pinta? = null
    private var alusta: Alusta? = null
    private val katsojaL: V3? get() = katsoja?.let { paikalliseen(it) }
    private val palloL: V3? get() = pallo?.let { paikalliseen(it) }
    private val katsojanSuuntaL: V3? get() = katsojanSuunta?.let { alusta?.suuntaSisaan(it) ?: it }

    // Blending the pose across a change of surface
    private var siirtyma = 1f
    private val siirtymaAlku = Array(Nivel.entries.size) { V3.NOLLA }
    private var siirtymaPaa = Kehys(V3.Z, V3.Y, V3(-1f, 0f, 0f))

    // ---- Things done to the figure ----
    /** Seconds of being slowed down: slower, and too sluggish to dodge. */
    var hidastettu = 0f
        private set
    /** Seconds of being glued to the spot. */
    var liimattu = 0f
        private set
    /** Seconds an invisible figure stays revealed. */
    var paljastettu = 0f
        private set
    /** An invisible figure nobody has revealed: only its eyes show, and balls pass through. */
    val haamu: Boolean get() = tyyppi == Tyyppi.PIILO && paljastettu <= 0f && !kiinni
    /** Figures of the same herd share a number; 0 = on its own. */
    var ryhma = 0
    private var poistuu = -1f
    private var pakoKohde: V3? = null
    private var varuste = 1f
    /** When false the figure only does what it is told (the lab); when true it decides for itself. */
    var omatoiminen = true
    /** Where to curl into when caught (the ball's centre). */
    var pallonKeskus: V3? = null

    // ---- Outputs ----
    val aanet = mutableListOf<Aantely>()
    var toiminto = Toiminto.SAAPUU
        private set
    /** Curled up completely: the game can remove the figure. */
    var valmis = false
        private set
    val kiinni: Boolean get() = toiminto == Toiminto.OSUMA || toiminto == Toiminto.KAPERTYY
    /** Centre of the body, for aiming and hit tests. */
    val keskipiste: V3 get() = (asento[Nivel.LANTIO] + asento[Nivel.RINTA]) * 0.5f
    /** Where the body faces, in the world. */
    val eteen: V3 get() = maailmaanSuunta(V3.suunnasta(suunta))
    val juuri: V3 get() = maailmaan(juuriL)
    /** Seconds since the figure appeared. */
    val ika: Float get() = aika
    /** Standing on a wall or a ceiling. */
    val seinalla: Boolean get() = alusta != null
    /** Not walking anywhere (any more). */
    val perilla: Boolean get() = toiminto != Toiminto.KAVELEE && toiminto != Toiminto.JUOKSEE

    // ---- Root motion ----
    private var juuriL = alku
    var suunta = alkuSuunta
        private set
    private var nopeus = V3.NOLLA
    private var edNopeus = V3.NOLLA
    private var korkeus = 0f
    private var pystyNopeus = 0f
    var ilmassa = false
        private set

    // ---- Action state ----
    private var aika = 0f
    private var toimintoAika = 0f
    private var toimintoKesto = 1f
    private var kohde: V3? = null
    private var vaistoSuunta = V3.X
    private var vaisti = false
    private var lahelta = false
    private var seuraavaLahelta = -1f
    private var odottavaVaisto: Toiminto? = null
    private var vaistoViive = 0f
    private val ennakoidut = mutableSetOf<Int>()
    private var katseKohde: V3? = null
    private var katseAjastin = 0f
    private var vilkaisuAjastin = 2f
    private var toisto = 0

    // ---- Springs: these give the motion its weight, overshoot and follow-through ----
    private val kyykky = Jousi(3.2f, 0.42f, 0f)
    private val noja = Jousi3(2.0f, 0.45f, 0f)
    private val katse = Jousi3(2.6f, 0.62f, 1.3f, V3.suunnasta(alkuSuunta))
    private val kallistus = Jousi(2.2f, 0.45f, 0.5f)
    private val venytys = Jousi(4.5f, 0.22f, 0f, 1f)
    private val kasiV = Jousi3(4.5f, 0.5f, 0.15f)
    private val kasiO = Jousi3(4.5f, 0.5f, 0.15f)
    private val painoV = Jousi(3.5f, 0.85f, 0f)
    private val painoO = Jousi(3.5f, 0.85f, 0f)
    private val silmaX = Jousi(10f, 0.75f, 1f)
    private val silmaY = Jousi(10f, 0.75f, 1f)
    private val hymy = Jousi(5f, 0.55f, 0.4f)
    private val suuAuki = Jousi(7f, 0.5f, 0.3f)
    private val suuLeveys = Jousi(6f, 0.55f, 0.3f, 1f)
    private val kulmaK = Jousi(6f, 0.45f, 0.6f)
    private val kulmaKulma = Jousi(6f, 0.55f, 0.3f)
    private val ilo = Jousi(5f, 0.7f, 0f)
    private val silmaKoko = Jousi(6f, 0.35f, 0.8f, 1f)
    private val kieli = Jousi(6f, 0.6f, 0f)
    private val luomet = Jousi(14f, 0.9f, 0f, 1f)

    // Blinking
    private var rapsayAjastin = 1.5f
    private var rapsays = -1f

    // Saccades: little eye jumps when nothing in particular is being looked at
    private var sakkadiAjastin = 0.5f
    private var sakkadiX = 0f
    private var sakkadiY = 0f

    private class Jalka(var paikka: V3) {
        var askel = false
        var t = 0f
        var alku = paikka
        var kohde = paikka
        var kesto = 0.2f
        var nosto = 0.01f
        var maassa = 0f
        var tompsu = false
        var nykyinen = paikka
    }

    private val jalat: Array<Jalka>

    private class Ohjaus {
        var kohde: V3? = null
        var vauhti = 0f
        var katse: V3? = null
        var kyykky = 0f
        var noja = V3.NOLLA
        var kasiV: V3? = null
        var kasiO: V3? = null
        var tunne = Tunne.NEUTRAALI
        var kallistus = 0f
        var silmatKiinni = false
        var suuAuki = -1f
        var kieli = -1f
        var askelNosto = 1f
        var kasiJousi = 1f
        var kaanny: V3? = null

        fun nollaa() {
            kohde = null; vauhti = 0f; katse = null; kyykky = 0f; noja = V3.NOLLA; kasiV = null; kasiO = null
            tunne = Tunne.NEUTRAALI; kallistus = 0f; silmatKiinni = false; suuAuki = -1f; kieli = -1f
            askelNosto = 1f; kasiJousi = 1f; kaanny = null
        }
    }

    private val o = Ohjaus()
    private var nukke: Nukke? = null

    init {
        val f = V3.suunnasta(alkuSuunta)
        val r = f cross V3.Y
        jalat = arrayOf(Jalka(alku - r * m.lonkkaLeveys * 1.45f), Jalka(alku + r * m.lonkkaLeveys * 1.45f))
        if (pinta != null) {
            asetaPinta(pinta)
            siirtyma = 1f // it appears there; there is no earlier pose to ease from
        }
        aloita(Toiminto.SAAPUU)
        korkeus = alkuKorkeus ?: if (tyyppi == Tyyppi.LASKUVARJO) 1.5f else m.pituus * 1.1f
        ilmassa = true
        varuste = if (tyyppi == Tyyppi.LASKUVARJO) 1f else if (tyyppi == Tyyppi.KILPI || tyyppi == Tyyppi.NINJA) 1f else 0f
        rakennaAsento(0.016f)
        julkaise()
    }

    // =====================================================================================================
    // Surfaces: the figure's own space
    // =====================================================================================================

    /** A surface as a frame: [y] is its normal. Own-space points are in this frame. */
    private class Alusta(val keskus: V3, val x: V3, val y: V3, val z: V3) {
        fun ulos(p: V3) = keskus + x * p.x + y * p.y + z * p.z
        fun suuntaUlos(v: V3) = x * v.x + y * v.y + z * v.z
        fun sisaan(p: V3): V3 {
            val d = p - keskus
            return V3(d dot x, d dot y, d dot z)
        }
        fun suuntaSisaan(v: V3) = V3(v dot x, v dot y, v dot z)
    }

    private fun maailmaan(p: V3) = alusta?.ulos(p) ?: p
    private fun maailmaanSuunta(v: V3) = alusta?.suuntaUlos(v) ?: v
    private fun paikalliseen(p: V3) = alusta?.sisaan(p) ?: p

    private fun asetaPinta(p: Pinta?) {
        if (p === pintaM || kiinni) return
        val vaaka = p == null || (p.onVaaka && p.normaali.y > 0f)
        val uusi = if (vaaka) null else Alusta(p!!.keskus, p.u, p.normaali, p.u cross p.normaali)
        val vanha = alusta
        if (vanha != null || uusi != null) {
            // The same surface seen a little differently (AR refines planes), or a move to another surface?
            val sama = pintaM != null && p != null && pintaM!!.id == p.id
            val siirra = { q: V3 -> (vanha?.ulos(q) ?: q).let { w -> uusi?.sisaan(w) ?: w } }
            if (sama) {
                juuriL = siirra(juuriL)
                kohde = kohde?.let(siirra)
                for (j in jalat) {
                    j.paikka = siirra(j.paikka); j.alku = siirra(j.alku); j.kohde = siirra(j.kohde); j.nykyinen = siirra(j.nykyinen)
                }
            } else {
                for (i in siirtymaAlku.indices) siirtymaAlku[i] = asento.nivelet[i]
                siirtymaPaa = asento.paa
                siirtyma = 0f
                val eteenM = vanha?.suuntaUlos(V3.suunnasta(suunta)) ?: V3.suunnasta(suunta)
                val ylosM = vanha?.y ?: V3.Y
                juuriL = siirra(juuriL)
                if (uusi != null) juuriL = V3(juuriL.x, 0f, juuriL.z)
                // Keep heading the same way along the new surface; walking straight at it means going "up" the old one
                var s = (uusi?.suuntaSisaan(eteenM) ?: eteenM).vaakaan()
                if (s.pituus < 0.35f) s = (uusi?.suuntaSisaan(ylosM) ?: ylosM).vaakaan()
                suunta = suuntakulma(s.suunta(V3.Z))
                val r = V3.suunnasta(suunta) cross V3.Y
                jalat[0].paikka = juuriL - r * (m.lonkkaLeveys * 1.45f)
                jalat[1].paikka = juuriL + r * (m.lonkkaLeveys * 1.45f)
                for (j in jalat) {
                    j.askel = false; j.nykyinen = j.paikka; j.alku = j.paikka; j.kohde = j.paikka
                }
                kohde = null
                nopeus = V3.NOLLA; edNopeus = V3.NOLLA
                korkeus = 0f; pystyNopeus = 0f; ilmassa = false
                katse.nollaa(V3.suunnasta(suunta)); noja.nollaa(V3.NOLLA)
                kasiV.nollaa(siirra(kasiV.arvo)); kasiO.nollaa(siirra(kasiO.arvo))
                odottavaVaisto = null
            }
        }
        alusta = uusi
        pintaM = p
        pintaL = if (uusi == null) p else Pinta(p!!.id, p.laji, V3.NOLLA, V3.Y, V3.X, p.reuna)
    }

    /** Turns the own-space pose into the world pose, easing across a change of surface. */
    private fun julkaise() {
        val a = alusta
        val t = pehmea(siirtyma)
        for (i in oma.nivelet.indices) {
            val w = a?.ulos(oma.nivelet[i]) ?: oma.nivelet[i]
            asento.nivelet[i] = if (siirtyma < 1f) lerp(siirtymaAlku[i], w, t) else w
        }
        var e = a?.suuntaUlos(oma.paa.eteen) ?: oma.paa.eteen
        var y = a?.suuntaUlos(oma.paa.ylos) ?: oma.paa.ylos
        if (siirtyma < 1f) {
            e = lerp(siirtymaPaa.eteen, e, t); y = lerp(siirtymaPaa.ylos, y, t)
        }
        asento.paa = if (a == null && siirtyma >= 1f) oma.paa else Kehys.luo(e, y)
        asento.paaVenytys = oma.paaVenytys
        asento.paksuus = oma.paksuus
        asento.nakyvissa = oma.nakyvissa
        asento.kasvot.kopioi(oma.kasvot)
        asento.varuste = varuste
        asento.haamu = haamu
        asento.eteen = eteen
        asento.ylos = a?.y ?: V3.Y
    }

    // =====================================================================================================
    // Things the game does to the figure
    // =====================================================================================================

    fun hidasta(sekunteja: Float) {
        hidastettu = max(hidastettu, sekunteja)
    }

    fun liimaa(sekunteja: Float) {
        liimattu = max(liimattu, sekunteja)
        odottavaVaisto = null
        if (!kiinni && !ilmassa) aloita(Toiminto.SAIKAHTAA)
    }

    fun paljasta(sekunteja: Float) {
        if (tyyppi == Tyyppi.PIILO && paljastettu <= 0f && !kiinni) {
            aanet += Aantely.IIK
            if (!ilmassa) aloita(Toiminto.SAIKAHTAA)
        }
        paljastettu = max(paljastettu, sekunteja)
    }

    /** Walks or runs to a point in the world (on the surface the figure is on). */
    fun meneKohti(p: V3, juosten: Boolean) {
        if (kiinni || valmis) return
        val l = paikalliseen(p)
        kohde = V3(l.x, juuriL.y, l.z)
        aloita(if (juosten) Toiminto.JUOKSEE else Toiminto.KAVELEE)
    }

    /** Runs off towards [pois] and vanishes: the rest of a herd when one of them is caught. */
    fun pakene(pois: V3) {
        if (kiinni || valmis || poistuu >= 0f) return
        poistuu = 0f
        omatoiminen = false
        aanet += Aantely.IIK
        pakoKohde = pois
        if (!ilmassa) meneKohti(pois, true)
    }

    /** Gone in an instant: a ninja leaving in its puff of smoke. */
    fun katoa() {
        if (kiinni) return
        oma.nakyvissa = false
        asento.nakyvissa = false
        valmis = true
    }

    /** A ball bounced off: the wrong kind of ball, or a shield in the way. The figure gloats. */
    fun torju() {
        if (kiinni || valmis) return
        odottavaVaisto = null
        seuraavaLahelta = -1f
        venytys.tyonna(1.5f)
        if (!ilmassa) aloita(if (rnd.nextFloat() < 0.5f) Toiminto.KIUSOITTELEE else Toiminto.TUULETTAA)
    }

    // =====================================================================================================
    // Commands
    // =====================================================================================================

    /** Starts an action at once (the lab's buttons, and the brain). */
    fun komenna(t: Toiminto) {
        if (kiinni || valmis) return
        when (t) {
            Toiminto.VAISTAA_HYPPY -> {
                val f = V3.suunnasta(suunta)
                vaistoSuunta = (f cross V3.Y) * (if (rnd.nextBoolean()) 1f else -1f)
            }
            Toiminto.KAVELEE, Toiminto.JUOKSEE -> kohde = uusiKohde(if (t == Toiminto.JUOKSEE) 0.6f else 0.35f)
            Toiminto.OSUMA -> {
                val kohti = (katsoja ?: (juuri + eteen)) - keskipiste
                osuma(keskipiste, -kohti.suunta() * 4f)
                return
            }
            Toiminto.KAPERTYY -> {
                if (pallonKeskus == null) pallonKeskus = keskipiste + eteen * 0.05f
                osuma(keskipiste, eteen * -1.5f)
                return
            }
            else -> {}
        }
        aloita(t)
    }

    /**
     * A ball hit a figure that takes more hits than this to catch: it staggers and gets cross, and the ball
     * bounces off.
     */
    fun tolaisu(pallonNopeus: V3) {
        if (kiinni || valmis) return
        osumiaJaljella--
        if (!ilmassa) nopeus += pallonNopeus.vaakaan().suunta(V3.NOLLA) * 0.35f
        noja.tyonna(pallonNopeus.vaakaan().suunta(V3.NOLLA) * 3f)
        venytys.tyonna(-2.5f)
        kyykky.tyonna(4f)
        // It was hit, not narrowly missed: no startled jump on top of this
        odottavaVaisto = null
        seuraavaLahelta = -1f
        aloita(Toiminto.SUUTTUU)
    }

    /** A ball hit the figure at [kohta] moving at [pallonNopeus]: it gets knocked, then curls into the ball. */
    fun osuma(kohta: V3, pallonNopeus: V3) {
        if (kiinni || valmis) return
        val omaNopeus = maailmaanSuunta(nopeus)
        val alkuNopeus = Array(Nivel.entries.size) { i ->
            val p = asento.nivelet[i]
            val lahella = 1f - saturoi((p - kohta).pituus / (m.pituus * 0.7f))
            omaNopeus + pallonNopeus * (0.25f + 0.35f * lahella) + V3.Y * 0.4f
        }
        nukke = Nukke(asento.nivelet.copyOf(), alkuNopeus, m)
        // From here on physics has the figure, in the world: it lets go of any wall or ceiling and falls
        if (alusta != null) {
            if (alusta!!.y.y < -0.5f) nukke?.katto = alusta!!.keskus.y
            juuriL = juuri
            oma.paa = asento.paa
            alusta = null; pintaM = null; pintaL = null
        }
        siirtyma = 1f
        aanet += if (rnd.nextFloat() < 0.5f) Aantely.AH else Aantely.HUPS
        aloita(Toiminto.OSUMA)
    }

    /**
     * Tells the figure about a ball in flight (position [p], velocity [v], gravity [g], radius [sade]).
     * Each ball is judged once: the figure may decide to dodge it, or just watch it fly past.
     */
    fun havaitsePallo(id: Int, p0: V3, v0: V3, g0: V3, sade: Float, vaistettavissa: Boolean = true) {
        if (id in ennakoidut || kiinni || valmis) return
        val p = paikalliseen(p0)
        val v = alusta?.suuntaSisaan(v0) ?: v0
        val g = alusta?.suuntaSisaan(g0) ?: g0
        val keski = juuriL + V3.Y * (m.pituus * 0.5f)
        var lahin = Float.MAX_VALUE
        var lahinT = 0f
        var lahinPiste = p
        var t = 0f
        while (t < 1.2f) {
            val q = p + v * t + g * (0.5f * t * t)
            val d = (q - keski).pituus
            if (d < lahin) {
                lahin = d; lahinT = t; lahinPiste = q
            }
            if (q.y < juuriL.y - 0.2f) break
            t += 0.02f
        }
        if (lahin > 0.9f && t < 1.2f) return // far away, judge it again later
        ennakoidut += id
        val uhkaava = lahin < m.pituus * 0.5f + sade
        val reaktio = 0.14f + rnd.nextFloat() * 0.12f
        val pystyy = vaistettavissa && liimattu <= 0f && hidastettu <= 0f
        if (uhkaava && pystyy && lahinT > reaktio && !ilmassa && rnd.nextFloat() < luonne.vaistotaito) {
            val korkea = lahinPiste.y - juuriL.y > m.pituus * 0.62f
            if (korkea) {
                odottavaVaisto = Toiminto.VAISTAA_KYYKKY
            } else {
                val sivu = (v.vaakaan() cross V3.Y).suunta(V3.X)
                val puoli = ((keski - lahinPiste) dot sivu)
                vaistoSuunta = sivu * (if (puoli >= 0f) 1f else -1f)
                odottavaVaisto = Toiminto.VAISTAA_HYPPY
            }
            vaistoViive = reaktio - 0.05f
        } else if (lahin < 0.4f) {
            seuraavaLahelta = lahinT + 0.05f
        }
    }

    // =====================================================================================================
    // Update
    // =====================================================================================================

    fun paivita(dt0: Float) {
        val dt = clamp(dt0, 0.0001f, 0.05f)
        aika += dt
        toimintoAika += dt

        // Reactions to balls scheduled by havaitsePallo
        if (odottavaVaisto != null) {
            vaistoViive -= dt
            if (vaistoViive <= 0f) {
                val v = odottavaVaisto!!
                odottavaVaisto = null
                if (!kiinni && !ilmassa) {
                    vaisti = true
                    aloita(v)
                }
            }
        }
        if (seuraavaLahelta >= 0f) {
            seuraavaLahelta -= dt
            if (seuraavaLahelta < 0f && !kiinni && !ilmassa && toiminto !in VAISTOT) {
                lahelta = true
                aloita(Toiminto.SAIKAHTAA)
            }
        }

        hidastettu -= dt; liimattu -= dt; paljastettu -= dt
        if (siirtyma < 1f) siirtyma = min(1f, siirtyma + dt / 0.32f)

        if (kiinni) {
            paivitaKiinni(dt)
            paivitaKasvot(dt)
            julkaise()
            return
        }

        o.nollaa()
        toimi(dt)
        liiku(dt)
        rakennaAsento(dt)
        konttaa(dt)
        istu(dt)
        paivitaKasvot(dt)
        // The parachute folds away after landing
        if (tyyppi == Tyyppi.LASKUVARJO && !ilmassa) varuste = max(0f, varuste - dt / 0.5f)
        // A fleeing figure runs for a moment, then shrinks away
        if (poistuu >= 0f) {
            // One that started its escape in mid-air sets off when it lands
            if (!ilmassa && perilla && poistuu < 1.2f) pakoKohde?.let { meneKohti(it, true) }
            poistuu += dt
            if (poistuu > 1.6f) oma.paksuus = max(0.02f, 1f - (poistuu - 1.6f) / 0.3f)
            if (poistuu > 1.9f) {
                oma.nakyvissa = false
                valmis = true
            }
        }
        julkaise()
    }

    private fun aloita(t: Toiminto) {
        toiminto = t
        toimintoAika = 0f
        toisto = 0
        toimintoKesto = when (t) {
            Toiminto.SAAPUU -> 1.6f
            Toiminto.SEISOO -> if (tyyppi == Tyyppi.JUOKSIJA) 0.3f + rnd.nextFloat() * 0.6f else 1.5f + rnd.nextFloat() * 2.5f
            Toiminto.KAVELEE, Toiminto.JUOKSEE -> 8f
            Toiminto.KATSELEE -> 2.8f
            Toiminto.VILKUTTAA -> 2.2f
            Toiminto.RAAPII_PAATA -> 2.2f
            Toiminto.HAUKOTTELEE -> 3.0f
            Toiminto.TUULETTAA -> 1.9f
            Toiminto.SAIKAHTAA -> 1.1f
            Toiminto.SUUTTUU -> 1.7f
            Toiminto.KIUSOITTELEE -> 2.6f
            Toiminto.VAISTAA_HYPPY -> 0.75f
            Toiminto.VAISTAA_KYYKKY -> 0.9f
            Toiminto.OSUMA -> 0.45f
            Toiminto.KAPERTYY -> 0.7f
            Toiminto.ISTUU -> 7f + rnd.nextFloat() * 6f
        }
        when (t) {
            Toiminto.VILKUTTAA -> aanet += Aantely.HEI
            Toiminto.TUULETTAA -> aanet += Aantely.JEE
            Toiminto.SAIKAHTAA -> aanet += Aantely.IIK
            Toiminto.SUUTTUU -> aanet += Aantely.AH
            Toiminto.KIUSOITTELEE -> aanet += Aantely.NANANA
            Toiminto.HAUKOTTELEE -> aanet += Aantely.HAUKOTUS
            Toiminto.RAAPII_PAATA -> aanet += Aantely.HMM
            else -> {}
        }
    }

    private fun valmiina() {
        // What to do after an action ends
        val ed = toiminto
        val tehtiinVaisto = vaisti
        val oliLahelta = lahelta
        vaisti = false
        lahelta = false
        if (!omatoiminen) {
            aloita(Toiminto.SEISOO)
            toimintoKesto = Float.MAX_VALUE
            return
        }
        when {
            ed in VAISTOT && tehtiinVaisto -> {
                val r = rnd.nextFloat()
                when {
                    r < luonne.leikkisyys * 0.6f -> aloita(Toiminto.KIUSOITTELEE)
                    r < 0.8f -> aloita(Toiminto.TUULETTAA)
                    else -> aloita(Toiminto.SEISOO)
                }
            }
            ed == Toiminto.SAIKAHTAA && oliLahelta && rnd.nextFloat() < 0.35f -> aloita(Toiminto.SUUTTUU)
            ed == Toiminto.SAAPUU && rnd.nextFloat() < 0.6f -> aloita(Toiminto.VILKUTTAA)
            ed == Toiminto.KAVELEE || ed == Toiminto.JUOKSEE -> aloita(Toiminto.SEISOO)
            ed == Toiminto.SEISOO -> valitseOmaToiminto()
            else -> aloita(if (rnd.nextFloat() < 0.5f) Toiminto.SEISOO else Toiminto.KAVELEE.also { kohde = uusiKohde(0.35f) })
        }
    }

    private fun valitseOmaToiminto() {
        val katsojaLahella = katsojaL?.let { (it.vaakaan() - juuriL.vaakaan()).pituus < 2.2f } ?: false
        val r = rnd.nextFloat()
        when {
            tyyppi == Tyyppi.JUOKSIJA && r < 0.85f -> {
                kohde = uusiKohde(0.9f)
                aloita(Toiminto.JUOKSEE)
            }
            r < 0.42f -> {
                kohde = uusiKohde(0.35f * m.koko)
                aloita(if (rnd.nextFloat() < 0.15f + luonne.vilkkaus * 0.2f) Toiminto.JUOKSEE else Toiminto.KAVELEE)
            }
            r < 0.55f && katsojaLahella -> aloita(Toiminto.VILKUTTAA)
            r < 0.65f -> aloita(Toiminto.RAAPII_PAATA)
            r < 0.73f -> aloita(Toiminto.HAUKOTTELEE)
            r < 0.85f -> aloita(Toiminto.KATSELEE)
            r < 0.85f + luonne.leikkisyys * 0.2f && katsojaLahella -> aloita(Toiminto.KIUSOITTELEE)
            else -> aloita(Toiminto.SEISOO)
        }
    }

    /** A new place to walk to: on the floor, in view of the player, not too close to them. */
    private fun uusiKohde(matka: Float): V3 {
        val l = pintaL
        var paras: V3? = null
        var parasPisteet = -Float.MAX_VALUE
        repeat(12) {
            val kulma = rnd.nextFloat() * 2f * PI.toFloat()
            val pituus = matka * (0.5f + rnd.nextFloat())
            var p = juuriL + V3.suunnasta(kulma) * pituus
            if (l != null) p = l.lahinPiste(p, 0.06f)
            if (esteeton?.invoke(maailmaan(juuriL), maailmaan(p)) == false) return@repeat
            var pisteet = 0f
            val kotiL = koti?.let { paikalliseen(it) }
            if (kotiL != null) {
                pisteet -= max(0f, (p - kotiL).vaakaan().pituus - 0.6f) * 2f
            } else katsojaL?.let { k ->
                val d = (p.vaakaan() - k.vaakaan()).pituus
                val toivottu = 0.9f + luonne.ujous * 1.0f
                pisteet -= abs(d - toivottu)
                katsojanSuuntaL?.let { s ->
                    val sivussa = fi.otsosoft.tikkujahti.matikka.kulmaValilla(s.vaakaan().suunta(V3.Z), (p - k).vaakaan().suunta(V3.Z))
                    pisteet -= max(0f, sivussa - 0.15f) * 2.5f
                }
            }
            pisteet += rnd.nextFloat() * 0.3f
            if (pisteet > parasPisteet) {
                parasPisteet = pisteet; paras = p
            }
        }
        return paras ?: juuriL
    }

    // =====================================================================================================
    // Actions: each one sets the frame's controls (where to go, where to look, hands, face)
    // =====================================================================================================

    private fun toimi(dt: Float) {
        val t = toimintoAika
        val f = V3.suunnasta(suunta)
        val r = f cross V3.Y
        val ylos = V3.Y
        val paaKeski = oma[Nivel.PAA]
        val olkaV = oma[Nivel.OLKA_V]
        val olkaO = oma[Nivel.OLKA_O]
        val kamera = katsojaL

        // Default gaze: the player now and then, otherwise ahead or around
        vilkaisuAjastin -= dt
        if (vilkaisuAjastin <= 0f) {
            vilkaisuAjastin = 1.2f + rnd.nextFloat() * 2.5f
            katseKohde = when {
                kamera != null && rnd.nextFloat() < 0.55f -> null // null = look at the player
                else -> juuriL + V3.suunnasta(suunta + (rnd.nextFloat() - 0.5f) * 2.4f) * 1f + ylos * (rnd.nextFloat() * 0.3f)
            }
        }
        o.katse = katseKohde ?: kamera

        when (toiminto) {
            Toiminto.SAAPUU -> {
                o.tunne = if (t < 0.6f) Tunne.YLLATTYNYT else Tunne.ILOINEN
                o.katse = kamera
                if (tyyppi == Tyyppi.LASKUVARJO && ilmassa) {
                    // Hanging from the parachute lines, swinging gently
                    o.tunne = Tunne.ILOINEN
                    toimintoKesto = t + 1.2f
                    val heilu = sin(aika * 2.2f)
                    o.kasiV = olkaV + ylos * (m.kadenPituus * 0.8f) - r * (m.kadenPituus * 0.45f)
                    o.kasiO = olkaO + ylos * (m.kadenPituus * 0.8f) + r * (m.kadenPituus * 0.45f)
                    o.noja = r * (0.12f * heilu)
                }
                if (!ilmassa && t > 0.25f) o.kallistus = 0.25f * sin(t * 5f) * (1f - saturoi((t - 0.6f) / 0.8f))
            }
            Toiminto.SEISOO -> {
                o.tunne = Tunne.NEUTRAALI
                kamera?.let { o.kaanny = it }
            }
            Toiminto.KAVELEE, Toiminto.JUOKSEE -> {
                val juoksee = toiminto == Toiminto.JUOKSEE
                o.kohde = kohde
                o.vauhti = (if (juoksee) 0.55f else 0.22f) * (0.8f + luonne.vilkkaus * 0.4f) * tyyppi.vauhti
                // The climber is quick on walls and clumsy on the floor
                if (tyyppi == Tyyppi.KIIPEILIJA) o.vauhti *= if (alusta != null) 1.6f else 0.55f
                if (hidastettu > 0f) o.vauhti *= 0.3f
                if (liimattu > 0f) o.vauhti = 0f
                o.tunne = if (juoksee) Tunne.ILOINEN else Tunne.NEUTRAALI
                if (katseKohde != null) o.katse = juuriL + f * 1f + ylos * 0.05f
                val k = kohde
                if (k == null || (k - juuriL).vaakaan().pituus < 0.03f) {
                    if (istumaan != null) aloita(Toiminto.ISTUU) else valmiina()
                    return
                }
            }
            Toiminto.KATSELEE -> {
                o.tunne = Tunne.MIETTELIAS
                val kulma = suunta + sin(t * 1.4f) * 1.1f
                o.katse = paaKeski + V3.suunnasta(kulma) * 1f + ylos * (0.15f * sin(t * 0.9f))
                o.kallistus = 0.22f * sin(t * 1.1f + 0.5f)
            }
            Toiminto.VILKUTTAA -> {
                o.tunne = Tunne.ILOINEN
                o.katse = kamera
                kamera?.let { o.kaanny = it }
                val nosto = pehmea(t / 0.3f) * (1f - pehmea((t - toimintoKesto + 0.35f) / 0.35f))
                val heilu = sin(t * 2f * PI.toFloat() * 2.3f)
                o.kasiO = olkaO + ylos * (m.kadenPituus * 0.66f) + r * (m.kadenPituus * (0.55f + heilu * 0.25f)) + f * (m.kadenPituus * 0.15f)
                if (nosto < 0.05f) o.kasiO = null
                o.kasiJousi = 1.8f
                o.kallistus = 0.18f * heilu * nosto
                o.noja = r * (-0.06f * nosto)
            }
            Toiminto.RAAPII_PAATA -> {
                o.tunne = Tunne.MIETTELIAS
                val h = oma.paa
                val raapi = sin(t * 2f * PI.toFloat() * 4f) * 0.25f
                if (t > 0.15f && t < toimintoKesto - 0.3f) {
                    o.kasiO = paaKeski + h.oikealle * (m.paaSade * 0.95f) + h.ylos * (m.paaSade * (0.55f + raapi)) - h.eteen * (m.paaSade * 0.15f)
                    o.kasiJousi = 2.2f
                }
                o.kallistus = -0.3f
                o.katse = paaKeski + f * 0.5f + ylos * 0.22f - r * 0.2f
                o.kieli = 0f
            }
            Toiminto.HAUKOTTELEE -> {
                val venyy = pehmea((t - 0.35f) / 0.6f) * (1f - pehmea((t - 2.0f) / 0.6f))
                o.tunne = Tunne.UNINEN
                o.suuAuki = pehmea((t - 0.2f) / 0.6f) * (1f - pehmea((t - 1.9f) / 0.5f))
                o.silmatKiinni = t > 0.5f && t < 1.95f
                if (venyy > 0.05f) {
                    o.kasiV = olkaV + ylos * (m.kadenPituus * 0.95f * venyy) - r * (m.olkaLeveys * 0.6f) + f * (0.004f)
                    o.kasiO = olkaO + ylos * (m.kadenPituus * 0.95f * venyy) + r * (m.olkaLeveys * 0.6f) + f * (0.004f)
                }
                o.noja = f * (-0.18f * venyy)
                o.kyykky = -0.08f * venyy
                o.katse = paaKeski + f * 0.5f + ylos * (0.3f * venyy)
            }
            Toiminto.TUULETTAA -> {
                o.tunne = Tunne.ILOINEN
                o.suuAuki = 0.55f
                o.katse = kamera
                kamera?.let { o.kaanny = it }
                val pumppu = sin(t * 2f * PI.toFloat() * 2.4f)
                if (t < 1.55f) {
                    o.kasiO = olkaO + ylos * (m.kadenPituus * (0.62f + 0.25f * pumppu)) + r * (m.kadenPituus * 0.45f) + f * 0.008f
                    o.kasiV = oma[Nivel.LONKKA_V] - r * (m.lonkkaLeveys * 1.8f) + f * 0.004f + ylos * 0.006f
                    o.kasiJousi = 2.0f
                }
                if (toisto == 0 && t > 0.12f) {
                    toisto = 1; hyppaa(0.035f * m.koko, V3.NOLLA)
                }
                if (toisto == 1 && t > 0.75f) {
                    toisto = 2; hyppaa(0.025f * m.koko, V3.NOLLA)
                }
            }
            Toiminto.SAIKAHTAA -> {
                o.tunne = if (t < 0.6f) Tunne.YLLATTYNYT else Tunne.PELOKAS
                o.katse = palloL ?: kamera
                if (toisto == 0) {
                    toisto = 1; hyppaa(0.022f * m.koko, V3.NOLLA)
                }
                val levi = 1f - pehmea((t - 0.55f) / 0.4f)
                if (levi > 0.05f) {
                    val heilu = sin(t * 30f) * 0.004f
                    o.kasiV = olkaV + ylos * (m.kadenPituus * 0.45f) - r * (m.kadenPituus * 0.55f) + V3.Y * heilu
                    o.kasiO = olkaO + ylos * (m.kadenPituus * 0.45f) + r * (m.kadenPituus * 0.55f) - V3.Y * heilu
                    o.kasiJousi = 2.5f
                }
                o.noja = f * (-0.15f * levi)
            }
            Toiminto.SUUTTUU -> {
                o.tunne = Tunne.VIHAINEN
                o.katse = kamera
                kamera?.let { o.kaanny = it }
                o.suuAuki = 0.25f + 0.15f * sin(t * 18f)
                val tarina = sin(t * 40f) * 0.0025f
                o.kasiV = oma[Nivel.LONKKA_V] - r * (m.lonkkaLeveys * 2.2f) + ylos * (0.01f + tarina)
                o.kasiO = oma[Nivel.LONKKA_O] + r * (m.lonkkaLeveys * 2.2f) + ylos * (0.01f - tarina)
                o.kasiJousi = 2.5f
                o.noja = f * 0.12f
                o.askelNosto = 3.2f
                // Stomp: alternate feet in place
                val vaihe = (t / 0.32f).toInt()
                if (vaihe > toisto && t < toimintoKesto - 0.3f) {
                    toisto = vaihe
                    tompsauta(vaihe % 2)
                }
            }
            Toiminto.KIUSOITTELEE -> {
                o.tunne = Tunne.KIUSOITTELEVA
                o.katse = kamera
                kamera?.let { o.kaanny = it }
                val h = oma.paa
                val heilu = sin(t * 2f * PI.toFloat() * 1.6f)
                val sormet = sin(t * 2f * PI.toFloat() * 6f) * 0.004f
                if (t > 0.1f && t < toimintoKesto - 0.25f) {
                    o.kasiV = paaKeski - h.oikealle * (m.paaSade * 1.35f) + h.ylos * (m.paaSade * 0.25f + sormet) + h.eteen * 0.004f
                    o.kasiO = paaKeski + h.oikealle * (m.paaSade * 1.35f) + h.ylos * (m.paaSade * 0.25f - sormet) + h.eteen * 0.004f
                    o.kasiJousi = 2.2f
                }
                o.noja = r * (0.12f * heilu)
                o.kallistus = 0.3f * heilu
                o.kieli = if (t > 0.2f && t < toimintoKesto - 0.3f) 1f else 0f
            }
            Toiminto.VAISTAA_HYPPY -> {
                o.tunne = Tunne.YLLATTYNYT
                o.katse = palloL ?: kamera
                if (t < 0.07f) {
                    o.kyykky = 0.7f
                } else if (toisto == 0) {
                    toisto = 1
                    hyppaa(0.06f * m.koko, vaistoSuunta * 0.75f)
                }
                if (ilmassa) {
                    o.kasiV = olkaV + ylos * (m.kadenPituus * 0.7f) - r * (m.kadenPituus * 0.3f)
                    o.kasiO = olkaO + ylos * (m.kadenPituus * 0.7f) + r * (m.kadenPituus * 0.3f)
                    o.kasiJousi = 1.6f
                    o.noja = vaistoSuunta * 0.25f
                }
                if (toisto == 1 && !ilmassa && t > 0.2f) {
                    toisto = 2
                    toimintoKesto = t + 0.3f
                }
            }
            Toiminto.VAISTAA_KYYKKY -> {
                o.tunne = Tunne.PELOKAS
                val alas = pehmea(t / 0.08f) * (1f - pehmea((t - 0.55f) / 0.3f))
                o.kyykky = 1f * alas
                o.silmatKiinni = alas > 0.5f
                if (alas > 0.1f) {
                    val h = oma.paa
                    o.kasiV = paaKeski + h.ylos * (m.paaSade * 1.05f) - h.oikealle * (m.paaSade * 0.5f)
                    o.kasiO = paaKeski + h.ylos * (m.paaSade * 1.05f) + h.oikealle * (m.paaSade * 0.5f)
                    o.kasiJousi = 2.8f
                }
                o.noja = f * (0.35f * alas)
                o.katse = juuriL + f * 0.3f
            }
            Toiminto.ISTUU -> {
                o.tunne = if (sin(aika * 0.7f) > 0.3f) Tunne.ILOINEN else Tunne.NEUTRAALI
                istumaan?.let { o.kaanny = juuriL + it }
                if (katseKohde != null) o.katse = juuriL + (istumaan ?: f) * 1f + ylos * 0.1f
            }
            Toiminto.OSUMA, Toiminto.KAPERTYY -> {}
        }
        if (toiminto != Toiminto.ISTUU && toiminto != Toiminto.KAVELEE) istumaan = null

        // The shield stays up in front unless the hands are busy
        if (tyyppi == Tyyppi.KILPI && o.kasiV == null && toiminto != Toiminto.SAAPUU) {
            o.kasiV = olkaV + f * (m.kadenPituus * 0.65f) + r * (m.olkaLeveys * 0.5f) - ylos * (m.kadenPituus * 0.42f)
        }
        // Under a ceiling the arms dangle towards the room
        if ((alusta?.y?.y ?: 1f) < -0.5f) {
            val heilu = sin(aika * 1.7f) * 0.012f
            if (o.kasiV == null) o.kasiV = olkaV + ylos * (m.kadenPituus * 0.92f) - r * (0.012f + heilu)
            if (o.kasiO == null) o.kasiO = olkaO + ylos * (m.kadenPituus * 0.92f) + r * (0.012f - heilu)
        }

        if (toimintoAika >= toimintoKesto && !(toiminto in VAISTOT && ilmassa)) valmiina()
    }

    // =====================================================================================================
    // Locomotion
    // =====================================================================================================

    private fun hyppaa(h: Float, vaaka: V3) {
        if (ilmassa || liimattu > 0f) return
        ilmassa = true
        pystyNopeus = sqrt(2f * PAINOVOIMA * h)
        nopeus = vaaka
        venytys.tyonna(2.5f)
        aanet += Aantely.HYPPY
    }

    private fun tompsauta(i: Int) {
        val j = jalat[i]
        if (j.askel) return
        j.askel = true
        j.t = 0f
        j.alku = j.paikka
        j.kohde = j.paikka
        j.kesto = 0.22f
        j.nosto = 0.016f * m.koko * o.askelNosto / 3.2f
        j.tompsu = true
    }

    private fun liiku(dt: Float) {
        edNopeus = nopeus
        var f = V3.suunnasta(suunta)

        // Steering
        val k = o.kohde
        if (!ilmassa) {
            if (k != null && o.vauhti > 0f) {
                val ero = (k - juuriL).vaakaan()
                val d = ero.pituus
                val haluttu = ero.suunta(f) * (o.vauhti * saturoi(d / 0.1f + 0.15f))
                val kulma = kulmaero(suunta, suuntakulma(haluttu))
                suunta += clamp(kulma, -6f * dt, 6f * dt)
                f = V3.suunnasta(suunta)
                val eteen = f * (haluttu.pituus * max(0f, cos(kulma)))
                nopeus = lahesty(nopeus, eteen, 1.6f * dt)
            } else {
                nopeus = lahesty(nopeus, V3.NOLLA, 2.2f * dt)
                o.kaanny?.let { kohti ->
                    val kulma = kulmaero(suunta, suuntakulma((kohti - juuriL).vaakaan()))
                    if (abs(kulma) > 0.6f || (toiminto != Toiminto.SEISOO && abs(kulma) > 0.15f)) {
                        suunta += clamp(kulma, -2.2f * dt, 2.2f * dt)
                    }
                }
            }
        } else {
            pystyNopeus -= PAINOVOIMA * dt
            if (tyyppi == Tyyppi.LASKUVARJO && varuste > 0.5f) pystyNopeus = max(pystyNopeus, -0.3f)
            korkeus += pystyNopeus * dt
            if (korkeus <= 0f && pystyNopeus < 0f) {
                // Landing: squash, bend the knees, and plant both feet where they are
                val isku = -pystyNopeus
                korkeus = 0f
                pystyNopeus = 0f
                ilmassa = false
                kyykky.tyonna(saturoi(isku / 1.5f) * 9f)
                venytys.tyonna(-isku * 4f)
                nopeus = nopeus * 0.3f
                val r = f cross V3.Y
                jalat[0].paikka = juuriL + nopeus * 0.05f - r * (m.lonkkaLeveys * 1.5f)
                jalat[1].paikka = juuriL + nopeus * 0.05f + r * (m.lonkkaLeveys * 1.5f)
                jalat.forEach { it.askel = false; it.maassa = 0f }
                aanet += Aantely.ALASTULO
            }
        }

        juuriL += nopeus * dt
        // Stay on the floor
        pintaL?.let { l ->
            // (one going to sit on the edge is allowed right up to it)
            if (istumaan == null && !l.sisalla(juuriL, -0.03f)) {
                val p = l.lahinPiste(juuriL, 0.04f)
                val siirto = p - juuriL
                juuriL = p
                val n = siirto.suunta(V3.NOLLA)
                if (n != V3.NOLLA) nopeus = nopeus - n * min(0f, nopeus dot n)
            }
            juuriL = V3(juuriL.x, l.keskus.y, juuriL.z)
        }

        paivitaJalat(dt)
    }

    private fun lahesty(a: V3, b: V3, maksimi: Float): V3 {
        val d = b - a
        val l = d.pituus
        return if (l <= maksimi) b else a + d * (maksimi / l)
    }

    private fun paivitaJalat(dt: Float) {
        val f = V3.suunnasta(suunta)
        val r = f cross V3.Y
        val vauhti = nopeus.pituus
        val kesto = clamp(0.23f - vauhti * 0.22f, 0.1f, 0.23f)
        for ((i, j) in jalat.withIndex()) {
            val puoli = if (i == 0) -1f else 1f
            val ihanne = juuriL + r * (puoli * m.lonkkaLeveys * 1.45f) + nopeus * (kesto * 0.9f)
            if (ilmassa) {
                j.askel = false
                j.paikka = ihanne
                continue
            }
            if (j.askel) {
                j.t += dt / j.kesto
                if (!j.tompsu) j.kohde = ihanne + nopeus * (j.kesto * (1f - j.t) * 0.5f)
                if (j.t >= 1f) {
                    j.askel = false
                    j.paikka = j.kohde
                    j.maassa = 0f
                    if (j.tompsu) {
                        j.tompsu = false
                        kyykky.tyonna(3f)
                        venytys.tyonna(-1.5f)
                        aanet += Aantely.TOMPS
                    }
                }
            } else {
                j.maassa += dt
            }
        }
        if (ilmassa) return
        if (jalat.none { it.askel }) {
            val kynnys = (0.012f + vauhti * 0.06f) * m.koko + 0.006f
            var paras = -1
            var parasEro = kynnys
            for ((i, j) in jalat.withIndex()) {
                val puoli = if (i == 0) -1f else 1f
                val ihanne = juuriL + r * (puoli * m.lonkkaLeveys * 1.45f) + nopeus * (kesto * 0.9f)
                val ero = (j.paikka - ihanne).vaakaan().pituus
                if (ero > parasEro && j.maassa > 0.03f) {
                    parasEro = ero; paras = i
                }
            }
            if (paras >= 0) {
                val j = jalat[paras]
                j.askel = true
                j.t = 0f
                j.alku = j.paikka
                j.kesto = kesto
                j.nosto = (0.008f + vauhti * 0.035f) * m.koko + 0.004f
            }
        }
        for (j in jalat) {
            j.nykyinen = if (j.askel) lerp(j.alku, j.kohde, pehmea(j.t)) + V3.Y * (j.nosto * kumpu(j.t)) else j.paikka
        }
    }

    // =====================================================================================================
    // Body pose
    // =====================================================================================================

    private fun rakennaAsento(dt: Float) {
        val ylos = V3.Y
        val f = V3.suunnasta(suunta)
        val r = f cross ylos
        val vauhti = nopeus.pituus

        // Crouch, with an overshooting spring so landings bounce
        val kyy = kyykky.paivita(dt, o.kyykky)

        // Pelvis bob and sway from the stepping foot
        var bob = 0f
        var sivu = 0f
        if (!ilmassa) {
            val amp = (0.003f + vauhti * 0.012f) * m.koko
            for ((i, j) in jalat.withIndex()) {
                if (j.askel) {
                    bob += amp * kumpu(j.t) - amp * 0.35f
                    val toinen = jalat[1 - i]
                    sivu += ((toinen.paikka - juuriL) dot r) * 0.28f * kumpu(j.t)
                }
            }
        }
        val hengitys = sin(aika * 2f * PI.toFloat() * 0.3f) * 0.0011f * m.koko

        var lantio = juuriL + ylos * (m.lantioSeisten * (1f - 0.42f * clamp(kyy, -0.3f, 1.2f)) + bob + korkeus) + r * sivu

        // Keep planted legs from overstretching: lower the pelvis instead
        if (!ilmassa) {
            for ((i, j) in jalat.withIndex()) {
                if (j.askel) continue
                val puoli = if (i == 0) -1f else 1f
                val lonkka = lantio + r * (puoli * m.lonkkaLeveys)
                val nilkka = j.paikka + ylos * m.nilkka
                val vaaka = (lonkka - nilkka).vaakaan().pituus
                val ulottuma = m.jalanPituus * 0.985f
                if (vaaka < ulottuma) {
                    val maksimi = nilkka.y + sqrt(ulottuma * ulottuma - vaaka * vaaka)
                    if (lantio.y > maksimi) lantio = V3(lantio.x, maksimi, lantio.z)
                }
            }
        }

        // Lean into acceleration and speed
        val kiihtyvyys = if (dt > 0f) (nopeus - edNopeus) / dt else V3.NOLLA
        var nojaKohde = kiihtyvyys.vaakaan() * 0.03f + nopeus * 0.35f + o.noja
        if (nojaKohde.pituus > 0.7f) nojaKohde = nojaKohde.suunta() * 0.7f
        val nojaNyt = noja.paivita(dt, nojaKohde)
        val selka = (ylos + nojaNyt.vaakaan()).suunta()

        // Hips and shoulders counter-rotate with the steps
        val etuV = (jalat[0].nykyinen - juuriL) dot f
        val etuO = (jalat[1].nykyinen - juuriL) dot f
        val kierto = clamp((etuV - etuO) / (0.06f * m.koko), -1f, 1f) * 0.28f
        val lonkkaSuunta = kierra(r, ylos, kierto * 0.7f)
        val olkaSuunta = kierra(r, selka, -kierto)

        val rinta = lantio + selka * (m.vartalo + hengitys)
        oma[Nivel.LANTIO] = lantio
        oma[Nivel.RINTA] = rinta
        oma[Nivel.LONKKA_V] = lantio - lonkkaSuunta * m.lonkkaLeveys
        oma[Nivel.LONKKA_O] = lantio + lonkkaSuunta * m.lonkkaLeveys
        oma[Nivel.OLKA_V] = rinta - olkaSuunta * m.olkaLeveys - selka * (0.004f * m.koko)
        oma[Nivel.OLKA_O] = rinta + olkaSuunta * m.olkaLeveys - selka * (0.004f * m.koko)

        // Head: turns towards what it looks at (within the neck's reach), tilts, and squashes on landings
        val katsePaikka = o.katse ?: (rinta + f * 1f)
        var haluttu = (katsePaikka - (rinta + selka * (m.kaula + m.paaSade))).suunta(f)
        val kaannos = clamp(kulmaero(suunta, suuntakulma(haluttu)), -1.35f, 1.35f)
        val nyokkays = clamp(asin(clamp(haluttu.y, -1f, 1f)), -0.75f, 0.85f)
        haluttu = V3.suunnasta(suunta + kaannos) * cos(nyokkays) + ylos * sin(nyokkays)
        val katseNyt = katse.paivita(dt, haluttu).suunta(f)
        val kallistusNyt = kallistus.paivita(dt, o.kallistus)
        val paaKehys0 = Kehys.luo(katseNyt, selka)
        val paaYlos = kierra(paaKehys0.ylos, paaKehys0.eteen, kallistusNyt)
        val paaKehys = Kehys.ylosta(paaYlos, katseNyt)
        oma.paa = paaKehys
        oma.paaVenytys = clamp(venytys.paivita(dt, 1f), 0.72f, 1.3f)
        val niska = rinta + selka * (0.002f * m.koko)
        oma[Nivel.NISKA] = niska
        val kaulaSuunta = (paaKehys.ylos * 0.6f + selka * 0.4f).suunta()
        oma[Nivel.PAA] = niska + kaulaSuunta * (m.kaula + m.paaSade * 0.88f * oma.paaVenytys)

        // Arms: natural swing opposite to the legs, blended with the action's hand targets
        val swingV = -(etuV - etuO) * 0.85f
        val juoksuNosto = saturoi((vauhti - 0.3f) / 0.3f)
        for (puoli in 0..1) {
            val s = if (puoli == 0) -1f else 1f
            val olka = if (puoli == 0) oma[Nivel.OLKA_V] else oma[Nivel.OLKA_O]
            val swing = if (puoli == 0) swingV else -swingV
            val oletus = olka - ylos * (m.kadenPituus * (0.93f - 0.3f * juoksuNosto)) +
                f * (swing + 0.008f * m.koko + juoksuNosto * 0.012f * m.koko) +
                r * (s * (0.011f * m.koko + vauhti * 0.012f))
            val pyynto = if (puoli == 0) o.kasiV else o.kasiO
            val paino = (if (puoli == 0) painoV else painoO).paivita(dt, if (pyynto != null) 1f else 0f)
            val kohde = if (pyynto != null) lerp(oletus, pyynto, saturoi(paino)) else oletus
            val jousi = if (puoli == 0) kasiV else kasiO
            jousi.aseta(4.5f * o.kasiJousi, 0.5f, 0.15f)
            val kasi = jousi.paivita(dt, kohde)
            val korkea = saturoi(((kasi - olka) dot ylos) / (m.kadenPituus * 0.5f) + 0.5f)
            // Elbows point back when the hand hangs, out to the side when it is raised
            val napa = olka + r * (s * lerp(0.012f, 0.05f, korkea)) + ylos * lerp(-0.03f, -0.05f, korkea) - f * lerp(0.06f, 0.0f, korkea)
            val kyynarpaa = kaksiLuuta(olka, kasi, m.olkavarsi, m.kyynarvarsi, napa)
            val kasiTodellinen = kyynarpaa + (kasi - kyynarpaa).suunta(-ylos) * m.kyynarvarsi
            if (puoli == 0) {
                oma[Nivel.KYYNARPAA_V] = kyynarpaa; oma[Nivel.KASI_V] = kasiTodellinen
            } else {
                oma[Nivel.KYYNARPAA_O] = kyynarpaa; oma[Nivel.KASI_O] = kasiTodellinen
            }
        }

        // Legs: knees bend forward towards the planted (or swinging) feet; in the air they tuck up
        for ((i, j) in jalat.withIndex()) {
            val s = if (i == 0) -1f else 1f
            val lonkka = if (i == 0) oma[Nivel.LONKKA_V] else oma[Nivel.LONKKA_O]
            val jalkaSuunta = kierra(f, ylos, s * 0.12f)
            val nilkkaKohde = if (ilmassa) {
                val tuck = saturoi(pystyNopeus * 2f + 0.6f)
                lonkka - ylos * (m.jalanPituus * lerp(0.92f, 0.55f, tuck)) + f * (0.01f * m.koko) + r * (s * 0.004f)
            } else {
                j.nykyinen + ylos * m.nilkka
            }
            val napa = lonkka + jalkaSuunta * 0.1f + r * (s * 0.015f)
            val polvi = kaksiLuuta(lonkka, nilkkaKohde, m.reisi, m.saari, napa)
            val nilkka = polvi + (nilkkaKohde - polvi).suunta(-ylos) * m.saari
            val varvasKallistus = if (j.askel) -0.35f * kumpu(j.t) else if (ilmassa) 0.5f else 0f
            val varvasSuunta = kierra(jalkaSuunta, jalkaSuunta cross ylos, varvasKallistus)
            val varvas = nilkka + varvasSuunta * m.jalkatera - ylos * (m.nilkka * 0.55f)
            if (i == 0) {
                oma[Nivel.POLVI_V] = polvi; oma[Nivel.NILKKA_V] = nilkka; oma[Nivel.VARVAS_V] = varvas
            } else {
                oma[Nivel.POLVI_O] = polvi; oma[Nivel.NILKKA_O] = nilkka; oma[Nivel.VARVAS_O] = varvas
            }
        }
        oma.paksuus = 1f
        oma.nakyvissa = true
    }

    // =====================================================================================================
    // Climbing: on a wall the climber goes on all fours, hands and feet on the surface
    // =====================================================================================================

    private var konttaus = 0f
    private var konttiMatka = 0f

    private fun konttaa(dt: Float) {
        val a = alusta
        val liikkeessa = toiminto == Toiminto.KAVELEE || toiminto == Toiminto.JUOKSEE || toiminto == Toiminto.SEISOO || toiminto == Toiminto.KATSELEE
        val haluttu = if (tyyppi == Tyyppi.KIIPEILIJA && a != null && abs(a.y.y) < 0.5f && liikkeessa && !ilmassa) 1f else 0f
        konttaus += clamp(haluttu - konttaus, -dt / 0.3f, dt / 0.3f)
        konttiMatka += nopeus.pituus * dt
        if (konttaus <= 0f) return
        val k = m.koko
        val ylos = V3.Y
        val f = V3.suunnasta(suunta)
        val r = f cross ylos
        val maa = V3(juuriL.x, pintaL?.keskus?.y ?: juuriL.y, juuriL.z)
        val hengitys = sin(aika * 2f * PI.toFloat() * 0.3f) * 0.001f * k
        val lantio = maa + ylos * (0.04f * k + hengitys)
        val rinta = lantio + f * (m.vartalo * 0.96f) + ylos * (0.014f * k)
        val p = Array(Nivel.entries.size) { V3.NOLLA }
        p[Nivel.LANTIO.ordinal] = lantio
        p[Nivel.RINTA.ordinal] = rinta
        p[Nivel.NISKA.ordinal] = rinta + f * (0.002f * k)
        // The head is held up off the wall, looking where the head already looks
        p[Nivel.PAA.ordinal] = rinta + (f * 0.55f + ylos * 0.6f + oma.paa.eteen * 0.25f).suunta() * (m.kaula + m.paaSade * 0.88f)
        // A diagonal gait driven by the distance covered: a hand or foot on the surface stays where it is
        val kierros = 0.075f * k
        val tuki = 0.65f
        val ulottuma = tuki * kierros / 2f
        for (i in 0..3) {
            val kasi = i < 2
            val s = if (i % 2 == 0) -1f else 1f // left, right
            val vaihe = ((konttiMatka / kierros) + (if (i == 0 || i == 3) 0f else 0.5f)) % 1f
            val siirto: Float
            var nosto = 0f
            if (vaihe < tuki) {
                siirto = ulottuma - vaihe / tuki * 2f * ulottuma
            } else {
                val t = (vaihe - tuki) / (1f - tuki)
                siirto = -ulottuma + pehmea(t) * 2f * ulottuma
                nosto = kumpu(t) * 0.014f * k
            }
            val tyvi = if (kasi) rinta - f * (0.004f * k) + r * (s * m.olkaLeveys) else lantio + r * (s * m.lonkkaLeveys)
            val pohja = V3(tyvi.x, maa.y, tyvi.z) + f * ((if (kasi) 0.03f else -0.014f) * k + siirto) + r * (s * (if (kasi) 0.024f else 0.03f) * k)
            if (kasi) {
                val kohde = pohja + ylos * (m.kasiSade * 0.8f + nosto)
                val kyynar = kaksiLuuta(tyvi, kohde, m.olkavarsi, m.kyynarvarsi, tyvi + r * (s * 0.05f) + ylos * 0.05f - f * 0.012f)
                p[(if (s < 0f) Nivel.OLKA_V else Nivel.OLKA_O).ordinal] = tyvi
                p[(if (s < 0f) Nivel.KYYNARPAA_V else Nivel.KYYNARPAA_O).ordinal] = kyynar
                p[(if (s < 0f) Nivel.KASI_V else Nivel.KASI_O).ordinal] = kyynar + (kohde - kyynar).suunta(-ylos) * m.kyynarvarsi
            } else {
                val kohde = pohja + ylos * (m.nilkka + nosto)
                val polvi = kaksiLuuta(tyvi, kohde, m.reisi, m.saari, tyvi + r * (s * 0.05f) + ylos * 0.05f + f * 0.02f)
                val nilkka = polvi + (kohde - polvi).suunta(-ylos) * m.saari
                p[(if (s < 0f) Nivel.LONKKA_V else Nivel.LONKKA_O).ordinal] = tyvi
                p[(if (s < 0f) Nivel.POLVI_V else Nivel.POLVI_O).ordinal] = polvi
                p[(if (s < 0f) Nivel.NILKKA_V else Nivel.NILKKA_O).ordinal] = nilkka
                p[(if (s < 0f) Nivel.VARVAS_V else Nivel.VARVAS_O).ordinal] = nilkka + (f * 0.5f + r * (s * 0.85f)).suunta() * m.jalkatera - ylos * (m.nilkka * 0.55f)
            }
        }
        val t = pehmea(konttaus)
        for (i in p.indices) oma.nivelet[i] = lerp(oma.nivelet[i], p[i], t)
    }

    // =====================================================================================================
    // Sitting on an edge
    // =====================================================================================================

    private var istumaan: V3? = null
    private var istuin: V3? = null
    private var istunta = 0f

    /** Walks to the edge of its surface at [reuna] and sits there, legs over the edge towards [ulos]. */
    fun istuReunalle(reuna: V3, ulos: V3) {
        if (kiinni || valmis || ilmassa) return
        val u = (alusta?.suuntaSisaan(ulos) ?: ulos).vaakaan().suunta(V3.Z)
        meneKohti(reuna - ulos * (0.012f * m.koko), false)
        istumaan = u
        istuin = kohde
    }

    private fun istu(dt: Float) {
        val ulos = istumaan
        // Sit down only once turned to face out over the edge
        val kaannytty = ulos != null && abs(kulmaero(suunta, suuntakulma(ulos))) < 0.5f
        val haluttu = if (toiminto == Toiminto.ISTUU && kaannytty) 1f else 0f
        istunta += clamp(haluttu - istunta, -dt / 0.35f, dt / 0.35f)
        if (istunta <= 0f) return
        // Shuffle to the exact spot, so that the knees clear the edge
        if (haluttu > 0f) istuin?.let { juuriL = lerp(juuriL, it, min(1f, dt * 6f)) }
        val ylos = V3.Y
        val f = V3.suunnasta(suunta)
        val r = f cross ylos
        val maa = V3(juuriL.x, pintaL?.keskus?.y ?: juuriL.y, juuriL.z)
        val p = oma.nivelet.copyOf()
        val lantio = maa + ylos * (m.vartaloSade * 1.05f) - f * (0.004f * m.koko)
        // The upper body keeps its shape, lowered onto the seat and leaning back a touch
        val siirto = lantio - oma[Nivel.LANTIO]
        for (n in YLAVARTALO) p[n.ordinal] = oma[n] + siirto - f * (0.1f * (oma[n].y - oma[Nivel.LANTIO].y))
        p[Nivel.LANTIO.ordinal] = lantio
        for (puoli in 0..1) {
            val s = if (puoli == 0) -1f else 1f
            // Hands rest on the surface beside the hips
            val olka = p[(if (puoli == 0) Nivel.OLKA_V else Nivel.OLKA_O).ordinal]
            val kohde = maa + r * (s * 0.034f * m.koko) - f * (0.012f * m.koko) + ylos * (m.kasiSade * 0.8f)
            val kyynar = kaksiLuuta(olka, kohde, m.olkavarsi, m.kyynarvarsi, olka - f * 0.05f + r * (s * 0.02f))
            p[(if (puoli == 0) Nivel.KYYNARPAA_V else Nivel.KYYNARPAA_O).ordinal] = kyynar
            p[(if (puoli == 0) Nivel.KASI_V else Nivel.KASI_O).ordinal] = kyynar + (kohde - kyynar).suunta(-ylos) * m.kyynarvarsi
            // Thighs out over the edge, shins swinging below it
            val lonkka = lantio + r * (s * m.lonkkaLeveys)
            val polvi = lonkka + (f + r * (s * 0.12f)).suunta() * m.reisi
            val heilu = 0.22f + 0.3f * sin(aika * 2.3f + puoli * 2.4f) // never back into the table
            val nilkka = polvi + (-ylos * cos(heilu) + f * sin(heilu)) * m.saari
            p[(if (puoli == 0) Nivel.LONKKA_V else Nivel.LONKKA_O).ordinal] = lonkka
            p[(if (puoli == 0) Nivel.POLVI_V else Nivel.POLVI_O).ordinal] = polvi
            p[(if (puoli == 0) Nivel.NILKKA_V else Nivel.NILKKA_O).ordinal] = nilkka
            p[(if (puoli == 0) Nivel.VARVAS_V else Nivel.VARVAS_O).ordinal] = nilkka + (f * cos(heilu * 0.5f) - ylos * 0.45f).suunta() * m.jalkatera
        }
        val t = pehmea(istunta)
        for (i in p.indices) oma.nivelet[i] = lerp(oma.nivelet[i], p[i], t)
    }

    // =====================================================================================================
    // Caught: knocked like a rag doll, then curled into the ball
    // =====================================================================================================

    private fun paivitaKiinni(dt: Float) {
        val n = nukke ?: return
        o.nollaa()
        val maa = maanTaso ?: pintaL?.keskus?.y ?: juuriL.y
        n.paivita(dt, maa)
        if (toiminto == Toiminto.OSUMA) {
            o.tunne = Tunne.YLLATTYNYT
            o.suuAuki = 0.8f
            for (i in oma.nivelet.indices) oma.nivelet[i] = n.paikat[i]
            if (toimintoAika >= toimintoKesto) {
                if (pallonKeskus == null) pallonKeskus = keskipiste
                aloita(Toiminto.KAPERTYY)
                aanet += Aantely.IIK
            }
        } else {
            o.tunne = Tunne.VIHAINEN
            o.silmatKiinni = toimintoAika > 0.25f
            val e = pehmea(toimintoAika / toimintoKesto)
            val c = pallonKeskus ?: keskipiste
            var painopiste = V3.NOLLA
            for (p in n.paikat) painopiste += p
            painopiste /= n.paikat.size.toFloat()
            for (i in oma.nivelet.indices) {
                val suhteellinen = (n.paikat[i] - painopiste) * (1f - 0.88f * e)
                oma.nivelet[i] = lerp(n.paikat[i], c + suhteellinen, e)
            }
            oma.paksuus = 1f - 0.7f * e
            if (toimintoAika >= toimintoKesto) {
                oma.nakyvissa = false
                valmis = true
            }
        }
        // The head keeps facing roughly where it did
        val paaYlos = (oma[Nivel.PAA] - oma[Nivel.NISKA]).suunta(V3.Y)
        oma.paa = Kehys.ylosta(paaYlos, oma.paa.eteen)
        oma.paaVenytys = clamp(venytys.paivita(dt, 1f), 0.72f, 1.3f)
        juuriL = V3(oma[Nivel.LANTIO].x, maa, oma[Nivel.LANTIO].z)
    }

    // =====================================================================================================
    // Face
    // =====================================================================================================

    private fun paivitaKasvot(dt: Float) {
        val k = oma.kasvot
        val (hy, au, le, kk, kku, il, sk) = when (o.tunne) {
            Tunne.NEUTRAALI -> Ilme(0.18f, 0f, 1f, 0f, 0f, 0.05f, 1f)
            Tunne.ILOINEN -> Ilme(0.95f, 0.22f, 1.1f, 0.35f, 0.25f, 0.5f, 1f)
            Tunne.PELOKAS -> Ilme(-0.55f, 0.45f, 0.7f, 0.85f, 0.95f, 0f, 1.22f)
            Tunne.VIHAINEN -> Ilme(-0.85f, 0.12f, 0.95f, -0.55f, -1f, 0.25f, 0.9f)
            Tunne.KIUSOITTELEVA -> Ilme(0.75f, 0.3f, 1.05f, 0.45f, -0.25f, 0.6f, 1f)
            Tunne.YLLATTYNYT -> Ilme(0f, 0.65f, 0.55f, 1f, 0.3f, 0f, 1.3f)
            Tunne.UNINEN -> Ilme(0f, 0f, 0.9f, -0.25f, 0.45f, 0f, 0.95f)
            Tunne.MIETTELIAS -> Ilme(-0.12f, 0f, 0.7f, 0.35f, 0.65f, 0f, 1f)
        }
        k.hymy = hymy.paivita(dt, hy)
        k.suuAuki = saturoi(suuAuki.paivita(dt, if (o.suuAuki >= 0f) o.suuAuki else au))
        k.suuLeveys = suuLeveys.paivita(dt, le)
        k.kulmaKorkeus = kulmaK.paivita(dt, kk)
        k.kulmaKulma = kulmaKulma.paivita(dt, kku)
        k.ilo = saturoi(ilo.paivita(dt, il))
        k.silmaKoko = silmaKoko.paivita(dt, sk)
        k.kieli = saturoi(kieli.paivita(dt, if (o.kieli >= 0f) o.kieli else 0f))

        // Blinks: every few seconds, sometimes twice
        rapsayAjastin -= dt
        if (rapsayAjastin <= 0f && rapsays < 0f) {
            rapsays = 0f
            rapsayAjastin = if (rnd.nextFloat() < 0.2f) 0.25f else 1.8f + rnd.nextFloat() * 3.2f
        }
        var auki = when (o.tunne) {
            Tunne.UNINEN -> 0.5f
            Tunne.VIHAINEN -> 0.75f
            else -> 1f
        }
        if (rapsays >= 0f) {
            rapsays += dt / 0.14f
            auki *= 1f - kumpu(rapsays)
            if (rapsays >= 1f) rapsays = -1f
        }
        if (o.silmatKiinni) auki = 0f
        val luomi = luomet.paivita(dt, auki)
        k.aukiV = saturoi(luomi)
        k.aukiO = saturoi(luomi)

        // Pupils lead the head: they jump straight to the target, the head follows
        val kohde = palloL?.takeIf { (it - oma[Nivel.PAA]).pituus < 2.5f } ?: o.katse
        sakkadiAjastin -= dt
        if (sakkadiAjastin <= 0f) {
            sakkadiAjastin = 0.4f + rnd.nextFloat() * 1.4f
            sakkadiX = (rnd.nextFloat() - 0.5f) * 0.35f
            sakkadiY = (rnd.nextFloat() - 0.5f) * 0.25f
        }
        var x = sakkadiX
        var y = sakkadiY
        if (kohde != null) {
            val h = oma.paa
            val d = (kohde - oma[Nivel.PAA]).suunta(h.eteen)
            x = clamp((d dot h.oikealle) * 1.6f, -1f, 1f) + sakkadiX * 0.25f
            y = clamp((d dot h.ylos) * 1.6f, -1f, 1f) + sakkadiY * 0.25f
        }
        k.katseX = silmaX.paivita(dt, x)
        k.katseY = silmaY.paivita(dt, y)
    }

    private data class Ilme(
        val hymy: Float, val auki: Float, val leveys: Float, val kulmaK: Float, val kulmaKulma: Float,
        val ilo: Float, val silmaKoko: Float,
    )

    companion object {
        /** Gravity for the figures' own jumps: a bit floaty, so small hops read as cartoon hops. */
        const val PAINOVOIMA = 6.5f
        private val VAISTOT = setOf(Toiminto.VAISTAA_HYPPY, Toiminto.VAISTAA_KYYKKY)
        private val YLAVARTALO = listOf(Nivel.RINTA, Nivel.NISKA, Nivel.PAA, Nivel.OLKA_V, Nivel.OLKA_O, Nivel.KYYNARPAA_V, Nivel.KYYNARPAA_O, Nivel.KASI_V, Nivel.KASI_O)

        /** Two-bone IK: where the middle joint goes when [a] reaches for [c], bending towards [napa]. */
        fun kaksiLuuta(a: V3, c: V3, l1: Float, l2: Float, napa: V3): V3 {
            val d = c - a
            val suunta = d.suunta(V3(0f, -1f, 0f))
            val pituus = clamp(d.pituus, abs(l1 - l2) + 1e-4f, l1 + l2 - 1e-4f)
            val cosA = clamp((l1 * l1 + pituus * pituus - l2 * l2) / (2f * l1 * pituus), -1f, 1f)
            val pitkin = l1 * cosA
            val korkeus = l1 * sqrt(max(0f, 1f - cosA * cosA))
            val napaSuunta = (napa - a).tasolle(suunta).suunta(fi.otsosoft.tikkujahti.matikka.kohtisuora(suunta))
            return a + suunta * pitkin + napaSuunta * korkeus
        }
    }
}
