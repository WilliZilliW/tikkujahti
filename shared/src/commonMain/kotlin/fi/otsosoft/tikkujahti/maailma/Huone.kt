package fi.otsosoft.tikkujahti.maailma

import fi.otsosoft.tikkujahti.matikka.V3

/**
 * The virtual room of the browser version, for playing without AR: a floor, four walls, a ceiling and a
 * chest of drawers in the far right corner. x runs right, z towards the player, who stands near +z.
 */
object Huone {
    const val LEVEYS = 4.4f
    const val SYVYYS = 4.4f
    const val KORKEUS = 2.5f

    // The chest: a box against the back wall in the right-hand corner
    const val LIPASTO_LEVEYS = 1.0f
    const val LIPASTO_SYVYYS = 0.7f
    const val LIPASTO_KORKEUS = 0.45f
    val lipastonKeskus = V3(LEVEYS / 2f - LIPASTO_LEVEYS / 2f, LIPASTO_KORKEUS / 2f, -SYVYYS / 2f + LIPASTO_SYVYYS / 2f)

    fun pinnat(): List<Pinta> {
        val w = LEVEYS / 2f
        val d = SYVYYS / 2f
        val lx = w - LIPASTO_LEVEYS // the chest's left side
        val lz = -d + LIPASTO_SYVYYS // the chest's front
        return listOf(
            // The floor the figures walk on goes around the chest. Plane coordinates are (x, -z).
            Pinta("lattia", PinnanLaji.LATTIA, V3.NOLLA, V3.Y, V3.X, listOf(-w to -d, w to -d, w to -lz, lx to -lz, lx to d, -w to d)),
            Pinta.suorakaide("lattia-lipaston-alla", PinnanLaji.LATTIA, V3(lipastonKeskus.x, 0f, lipastonKeskus.z), V3.Y, V3.X, LIPASTO_LEVEYS, LIPASTO_SYVYYS),
            Pinta.suorakaide("takaseina", PinnanLaji.SEINA, V3(0f, KORKEUS / 2f, -d), V3.Z, V3.X, LEVEYS, KORKEUS),
            Pinta.suorakaide("etuseina", PinnanLaji.SEINA, V3(0f, KORKEUS / 2f, d), -V3.Z, V3.X, LEVEYS, KORKEUS),
            Pinta.suorakaide("vasen-seina", PinnanLaji.SEINA, V3(-w, KORKEUS / 2f, 0f), V3.X, V3.Z, SYVYYS, KORKEUS),
            Pinta.suorakaide("oikea-seina", PinnanLaji.SEINA, V3(w, KORKEUS / 2f, 0f), -V3.X, V3.Z, SYVYYS, KORKEUS),
            Pinta.suorakaide("katto", PinnanLaji.KATTO, V3(0f, KORKEUS, 0f), -V3.Y, V3.X, LEVEYS, SYVYYS),
            Pinta.suorakaide("lipaston-kansi", PinnanLaji.POYTA, V3(lipastonKeskus.x, LIPASTO_KORKEUS, lipastonKeskus.z), V3.Y, V3.X, LIPASTO_LEVEYS, LIPASTO_SYVYYS),
            Pinta.suorakaide("lipaston-etu", PinnanLaji.SEINA, V3(lipastonKeskus.x, LIPASTO_KORKEUS / 2f, lz), V3.Z, V3.X, LIPASTO_LEVEYS, LIPASTO_KORKEUS),
            Pinta.suorakaide("lipaston-kylki", PinnanLaji.SEINA, V3(lx, LIPASTO_KORKEUS / 2f, lipastonKeskus.z), -V3.X, V3.Z, LIPASTO_SYVYYS, LIPASTO_KORKEUS),
        )
    }
}
