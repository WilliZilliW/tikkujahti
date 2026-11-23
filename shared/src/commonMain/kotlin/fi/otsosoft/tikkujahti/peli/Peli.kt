package fi.otsosoft.tikkujahti.peli

import fi.otsosoft.tikkujahti.maailma.Maailma
import fi.otsosoft.tikkujahti.maailma.PaikanLaji
import fi.otsosoft.tikkujahti.maailma.Paikat
import fi.otsosoft.tikkujahti.maailma.PinnanLaji
import fi.otsosoft.tikkujahti.maailma.Pinta
import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.matikka.kierra
import fi.otsosoft.tikkujahti.matikka.kulmaValilla
import fi.otsosoft.tikkujahti.matikka.kumpu
import fi.otsosoft.tikkujahti.matikka.lerp
import fi.otsosoft.tikkujahti.matikka.pehmea
import fi.otsosoft.tikkujahti.matikka.saturoi
import fi.otsosoft.tikkujahti.matikka.suuntakulma
import fi.otsosoft.tikkujahti.ukko.Aantely
import fi.otsosoft.tikkujahti.ukko.Harvinaisuus
import fi.otsosoft.tikkujahti.ukko.Luonne
import fi.otsosoft.tikkujahti.ukko.Nivel
import fi.otsosoft.tikkujahti.ukko.Toiminto
import fi.otsosoft.tikkujahti.ukko.Tyyppi
import fi.otsosoft.tikkujahti.ukko.Ukko
import fi.otsosoft.tikkujahti.ukko.Ulkonako
import fi.otsosoft.tikkujahti.ukko.Vari
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class Aani { HEITTO, POMPPU, OSUMA, KERAYS, AANTELY, TORJUNTA, TUTKA, HIDASTUS, LIIMA, POIMINTA, KIMALLUS, PYRISTELY, KARKASI, TASO }

/**
 * Something that happened this frame, for sounds and effects.
 *
 * @param voima 0…1 loudness (bounces)
 * @param aantely the voice sound when [laji] is [Aani.AANTELY], with the figure's own voice pitch in [korkeus]
 * @param teksti a short note to show the player, e.g. what they just found
 * @param nuoli whether a sound from out of view may be pointed at with an arrow (harder levels give fewer hints)
 */
class Tapahtuma(
    val laji: Aani,
    val paikka: V3,
    val voima: Float = 1f,
    val aantely: Aantely? = null,
    val korkeus: Float = 1f,
    val teksti: String? = null,
    val nuoli: Boolean = true,
)

/** A special ball lying somewhere in the room, glittering. Hit it with a ball to pick it up. */
class Aarre(val laji: PalloLaji, val paikka: V3) {
    var ika = 0f
    internal var kilahdus = 0.5f
}

/** A burst: a special ball going off, a ninja's puff of smoke. Drawn as a growing, fading sphere. */
class Tehoste(val vari: Vari, val paikka: V3, val sade: Float, val kesto: Float) {
    var ika = 0f
    /** Radius now. */
    val koko: Float get() = sade * pehmea(ika / kesto * 1.4f)
    /** Opacity now, 0…1. */
    val nakyvyys: Float get() = 1f - saturoi(ika / kesto)
}

/**
 * The game: stick figures of many kinds wander the surfaces of [maailma], the player throws balls at them,
 * and a hit figure curls into the ball, which flies back to the player.
 */
class Peli(siemen: Int = 1, val albumi: Albumi = Albumi(), val reppu: Reppu = Reppu()) {
    val maailma = Maailma()
    val ukot = ArrayList<Ukko>()
    val pallot = ArrayList<Pallo>()
    val aarteet = ArrayList<Aarre>()
    val tehosteet = ArrayList<Tehoste>()
    /** What happened during the latest [paivita]; the platform plays these and need not clear them. */
    val tapahtumat = ArrayList<Tapahtuma>()
    var keratyt = 0
        private set
    var heitot = 0
        private set
    var pisteet = 0
        private set
    /** Catches in a row without a miss. The next catch scores times [kerroin]. */
    var putki = 0
        private set
    val kerroin: Int get() = (putki + 1).coerceAtMost(SUURIN_KERROIN)
    /** Which kinds of figure may arrive; the lab narrows this to one. */
    var tyypit: List<Tyyppi> = Tyyppi.entries
    /** How many figures (or herds) are out at once. */
    var ukkojaKerralla = 2
    /**
     * Whether a caught figure may struggle in the ball, so that the player has to tap the ball ([napauta])
     * to keep it. Platforms that pass taps on switch this on.
     */
    var pyristely = false
    /**
     * The real game: the player walks about the house, phone in hand, looking for figures and balls. Figures
     * turn up all around, each keeping to its own spot, also out of sight and in the room's nooks and corners;
     * the ones left far behind go, and new ones appear where the player is now. When off, figures turn up in
     * front of the player and stay in view (the browser's room, where the player cannot walk).
     */
    var etsinta = false
    /**
     * Whether the game is played in levels ([Taso]): each is finished by catching enough figures, and each
     * hides them better than the last. The level reached is kept in [albumi]. Off, every kind of figure may
     * turn up and nothing gets harder (the lab, the tests).
     */
    var tasot = false
    var taso = Taso(albumi.taso)
        private set
    /** Whether to tell the player when a new kind of surface or spot is recognised (AR, where the room is unknown). */
    var ilmoitaLoydot = false
    private var paikkaAjastin = 0f
    private val loydetyt = HashSet<String>()
    /** Whether special balls turn up in the room. */
    var aarteita = true

    private val rnd = Random(siemen)
    private var seuraavaId = 1
    private var ukkoAjastin = 0.8f
    private var aarreAjastin = 10f
    private var edellinenVari = -1
    private var omaKatto: Pinta? = null
    private val vihjattu = HashSet<Int>()

    /** A climber's plan: 0 on the floor, 1 heading for a wall, 2 on the wall, 3 heading back down. */
    private class Reitti(var ajastin: Float) {
        var vaihe = 0
        var seina: String? = null
        var kohta = V3.NOLLA
    }

    private val reitit = HashMap<Ukko, Reitti>()
    private val laumaAjastimet = HashMap<Int, Float>()

    fun paivita(dt0: Float, kamera: Kamera) {
        val dt = dt0.coerceIn(0.0001f, 0.05f)
        tapahtumat.clear()
        val lattia = maailma.lattia

        // New figures arrive one at a time
        val paikalla = ukot.filter { !it.kiinni }.map { if (it.ryhma != 0) -it.ryhma else it.id }.distinct().size
        if (taso.numero != albumi.taso) taso = Taso(albumi.taso)
        val enintaan = if (tasot && etsinta) taso.ukkoja else ukkojaKerralla
        if (lattia != null && paikalla < enintaan) {
            ukkoAjastin -= dt
            if (ukkoAjastin <= 0f) {
                ukkoAjastin = 1.5f + rnd.nextFloat() * 2.5f
                uusiUkko(kamera, lattia)
            }
        }
        paikkaAjastin -= dt
        if (paikkaAjastin <= 0f) {
            paikkaAjastin = 1f
            tutkiHuone()
        }
        if (lattia != null && aarteita) paivitaAarteet(dt, kamera)

        // Balls
        val askelia = (dt / (1f / 180f)).toInt() + 1
        val h = dt / askelia
        var i = 0
        while (i < pallot.size) { // by index: a multi-ball adds balls while this runs
            val pallo = pallot[i++]
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
                    if (u == null || u.valmis) {
                        // The rarer the figure, the likelier it puts up a fight
                        val sisu = when (u?.ulkonako?.tyyppi?.harvinaisuus) {
                            null -> 0f
                            Harvinaisuus.TAVALLINEN -> 0.12f
                            Harvinaisuus.HARVINAINEN -> 0.3f
                            Harvinaisuus.TOSI_HARVINAINEN -> 0.55f
                        }
                        if (pyristely && rnd.nextFloat() < sisu) {
                            pallo.vaihda(PallonTila.PYRISTELEE)
                            tapahtumat += Tapahtuma(Aani.PYRISTELY, pallo.p, teksti = "Ukko pyristelee! Napauta palloa nopeasti!")
                        } else {
                            pallo.vaihda(PallonTila.PALAA)
                        }
                    }
                }
                PallonTila.PYRISTELEE -> {
                    val t = pallo.tilaAika
                    pallo.koko = 1f + 0.22f * sin(t * 34f)
                    pallo.pyorita(kamera.kehys.eteen, sin(t * 26f) * 9f * dt)
                    if (t > PYRISTELYAIKA) karkaa(pallo, kamera)
                }
                PallonTila.PALAA -> palaa(pallo, dt, kamera)
                PallonTila.HAIPUU -> {
                    pallo.koko = 1f - pehmea(pallo.tilaAika / 0.35f)
                    if (pallo.tilaAika >= 0.35f) pallo.vaihda(PallonTila.POIS)
                }
                PallonTila.POIS -> {}
            }
        }
        pallot.removeAll { it.tila == PallonTila.POIS }
        for (t in tehosteet) t.ika += dt
        tehosteet.removeAll { it.ika >= it.kesto }

        // Figures
        for (u in ukot) {
            if (!u.kiinni) u.lattia = pintaUkolle(u, lattia)
            u.maanTaso = lattia?.keskus?.y
            u.katsoja = kamera.paikka
            u.katsojanSuunta = if (etsinta) null else kamera.kehys.eteen
            // Left behind in another room: gone, so that there is always something where the player is now
            if (etsinta && !u.kiinni && (u.juuri - kamera.paikka).vaakaan().pituus > JATTOMATKA) u.katoa()
            var lahin: Pallo? = null
            for (pallo in pallot) {
                if (pallo.tila != PallonTila.LENTAA) continue
                if (pallo.pomppuja == 0) {
                    u.havaitsePallo(pallo.id, pallo.p, pallo.v, Pallo.PAINOVOIMA * pallo.laji.paino + pallo.kaarre, pallo.sade, pallo.laji != PalloLaji.VERKKO)
                }
                if (lahin == null || pallo.p.etaisyys(u.juuri) < lahin.p.etaisyys(u.juuri)) lahin = pallo
            }
            u.pallo = lahin?.p
            // Special figures do not hang about for ever: miss your chance and they slip away
            if (u.ulkonako.tyyppi == Tyyppi.NINJA && tyypit.size > 1 && u.ika > (if (tasot) taso.vierailu else VIERAILU) && !u.kiinni && !u.ilmassa && lahin == null) {
                // A ninja does not walk away: a puff of smoke, and it is gone
                savu(u.keskipiste, u.m.pituus)
                u.katoa()
            } else if (u.ulkonako.tyyppi != Tyyppi.TAVALLINEN && tyypit.size > 1 && u.ika > (if (tasot) taso.vierailu else VIERAILU) && !u.kiinni && !u.ilmassa && lahin == null) {
                val pois = (u.juuri - kamera.paikka).vaakaan().suunta(V3.Z)
                u.pakene(u.juuri + (if (u.seinalla) V3.Y * 0.6f else pois * 1.2f))
            }
            if (u.ulkonako.tyyppi == Tyyppi.KIIPEILIJA || u.ulkonako.tyyppi == Tyyppi.NINJA) ohjaaKiipeilijaa(u, dt, lattia)
            istuta(u, dt)
            piilota(u, dt)
            u.paivita(dt)
            for (a in u.aanet) {
                tapahtumat += Tapahtuma(Aani.AANTELY, u.keskipiste, aantely = a, korkeus = u.ulkonako.aani, nuoli = !tasot || rnd.nextFloat() < taso.nuolia)
            }
            u.aanet.clear()
        }
        if (lattia != null) ohjaaLaumoja(dt, kamera, lattia)
        ukot.removeAll { it.valmis }
        reitit.keys.retainAll { it in ukot }
    }

    // =====================================================================================================
    // Surfaces, climbers and herds
    // =====================================================================================================

    /** The surface a figure should be on now: surfaces are replaced as AR tracking refines them. */
    private fun pintaUkolle(u: Ukko, lattia: Pinta?): Pinta? {
        val vanha = u.lattia ?: return lattia
        // Figures on the floor follow the floor; ones on a table, wall or ceiling stay on theirs while it exists
        if (vanha.laji == PinnanLaji.LATTIA) return lattia
        return maailma.pinnat.firstOrNull { it.id == vanha.id } ?: omaKatto?.takeIf { it.id == vanha.id } ?: lattia
    }

    private fun ohjaaKiipeilijaa(u: Ukko, dt: Float, lattia: Pinta?) {
        if (lattia == null || u.kiinni || u.toiminto == Toiminto.SAAPUU) return
        val r = reitit.getOrPut(u) { Reitti(1.5f + rnd.nextFloat() * 3f) }
        when (r.vaihe) {
            0 -> {
                r.ajastin -= dt
                if (r.ajastin > 0f || u.ilmassa) return
                r.ajastin = 2.5f
                val seinat = maailma.pinnat.filter { it.laji == PinnanLaji.SEINA && abs(it.normaali.y) < 0.3f }
                for (yritys in 0 until 8) {
                    val seina = seinat.randomOrNull(rnd) ?: return
                    val p = seina.satunnainenPiste(rnd, 0.15f) ?: continue
                    val juurella = V3(p.x, lattia.keskus.y, p.z)
                    val lahto = juurella + seina.normaali.vaakaan().suunta() * 0.07f
                    // The wall has to come down to the floor here, with free floor in front of it
                    if (!lattia.sisalla(lahto) || !seina.sisalla(juurella + V3.Y * 0.1f)) continue
                    if (abs(seina.etaisyys(juurella)) > 0.05f || (lahto - u.juuri).pituus > 3.5f) continue
                    r.seina = seina.id
                    r.kohta = lahto
                    r.vaihe = 1
                    u.omatoiminen = false
                    u.meneKohti(lahto, true)
                    return
                }
            }
            1 -> if (u.perilla && !u.ilmassa) {
                val seina = maailma.pinnat.firstOrNull { it.id == r.seina }
                u.omatoiminen = true
                if (seina != null && (u.juuri - r.kohta).pituus < 0.15f) {
                    // Step onto the wall and carry on upwards
                    u.lattia = seina
                    u.meneKohti(u.juuri + V3.Y * (0.5f + rnd.nextFloat() * 0.9f), true)
                    r.vaihe = 2
                    r.ajastin = 9f + rnd.nextFloat() * 9f
                } else {
                    r.vaihe = 0 // something got in the way (a dodge, say): try again in a moment
                }
            }
            2 -> {
                if (!u.seinalla) {
                    r.vaihe = 0
                    return
                }
                r.ajastin -= dt
                if (r.ajastin <= 0f && !u.ilmassa) {
                    r.kohta = V3(u.juuri.x, lattia.keskus.y + 0.05f, u.juuri.z)
                    r.vaihe = 3
                    u.omatoiminen = false
                    u.meneKohti(r.kohta, false)
                }
            }
            3 -> if (u.perilla && !u.ilmassa) {
                u.omatoiminen = true
                if ((u.juuri - r.kohta).pituus < 0.15f) {
                    u.lattia = lattia
                    r.vaihe = 0
                    r.ajastin = 5f + rnd.nextFloat() * 6f
                } else {
                    r.vaihe = 2
                    r.ajastin = 1f
                }
            }
        }
    }

    /** Now and then a figure idling on a table top goes to sit on its edge, legs dangling. */
    private fun istuta(u: Ukko, dt: Float) {
        val pinta = u.lattia ?: return
        if (pinta.laji != PinnanLaji.POYTA || !u.omatoiminen || u.toiminto != Toiminto.SEISOO || u.ilmassa) return
        if (rnd.nextFloat() > dt * 0.35f) return
        val (reuna, ulos) = pinta.lahinReuna(u.juuri)
        // Not where a wall or anything else known is in the way of the legs
        val jalat = reuna + ulos * (0.05f * u.m.koko) - pinta.normaali * (0.06f * u.m.koko)
        if (maailma.pinnat.any { it !== pinta && abs(it.etaisyys(jalat)) < 0.08f && it.sisalla(jalat, 0.02f) }) return
        u.istuReunalle(reuna, ulos)
    }

    /** Works out the room's spots again, and tells the player about the first of each new kind of thing found. */
    private fun tutkiHuone() {
        maailma.paikat = Paikat.etsi(maailma, maailma.varjot)
        if (!ilmoitaLoydot || maailma.lattia == null) return
        val kamera = ukot.firstOrNull()?.katsoja ?: maailma.lattia!!.keskus
        fun loyto(avain: String, teksti: String) {
            if (loydetyt.add(avain)) tapahtumat += Tapahtuma(Aani.KIMALLUS, kamera, 0.5f, teksti = teksti)
        }
        for (p in maailma.pinnat) when (p.laji) {
            PinnanLaji.SEINA -> loyto("seina", "Tunnistin seinän: kiipeilijät pääsevät sille.")
            PinnanLaji.POYTA -> loyto("poyta", "Tunnistin tason: ukot voivat istua sen reunalla.")
            PinnanLaji.KATTO -> loyto("katto", "Tunnistin katon: kattoukot roikkuvat siitä.")
            else -> {}
        }
        for (p in maailma.paikat) when (p.laji) {
            PaikanLaji.NURKKA -> loyto("nurkka", "Löytyi nurkka: ujot ukot piiloutuvat sinne.")
            PaikanLaji.KOLO -> loyto("kolo", "Löytyi kolo tason alta: sieltä voi löytyä jotain.")
            PaikanLaji.VARJO -> loyto("varjo", "Löytyi varjoisa paikka: siellä viihtyvät piiloukot.")
            PaikanLaji.KORKEA -> loyto("korkea", "Löytyi korkea paikka: sinne päätyy erikoispalloja.")
        }
    }

    /** Shy figures and the invisible one slip off to corners, nooks and shadows, and peek out from there. */
    private fun piilota(u: Ukko, dt: Float) {
        val tyyppi = u.ulkonako.tyyppi
        val ujo = tyyppi == Tyyppi.PIILO || (tyyppi == Tyyppi.TAVALLINEN && u.ulkonako.luonne.ujous > 0.6f)
        if (!ujo || !u.omatoiminen || u.toiminto != Toiminto.SEISOO || u.ilmassa || u.lattia?.laji != PinnanLaji.LATTIA) return
        if (rnd.nextFloat() > dt * 0.2f) return
        val paikka = maailma.paikat.filter { it.lattialla && (it.piste - u.juuri).pituus in 0.25f..3f }.minByOrNull { (it.piste - u.juuri).pituus } ?: return
        u.meneKohti(paikka.piste, u.ulkonako.luonne.ujous > 0.85f)
    }

    /** A herd moves as one: every few seconds the whole group heads for a new spot. */
    private fun ohjaaLaumoja(dt: Float, kamera: Kamera, lattia: Pinta) {
        val laumat = ukot.filter { it.ryhma != 0 && !it.kiinni }.groupBy { it.ryhma }
        laumaAjastimet.keys.retainAll(laumat.keys)
        for ((ryhma, jasenet) in laumat) {
            val t = (laumaAjastimet[ryhma] ?: 2.5f) - dt
            if (t > 0f || jasenet.any { it.toiminto == Toiminto.SAAPUU }) {
                laumaAjastimet[ryhma] = t
                continue
            }
            laumaAjastimet[ryhma] = 2.5f + rnd.nextFloat() * 3f
            val kohde = (if (etsinta) {
                // The herd roams around where it is, not after the player
                val keski = jasenet.fold(V3.NOLLA) { a, u -> a + u.juuri } / jasenet.size.toFloat()
                lattia.lahinPiste(keski + V3.suunnasta(rnd.nextFloat() * 6.28f) * (0.4f + rnd.nextFloat() * 0.8f), 0.12f)
            } else paikkaNakyvissa(kamera, lattia, emptyList())) ?: continue
            val juosten = rnd.nextFloat() < 0.4f
            for ((k, u) in jasenet.withIndex()) {
                val kulma = k * 2f * PI.toFloat() / 3f + ryhma
                u.meneKohti(lattia.lahinPiste(kohde + V3.suunnasta(kulma) * 0.13f, 0.06f), juosten)
            }
        }
    }

    // =====================================================================================================
    // Balls
    // =====================================================================================================

    /**
     * The player tapped the screen at ([x], [y]), -1…1 with y up. A tap on a ball with a struggling figure
     * in it keeps the figure in. Returns true if the tap did something.
     */
    fun napauta(x: Float, y: Float, kamera: Kamera): Boolean {
        val suunta = kamera.sade(x, y)
        for (pallo in pallot) {
            if (pallo.tila != PallonTila.PYRISTELEE) continue
            // Generous: the ball is small and shaking, and the player is in a hurry
            val kohti = pallo.p - kamera.paikka
            val ohi = (kohti - suunta * (kohti dot suunta)).pituus
            if (ohi > 0.1f + 0.05f * kohti.pituus) continue
            pallo.vaihda(PallonTila.PALAA)
            tapahtumat += Tapahtuma(Aani.OSUMA, pallo.p)
            return true
        }
        return false
    }

    /** Nobody tapped in time: the figure pops out of the ball and runs off. */
    private fun karkaa(pallo: Pallo, kamera: Kamera) {
        pallo.vaihda(PallonTila.HAIPUU)
        putki = 0
        tapahtumat += Tapahtuma(Aani.KARKASI, pallo.p, teksti = "Se pääsi karkuun!")
        val vanha = pallo.ukko ?: return
        val lattia = maailma.lattia ?: return
        val alas = lattia.lahinPiste(V3(pallo.p.x, lattia.keskus.y, pallo.p.z), 0.06f)
        val u = Ukko(seuraavaId++, vanha.ulkonako, alas, suuntakulma((alas - kamera.paikka).vaakaan()), rnd.nextInt(), alkuKorkeus = (pallo.p.y - alas.y).coerceAtLeast(0.02f))
        u.esteeton = seinaton
        u.lattia = lattia
        u.maanTaso = lattia.keskus.y
        u.katsoja = kamera.paikka
        ukot += u
        u.pakene(alas + (alas - kamera.paikka).vaakaan().suunta(V3.Z) * 1.5f)
    }

    private fun palaa(pallo: Pallo, dt: Float, kamera: Kamera) {
        // A happy wobble, then home to the player's hand
        val t = pallo.tilaAika
        if (t < 0.45f) {
            pallo.koko = 1f + 0.18f * sin(t * 40f) * (1f - t / 0.45f)
            pallo.pyorita(kamera.kehys.eteen, sin(t * 30f) * 6f * dt)
            return
        }
        pallo.koko = 1f
        val koti = kamera.paikka + kamera.kehys.eteen * 0.12f - kamera.kehys.ylos * 0.1f
        val ero = koti - pallo.p
        val matka = ero.pituus
        val vauhti = 1.5f + (t - 0.45f) * 9f
        if (matka >= vauhti * dt + 0.06f) {
            pallo.p += ero * (vauhti * dt / matka)
            pallo.pyorita(kamera.kehys.oikealle, -vauhti / pallo.sade * dt * 0.3f)
            return
        }
        pallo.vaihda(PallonTila.POIS)
        keratyt++
        tapahtumat += Tapahtuma(Aani.KERAYS, pallo.p)
        val u = pallo.ukko ?: return
        pisteet += u.ulkonako.tyyppi.pisteet * kerroin
        putki++
        albumi.lisaa(u.ulkonako.tyyppi)
        if (tasot && albumi.etene(taso.tavoite)) {
            taso = Taso(albumi.taso)
            val uudet = taso.uudet.joinToString(" ja ") { it.nimi.lowercase() }
            tapahtumat += Tapahtuma(
                Aani.TASO, pallo.p,
                teksti = "Taso ${taso.numero}! Ukot piiloutuvat nyt paremmin." + if (uudet.isNotEmpty()) " Uutena mukana: $uudet." else "",
            )
        }
        // Some figures were carrying a special ball
        if (aarteita && rnd.nextFloat() < 0.12f) {
            val l = PalloLaji.entries[1 + rnd.nextInt(PalloLaji.entries.size - 1)]
            if (reppu.lisaa(l)) tapahtumat += Tapahtuma(Aani.POIMINTA, pallo.p, teksti = "Ukolla oli mukanaan ${l.nimi.lowercase()}!")
        }
    }

    private fun lenna(pallo: Pallo, h: Float, askelia: Int) {
        val laji = pallo.laji
        // The net spreads as it flies
        if (laji == PalloLaji.VERKKO) pallo.koko = 1f + 0.9f * saturoi(pallo.ika * 4f)
        // The multi-ball splits into three soon after leaving the hand
        if (laji == PalloLaji.MONI && !pallo.lauennut && pallo.ika > 0.16f) {
            pallo.lauennut = true
            for (kulma in listOf(-0.17f, 0.17f)) {
                val osa = Pallo(seuraavaId++, pallo.p, kierra(pallo.v, V3.Y, kulma), pallo.sade, PalloLaji.MONI)
                osa.lauennut = true
                osa.ika = pallo.ika
                pallot += osa
            }
        }
        if (laji == PalloLaji.TUTKA && !pallo.lauennut && (pallo.pomppuja > 0 || pallo.ika > 0.6f)) laukaise(pallo)

        repeat(askelia) {
            val ed = pallo.p
            if (laji == PalloLaji.HAKEUTUVA && pallo.pomppuja == 0) hakeudu(pallo, h)
            val isku = pallo.askel(h, maailma.pinnat)
            if (isku > 0.35f) {
                tapahtumat += Tapahtuma(Aani.POMPPU, pallo.p, saturoi(isku / 5f))
                if (laji == PalloLaji.HIDASTUS && !pallo.lauennut) laukaise(pallo)
            }
            // A special ball lying in the room: any ball that touches it picks it up
            val aarreIt = aarteet.iterator()
            while (aarreIt.hasNext()) {
                val a = aarreIt.next()
                if (janojenEtaisyys(ed, pallo.p, a.paikka, a.paikka).first > (pallo.sade + 0.075f) * (pallo.sade + 0.075f)) continue
                pallo.osui = true
                if (reppu.lisaa(a.laji)) {
                    aarreIt.remove()
                    tapahtumat += Tapahtuma(Aani.POIMINTA, a.paikka, teksti = "Löysit: ${a.laji.nimi.lowercase()}! ${a.laji.kuvaus}")
                } else if (a.kilahdus > 0f) {
                    a.kilahdus = 0f
                    tapahtumat += Tapahtuma(Aani.KIMALLUS, a.paikka, teksti = "Reppu on täynnä: heitä ensin erikoispalloja pois.")
                }
            }
            // Only a ball still on the fly catches: one that has bounced too often or rolls is spent
            if (pallo.pomppuja <= laji.pomput && pallo.v.pituus > 0.6f) {
                for (u in ukot) {
                    if (u.kiinni || !u.asento.nakyvissa || u.haamu) continue
                    val kohta = osuuko(ed, pallo.p, pallo.sade, u, laji.osumaKerroin) ?: continue
                    pallo.p = kohta
                    pallo.osui = true
                    if (laji == PalloLaji.HIDASTUS && !pallo.lauennut) laukaise(pallo)
                    if (osuma(pallo, u, kohta)) return
                    break
                }
            }
        }
        val lattiaY = maailma.lattia?.keskus?.y ?: (pallo.p.y - 10f)
        val levossa = pallo.v.pituus < 0.12f && pallo.ika > 0.5f
        if (levossa || pallo.ika > 6f || pallo.p.y < lattiaY - 3f) {
            pallo.vaihda(PallonTila.HAIPUU)
            if (!pallo.osui) putki = 0 // a miss ends the streak
        }
    }

    /** The ball touched a figure. Returns true if the ball's flight ended there. */
    private fun osuma(pallo: Pallo, u: Ukko, kohta: V3): Boolean {
        val laji = pallo.laji
        val tyyppi = u.ulkonako.tyyppi
        if (laji == PalloLaji.TAHMA) {
            u.liimaa(5f)
            pallo.v = V3.NOLLA
            pallo.vaihda(PallonTila.HAIPUU)
            tapahtumat += Tapahtuma(Aani.LIIMA, kohta)
            return true
        }
        val vaadittu = vaadittuPallo(tyyppi)
        // A shield stops what comes straight from the front; a bounce or a throw from behind gets past it
        val kilpiEdessa = tyyppi == Tyyppi.KILPI && pallo.pomppuja == 0 && laji != PalloLaji.VERKKO &&
            (pallo.v.vaakaan().suunta(V3.NOLLA) dot u.eteen) < -0.25f && u.liimattu <= 0f
        val torjuu = (vaadittu != null && laji != vaadittu) || kilpiEdessa
        if (torjuu || u.osumiaJaljella > 1) {
            if (torjuu) {
                u.torju()
                tapahtumat += Tapahtuma(
                    Aani.TORJUNTA, kohta,
                    teksti = if (vihjattu.add(u.id)) when (tyyppi) {
                        Tyyppi.NINJA -> "Ninja torjui pallon. Se pelkää verkkoja!"
                        Tyyppi.KULTAINEN -> "Kultainen ukko jää kiinni vain kultaiseen palloon."
                        else -> "Kilpi torjui! Kokeile takaa tai pompun kautta."
                    } else null,
                )
            } else {
                u.tolaisu(pallo.v)
                tapahtumat += Tapahtuma(Aani.POMPPU, kohta, 0.9f)
            }
            // The ball bounces off and is spent
            val n = (kohta - u.keskipiste).suunta(-pallo.v.suunta())
            pallo.v = (pallo.v - n * (2f * (pallo.v dot n))) * 0.45f
            pallo.pomppuja = 99
            return true
        }
        u.pallonKeskus = kohta
        u.osuma(kohta, pallo.v)
        pallo.ukko = u
        pallo.v = V3.NOLLA
        pallo.koko = 1f
        pallo.vaihda(PallonTila.VANGITSEE)
        tapahtumat += Tapahtuma(Aani.OSUMA, kohta)
        // The rest of a herd bolts, unless the multi-ball is about to get them too
        if (u.ryhma != 0 && laji != PalloLaji.MONI) {
            for (toinen in ukot) {
                if (toinen.ryhma != u.ryhma || toinen.kiinni) continue
                val pois = (toinen.juuri - (u.katsoja ?: kohta)).vaakaan().suunta(V3.Z)
                toinen.pakene(toinen.juuri + kierra(pois, V3.Y, (rnd.nextFloat() - 0.5f) * 1.6f) * 1.5f)
            }
        }
        return true
    }

    /** A special ball goes off: the radar reveals, the slow ball slows. */
    private fun laukaise(pallo: Pallo) {
        pallo.lauennut = true
        when (pallo.laji) {
            PalloLaji.TUTKA -> {
                for (u in ukot) if (u.juuri.etaisyys(pallo.p) < TUTKAN_SADE) u.paljasta(10f)
                tehosteet += Tehoste(pallo.laji.a, pallo.p, TUTKAN_SADE, 1.1f)
                tapahtumat += Tapahtuma(Aani.TUTKA, pallo.p)
            }
            PalloLaji.HIDASTUS -> {
                for (u in ukot) if (u.keskipiste.etaisyys(pallo.p) < HIDASTUKSEN_SADE) u.hidasta(8f)
                tehosteet += Tehoste(pallo.laji.a, pallo.p, HIDASTUKSEN_SADE, 0.9f)
                tapahtumat += Tapahtuma(Aani.HIDASTUS, pallo.p)
            }
            else -> {}
        }
    }

    /** Steers a homing ball towards the nearest figure it could catch. */
    private fun hakeudu(pallo: Pallo, h: Float) {
        var kohde: Ukko? = null
        for (u in ukot) {
            if (u.kiinni || u.haamu || u.keskipiste.etaisyys(pallo.p) > 5f) continue
            if (kohde == null || u.keskipiste.etaisyys(pallo.p) < kohde.keskipiste.etaisyys(pallo.p)) kohde = u
        }
        val k = kohde ?: return
        val vauhti = pallo.v.pituus.coerceAtLeast(3.5f)
        val nyt = pallo.v.suunta(V3.Z)
        val haluttu = (k.keskipiste - pallo.p).suunta(nyt)
        pallo.v = lerp(nyt, haluttu, (9f * h).coerceAtMost(1f)).suunta(nyt) * vauhti
    }

    /** Where along the ball's step from [a] to [b] it touches the figure, or null. */
    private fun osuuko(a: V3, b: V3, sade0: Float, u: Ukko, kerroin: Float): V3? {
        val m = u.m
        val as0 = u.asento
        val sade = sade0 * kerroin
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
     * Throws the ball chosen in [reppu] towards where the swipe aimed. It starts from [mista]: where the
     * platform draws the ball in the player's hand, by default low in front of the camera.
     */
    fun heita(heitto: Heitto, kamera: Kamera, mista: V3? = null): Pallo {
        val laji = reppu.kayta()
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
            if (u.kiinni || u.haamu) continue
            val kulma = kulmaValilla(suunta, (u.keskipiste - kamera.paikka).suunta())
            if (kulma < parasKulma) {
                parasKulma = kulma
                kohde = lerp(kamera.paikka + suunta * matka, u.keskipiste, 0.8f)
            }
        }

        // A ballistic arc that reaches the aim point: harder throws fly faster and flatter
        val vauhti = lerp(3.2f, 7.5f, heitto.voima)
        val ero = kohde - alku
        val aika = (ero.pituus / vauhti).coerceAtLeast(0.08f)
        val kaarre = kamera.kehys.oikealle * (heitto.kaarre * KAARRE)
        val v = ero / aika - (Pallo.PAINOVOIMA * laji.paino + kaarre) * (aika / 2f)
        val pallo = Pallo(seuraavaId++, alku, v, Pallo.SADE, laji)
        pallo.kaarre = kaarre
        pallot += pallo
        heitot++
        tapahtumat += Tapahtuma(Aani.HEITTO, alku, 0.5f + 0.5f * heitto.voima)
        return pallo
    }

    // =====================================================================================================
    // Special balls lying about
    // =====================================================================================================

    private fun paivitaAarteet(dt: Float, kamera: Kamera) {
        for (a in aarteet) {
            a.ika += dt
            a.kilahdus -= dt
            if (a.kilahdus <= 0f && a.kilahdus > -100f) {
                // The glitter tinkles; the platform makes it louder the closer the player is
                a.kilahdus = 2.6f
                tapahtumat += Tapahtuma(Aani.KIMALLUS, a.paikka, 0.6f, nuoli = !tasot || rnd.nextFloat() < taso.nuolia)
            }
        }
        aarteet.removeAll { it.ika > AARTEEN_IKA }
        aarreAjastin -= dt
        if (etsinta) aarteet.removeAll { (it.paikka - kamera.paikka).vaakaan().pituus > JATTOMATKA + 1f }
        if (aarreAjastin > 0f || aarteet.size >= (if (etsinta) 3 else 2)) return
        aarreAjastin = 14f + rnd.nextFloat() * 14f
        // More often than not, the ball that suits a figure that is out right now
        val sopivat = ukot.filter { !it.kiinni }.mapNotNull { sopivaPallo(it.ulkonako.tyyppi) }.filter { reppu[it] == 0 }
        val laji = if (sopivat.isNotEmpty() && rnd.nextFloat() < 0.65f) sopivat.random(rnd) else PalloLaji.entries[1 + rnd.nextInt(PalloLaji.entries.size - 1)]
        // Special balls end up where things get lost: nooks, corners, shadows, the tops of tall things
        val paikat = maailma.paikat.filter { p ->
            val d = (p.piste - kamera.paikka).vaakaan().pituus
            d in 0.7f..4f && aarteet.none { (it.paikka - p.piste).pituus < 0.3f } && ukot.none { (it.juuri - p.piste).pituus < 0.25f }
        }
        if (paikat.isNotEmpty() && rnd.nextFloat() < 0.6f) {
            aarteet += Aarre(laji, paikat.random(rnd).piste + V3.Y * 0.07f)
            return
        }
        val pinnat = maailma.vaakapinnat
        for (yritys in 0 until 12) {
            val pinta = pinnat.randomOrNull(rnd) ?: return
            val p = pinta.satunnainenPiste(rnd, 0.1f) ?: continue
            val katsojasta = (p - kamera.paikka).vaakaan().pituus
            if (katsojasta < 0.7f || katsojasta > 3.5f || ukot.any { (it.juuri - p).pituus < 0.25f }) continue
            aarteet += Aarre(laji, p + V3.Y * 0.07f)
            return
        }
    }

    // =====================================================================================================
    // New figures
    // =====================================================================================================

    /** A ninja's puff of smoke. */
    private fun savu(p: V3, koko: Float) {
        tehosteet += Tehoste(SAVU, p, koko * 0.9f, 0.7f)
        tehosteet += Tehoste(SAVU, p + V3.Y * (koko * 0.3f), koko * 0.6f, 0.9f)
        tapahtumat += Tapahtuma(Aani.HEITTO, p, 0.2f)
    }

    /**
     * Where a ninja turns up: out of a shadow, a corner or a nook if the room has any within sight, and
     * otherwise (or half the time anyway) dropping from above.
     */
    private fun ninjanPaikka(kamera: Kamera, lattia: Pinta): V3? {
        val eteen = kamera.kehys.eteen.vaakaan().suunta(V3.Z)
        val paikat = maailma.paikat.filter { p ->
            val ero = (p.piste - kamera.paikka).vaakaan()
            // Shadows first; all of them in front of the player, not too near and not too far
            p.lattialla && ero.pituus in 0.8f..4.5f && (etsinta || (ero.suunta() dot eteen) > 0.3f) && ukot.none { (it.juuri - p.piste).pituus < 0.3f }
        }
        val varjot = paikat.filter { it.laji == PaikanLaji.VARJO }
        return (if (varjot.isNotEmpty() && rnd.nextFloat() < 0.7f) varjot else paikat).randomOrNull(rnd)?.piste?.let { lattia.lahinPiste(it, 0.06f) }
    }

    /** For the figures: whether the way between two points on the floor is clear of known walls. */
    private val seinaton: (V3, V3) -> Boolean = { a, b -> esteeton(a + V3.Y * 0.1f, b + V3.Y * 0.1f) }

    /** Whether anything known (a wall, a table's side) stands between two points. */
    private fun esteeton(a: V3, b: V3): Boolean {
        val d = b - a
        for (pinta in maailma.pinnat) {
            if (pinta.laji != PinnanLaji.SEINA) continue
            val da = pinta.etaisyys(a)
            val db = pinta.etaisyys(b)
            if (da * db >= 0f) continue
            if (pinta.sisalla(a + d * (da / (da - db)))) return false
        }
        return true
    }

    /**
     * A spot for a figure to be found at: somewhere around the player, most often out of view and by choice
     * in one of the room's corners, nooks and shadows, but not behind a known wall.
     */
    private fun paikkaEtsittava(kamera: Kamera, lattia: Pinta): V3? {
        val eteen = kamera.kehys.eteen.vaakaan().suunta(V3.Z)
        val nakokentta = atan(kamera.tanPuoliFov * kamera.kuvasuhde)
        fun kelpaa(p: V3): Boolean {
            val ero = (p - kamera.paikka).vaakaan()
            return ero.pituus in 1.2f..(if (tasot) taso.etaisyys else 4f) && ukot.none { (it.juuri - p).vaakaan().pituus < 0.5f } && esteeton(kamera.paikka, p + V3.Y * 0.15f)
        }
        val paikat = maailma.paikat.filter { it.lattialla && kelpaa(it.piste) }
        if (paikat.isNotEmpty() && rnd.nextFloat() < (if (tasot) taso.paikoissa else 0.5f)) return lattia.lahinPiste(paikat.random(rnd).piste, 0.06f)
        val piiloon = rnd.nextFloat() < (if (tasot) taso.piilossa else 0.7f)
        val kauimmas = (if (tasot) taso.etaisyys else 4f) - 0.5f
        for (yritys in 0 until 24) {
            val suunta = kierra(eteen, V3.Y, (rnd.nextFloat() - 0.5f) * 2f * PI.toFloat())
            // Mostly out of view on the hard levels, in plain view on the easy ones
            val nakyvissa = kulmaValilla(suunta, eteen) < nakokentta * (if (piiloon) 1.3f else 0.8f)
            if (yritys < 16 && nakyvissa == piiloon) continue
            val p = lattia.lahinPiste(V3(kamera.paikka.x, lattia.keskus.y, kamera.paikka.z) + suunta * (1.5f + rnd.nextFloat() * (kauimmas - 1.5f).coerceAtLeast(0.3f)), 0.12f)
            if (kelpaa(p)) return p
        }
        return null
    }

    /** A free spot on the floor in front of the player. */
    private fun paikkaNakyvissa(kamera: Kamera, lattia: Pinta, varo: List<Ukko>): V3? {
        val kulma = suuntakulma(kamera.kehys.eteen.vaakaan().suunta(V3.Z))
        val nakokentta = atan(kamera.tanPuoliFov * kamera.kuvasuhde)
        for (yritys in 0 until 20) {
            // Inside the player's view if possible, wider if the first tries fail
            val a = kulma + (rnd.nextFloat() - 0.5f) * 2f * (nakokentta * 0.75f + yritys * 0.08f)
            val d = 1.1f + rnd.nextFloat() * 1.3f
            val p = lattia.lahinPiste(V3(kamera.paikka.x + sin(a) * d, lattia.keskus.y, kamera.paikka.z + cos(a) * d), 0.12f)
            if ((p - kamera.paikka).vaakaan().pituus < 0.6f && yritys < 19) continue
            if (varo.any { (it.juuri - p).vaakaan().pituus < 0.3f } && yritys < 19) continue
            return p
        }
        return null
    }

    private fun arvoTyyppi(): Tyyppi {
        if (tyypit.size == 1) return tyypit[0]
        val sallitut = if (tasot) tyypit.filter { it in taso.tyypit } else tyypit
        // One special figure at a time, so that a rare one is an event
        if (ukot.any { it.ulkonako.tyyppi != Tyyppi.TAVALLINEN } || rnd.nextFloat() > (if (tasot) taso.erikoisia else 0.45f) || Tyyppi.TAVALLINEN !in sallitut) {
            if (Tyyppi.TAVALLINEN in sallitut) return Tyyppi.TAVALLINEN
        }
        val ehdokkaat = sallitut.filter { it != Tyyppi.TAVALLINEN }
        if (ehdokkaat.isEmpty()) return Tyyppi.TAVALLINEN
        // A figure the player holds the right ball for turns up more readily
        val painot = ehdokkaat.map { t -> (YLEISYYS[t] ?: 1f) * (if (sopivaPallo(t)?.let { reppu[it] > 0 && !reppu.rajaton } == true) 3f else 1f) }
        var arpa = rnd.nextFloat() * painot.sum()
        for ((i, t) in ehdokkaat.withIndex()) {
            arpa -= painot[i]
            if (arpa <= 0f) return t
        }
        return ehdokkaat.last()
    }

    private fun uusiUkko(kamera: Kamera, lattia: Pinta) {
        val p = (if (etsinta) paikkaEtsittava(kamera, lattia) else paikkaNakyvissa(kamera, lattia, ukot)) ?: return
        var vari = rnd.nextInt(Ulkonako.VARIT.size)
        if (vari == edellinenVari) vari = (vari + 1) % Ulkonako.VARIT.size
        edellinenVari = vari
        val tyyppi = arvoTyyppi()
        fun luo(paikka: V3, pinta: Pinta = lattia, korkeus: Float? = null): Ukko {
            var ulkonako = luoUlkonako(tyyppi, Ulkonako.VARIT[vari], rnd)
            // Higher up, every figure is a little quicker to dodge
            if (tasot) ulkonako = ulkonako.copy(luonne = ulkonako.luonne.copy(vaistotaito = (ulkonako.luonne.vaistotaito + taso.vaistolisa).coerceAtMost(1f)))
            val u = Ukko(seuraavaId++, ulkonako, paikka, suuntakulma((kamera.paikka - paikka).vaakaan()), rnd.nextInt(), alkuKorkeus = korkeus, pinta = pinta)
            u.maanTaso = lattia.keskus.y
            u.katsoja = kamera.paikka
            u.esteeton = seinaton
            // To be looked for, a figure has to stay somewhere (the wall climbers have their own routes)
            if (etsinta && pinta === lattia && tyyppi != Tyyppi.KIIPEILIJA && tyyppi != Tyyppi.NINJA) u.koti = paikka
            ukot += u
            return u
        }
        when (tyyppi) {
            Tyyppi.LAUMA -> {
                val ryhma = seuraavaId++
                for (k in 0 until 3) {
                    val u = luo(lattia.lahinPiste(p + V3.suunnasta(k * 2f * PI.toFloat() / 3f) * 0.13f, 0.06f))
                    u.ryhma = ryhma
                    u.omatoiminen = false
                }
            }
            Tyyppi.KATTO -> {
                // The real ceiling if one is known here, otherwise one at the measured ceiling height or room height
                val katto = maailma.pinnat.filter { it.laji == PinnanLaji.KATTO && it.sisalla(p) }.minByOrNull { it.keskus.y }
                    ?: Pinta("oma-katto", PinnanLaji.KATTO, V3(p.x, maailma.kattoY ?: (lattia.keskus.y + OLETUSKATTO), p.z), -V3.Y, V3.X, List(12) { i ->
                        (cos(i * PI / 6) * 1.2).toFloat() to (sin(i * PI / 6) * 1.2).toFloat()
                    }).also { omaKatto = it }
                luo(V3(p.x, katto.keskus.y, p.z), katto)
            }
            Tyyppi.TAVALLINEN -> {
                // Now and then an ordinary one turns up on a table top instead of the floor
                val poyta = if (rnd.nextFloat() < 0.22f) maailma.pinnat.filter {
                    it.laji == PinnanLaji.POYTA && it.ala > 0.12f && (it.keskus - kamera.paikka).pituus < 4f && ukot.none { u -> u.lattia === it }
                }.randomOrNull(rnd) else null
                val pp = poyta?.satunnainenPiste(rnd, 0.1f)
                if (poyta != null && pp != null) luo(pp, poyta) else luo(p)
            }
            Tyyppi.NINJA -> {
                val piilo = if (rnd.nextFloat() < 0.6f) ninjanPaikka(kamera, lattia) else null
                if (piilo != null) {
                    // Out of the shadows, in a puff of smoke
                    val u = luo(piilo, korkeus = 0.03f)
                    savu(piilo + V3.Y * (u.m.pituus * 0.5f), u.m.pituus)
                } else {
                    // Dropping in from above: from the ceiling if there is one, else from well overhead
                    val katto = maailma.pinnat.filter { it.laji == PinnanLaji.KATTO && it.sisalla(p) }.minOfOrNull { it.keskus.y }
                    luo(p, korkeus = ((katto ?: (lattia.keskus.y + 2.2f)) - lattia.keskus.y - 0.3f).coerceIn(1.2f, 2.6f))
                }
            }
            else -> luo(p)
        }
    }

    companion object {
        const val SUURIN_KERROIN = 5
        /** In the searching game, a figure this far behind the player is let go. */
        const val JATTOMATKA = 6.5f
        val SAVU = Vari.hex(0x6B6875)
        /** How long the player has to tap a ball with a struggling figure in it, seconds. */
        const val PYRISTELYAIKA = 2.2f
        /** How long a special figure stays if nobody catches it, seconds. */
        const val VIERAILU = 40f
        const val TUTKAN_SADE = 4f
        const val HIDASTUKSEN_SADE = 1.3f
        const val AARTEEN_IKA = 50f
        /** Height of the imagined ceiling a ceiling figure hangs from when AR has found no real one. */
        const val OLETUSKATTO = 2.1f

        /** How readily each special kind turns up, relative to the others. */
        private val YLEISYYS = mapOf(
            Tyyppi.JUOKSIJA to 3f, Tyyppi.ISO to 2.5f, Tyyppi.KIIPEILIJA to 2f, Tyyppi.LASKUVARJO to 2f, Tyyppi.KILPI to 2f,
            Tyyppi.LAUMA to 2f, Tyyppi.NINJA to 1.2f, Tyyppi.PIILO to 1.2f, Tyyppi.KATTO to 1.2f, Tyyppi.KULTAINEN to 0.7f,
        )

        /** The only ball that catches this kind of figure, if it is that picky. */
        fun vaadittuPallo(t: Tyyppi): PalloLaji? = when (t) {
            Tyyppi.NINJA -> PalloLaji.VERKKO
            Tyyppi.KULTAINEN -> PalloLaji.KULTAINEN
            else -> null
        }

        /** The ball that pairs with this kind: required, or just the one that makes it easy. */
        fun sopivaPallo(t: Tyyppi): PalloLaji? = vaadittuPallo(t) ?: when (t) {
            Tyyppi.PIILO -> PalloLaji.TUTKA
            Tyyppi.JUOKSIJA -> PalloLaji.HIDASTUS
            Tyyppi.LAUMA -> PalloLaji.MONI
            Tyyppi.KATTO, Tyyppi.KIIPEILIJA, Tyyppi.LASKUVARJO -> PalloLaji.HAKEUTUVA
            Tyyppi.KILPI -> PalloLaji.POMPPU
            Tyyppi.ISO -> PalloLaji.TAHMA
            else -> null
        }

        /** A figure of the given kind with a random personality. */
        fun luoUlkonako(tyyppi: Tyyppi, vari: Vari, rnd: Random): Ulkonako {
            val luonne = Luonne(
                ujous = rnd.nextFloat(),
                leikkisyys = rnd.nextFloat(),
                vaistotaito = 0.15f + rnd.nextFloat() * 0.5f,
                vilkkaus = rnd.nextFloat(),
            )
            val aani = 0.8f + rnd.nextFloat() * 0.5f
            return when (tyyppi) {
                Tyyppi.TAVALLINEN -> Ulkonako(vari, 0.85f + rnd.nextFloat() * 0.4f, luonne, aani)
                // The runner slips every ball until it is slowed down
                Tyyppi.JUOKSIJA -> Ulkonako(vari, 0.72f, luonne.copy(vilkkaus = 1f, vaistotaito = 0.9f), 1.35f, tyyppi)
                Tyyppi.ISO -> Ulkonako(vari, 1.75f, luonne.copy(vilkkaus = 0.1f, vaistotaito = 0.05f, ujous = 0.1f), 0.62f, tyyppi)
                Tyyppi.KULTAINEN -> Ulkonako(Ulkonako.KULTA, 0.95f, luonne.copy(vaistotaito = 0.85f, ujous = 0.9f, leikkisyys = 0.9f), 1.15f, tyyppi)
                Tyyppi.KIIPEILIJA -> Ulkonako(vari, 0.9f, luonne.copy(vilkkaus = 0.8f), 1.1f, tyyppi)
                Tyyppi.LASKUVARJO -> Ulkonako(vari, 0.9f, luonne.copy(leikkisyys = 0.9f), aani, tyyppi)
                Tyyppi.NINJA -> Ulkonako(Ulkonako.NINJA, 0.95f, luonne.copy(vaistotaito = 1f, vilkkaus = 0.9f, leikkisyys = 0.8f), 0.9f, tyyppi)
                Tyyppi.KILPI -> Ulkonako(vari, 1.15f, luonne.copy(vaistotaito = 0f, ujous = 0.1f), 0.8f, tyyppi)
                Tyyppi.PIILO -> Ulkonako(Ulkonako.HAALEA, 0.9f, luonne.copy(ujous = 1f, vaistotaito = 0.2f), 1.25f, tyyppi)
                Tyyppi.LAUMA -> Ulkonako(vari, 0.6f, luonne.copy(vilkkaus = 0.8f, vaistotaito = 0.2f), 1.45f, tyyppi)
                Tyyppi.KATTO -> Ulkonako(vari, 1f, luonne.copy(vaistotaito = 0.1f, leikkisyys = 0.9f), aani, tyyppi)
            }
        }

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
