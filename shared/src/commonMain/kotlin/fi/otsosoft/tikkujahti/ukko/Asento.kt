package fi.otsosoft.tikkujahti.ukko

import fi.otsosoft.tikkujahti.matikka.Kehys
import fi.otsosoft.tikkujahti.matikka.V3

/** Joints of a stick figure. V = vasen (left), O = oikea (right), from the figure's own point of view. */
enum class Nivel {
    LANTIO, RINTA, NISKA, PAA,
    OLKA_V, KYYNARPAA_V, KASI_V,
    OLKA_O, KYYNARPAA_O, KASI_O,
    LONKKA_V, POLVI_V, NILKKA_V, VARVAS_V,
    LONKKA_O, POLVI_O, NILKKA_O, VARVAS_O,
}

/** Facial expression parameters. The mesh turns these into eyes, brows and a mouth. */
class Kasvot {
    /** Where the pupils look, in the head's frame: -1…1 right and up. */
    var katseX = 0f
    var katseY = 0f
    /** Eyelid opening per eye, 0 closed … 1 open. */
    var aukiV = 1f
    var aukiO = 1f
    /** Eye size: above 1 is wide-eyed surprise or fear. */
    var silmaKoko = 1f
    /** Happy squint: the lower lid rises, 0…1. */
    var ilo = 0f
    /** Brow height -1 (low) … 1 (raised). */
    var kulmaKorkeus = 0f
    /** Brow tilt: -1 angry (inner ends down) … 1 worried (inner ends up). */
    var kulmaKulma = 0f
    /** Mouth curve -1 frown … 1 smile. */
    var hymy = 0f
    /** Mouth opening 0…1. */
    var suuAuki = 0f
    /** Mouth width, 1 normal. */
    var suuLeveys = 1f
    /** Tongue out 0…1. */
    var kieli = 0f

    fun kopioi(o: Kasvot) {
        katseX = o.katseX; katseY = o.katseY; aukiV = o.aukiV; aukiO = o.aukiO; silmaKoko = o.silmaKoko
        ilo = o.ilo; kulmaKorkeus = o.kulmaKorkeus; kulmaKulma = o.kulmaKulma; hymy = o.hymy
        suuAuki = o.suuAuki; suuLeveys = o.suuLeveys; kieli = o.kieli
    }
}

/** Everything the renderer needs for one frame of a stick figure. */
class Asento {
    val nivelet = Array(Nivel.entries.size) { V3.NOLLA }
    /** Orientation of the head: where the face points. */
    var paa = Kehys(V3.Z, V3.Y, V3(-1f, 0f, 0f))
    /** Head squash and stretch along its up axis, 1 = round. */
    var paaVenytys = 1f
    /** Scales every thickness; shrinks the figure as it curls into the ball. */
    var paksuus = 1f
    val kasvot = Kasvot()
    var nakyvissa = true
    /** How far the figure's accessory is out: an open parachute is 1, a folded one 0. */
    var varuste = 1f
    /** An invisible figure: only the face is drawn. */
    var haamu = false
    /** Where the body faces and which way is up for it (the normal of the surface it stands on). */
    var eteen = V3.Z
    var ylos = V3.Y

    operator fun get(n: Nivel) = nivelet[n.ordinal]
    operator fun set(n: Nivel, v: V3) {
        nivelet[n.ordinal] = v
    }
}
