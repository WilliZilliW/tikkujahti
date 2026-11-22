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
```
Kaikki kolme rakentuivat viimeksi onnistuneesti.

## Tilanne
Valmiina (`shared/src/commonMain/kotlin/fi/otsosoft/tikkujahti/`):
- `matikka/V3.kt` – vektorit, `Kehys` (oikealle = eteen × ylös), apufunktiot. Maailma y ylös, metrit.
- `matikka/Jousi.kt` – toisen kertaluvun dynamiikka (taajuus, vaimennus, ennakointi): kaikki liike kulkee
  näiden läpi, jotta ukot eivät ole pökkelöitä.
- `maailma/Pinta.kt` – tunnistetut pinnat monikulmioina, `Maailma`.
- `ukko/Asento.kt`, `ukko/Ulkonako.kt` – nivelet, kasvoparametrit, mitat (~25 cm ukko), luonne, värit.
- `ukko/Ukko.kt` – aivot ja animaatio: astuvat jalat (ei liukumista), lantion keinunta, nojaus kiihtyvyyteen,
  kädet heiluvat jalkoja vastaan, kahden luun IK, pää ja pupillit katsovat kameraa/palloa, räpsyt, sakkadit,
  ilmeet tunteittain. Toiminnot: SAAPUU, SEISOO, KAVELEE, JUOKSEE, KATSELEE, VILKUTTAA, RAAPII_PAATA,
  HAUKOTTELEE, TUULETTAA, SAIKAHTAA, SUUTTUU, KIUSOITTELEE, VAISTAA_HYPPY, VAISTAA_KYYKKY, OSUMA, KAPERTYY.
  `havaitsePallo` ennakoi lentoradan ja päättää väistöstä, `osuma` käynnistää nuken ja käpertymisen.
  Ääniä varten `aanet`-lista (`Aantely`). Kääntyy, mutta EI OLE VIELÄ TESTATTU eikä nähty kuvana.
- `ukko/Nukke.kt` – Verlet-ragdoll osumaan.
- Issue #1 päivitetty päätöksillä. Testiavain `androidApp/testiavain.keystore` (salasana android), kuten Pisarassa.

## Seuraavaksi (järjestyksessä)
1. `mesh/UkkoVerkko.kt`: kiinteä topologia (indeksit kerran, joka ruudulla vain paikat+normaalit), osat
   väreittäin: 0 keho, 1 silmänvalkuaiset+kiiltopisteet, 2 tumma (pupillit, kulmat, suu, suljetun silmän viiva),
   3 suun sisus, 4 kieli. Raajat yhtenä putkena pyöristetyn nivelen läpi (rinnakkaissiirtokehykset), pää
   ellipsoidi `paaVenytys`-litistyksellä, kasvot pään pinnalle `Asento.paa`-kehyksessä, piilotetut osat
   pienennetään lähes nollaan. Kolmioiden kierto CCW ulospäin. Myös `PalloVerkko` (kaksivärinen pallo).
2. `peli/`: pallon fysiikka (painovoima, kimpoilu pinnoista, kaarre), pyyhkäisy → heitto (`Kamera`-säde NDC:stä,
   pieni tähtäysapu), osumatesti pallo vs. raajakapselit, `Peli` (ukot, pallot, kerätyt, tapahtumat ääniä varten).
   Osuman jälkeen pallo pysähtyy, ukko käpertyy sisään (`pallonKeskus`), pallo lentää pelaajalle → kerätty.
3. `aani/`: syntetisoitu siansaksa (formantit, sävelkulku tunteen mukaan) + heitto, pomppu, osuma, keräys PCM:nä.
4. jvmTest: kävely ei liu'u, ukko pysyy lattialla, väistö toimii, osuma kerää.
5. webApp: three.js-externals, 3D-huone + pyyhkäisyheitto, `labra.html` (napit jokaiselle toiminnolle,
   hidastus, värit, pyöritys), WebXR AR. Tarkista ulkonäkö headless-Chromiumilla (Playwright valmiina) ja katso kuvat.
6. androidApp: `ARSceneView(engine, scene, materialLoader, planeFindingMode, depthMode, onSessionUpdated,
   onTouchEvent)`; ukko omana Filament-renderablena (VertexBuffer: POSITION FLOAT3 + TANGENTS FLOAT4 kvaternio,
   päivitetään joka ruutu), materiaalit `materialLoader.createColorInstance(...)`, varjo
   (`ShadowReceiverPlaneNode`). Lähdekoodi: lataa `arsceneview-4.66.0-sources.jar` Maven Centralista.
   Rajaa ABI:t (arm64-v8a, armeabi-v7a), APK on nyt 48 Mt.
7. GitHub Actions Pisaran mallilla (WilliZilliW/pisara: `apk.yml` rullaava "testi"-prerelease, `julkaisu.yml`
   AAB tagista, salaisuudet TIKKU_UPLOAD_KEYSTORE_BASE64, TIKKU_KEYSTORE_PASSWORD, TIKKU_KEY_ALIAS,
   TIKKU_KEY_PASSWORD; build.gradle.kts lukee TIKKU_KEYSTORE_FILE jne.) + `pages.yml` selainversiolle.
   Gradle-ominaisuudet: `-PtikkuBuild=<ajo>` ja `-PtikkuVersion=1.2.3`. Pages pitää kytkeä päälle asetuksista.
8. README.md suomeksi Pisaran tyyliin. Ehdota pilviympäristön asennusskriptiksi yllä oleva SDK-asennus.

Kehityshaara: `ccr-abe38453-zavzha`.
