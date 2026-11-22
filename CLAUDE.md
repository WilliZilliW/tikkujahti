# Tikkujahti – ohjeet jatkajalle

Android-AR-peli (Otsosoft): oikeassa huoneessa liikkuu ilmeikkäitä tikku-ukkoja, joihin heitetään palloja
pyyhkäisemällä. Koko suunnitelma ja päätökset: issue #1. Peli tehdään kokonaan Claude Coden pilviympäristössä,
joten kaikki grafiikka ja äänet tehdään koodilla. Kommentit koodissa englanniksi, nimet suomeksi.

## Rakenne
- `shared/` – Kotlin Multiplatform (jvm + js): pelilogiikka, ukot, fysiikka, mallien generointi. Ei alustakoodia.
- `androidApp/` – ARCore + SceneView (lukittu 4.66.0, `gradle/libs.versions.toml`; SceneView julkaisee lähes
  päivittäin ja rajapinta muuttuu, älä päivitä sokkona). Compose. minSdk 26, compileSdk 37.
- `webApp/` – Kotlin/JS + three.js 0.187.0 → GitHub Pages. Tulossa: tavallinen 3D-huone, ukkolabra
  (`labra.html`) ja WebXR immersive-ar (hit-test) Androidin Chromelle. (sceneview-web hylättiin: alfa.)

## Ympäristö (pilvikontti on joka kerta tyhjä)
Googlen Maven toimii pilvestä. Android SDK pitää asentaa:
```bash
mkdir -p /opt/android-sdk && cd /opt/android-sdk
curl -sL -o clt.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q clt.zip && mkdir -p cmdline-tools/latest && mv cmdline-tools/{bin,lib,NOTICE.txt,source.properties} cmdline-tools/latest/ && rm clt.zip
export ANDROID_HOME=/opt/android-sdk
yes | cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null
cmdline-tools/latest/bin/sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "platform-tools"
echo "sdk.dir=/opt/android-sdk" > /home/user/tikkujahti/local.properties
```
Maven Central antaa välillä 429 Too Many Requests: aja build uudelleen (`--max-workers=2` auttaa).
Kotlin/JS käyttää npm:ää (`kotlin.js.yarn=false`), ja Karma on pois päältä, koska välityspalvelin ei päästä
yarnia GitHubiin.

## Komennot
```bash
./gradlew :shared:jvmTest                 # logiikan testit
./gradlew :androidApp:assembleDebug       # APK: androidApp/build/outputs/apk/debug/
./gradlew :webApp:jsBrowserDistribution   # selainversio: webApp/build/dist/js/productionExecutable/
node tools/kuvat.mjs                      # kuvat ukon liikkeistä → kuvat/labra-*.png (KATSO NE aina kun kosket ukkoon)
node tools/kuvat.mjs --sivu 'index.html?kuva=1&t=9&heitto=8.6&siemen=9' peli.png   # kuva pelistä huoneessa
```
Kaikki kolme rakentuivat viimeksi onnistuneesti ja testit menivät läpi. `kuvat.mjs` käyttää kontin valmista
Playwrightia ja Chromiumia (WebGL swiftshaderilla); ei tarvitse asentaa mitään.

## Tilanne
Ensimmäisen version koodi on kirjoitettu päästä päähän. Selainversio on nähty toimivan headless-Chromiumissa
(ukot, heitto, osuma, keräys). **Android-sovellusta ja WebXR-AR-tilaa ei ole ajettu millään laitteella**: ne
kääntyvät, mutta kaikki laitteella näkyvä on tarkistamatta (ks. "Tarkistettava laitteella").

`shared/src/commonMain/kotlin/fi/otsosoft/tikkujahti/`:
- `matikka/` – `V3`, `Kehys` (oikealle = eteen × ylos, eli (oikealle, ylos, eteen) on vasenkätinen), `Jousi`.
- `maailma/Pinta.kt` – pinnat monikulmioina, `Maailma`. `maailma/Huone.kt` – selainversion huoneen pinnat.
- `ukko/Ukko.kt` – aivot ja animaatio, `ukko/Nukke.kt` – ragdoll, `Asento`, `Ulkonako` (mitat, luonne, värit).
  Ukko suosii kävelykohteita pelaajan näkökentässä (`katsojanSuunta`).
- `mesh/Verkko.kt` – kiinteän topologian verkko osittain (materiaaleittain). `mesh/UkkoVerkko.kt` – ukko:
  putkiraajat pyöristetyin nivelin, pää ellipsoidina, kasvot pään pinnalla (`kp(a, b, koho)`); osat KEHO,
  VALKOINEN, TUMMA, SUU, KIELI. `PalloVerkko` – kaksivärinen pallo aaltoilevalla saumalla.
- `peli/Heitto.kt` – `Kamera`, `Pyyhkaisy` → `Heitto` (tähtäyspiste, voima, kaarre). `peli/Pallo.kt` – fysiikka.
  `peli/Peli.kt` – ukkojen tuonti, heitto (ballistinen kaari tähtäyspisteeseen + tähtäysapu), osumat
  (pallon askel vs. raajakapselit), vangitseminen ja paluu, `tapahtumat` ääniä varten.
- `aani/Syntikka.kt` – kaikki äänet PCM:nä (22050 Hz): formanttisiansaksa `Aantely`-kohtaisesti + tehosteet.
  **Kukaan ei ole vielä kuullut näitä**: testi tarkistaa vain, että ne ovat kelvollista ääntä.

`webApp/` (Kotlin/JS, ES-moduulit koska three.js on ESM-only): `Three.kt` (sidonnat vain käytetylle osalle),
`Piirto.kt` (`UkkoPiirto`, `PalloPiirto`, `Nakyma`), `PeliSivu.kt` (huone + WebXR-AR hit-testillä ja
dom-overlaylla), `Labra.kt`, `Aanet.kt` (Web Audio). Sivut `index.html`, `labra.html`, tyylit `tyyli.css`.

`androidApp/`: `MainActivity.kt` (`PeliTila`, `PeliNakyma`: `ARSceneView` + Compose-HUD), `ArMaailma.kt`
(ARCore-tasot → `Pinta`, kamera → `Kamera`; lattia = suurin ylöspäin osoittava taso alimman lähellä),
`Piirtaja.kt` (ukot ja pallot omina Filament-renderableina suoraan `scene`en; TANGENTS-kvaterniot lasketaan
normaaleista; puskureita kierrätetään, koska Filament lukee ne myöhemmin omassa säikeessään), `Aanet.kt`
(AudioTrack-mikseri). SceneViewin lähteet: `arsceneview-4.66.0-sources.jar` ja `sceneview-4.66.0-sources.jar`
Maven Centralista (`io/github/sceneview/`).

`.github/workflows/`: `apk.yml`, `julkaisu.yml`, `pages.yml`. **Ne käynnistyvät `main`-haarasta, jota ei vielä
ole** (oletushaara on vanha `ccr-abe38453-zavzha`): omistajan pitää luoda `main` ja tehdä siitä oletushaara,
kytkeä Pages päälle (Settings → Pages → Source: GitHub Actions) ja lisätä julkaisun salaisuudet.

## Tarkistettava laitteella (ei voi pilvessä)
- Android: näkyvätkö ukot ja pallot (materiaali `createColorInstance`, TANGENTS-kvaterniot), varjo lattialla
  (`ShadowReceiverPlane`), ukkojen koko ja paikka, kosketuksen suunta (`displayOrientedPose`), äänet.
- WebXR: lattian asetus napauttamalla, pyyhkäisy dom-overlayn läpi, varjo (`ShadowMaterial`).
- Heiton tuntuma (`Pyyhkaisy.lopeta`, `Peli.heita`: vauhti 3,2–7,5 m/s, `TAHTAYSAPU`, `OSUMA_APU`) ja
  väistöjen määrä (`Luonne.vaistotaito` 0,15–0,65) ovat arvauksia: säädä palautteen mukaan.

## Seuraavaksi
1. Laitepalaute yllä olevista ja korjaukset. Äänien kuuntelu ja viritys.
2. Issue #1:n "Ensimmäinen versio" -listan ruksit, kun omistaja on todennut kohdat laitteella.
3. Labraan pallon heitto ukkoa kohti (väistöjen hiomiseen) ja luonteen säätimet.
4. Depth API (piiloutuminen, osumat oikeisiin pintoihin), valon arvio selaimen AR:ään.
5. Issue #1:n seuraavat asiat: ukkotyypit, kiipeily seinillä ja reunoilla, erikoispallot, albumi.
6. Ehdota pilviympäristön asennusskriptiksi yllä oleva SDK-asennus.
