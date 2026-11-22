package fi.otsosoft.tikkujahti

import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalContext
import fi.otsosoft.tikkujahti.ukko.Tyyppi
import fi.otsosoft.tikkujahti.ukko.Ulkonako
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import fi.otsosoft.tikkujahti.maailma.LattianHaku
import fi.otsosoft.tikkujahti.maailma.Tunnistin
import fi.otsosoft.tikkujahti.matikka.Kehys
import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.matikka.pehmea
import fi.otsosoft.tikkujahti.peli.Aani
import fi.otsosoft.tikkujahti.peli.Albumi
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.draw.rotate
import fi.otsosoft.tikkujahti.peli.Heitto
import fi.otsosoft.tikkujahti.peli.Kamera
import fi.otsosoft.tikkujahti.peli.PalloLaji
import fi.otsosoft.tikkujahti.peli.Reppu
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.delay
import fi.otsosoft.tikkujahti.peli.Peli
import fi.otsosoft.tikkujahti.peli.Pyyhkaisy
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberScene

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { PeliNakyma() }
    }
}

/**
 * The game's state on the phone: the shared [Peli], the camera ARCore reports, the swipe being drawn and
 * the ball in the player's hand.
 */
class PeliTila(albumi: Albumi, reppu: Reppu, private val tallenna: (Albumi, Reppu) -> Unit) {
    val peli = Peli((System.nanoTime() % 100000).toInt(), albumi, reppu)
    /** The latest note for the player (what was found, why a ball bounced off), and a count that grows with each. */
    var ilmoitus: String? = null
        private set
    var ilmoituksia = 0
        private set
    private var tallennettu = 0
    val kamera = Kamera(V3.NOLLA, Kehys.luo(-V3.Z), 0.6f, 0.5f)
    val aanet = Aanet()
    private val pyyhkaisy = Pyyhkaisy()
    private var edellinen = 0L

    init {
        peli.pyristely = true
        peli.ilmoitaLoydot = true
        // The player walks about the house looking for figures and balls
        peli.etsinta = true
        peli.ukkojaKerralla = 4
    }
    private var seuranta = false

    // The ball in the hand, in screen coordinates -1…1
    private var kasiX = 0f
    private var kasiY = KASI_LEPO_Y
    private var kasiTauko = 0f

    var lattia: Plane? = null
        private set
    /** There is a floor to play on, real or guessed. */
    var pelissa = false
        private set
    /** An arrow at the edge of the screen (x, y from -1 to 1, y up) towards something heard out of view. */
    class Nuoli(val x: Float, val y: Float, val pallo: Boolean, val loppu: Long)

    val nuolet = ArrayList<Nuoli>()
    /** Grows with every new arrow, so the screen knows to redraw them. */
    var nuolia = 0
        private set
    private val haku = LattianHaku()
    private val tunnistin = Tunnistin()
    private var mittausAjastin = 0f
    private var varjoAjastin = 0f
    private var aika = 0f
    private val varjot = ArrayList<Pair<V3, Float>>()
    val kadenKoko: Float get() = pehmea(-kasiTauko / 0.2f)

    /** One AR frame: read the room and the camera, then advance the game. */
    fun ruutu(session: Session, frame: Frame, koko: IntSize) {
        val nyt = frame.timestamp
        val dt = if (edellinen == 0L) 0.016f else ((nyt - edellinen) / 1e9f).coerceIn(0f, 0.05f)
        edellinen = nyt
        seuranta = ArMaailma.kamera(frame.camera, kamera)
        if (!seuranta || dt <= 0f) return
        val (pinnat, lattiataso) = ArMaailma.pinnat(session)
        lattia = lattiataso
        // Until ARCore has found the floor, play on a guessed one, so that figures turn up without a wait
        haku.ruutu(dt, kamera.paikka, null)
        val arvaus = if (lattiataso == null) haku.lattia(kamera.paikka) else null
        val lattiaY = lattiataso?.centerPose?.ty() ?: arvaus?.keskus?.y
        // The room is probed all the time, beyond the planes ARCore finds by itself
        mittausAjastin -= dt
        if (mittausAjastin <= 0f && koko.width > 0) {
            mittausAjastin = 0.15f
            ArMaailma.mittaa(frame, koko.width, koko.height, tunnistin)
        }
        val kaikki = ArrayList(pinnat)
        if (arvaus != null) kaikki += arvaus
        for (t in tunnistin.pinnat(lattiaY)) {
            val keski = t.pisteTasolta(0f, 0f)
            if (kaikki.none { it.laji == t.laji && kotlin.math.abs(it.etaisyys(keski)) < 0.1f && it.sisalla(keski, 0.1f) }) kaikki += t
        }
        peli.maailma.asetaPinnat(kaikki)
        // Dark spots from the camera image, remembered for a while
        varjoAjastin -= dt
        if (varjoAjastin <= 0f) {
            varjoAjastin = 1.2f
            aika += 1.2f
            varjot.removeAll { aika - it.second > 90f }
            for (v in ArMaailma.varjot(frame)) {
                if (varjot.none { (it.first - v).pituus < 0.4f } && varjot.size < 8) varjot += v to aika
            }
            peli.maailma.varjot = varjot.map { it.first }
        }
        pelissa = peli.maailma.lattia != null
        peli.paivita(dt, kamera)
        nuolet.removeAll { it.loppu < nyt }
        for (t in peli.tapahtumat) {
            aanet.soita(t, kamera.paikka)
            t.teksti?.let { ilmoita(it) }
            // Something out of view made a sound: an arrow at the edge of the screen shows which way to turn
            if (t.laji == Aani.AANTELY || (t.laji == Aani.KIMALLUS && t.teksti == null)) {
                val (x, y, nakyvissa) = kamera.ruudulla(t.paikka)
                if (!nakyvissa && nuolet.size < 3) {
                    nuolet += Nuoli(x, y, t.laji == Aani.KIMALLUS, nyt + 1_700_000_000L)
                    nuolia++
                }
            }
        }
        if (peli.albumi.muutoksia + peli.reppu.muutoksia != tallennettu) {
            tallennettu = peli.albumi.muutoksia + peli.reppu.muutoksia
            tallenna(peli.albumi, peli.reppu)
        }
        kasiTauko -= dt
        if (!pyyhkaisy.kaynnissa) {
            kasiX += (0f - kasiX) * minOf(1f, dt * 12f)
            kasiY += (KASI_LEPO_Y - kasiY) * minOf(1f, dt * 12f)
        }
    }

    fun ilmoita(teksti: String) {
        ilmoitus = teksti
        ilmoituksia++
    }

    /** Where the ball in the hand is in the world, or null when there is none to show. */
    fun kadenPaikka(): V3? {
        if (!seuranta || !pelissa || kasiTauko > 0f) return null
        return kamera.paikka + (kamera.kehys.eteen +
            kamera.kehys.oikealle * (kasiX * kamera.tanPuoliFov * kamera.kuvasuhde) +
            kamera.kehys.ylos * (kasiY * kamera.tanPuoliFov)) * KASI_SYVYYS
    }

    fun kosketus(e: MotionEvent, koko: IntSize): Boolean {
        if (koko.width == 0 || koko.height == 0) return false
        val x = e.x / koko.width * 2f - 1f
        val y = 1f - e.y / koko.height * 2f
        val t = e.eventTime / 1000f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (pelissa && kasiTauko <= 0f) pyyhkaisy.aloita(x, y, t) else if (pelissa) peli.napauta(x, y, kamera)
            MotionEvent.ACTION_MOVE -> if (pyyhkaisy.kaynnissa) {
                pyyhkaisy.lisaa(x, y, t)
                kasiX = x; kasiY = y
            }
            MotionEvent.ACTION_UP -> if (pyyhkaisy.kaynnissa) {
                pyyhkaisy.lisaa(x, y, t)
                kasiX = x; kasiY = y
                val mista = kadenPaikka()
                val heitto = pyyhkaisy.lopeta()
                // Not a throw, so a tap: it may be for a ball with a struggling figure in it
                if (heitto == null) peli.napauta(x, y, kamera)
                heitto?.let { heitto: Heitto ->
                    peli.heita(heitto, kamera, mista)
                    peli.tapahtumat.lastOrNull()?.let { aanet.soita(it, kamera.paikka) }
                    kasiTauko = 0.45f
                    kasiX = 0f
                    kasiY = KASI_LEPO_Y - 0.5f
                }
            }
            MotionEvent.ACTION_CANCEL -> pyyhkaisy.peru()
        }
        return true
    }

    companion object {
        const val KASI_LEPO_Y = -0.72f
        const val KASI_SYVYYS = 0.5f
    }
}

@Composable
fun PeliNakyma() {
    val engine = rememberEngine()
    val materiaalit = rememberMaterialLoader(engine)
    val scene = rememberScene(engine)
    val muisti = LocalContext.current.getSharedPreferences("tikkujahti", Context.MODE_PRIVATE)
    val tila = remember {
        PeliTila(Albumi.lue(muisti.getString("albumi", null)), Reppu.lue(muisti.getString("reppu", null))) { a, r ->
            muisti.edit().putString("albumi", a.teksti()).putString("reppu", r.teksti()).apply()
        }
    }
    var ilmoituksia by remember { mutableIntStateOf(0) }
    var nuolia by remember { mutableIntStateOf(0) }
    var reppuMuutos by remember { mutableIntStateOf(0) }
    var valittu by remember { mutableStateOf(PalloLaji.NORMI) }
    var ilmoitusNakyy by remember { mutableStateOf(false) }
    LaunchedEffect(ilmoituksia) {
        if (ilmoituksia > 0) {
            ilmoitusNakyy = true
            delay(4200)
            ilmoitusNakyy = false
        }
    }
    var pisteet by remember { mutableIntStateOf(0) }
    var kerroin by remember { mutableIntStateOf(1) }
    var albumiAuki by remember { mutableStateOf(false) }
    val piirtaja = remember(engine, scene) { Piirtaja(engine, scene, materiaalit) }
    var koko by remember { mutableStateOf(IntSize.Zero) }
    var lattia by remember { mutableStateOf<Plane?>(null) }
    var pelissa by remember { mutableStateOf(false) }
    var keratyt by remember { mutableIntStateOf(0) }
    var heitot by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        tila.aanet.kaynnista()
        onDispose { tila.aanet.pysayta() }
    }
    // Declared after the engine, so it is disposed before the engine is
    DisposableEffect(piirtaja) {
        onDispose { piirtaja.tuhoa() }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { koko = it }) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            materialLoader = materiaalit,
            scene = scene,
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL,
            // Depth, where the phone has it, lets the game measure surfaces ARCore makes no planes of
            depthMode = Config.DepthMode.AUTOMATIC,
            // The dots that show detected surfaces help until the floor is found, then get in the way
            planeRenderer = lattia == null,
            onSessionUpdated = { session, frame ->
                tila.ruutu(session, frame, koko)
                piirtaja.paivita(tila.peli, tila.kadenPaikka(), tila.kadenKoko)
                lattia = tila.lattia
                pelissa = tila.pelissa
                keratyt = tila.peli.keratyt
                pisteet = tila.peli.pisteet
                kerroin = tila.peli.kerroin
                ilmoituksia = tila.ilmoituksia
                nuolia = tila.nuolia + tila.nuolet.size * 100000
                reppuMuutos = tila.peli.reppu.muutoksia
                valittu = tila.peli.reppu.valittu
                heitot = tila.peli.heitot
            },
            onTouchEvent = { e, _ -> if (albumiAuki) false else tila.kosketus(e, koko) },
        ) {
            // The real floor catches the figures' shadows
            lattia?.let { ShadowReceiverPlane(plane = it) }
        }

        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            Row(
                Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(50)).background(Color(0xE0FFFCF5)).padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(Brush.verticalGradient(0.52f to Color(0xFFFFC93C), 0.52f to Color(0xFFFFF7E6))))
                Text("$pisteet", Modifier.padding(start = 8.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2630))
            }
            // The streak badge shows what the next catch is worth
            if (kerroin > 1) {
                Text(
                    "×$kerroin",
                    Modifier.align(Alignment.TopStart).padding(top = 46.dp).clip(RoundedCornerShape(50)).background(Color(0xFFE85D75)).padding(horizontal = 12.dp, vertical = 3.dp),
                    color = Color.White, fontWeight = FontWeight.Bold,
                )
            }
            Text(
                "Albumi",
                Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(50)).background(Color(0xE0FFFCF5)).clickable { albumiAuki = true }.padding(horizontal = 14.dp, vertical = 8.dp),
                color = Color(0xFF2A2630),
            )
            val vihje = when {
                !pelissa -> "Katsele puhelimella lattiaa: ukot tulevat kohta esiin."
                heitot < 2 -> "Ukkoja ja palloja on ympäri taloa: kulje ja etsi. Pyyhkäise ylöspäin heittääksesi."
                else -> null
            }
            if (vihje != null) {
                Text(
                    vihje,
                    Modifier.align(Alignment.TopCenter).padding(top = 56.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xB82A2630)).padding(horizontal = 14.dp, vertical = 8.dp),
                    color = Color.White, textAlign = TextAlign.Center,
                )
            }
        }
        // The balls to choose from: plain ones without end, and the special ones in the bag
        val reppu = tila.peli.reppu
        val omat = remember(reppuMuutos) { PalloLaji.entries.filter { it == PalloLaji.NORMI || reppu[it] > 0 } }
        if (omat.size > 1 && pelissa) {
            Column(Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (l in omat.reversed()) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        Box(
                            Modifier.size(if (l == valittu) 52.dp else 44.dp).clip(CircleShape)
                                .background(if (l == valittu) Color(0xFF2A2630) else Color(0xE6FFFCF5)).padding(3.dp).clip(CircleShape)
                                .background(Brush.verticalGradient(0.52f to Color(l.a.r, l.a.g, l.a.b), 0.52f to Color(l.b.r, l.b.g, l.b.b)))
                                .clickable {
                                    reppu.valitse(l)
                                    tila.ilmoita("${l.nimi}: ${l.kuvaus}")
                                },
                        )
                        if (l != PalloLaji.NORMI) {
                            Text("${reppu[l]}", Modifier.clip(CircleShape).background(Color(0xFF2A2630)).padding(horizontal = 6.dp), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        // (reading nuolia redraws this when arrows come and go)
        if (nuolia >= 0) for (n in tila.nuolet.toList()) {
            Text(
                "▲",
                Modifier.align(BiasAlignment(n.x * 0.88f, -n.y * 0.88f)).rotate(Math.toDegrees(kotlin.math.atan2(n.x.toDouble(), n.y.toDouble())).toFloat()),
                color = if (n.pallo) Color(0xFFFFC93C) else Color(0xF2FFFCF5), fontSize = 30.sp,
            )
        }
        if (ilmoitusNakyy) {
            Text(
                tila.ilmoitus.orEmpty(),
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(start = 24.dp, end = 72.dp, bottom = 150.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xF0FFFCF5)).padding(horizontal = 14.dp, vertical = 8.dp),
                color = Color(0xFF2A2630), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
        }
        if (albumiAuki) AlbumiNakyma(tila.peli.albumi, keratyt) { albumiAuki = false }
    }
}

/**
 * The album: every kind of figure, with a hint in place of the ones not caught yet.
 * [keratyt] is only read so that the album redraws when something new is caught while it is open.
 */
@Composable
fun AlbumiNakyma(albumi: Albumi, @Suppress("UNUSED_PARAMETER") keratyt: Int, sulje: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x732A2630)).clickable(onClick = sulje).safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFFFFFCF5)).clickable(enabled = false) {}.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val tumma = Color(0xFF2A2630)
            Text("Ukkoalbumi ${albumi.loydetty}/${Tyyppi.entries.size}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = tumma)
            Text("Napattu yhteensä ${albumi.yhteensa}. Kierrä taloa ja etsi jokaista lajia mahdollisimman monta.", fontSize = 13.sp, color = tumma.copy(alpha = 0.7f))
            for (t in Tyyppi.entries) {
                val maara = albumi[t]
                val vari = when (t) {
                    Tyyppi.KULTAINEN -> Ulkonako.KULTA
                    Tyyppi.NINJA -> Ulkonako.NINJA
                    Tyyppi.PIILO -> Ulkonako.HAALEA
                    else -> Ulkonako.VARIT[t.ordinal % Ulkonako.VARIT.size]
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(if (maara > 0) Color(vari.r, vari.g, vari.b) else Color(0x402A2630)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (maara > 0) t.nimi else "???", fontWeight = FontWeight.Bold, color = tumma)
                        Text(if (maara > 0) "${t.harvinaisuus.nimi} · ${t.pisteet} p · ${t.vihje}" else t.vihje, fontSize = 13.sp, color = tumma.copy(alpha = 0.7f))
                    }
                    if (maara > 0) Text("$maara", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = tumma)
                }
            }
            Text(
                "Sulje",
                Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(50)).background(tumma).clickable(onClick = sulje).padding(horizontal = 18.dp, vertical = 8.dp),
                color = Color.White,
            )
        }
    }
}
