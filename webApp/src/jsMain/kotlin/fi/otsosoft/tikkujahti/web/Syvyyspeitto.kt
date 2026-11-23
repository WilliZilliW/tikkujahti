package fi.otsosoft.tikkujahti.web

import fi.otsosoft.tikkujahti.maailma.Pinta
import fi.otsosoft.tikkujahti.mesh.Peittajat

/**
 * Hides the figures behind real things in browser AR.
 *
 * - Where the browser gives a depth image (WebXR depth sensing, CPU): a screen-filling quad, drawn before
 *   anything else, writes the real room's depth into the depth buffer, so whatever virtual is behind a real
 *   wall, sofa or door frame fails the depth test. It writes no colour, so the camera image shows through.
 * - Always: invisible stand-ins for the recognised walls and table tops ([Peittajat]) do the same where the
 *   game knows the room, also on phones without depth.
 */
class Syvyyspeitto(private val scene: Scene) {
    private var tekstuuri: DataTexture? = null
    private var leveys = 0
    private var korkeus = 0
    private var metrit: dynamic = null
    private val muunna: dynamic = js("(function (lahde, kohde, k) { for (var i = 0; i < kohde.length; i++) kohde[i] = lahde[i] * k; })")

    private val aine = ShaderMaterial(olio {
        glslVersion = GLSL3
        uniforms = olio {
            syvyys = olio { value = null }
            muunnos = olio { value = Matrix4() }
            projektio = olio { value = arrayOf(0.0, 0.0) }
        }
        vertexShader = """
            out vec2 vUv;
            void main() {
                vUv = position.xy * 0.5 + 0.5;
                gl_Position = vec4(position.xy, 0.0, 1.0);
            }
        """
        fragmentShader = """
            uniform sampler2D syvyys;
            uniform mat4 muunnos;
            uniform vec2 projektio;
            in vec2 vUv;
            layout(location = 0) out highp vec4 tulos;
            void main() {
                // Normalised view coordinates have their origin top left; the depth buffer has its own
                vec2 uv = (muunnos * vec4(vUv.x, 1.0 - vUv.y, 0.0, 1.0)).xy;
                float d = texture(syvyys, uv).r;
                if (d <= 0.0) {
                    gl_FragDepth = 1.0; // nothing measured here: hide nothing
                } else {
                    // A little beyond the measured surface, so that figures standing or climbing on it stay whole
                    d = d * 1.02 + 0.04;
                    float ndc = (projektio.x * -d + projektio.y) / d;
                    gl_FragDepth = clamp(ndc * 0.5 + 0.5, 0.0, 1.0);
                }
                tulos = vec4(0.0);
            }
        """
        colorWrite = false
        depthWrite = true
        depthTest = true
        depthFunc = AlwaysDepth
    })

    private val ruutu = Mesh(PlaneGeometry(2.0, 2.0), aine).apply {
        frustumCulled = false
        renderOrder = -1000
        visible = false
    }

    private val peittoAine = MeshBasicMaterial(olio {
        colorWrite = false
        side = DoubleSide
    })
    private val peitot = Group()
    private val pinnat = HashMap<String, Pair<Pinta, Mesh>>()

    init {
        scene.add(ruutu)
        scene.add(peitot)
    }

    /**
     * The newest depth image ([tieto], an XRCPUDepthInformation, in data format [muoto]) and the projection
     * matrix of the view it belongs to.
     */
    fun syvyys(tieto: dynamic, muoto: String?, projektio: dynamic) {
        val w = tieto.width as Int
        val h = tieto.height as Int
        if (w != leveys || h != korkeus || tekstuuri == null) {
            tekstuuri?.dispose()
            leveys = w
            korkeus = h
            metrit = js("new Float32Array(w * h)")
            tekstuuri = DataTexture(metrit, w, h, RedFormat, FloatType).also {
                it.magFilter = NearestFilter
                it.minFilter = NearestFilter
                aine.uniforms.syvyys.value = it
            }
        }
        val data = tieto.data
        val lahde: dynamic = if (muoto == "float32") js("new Float32Array(data)") else js("new Uint16Array(data)")
        muunna(lahde, metrit, tieto.rawValueToMeters)
        tekstuuri!!.needsUpdate = true
        aine.uniforms.muunnos.value.fromArray(tieto.normDepthBufferFromNormView.matrix)
        aine.uniforms.projektio.value = arrayOf(projektio[10] as Double, projektio[14] as Double)
        ruutu.visible = true
    }

    /** No depth this frame (or not in AR): nothing is hidden by depth. */
    fun eiSyvyytta() {
        ruutu.visible = false
    }

    /** Keeps a stand-in for every recognised wall and table top in [kaikki] (an empty list removes them all). */
    fun pinnat(kaikki: List<Pinta>) {
        val nyt = kaikki.filter { Peittajat.peittaa(it) }.associateBy { it.id }
        val pois = pinnat.keys.filter { id -> nyt[id] !== pinnat[id]?.first }
        for (id in pois) {
            val (_, m) = pinnat.remove(id)!!
            peitot.remove(m)
            m.geometry.dispose()
        }
        for ((id, p) in nyt) {
            if (id in pinnat) continue
            val (paikat, kolmiot) = Peittajat.kolmiot(p)
            val g = BufferGeometry()
            g.setAttribute("position", BufferAttribute(js("new Float32Array(paikat)"), 3))
            g.setIndex(BufferAttribute(js("new Uint16Array(kolmiot)"), 1))
            val m = Mesh(g, peittoAine)
            m.frustumCulled = false
            m.renderOrder = -999
            peitot.add(m)
            pinnat[id] = p to m
        }
    }
}
