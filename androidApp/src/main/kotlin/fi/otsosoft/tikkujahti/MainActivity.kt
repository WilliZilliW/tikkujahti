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
import fi.otsosoft.tikkujahti.matikka.Kehys
import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.matikka.pehmea
import fi.otsosoft.tikkujahti.peli.Albumi
import fi.otsosoft.tikkujahti.peli.Heitto
import fi.otsosoft.tikkujahti.peli.Kamera
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
class PeliTila(albumi: Albumi, private val tallenna: (Albumi) -> Unit) {
    val peli = Peli((System.nanoTime() % 100000).toInt(), albumi)
    private var tallennettu = 0
    val kamera = Kamera(V3.NOLLA, Kehys.luo(-V3.Z), 0.6f, 0.5f)
    val aanet = Aanet()
    private val pyyhkaisy = Pyyhkaisy()
    private var edellinen = 0L
    private var seuranta = false

    // The ball in the hand, in screen coordinates -1…1
    private var kasiX = 0f
    private var kasiY = KASI_LEPO_Y
    private var kasiTauko = 0f

    var lattia: Plane? = null
        private set
    val kadenKoko: Float get() = pehmea(-kasiTauko / 0.2f)

    /** One AR frame: read the room and the camera, then advance the game. */
    fun ruutu(session: Session, frame: Frame) {
        val nyt = frame.timestamp
        val dt = if (edellinen == 0L) 0.016f else ((nyt - edellinen) / 1e9f).coerceIn(0f, 0.05f)
        edellinen = nyt
        seuranta = ArMaailma.kamera(frame.camera, kamera)
        if (!seuranta || dt <= 0f) return
        val (pinnat, lattiataso) = ArMaailma.pinnat(session)
        lattia = lattiataso
        peli.maailma.asetaPinnat(pinnat)
        peli.paivita(dt, kamera)
        for (t in peli.tapahtumat) aanet.soita(t, kamera.paikka)
        if (peli.albumi.muutoksia != tallennettu) {
            tallennettu = peli.albumi.muutoksia
            tallenna(peli.albumi)
        }
        kasiTauko -= dt
        if (!pyyhkaisy.kaynnissa) {
            kasiX += (0f - kasiX) * minOf(1f, dt * 12f)
            kasiY += (KASI_LEPO_Y - kasiY) * minOf(1f, dt * 12f)
        }
    }

    /** Where the ball in the hand is in the world, or null when there is none to show. */
    fun kadenPaikka(): V3? {
        if (!seuranta || lattia == null || kasiTauko > 0f) return null
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
            MotionEvent.ACTION_DOWN -> if (lattia != null && kasiTauko <= 0f) pyyhkaisy.aloita(x, y, t)
            MotionEvent.ACTION_MOVE -> if (pyyhkaisy.kaynnissa) {
                pyyhkaisy.lisaa(x, y, t)
                kasiX = x; kasiY = y
            }
            MotionEvent.ACTION_UP -> if (pyyhkaisy.kaynnissa) {
                pyyhkaisy.lisaa(x, y, t)
                kasiX = x; kasiY = y
                val mista = kadenPaikka()
                pyyhkaisy.lopeta()?.let { heitto: Heitto ->
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
        PeliTila(Albumi.lue(muisti.getString("albumi", null))) { a -> muisti.edit().putString("albumi", a.teksti()).apply() }
    }
    var pisteet by remember { mutableIntStateOf(0) }
    var kerroin by remember { mutableIntStateOf(1) }
    var albumiAuki by remember { mutableStateOf(false) }
    val piirtaja = remember(engine, scene) { Piirtaja(engine, scene, materiaalit) }
    var koko by remember { mutableStateOf(IntSize.Zero) }
    var lattia by remember { mutableStateOf<Plane?>(null) }
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
            depthMode = Config.DepthMode.DISABLED,
            // The dots that show detected surfaces help until the floor is found, then get in the way
            planeRenderer = lattia == null,
            onSessionUpdated = { session, frame ->
                tila.ruutu(session, frame)
                piirtaja.paivita(tila.peli, tila.kadenPaikka(), tila.kadenKoko)
                lattia = tila.lattia
                keratyt = tila.peli.keratyt
                pisteet = tila.peli.pisteet
                kerroin = tila.peli.kerroin
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
                lattia == null -> "Liikuttele puhelinta hitaasti ja osoita lattiaa, niin ukot löytävät paikalle."
                heitot < 2 -> "Pyyhkäise ylöspäin heittääksesi pallon."
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
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFFFFFCF5)).clickable(enabled = false) {}.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val tumma = Color(0xFF2A2630)
            Text("Ukkoalbumi ${albumi.loydetty}/${Tyyppi.entries.size}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = tumma)
            for (t in Tyyppi.entries) {
                val maara = albumi[t]
                val vari = when (t) {
                    Tyyppi.TAVALLINEN -> Ulkonako.VARIT[0]
                    Tyyppi.JUOKSIJA -> Ulkonako.VARIT[1]
                    Tyyppi.ISO -> Ulkonako.VARIT[2]
                    Tyyppi.KULTAINEN -> Ulkonako.KULTA
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(if (maara > 0) Color(vari.r, vari.g, vari.b) else Color(0x402A2630)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (maara > 0) t.nimi else "???", fontWeight = FontWeight.Bold, color = tumma)
                        Text(if (maara > 0) "${t.harvinaisuus.nimi} · ${t.pisteet} p" else t.vihje, fontSize = 13.sp, color = tumma.copy(alpha = 0.7f))
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
