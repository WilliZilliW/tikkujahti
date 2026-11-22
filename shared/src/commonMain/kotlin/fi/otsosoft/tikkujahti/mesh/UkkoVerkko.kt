package fi.otsosoft.tikkujahti.mesh

import fi.otsosoft.tikkujahti.matikka.V3
import fi.otsosoft.tikkujahti.matikka.clamp
import fi.otsosoft.tikkujahti.matikka.kohtisuora
import fi.otsosoft.tikkujahti.matikka.lerp
import fi.otsosoft.tikkujahti.ukko.Tyyppi
import fi.otsosoft.tikkujahti.matikka.saturoi
import fi.otsosoft.tikkujahti.ukko.Asento
import fi.otsosoft.tikkujahti.ukko.Mitat
import fi.otsosoft.tikkujahti.ukko.Nivel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The stick figure as one soft mesh, rebuilt from an [Asento] every frame.
 *
 * Parts (materials): [KEHO] body, [VALKOINEN] eye whites and highlights, [TUMMA] pupils, brows, lips and
 * closed-eye lines, [SUU] inside of the mouth, [KIELI] tongue, [VARUSTE] what the kind of figure carries: a
 * shield, a parachute, a ninja's headband (one hidden triangle for the kinds that carry nothing).
 *
 * Limbs are tubes swept through rounded joints, so elbows and knees bend smoothly. The face is drawn on the
 * head's surface in the head's own frame.
 */
class UkkoVerkko(private val m: Mitat, private val tyyppi: Tyyppi = Tyyppi.TAVALLINEN) : Verkko(6) {

    fun paivita(a: Asento) {
        aloita()
        val p = a.paksuus
        val rs = m.raajaSade * p
        val vs = m.vartaloSade * p

        osa = KEHO
        // Torso and neck, ending inside the head
        putki4(a[Nivel.LANTIO], vs, a[Nivel.RINTA], vs * 0.92f, a[Nivel.PAA], vs * 0.6f, null, 0f)
        // Arms from the chest through the shoulder
        putki4(a[Nivel.RINTA], rs * 1.05f, a[Nivel.OLKA_V], rs, a[Nivel.KYYNARPAA_V], rs * 0.92f, a[Nivel.KASI_V], rs * 0.85f)
        putki4(a[Nivel.RINTA], rs * 1.05f, a[Nivel.OLKA_O], rs, a[Nivel.KYYNARPAA_O], rs * 0.92f, a[Nivel.KASI_O], rs * 0.85f)
        // Legs from the pelvis through the hip
        putki4(a[Nivel.LANTIO], rs * 1.1f, a[Nivel.LONKKA_V], rs * 1.08f, a[Nivel.POLVI_V], rs * 0.98f, a[Nivel.NILKKA_V], rs * 0.9f)
        putki4(a[Nivel.LANTIO], rs * 1.1f, a[Nivel.LONKKA_O], rs * 1.08f, a[Nivel.POLVI_O], rs * 0.98f, a[Nivel.NILKKA_O], rs * 0.9f)
        // Feet
        putki4(a[Nivel.NILKKA_V], rs * 1.15f, a[Nivel.VARVAS_V], rs * 1.3f, null, 0f, null, 0f)
        putki4(a[Nivel.NILKKA_O], rs * 1.15f, a[Nivel.VARVAS_O], rs * 1.3f, null, 0f, null, 0f)
        // Hands
        val ks = m.kasiSade * p
        ellipsoidi(a[Nivel.KASI_V], V3.Y, V3.Z, V3.X, ks, ks, ks, 6, 10)
        ellipsoidi(a[Nivel.KASI_O], V3.Y, V3.Z, V3.X, ks, ks, ks, 6, 10)

        // Head: squash and stretch keeps the volume
        val r = m.paaSade * p
        val ven = a.paaVenytys
        val leveys = 1f / sqrt(ven)
        paaK = a[Nivel.PAA]
        paaO = a.paa.oikealle; paaY = a.paa.ylos; paaE = a.paa.eteen
        paaR = r
        paaRx = r * leveys; paaRy = r * ven; paaRz = r * leveys
        ellipsoidi(paaK, paaY, paaO, paaE, paaRy, paaRx, paaRz, 11, 18)

        kasvot(a)
        varuste(a)
        valmis()
    }

    // =====================================================================================================
    // Accessories
    // =====================================================================================================

    private fun varuste(a: Asento) {
        osa = VARUSTE
        val k = m.koko * a.paksuus
        val ylos = a.ylos
        val eteen = a.eteen.tasolle(ylos).suunta(kohtisuora(ylos))
        val oikealle = eteen cross ylos
        when (tyyppi) {
            Tyyppi.KILPI -> {
                // A round shield on the left forearm, facing the way the body faces
                val keski = lerp(a[Nivel.KYYNARPAA_V], a[Nivel.KASI_V], 0.75f) + eteen * (0.012f * k)
                ellipsoidi(keski, eteen, ylos, oikealle, 0.008f * k, 0.052f * k, 0.052f * k, 5, 16)
            }
            Tyyppi.LASKUVARJO -> {
                // A canopy above the head with a line to each hand; it folds to nothing after landing
                val v = a.varuste * k
                val huippu = a[Nivel.PAA] + ylos * (0.2f * m.koko * (0.3f + 0.7f * a.varuste))
                ellipsoidi(huippu, ylos, oikealle, eteen, 0.05f * v, 0.13f * v, 0.1f * v, 6, 16)
                putki4(a[Nivel.KASI_V], 0.0016f * v, huippu - oikealle * (0.11f * v), 0.0016f * v, null, 0f, null, 0f)
                putki4(a[Nivel.KASI_O], 0.0016f * v, huippu + oikealle * (0.11f * v), 0.0016f * v, null, 0f, null, 0f)
            }
            Tyyppi.NINJA -> {
                // A headband: a flat disc through the head, of which only the rim shows
                ellipsoidi(paaK + paaY * (paaRy * 0.42f), paaY, paaO, paaE, paaRy * 0.17f, paaRx * 1.0f, paaRz * 1.0f, 5, 18)
            }
            else -> {
                // Nothing to carry: one collapsed triangle keeps the part from being empty
                val i = piste(paaK.x, paaK.y, paaK.z, 0f, 1f, 0f)
                piste(paaK.x, paaK.y, paaK.z, 0f, 1f, 0f)
                piste(paaK.x, paaK.y, paaK.z, 0f, 1f, 0f)
                kolmio(i, i + 1, i + 2)
            }
        }
    }

    // =====================================================================================================
    // Tubes
    // =====================================================================================================

    private val sx = FloatArray(MAX_NAYTTEITA)
    private val sy = FloatArray(MAX_NAYTTEITA)
    private val sz = FloatArray(MAX_NAYTTEITA)
    private val sr = FloatArray(MAX_NAYTTEITA)
    private var ns = 0

    private fun nayte(x: Float, y: Float, z: Float, r: Float) {
        sx[ns] = x; sy[ns] = y; sz[ns] = z; sr[ns] = r; ns++
    }

    /** A tube through two to four points with the given radii; the inner points become rounded joints. */
    private fun putki4(p0: V3, r0: Float, p1: V3, r1: Float, p2: V3?, r2: Float, p3: V3?, r3: Float) {
        ns = 0
        nayte(p0.x, p0.y, p0.z, r0)
        if (p2 != null) {
            nivel(p0, r0, p1, r1, p2, r2)
            if (p3 != null) {
                nivel(p1, r1, p2, r2, p3, r3)
                nayte(p3.x, p3.y, p3.z, r3)
            } else {
                nayte(p2.x, p2.y, p2.z, r2)
            }
        } else {
            nayte(p1.x, p1.y, p1.z, r1)
        }
        rakennaPutki()
    }

    /** Samples a rounded corner at [p] between [a] and [b] (a quadratic Bézier that cuts the corner). */
    private fun nivel(a: V3, ra: Float, p: V3, rp: Float, b: V3, rb: Float) {
        val la = (p - a).pituus
        val lb = (b - p).pituus
        val d = min(min(la, lb) * 0.38f, rp * 3f)
        val ta = if (la > 1e-7f) d / la else 0f
        val tb = if (lb > 1e-7f) d / lb else 0f
        val ax = p.x + (a.x - p.x) * ta
        val ay = p.y + (a.y - p.y) * ta
        val az = p.z + (a.z - p.z) * ta
        val bx = p.x + (b.x - p.x) * tb
        val by = p.y + (b.y - p.y) * tb
        val bz = p.z + (b.z - p.z) * tb
        val rA = rp + (ra - rp) * ta
        val rB = rp + (rb - rp) * tb
        for (j in 0 until NIVEL_NAYTTEITA) {
            val t = j / (NIVEL_NAYTTEITA - 1f)
            val w0 = (1f - t) * (1f - t)
            val w1 = 2f * t * (1f - t)
            val w2 = t * t
            nayte(w0 * ax + w1 * p.x + w2 * bx, w0 * ay + w1 * p.y + w2 * by, w0 * az + w1 * p.z + w2 * bz, w0 * rA + w1 * rp + w2 * rB)
        }
    }

    // Current ring frame: tangent T, normal N, binormal B = T × N
    private var tx = 0f; private var ty = 1f; private var tz = 0f
    private var nx = 1f; private var ny = 0f; private var nz = 0f
    private var bx = 0f; private var by = 0f; private var bz = 1f

    private fun tangentti(i: Int) {
        val a = max(0, i - 1)
        val b = min(ns - 1, i + 1)
        val dx = sx[b] - sx[a]
        val dy = sy[b] - sy[a]
        val dz = sz[b] - sz[a]
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        if (l > 1e-7f) {
            tx = dx / l; ty = dy / l; tz = dz / l
        }
    }

    /** Makes N perpendicular to T again (parallel transport along the tube) and recomputes B. */
    private fun kuljetaKehys() {
        val d = nx * tx + ny * ty + nz * tz
        var x = nx - tx * d
        var y = ny - ty * d
        var z = nz - tz * d
        var l = sqrt(x * x + y * y + z * z)
        if (l < 1e-4f) {
            // N was along T: pick any perpendicular
            if (abs(ty) < 0.9f) {
                x = -tz; y = 0f; z = tx
            } else {
                x = 0f; y = tz; z = -ty
            }
            l = sqrt(x * x + y * y + z * z)
        }
        nx = x / l; ny = y / l; nz = z / l
        bx = ty * nz - tz * ny
        by = tz * nx - tx * nz
        bz = tx * ny - ty * nx
    }

    /** One ring of the tube around (cx, cy, cz); [aks] tilts the normals along the tangent (for the caps). */
    private fun rengas(cx: Float, cy: Float, cz: Float, r: Float, aks: Float) {
        val sade = sqrt(max(0f, 1f - aks * aks))
        for (k in 0 until SIVUJA) {
            val c = COS[k]
            val s = SIN[k]
            val ux = nx * c + bx * s
            val uy = ny * c + by * s
            val uz = nz * c + bz * s
            piste(cx + ux * r, cy + uy * r, cz + uz * r, ux * sade + tx * aks, uy * sade + ty * aks, uz * sade + tz * aks)
        }
    }

    private fun rakennaPutki() {
        val alku = pisteita
        tx = 0f; ty = 1f; tz = 0f
        tangentti(0)
        nx = 1f; ny = 0f; nz = 0f
        kuljetaKehys()
        // Start cap
        val r0 = sr[0]
        piste(sx[0] - tx * r0, sy[0] - ty * r0, sz[0] - tz * r0, -tx, -ty, -tz)
        for (j in KANSI downTo 1) {
            val f = j / (KANSI + 1f) * (PI.toFloat() / 2f)
            val s = sin(f)
            val c = cos(f)
            rengas(sx[0] - tx * r0 * s, sy[0] - ty * r0 * s, sz[0] - tz * r0 * s, r0 * c, -s)
        }
        for (i in 0 until ns) {
            tangentti(i)
            kuljetaKehys()
            rengas(sx[i], sy[i], sz[i], sr[i], 0f)
        }
        // End cap
        val e = ns - 1
        val r1 = sr[e]
        for (j in 1..KANSI) {
            val f = j / (KANSI + 1f) * (PI.toFloat() / 2f)
            val s = sin(f)
            val c = cos(f)
            rengas(sx[e] + tx * r1 * s, sy[e] + ty * r1 * s, sz[e] + tz * r1 * s, r1 * c, s)
        }
        piste(sx[e] + tx * r1, sy[e] + ty * r1, sz[e] + tz * r1, tx, ty, tz)
        kuori(alku, ns + 2 * KANSI, SIVUJA)
    }

    // =====================================================================================================
    // Ellipsoids (head, hands)
    // =====================================================================================================

    /** An ellipsoid with its pole axis along [t]; [n] and [b] must satisfy b = t × n. */
    private fun ellipsoidi(c: V3, t: V3, n: V3, b: V3, rt: Float, rn: Float, rb: Float, renkaita: Int, sivuja: Int) {
        val alku = pisteita
        piste(c.x - t.x * rt, c.y - t.y * rt, c.z - t.z * rt, -t.x, -t.y, -t.z)
        for (i in 1..renkaita) {
            val lat = (i / (renkaita + 1f) - 0.5f) * PI.toFloat()
            val sl = sin(lat)
            val cl = cos(lat)
            for (k in 0 until sivuja) {
                val lon = k * 2f * PI.toFloat() / sivuja
                val a = cl * cos(lon)
                val d = cl * sin(lon)
                // Normal of an ellipsoid: the unit-sphere point divided by the radii
                var ex = n.x * (a / rn) + b.x * (d / rb) + t.x * (sl / rt)
                var ey = n.y * (a / rn) + b.y * (d / rb) + t.y * (sl / rt)
                var ez = n.z * (a / rn) + b.z * (d / rb) + t.z * (sl / rt)
                val l = sqrt(ex * ex + ey * ey + ez * ez)
                if (l > 1e-12f) {
                    ex /= l; ey /= l; ez /= l
                }
                piste(
                    c.x + n.x * (a * rn) + b.x * (d * rb) + t.x * (sl * rt),
                    c.y + n.y * (a * rn) + b.y * (d * rb) + t.y * (sl * rt),
                    c.z + n.z * (a * rn) + b.z * (d * rb) + t.z * (sl * rt),
                    ex, ey, ez,
                )
            }
        }
        piste(c.x + t.x * rt, c.y + t.y * rt, c.z + t.z * rt, t.x, t.y, t.z)
        kuori(alku, renkaita, sivuja)
    }

    // =====================================================================================================
    // Face
    // =====================================================================================================

    private var paaK = V3.NOLLA
    private var paaO = V3.X
    private var paaY = V3.Y
    private var paaE = V3.Z
    private var paaR = 1f
    private var paaRx = 1f
    private var paaRy = 1f
    private var paaRz = 1f

    /**
     * A vertex on the face: ([a], [b]) are metres to the figure's right and up from the middle of the face on
     * an unsquashed head, projected straight back onto the head and lifted [koho] off the surface.
     */
    private fun kp(a: Float, b: Float, koho: Float): Int {
        val r = paaR
        var x = a / r
        var y = b / r
        val d2 = x * x + y * y
        if (d2 > 0.9f) {
            val k = sqrt(0.9f / d2)
            x *= k; y *= k
        }
        val z = sqrt(1f - x * x - y * y)
        var ux = x / paaRx
        var uy = y / paaRy
        var uz = z / paaRz
        val l = sqrt(ux * ux + uy * uy + uz * uz)
        ux /= l; uy /= l; uz /= l
        val lx = x * paaRx + ux * koho
        val ly = y * paaRy + uy * koho
        val lz = z * paaRz + uz * koho
        return piste(
            paaK.x + paaO.x * lx + paaY.x * ly + paaE.x * lz,
            paaK.y + paaO.y * lx + paaY.y * ly + paaE.y * lz,
            paaK.z + paaO.z * lx + paaY.z * ly + paaE.z * lz,
            paaO.x * ux + paaY.x * uy + paaE.x * uz,
            paaO.y * ux + paaY.y * uy + paaE.y * uz,
            paaO.z * ux + paaY.z * uy + paaE.z * uz,
        )
    }

    /** A face triangle given counter-clockwise in the (right, up) plane; seen from the front that is mirrored. */
    private fun k2(a: Int, b: Int, c: Int) = kolmio(a, c, b)

    /** Triangles for a strip whose vertices alternate lower, upper along increasing a, starting at [alku]. */
    private fun nauha(alku: Int, n: Int) {
        if (!ensimmainen) return
        for (i in 0 until n - 1) {
            val l0 = alku + i * 2
            k2(l0, l0 + 2, l0 + 3)
            k2(l0, l0 + 3, l0 + 1)
        }
    }

    /** Triangles for a fan: a centre at [alku] followed by [n] rim vertices counter-clockwise in (right, up). */
    private fun viuhka(alku: Int, n: Int) {
        if (!ensimmainen) return
        for (k in 0 until n) k2(alku, alku + 1 + k, alku + 1 + (k + 1) % n)
    }

    private fun kasvot(a: Asento) {
        val k = a.kasvot
        val r = paaR
        silma(-1f, k.aukiV, k.silmaKoko, k.ilo, k.katseX, k.katseY)
        silma(1f, k.aukiO, k.silmaKoko, k.ilo, k.katseX, k.katseY)

        // Brows: tilt -1 angry (inner ends down) … 1 worried (inner ends up)
        osa = TUMMA
        val silmaR = SILMA_R * clamp(k.silmaKoko, 0.6f, 1.4f)
        for (puoli in 0..1) {
            val s = if (puoli == 0) -1f else 1f
            val alku = pisteita
            val keskiB = SILMA_Y + silmaR + 0.10f + k.kulmaKorkeus * 0.085f
            for (i in 0 until KULMA_N) {
                val ua = i / (KULMA_N - 1f) * 2f - 1f // along +a
                val u = s * ua // from the inner end to the outer end
                val pa = s * SILMA_X + ua * 0.21f
                val pb = keskiB + k.kulmaKulma * -u * 0.085f + 0.035f * (1f - u * u)
                val paksu = 0.034f * (0.45f + 0.55f * (1f - u * u)) * (1f + 0.25f * abs(k.kulmaKulma))
                kp(pa * r, (pb - paksu) * r, 0.07f * r)
                kp(pa * r, (pb + paksu) * r, 0.07f * r)
            }
            nauha(alku, KULMA_N)
        }

        // Mouth
        val w = 0.28f * clamp(k.suuLeveys, 0.3f, 1.6f)
        val auki = saturoi(k.suuAuki)
        val hymy = clamp(k.hymy, -1.2f, 1.2f)
        fun keski(u: Float) = SUU_Y + hymy * 0.13f * (u * u - 0.4f)
        fun aukko(u: Float) = auki * 0.36f * max(0f, 1f - u * u).pow(0.7f)
        fun yla(u: Float) = keski(u) + 0.3f * aukko(u)
        fun ala(u: Float) = keski(u) - 0.7f * aukko(u)

        // Inside of the mouth, in two strips: one tall strip would cut through the curve of the head
        osa = SUU
        var alku = pisteita
        for (puolisko in 0..1) {
            alku = pisteita
            for (i in 0 until SUU_N) {
                val u = i / (SUU_N - 1f) * 2f - 1f
                val puolivali = (ala(u) + yla(u)) / 2f
                kp(u * w * r, (if (puolisko == 0) ala(u) else puolivali) * r, 0.01f * r)
                kp(u * w * r, (if (puolisko == 0) puolivali else yla(u)) * r, 0.01f * r)
            }
            nauha(alku, SUU_N)
        }

        // Tongue: rests inside the open mouth, sticks out over the lower lip when teasing
        osa = KIELI
        val kieli = saturoi(k.kieli)
        alku = pisteita
        val kieliY = ala(0f) + 0.07f - 0.2f * kieli
        val kieliKoho = (0.018f + 0.012f * kieli) * r
        fun kieliPiste(dx: Float, dy: Float) {
            val x = clamp(dx, -0.9f * w, 0.9f * w)
            val u = x / w
            val alaraja = ala(u) + 0.012f - kieli * 0.4f
            val ylaraja = max(alaraja, yla(u) - 0.012f)
            kp(x * r, clamp(kieliY + dy, alaraja, ylaraja) * r, kieliKoho)
        }
        kieliPiste(0f, 0f)
        for (i in 0 until KIELI_N) {
            val kulma = i * 2f * PI.toFloat() / KIELI_N
            kieliPiste(cos(kulma) * 0.13f, sin(kulma) * (0.11f + 0.07f * kieli))
        }
        viuhka(alku, KIELI_N)

        // Lips: a dark line along both edges of the mouth; they meet when the mouth is shut
        osa = TUMMA
        for (huuli in 0..1) {
            alku = pisteita
            for (i in 0 until SUU_N) {
                val u = i / (SUU_N - 1f) * 2f - 1f
                val y = if (huuli == 0) ala(u) else yla(u)
                val paksu = 0.02f * (0.5f + 0.5f * sqrt(max(0f, 1f - u * u * u * u)))
                kp(u * w * r, (y - paksu) * r, 0.024f * r)
                kp(u * w * r, (y + paksu) * r, 0.024f * r)
            }
            nauha(alku, SUU_N)
        }
    }

    private fun silma(s: Float, auki0: Float, koko: Float, ilo0: Float, katseX: Float, katseY: Float) {
        val r = paaR
        val auki = saturoi(auki0)
        val ilo = saturoi(ilo0)
        val er = SILMA_R * clamp(koko, 0.6f, 1.4f) // eye radius, in head radii
        val cx = s * SILMA_X
        val cy = SILMA_Y
        val hYla = er * auki
        val hAla = er * auki * (1f - 0.8f * ilo)
        // Where the lids meet when shut: an arch (∩) when happy, a sleepy curve (∪) otherwise
        fun kiinni(x: Float) = er * (1f - (x / er) * (x / er)) * (0.34f * ilo - 0.12f) * (1f - auki)
        fun kaari(x: Float) = sqrt(max(0f, 1f - (x / er) * (x / er)))
        fun koho(x: Float, y: Float) = (0.012f + 0.035f * max(0f, 1f - (x * x + y * y) / (er * er))) * r
        /** A vertex inside the eye, kept between the lids. */
        fun sisalla(x0: Float, y0: Float, lisa: Float) {
            val x = clamp(x0, -0.97f * er, 0.97f * er)
            val k = kaari(x) * 0.96f
            val y = clamp(y0, kiinni(x) - hAla * k, kiinni(x) + hYla * k)
            kp((cx + x) * r, (cy + y) * r, koho(x, y) + lisa * r)
        }

        // White
        osa = VALKOINEN
        var alku = pisteita
        kp(cx * r, (cy + kiinni(0f)) * r, koho(0f, 0f))
        for (rengas in 1..2) {
            val rho = rengas / 2f
            for (i in 0 until SILMA_N) {
                val kulma = i * 2f * PI.toFloat() / SILMA_N
                val x = cos(kulma) * er * rho
                val si = sin(kulma)
                val y = kiinni(x) + (if (si >= 0f) hYla else hAla) * si * rho
                kp((cx + x) * r, (cy + y) * r, koho(x, si * er * rho))
            }
        }
        if (ensimmainen) {
            viuhka(alku, SILMA_N)
            for (i in 0 until SILMA_N) {
                val i1 = (i + 1) % SILMA_N
                val a0 = alku + 1 + i
                val a1 = alku + 1 + i1
                k2(a0, a0 + SILMA_N, a1 + SILMA_N)
                k2(a0, a1 + SILMA_N, a1)
            }
        }

        // Pupil
        osa = TUMMA
        val nakyy = saturoi((auki - 0.08f) / 0.2f)
        val px = clamp(katseX, -1f, 1f) * 0.42f * er
        val py = clamp(katseY, -1f, 1f) * 0.36f * er
        val pr = 0.5f * er
        alku = pisteita
        sisalla(px, py, 0.01f)
        for (i in 0 until PUPILLI_N) {
            val kulma = i * 2f * PI.toFloat() / PUPILLI_N
            sisalla(px + cos(kulma) * pr, py + sin(kulma) * pr, 0.01f)
        }
        viuhka(alku, PUPILLI_N)

        // Highlight: always up and to the same side, so both eyes catch the same light
        osa = VALKOINEN
        val kx = px + 0.2f * er
        val ky = py + 0.22f * er
        val kr = 0.17f * er * nakyy
        alku = pisteita
        sisalla(kx, ky, 0.018f)
        for (i in 0 until KIILTO_N) {
            val kulma = i * 2f * PI.toFloat() / KIILTO_N
            sisalla(kx + cos(kulma) * kr, ky + sin(kulma) * kr, 0.018f)
        }
        viuhka(alku, KIILTO_N)

        // The line of a shut eye
        osa = TUMMA
        val viiva = 0.075f * er * saturoi((0.32f - auki) / 0.2f)
        alku = pisteita
        for (i in 0 until VIIVA_N) {
            val u = i / (VIIVA_N - 1f) * 2f - 1f
            val x = u * er
            val paksu = viiva * (0.4f + 0.6f * kaari(x))
            kp((cx + x) * r, (cy + kiinni(x) - paksu) * r, 0.05f * r)
            kp((cx + x) * r, (cy + kiinni(x) + paksu) * r, 0.05f * r)
        }
        nauha(alku, VIIVA_N)
    }

    // Last, so that every field above is ready: the first build fixes the topology
    init {
        paivita(Asento().also { a ->
            // Any non-degenerate pose will do for the first build
            for (n in Nivel.entries) a[n] = V3(n.ordinal * 0.01f, n.ordinal * 0.013f, (n.ordinal % 3) * 0.01f)
        })
    }

    companion object {
        const val KEHO = 0
        const val VALKOINEN = 1
        const val TUMMA = 2
        const val SUU = 3
        const val KIELI = 4
        const val VARUSTE = 5

        private const val SIVUJA = 10
        private const val KANSI = 2
        private const val NIVEL_NAYTTEITA = 5
        private const val MAX_NAYTTEITA = 2 + 2 * NIVEL_NAYTTEITA

        // Face layout, in head radii
        private const val SILMA_X = 0.37f
        private const val SILMA_Y = 0.12f
        private const val SILMA_R = 0.29f
        private const val SUU_Y = -0.42f
        private const val SILMA_N = 14
        private const val PUPILLI_N = 12
        private const val KIILTO_N = 8
        private const val VIIVA_N = 9
        private const val KULMA_N = 7
        private const val SUU_N = 11
        private const val KIELI_N = 10

        private val COS = FloatArray(SIVUJA) { cos(it * 2f * PI.toFloat() / SIVUJA) }
        private val SIN = FloatArray(SIVUJA) { sin(it * 2f * PI.toFloat() / SIVUJA) }
    }
}

/**
 * A ball in two colours with a wavy seam, like a tennis ball. Part 0 is one half, part 1 the other.
 * Unit radius, centred on the origin: the renderer scales, moves and spins it.
 */
class PalloVerkko(renkaita: Int = 14, sivuja: Int = 24) : Verkko(2) {
    init {
        aloita()
        piste(0f, -1f, 0f, 0f, -1f, 0f)
        for (i in 1..renkaita) {
            val lat0 = (i / (renkaita + 1f) - 0.5f) * PI.toFloat()
            for (k in 0 until sivuja) {
                val lon = k * 2f * PI.toFloat() / sivuja
                // The grid itself waves, so the equator ring becomes the seam
                val lat = lat0 + 0.3f * sin(3f * lon) * cos(lat0) * cos(lat0)
                val x = cos(lat) * cos(lon)
                val y = sin(lat)
                val z = -cos(lat) * sin(lon)
                piste(x, y, z, x, y, z)
            }
        }
        piste(0f, 1f, 0f, 0f, 1f, 0f)
        val keski = (renkaita + 1) / 2
        // Shell indices as in Verkko.kuori, split at the middle ring
        for (k in 0 until sivuja) {
            val k1 = (k + 1) % sivuja
            osa = 0
            kolmio(0, 1 + k1, 1 + k)
            for (i in 0 until renkaita - 1) {
                osa = if (i + 1 < keski) 0 else 1
                val a = 1 + i * sivuja
                val b = a + sivuja
                kolmio(a + k, a + k1, b + k)
                kolmio(a + k1, b + k1, b + k)
            }
            osa = 1
            val a = 1 + (renkaita - 1) * sivuja
            kolmio(a + k, a + k1, 1 + renkaita * sivuja)
        }
        valmis()
    }
}
