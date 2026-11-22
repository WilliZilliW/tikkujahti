package fi.otsosoft.tikkujahti.peli

import fi.otsosoft.tikkujahti.maailma.Maailma
import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.matikka.kumpu
import fi.otsosoft.tikkujahti.matikka.lerp
import fi.otsosoft.tikkujahti.matikka.pehmea
import fi.otsosoft.tikkujahti.matikka.saturoi
import fi.otsosoft.tikkujahti.matikka.suuntakulma
import fi.otsosoft.tikkujahti.ukko.Aantely
import fi.otsosoft.tikkujahti.ukko.Luonne
import fi.otsosoft.tikkujahti.ukko.Nivel
import fi.otsosoft.tikkujahti.ukko.Ukko
import fi.otsosoft.tikkujahti.ukko.Ulkonako
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class Aani { HEITTO, POMPPU, OSUMA, KERAYS, AANTELY }

/**
 * Something that happened this frame, for sounds and effects.
 *
 * @param voima 0…1 loudness (bounces)
 * @param aantely the voice sound when [laji] is [Aani.AANTELY], with the figure's own voice pitch in [korkeus]
 */
class Tapahtuma(val laji: Aani, val paikka: V3, val voima: Float = 1f, val aantely: Aantely? = null, val korkeus: Float = 1f)

/**
 * The game: stick figures wander on the floor of [maailma], the player throws balls at them, and a hit
 * figure curls into the ball, which flies back to the player.
 */
class Peli(siemen: Int = 1) {
    val maailma = Maailma()
    val ukot = ArrayList<Ukko>()
    val pallot = ArrayList<Pallo>()
    /** What happened during the latest [paivita]; the platform plays these and need not clear them. */
    val tapahtumat = ArrayList<Tapahtuma>()
    var keratyt = 0
        private set
    var heitot = 0
        private set
    /** How many figures are out at once. */
    var ukkojaKerralla = 2

    private val rnd = Random(siemen)
    private var seuraavaId = 1
    private var ukkoAjastin = 0.8f
    private var edellinenVari = -1

    fun paivita(dt0: Float, kamera: Kamera) {
        val dt = dt0.coerceIn(0.0001f, 0.05f)
        tapahtumat.clear()
        val lattia = maailma.lattia

        // New figures arrive one at a time
        if (lattia != null && ukot.count { !it.kiinni } < ukkojaKerralla) {
            ukkoAjastin -= dt
            if (ukkoAjastin <= 0f) {
                ukkoAjastin = 1.5f + rnd.nextFloat() * 2.5f
                uusiUkko(kamera)
            }
        }

        // Balls
        val askelia = (dt / (1f / 180f)).toInt() + 1
        val h = dt / askelia
        for (pallo in pallot) {
            pallo.ika += dt
            pallo.tilaAika += dt
            when (pallo.tila) {
                PallonTila.LENTAA -> lenna(pallo, h, askelia)
                PallonTila.VANGITSEE -> {
                    val u = pallo.ukko
                    // A little pop, then the ball hangs where it hit while the figure curls in
                    pallo.koko = 1f + 0.35f * kumpu(pallo.tilaAika / 0.25f)
                    pallo.p += V3.Y * (0.05f * dt * (1f - saturoi(pallo.tilaAika / 1.0f)))
                    u?.pallonKeskus = pallo.p
                    if (u == null || u.valmis) pallo.vaihda(PallonTila.PALAA)
                }
                PallonTila.PALAA -> {
                    // A happy wobble, then home to the player's hand
                    val t = pallo.tilaAika
                    if (t < 0.45f) {
                        pallo.koko = 1f + 0.18f * sin(t * 40f) * (1f - t / 0.45f)
                        pallo.pyorita(kamera.kehys.eteen, sin(t * 30f) * 6f * dt)
                    } else {
                        pallo.koko = 1f
                        val koti = kamera.paikka + kamera.kehys.eteen * 0.12f - kamera.kehys.ylos * 0.1f
                        val ero = koti - pallo.p
                        val matka = ero.pituus
                        val vauhti = 1.5f + (t - 0.45f) * 9f
                        if (matka < vauhti * dt + 0.06f) {
                            pallo.vaihda(PallonTila.POIS)
                            keratyt++
                            tapahtumat += Tapahtuma(Aani.KERAYS, pallo.p)
                        } else {
                            pallo.p += ero * (vauhti * dt / matka)
                            pallo.pyorita(kamera.kehys.oikealle, -vauhti / pallo.sade * dt * 0.3f)
                        }
                    }
                }
                PallonTila.HAIPUU -> {
                    pallo.koko = 1f - pehmea(pallo.tilaAika / 0.35f)
                    if (pallo.tilaAika >= 0.35f) pallo.vaihda(PallonTila.POIS)
                }
                PallonTila.POIS -> {}
            }
        }
        pallot.removeAll { it.tila == PallonTila.POIS }

        // Figures
        for (u in ukot) {
            u.lattia = lattia
            u.katsoja = kamera.paikka
            u.katsojanSuunta = kamera.kehys.eteen
            var lahin: Pallo? = null
            for (pallo in pallot) {
                if (pallo.tila != PallonTila.LENTAA) continue
                if (pallo.pomppuja == 0) u.havaitsePallo(pallo.id, pallo.p, pallo.v, Pallo.PAINOVOIMA + pallo.kaarre, pallo.sade)
                if (lahin == null || pallo.p.etaisyys(u.juuri) < lahin.p.etaisyys(u.juuri)) lahin = pallo
            }
            u.pallo = lahin?.p
            u.paivita(dt)
            for (a in u.aanet) tapahtumat += Tapahtuma(Aani.AANTELY, u.keskipiste, aantely = a, korkeus = u.ulkonako.aani)
            u.aanet.clear()
        }
        ukot.removeAll { it.valmis }
    }

    private fun lenna(pallo: Pallo, h: Float, askelia: Int) {
        repeat(askelia) {
            val ed = pallo.p
            val isku = pallo.askel(h, maailma.pinnat)
            if (isku > 0.35f) tapahtumat += Tapahtuma(Aani.POMPPU, pallo.p, saturoi(isku / 5f))
            // Only a ball still on the fly catches: one that has bounced twice or rolls is spent
            if (pallo.pomppuja <= 1 && pallo.v.pituus > 0.6f) {
                for (u in ukot) {
                    if (u.kiinni || !u.asento.nakyvissa) continue
                    val kohta = osuuko(ed, pallo.p, pallo.sade, u) ?: continue
                    pallo.p = kohta
                    u.pallonKeskus = kohta
                    u.osuma(kohta, pallo.v)
                    pallo.ukko = u
                    pallo.v = V3.NOLLA
                    pallo.vaihda(PallonTila.VANGITSEE)
                    tapahtumat += Tapahtuma(Aani.OSUMA, kohta)
                    return
                }
            }
        }
        val lattiaY = maailma.lattia?.keskus?.y ?: (pallo.p.y - 10f)
        val levossa = pallo.v.pituus < 0.12f && pallo.ika > 0.5f
        if (levossa || pallo.ika > 6f || pallo.p.y < lattiaY - 3f) pallo.vaihda(PallonTila.HAIPUU)
    }

    /** Where along the ball's step from [a] to [b] it touches the figure, or null. */
    private fun osuuko(a: V3, b: V3, sade: Float, u: Ukko): V3? {
        val m = u.m
        val as0 = u.asento
        // Quick reject: nowhere near the figure
        val keski = as0[Nivel.RINTA]
        if (janojenEtaisyys(a, b, keski, keski).first > (m.pituus + sade) * (m.pituus + sade)) return null
        var parasS = 2f
        fun kokeile(n0: Nivel, n1: Nivel, r: Float) {
            val (d2, s) = janojenEtaisyys(a, b, as0[n0], as0[n1])
            val raja = sade + r * OSUMA_APU
            if (d2 < raja * raja && s < parasS) parasS = s
        }
        kokeile(Nivel.PAA, Nivel.PAA, m.paaSade)
        kokeile(Nivel.LANTIO, Nivel.RINTA, m.vartaloSade)
        for ((n0, n1) in LUUT) kokeile(n0, n1, m.raajaSade)
        return if (parasS <= 1f) lerp(a, b, parasS) else null
    }

    /**
     * Throws a ball towards where the swipe aimed. It starts from [mista]: where the platform draws the ball
     * in the player's hand, by default low in front of the camera.
     */
    fun heita(heitto: Heitto, kamera: Kamera, mista: V3? = null): Pallo {
        val alku = mista ?: (kamera.paikka + kamera.kehys.eteen * 0.3f - kamera.kehys.ylos * 0.12f)
        val suunta = kamera.sade(heitto.x, heitto.y)

        // The aim point: the first surface the aiming ray meets, or a spot a few metres out
        var matka = 2.5f
        for (pinta in maailma.pinnat) {
            val nimittaja = suunta dot pinta.normaali
            if (nimittaja > -1e-4f) continue
            val t = -pinta.etaisyys(kamera.paikka) / nimittaja
            if (t > 0.3f && t < matka && pinta.sisalla(kamera.paikka + suunta * t)) matka = t
        }
        var kohde = kamera.paikka + suunta * matka

        // Aim assist: a throw that nearly points at a figure is nudged onto it
        var parasKulma = TAHTAYSAPU
        for (u in ukot) {
            if (u.kiinni) continue
            val kohti = u.keskipiste - kamera.paikka
            val kulma = fi.otsosoft.tikkujahti.matikka.kulmaValilla(suunta, kohti.suunta())
            if (kulma < parasKulma) {
                parasKulma = kulma
                kohde = lerp(kohde, u.keskipiste, 0.8f)
            }
        }

        // A ballistic arc that reaches the aim point: harder throws fly faster and flatter
        val vauhti = lerp(3.2f, 7.5f, heitto.voima)
        val ero = kohde - alku
        val aika = (ero.pituus / vauhti).coerceAtLeast(0.08f)
        val kaarre = kamera.kehys.oikealle * (heitto.kaarre * KAARRE)
        val v = ero / aika - (Pallo.PAINOVOIMA + kaarre) * (aika / 2f)
        val pallo = Pallo(seuraavaId++, alku, v, Pallo.SADE)
        pallo.kaarre = kaarre
        pallot += pallo
        heitot++
        tapahtumat += Tapahtuma(Aani.HEITTO, alku, 0.5f + 0.5f * heitto.voima)
        return pallo
    }

    private fun uusiUkko(kamera: Kamera) {
        val lattia = maailma.lattia ?: return
        val eteen = kamera.kehys.eteen.vaakaan().suunta(V3.Z)
        val kulma = suuntakulma(eteen)
        val nakokentta = kotlin.math.atan(kamera.tanPuoliFov * kamera.kuvasuhde)
        var paikka: V3? = null
        for (yritys in 0 until 20) {
            // Inside the player's view if possible, wider if the first tries fail
            val a = kulma + (rnd.nextFloat() - 0.5f) * 2f * (nakokentta * 0.75f + yritys * 0.08f)
            val d = 1.1f + rnd.nextFloat() * 1.3f
            val p = lattia.lahinPiste(V3(kamera.paikka.x + sin(a) * d, lattia.keskus.y, kamera.paikka.z + cos(a) * d), 0.12f)
            val katsojasta = (p - kamera.paikka).vaakaan().pituus
            if (katsojasta < 0.6f && yritys < 19) continue
            if (ukot.any { (it.juuri - p).pituus < 0.3f } && yritys < 19) continue
            paikka = p
            break
        }
        val p = paikka ?: return
        var vari = rnd.nextInt(Ulkonako.VARIT.size)
        if (vari == edellinenVari) vari = (vari + 1) % Ulkonako.VARIT.size
        edellinenVari = vari
        val ulkonako = Ulkonako(
            vari = Ulkonako.VARIT[vari],
            koko = 0.85f + rnd.nextFloat() * 0.4f,
            luonne = Luonne(
                ujous = rnd.nextFloat(),
                leikkisyys = rnd.nextFloat(),
                vaistotaito = 0.15f + rnd.nextFloat() * 0.5f,
                vilkkaus = rnd.nextFloat(),
            ),
            aani = 0.8f + rnd.nextFloat() * 0.5f,
        )
        val u = Ukko(seuraavaId++, ulkonako, p, suuntakulma((kamera.paikka - p).vaakaan()), rnd.nextInt())
        u.lattia = lattia
        u.katsoja = kamera.paikka
        ukot += u
    }

    companion object {
        /** Hit shapes are this much fatter than the drawn limbs: near misses on a thin leg would feel unfair. */
        const val OSUMA_APU = 1.6f
        /** Aim assist cone, radians. */
        const val TAHTAYSAPU = 0.14f
        /** Sideways acceleration of a fully curved throw, m/s². */
        const val KAARRE = 7f

        private val LUUT = listOf(
            Nivel.RINTA to Nivel.PAA,
            Nivel.OLKA_V to Nivel.KYYNARPAA_V, Nivel.KYYNARPAA_V to Nivel.KASI_V,
            Nivel.OLKA_O to Nivel.KYYNARPAA_O, Nivel.KYYNARPAA_O to Nivel.KASI_O,
            Nivel.LONKKA_V to Nivel.POLVI_V, Nivel.POLVI_V to Nivel.NILKKA_V, Nivel.NILKKA_V to Nivel.VARVAS_V,
            Nivel.LONKKA_O to Nivel.POLVI_O, Nivel.POLVI_O to Nivel.NILKKA_O, Nivel.NILKKA_O to Nivel.VARVAS_O,
        )
    }
}
