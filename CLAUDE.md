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
  `maailma/LattianHaku.kt` – AR-lattia ilman pelaajan toimia (omistajan palaute 22.11.2026: lattiaa ei saa
  joutua napauttamaan, ukkojen pitää vain alkaa ilmestyä): ensimmäinen ylöspäin osoittava pinta reilusti
  puhelimen alla on lattia, selvästi alempi korvaa sen, ja 2,5 s:n jälkeen lattia arvataan 1,25 m puhelimen
  alle, kunnes oikea löytyy. Selain syöttää sille hit-testin, Android käyttää arvausta kunnes ARCore löytää tason.
- **Ympäristön tunnistus** (omistajan toive 22.11.2026: pelin pitää tunnistaa pintoja ja paikkoja aktiivisesti):
  `maailma/Tunnistin.kt` kokoaa yksittäisistä mittauksista (piste + pinnan suunta) tasoja: pöydät, seinät
  (jatketaan lattiaan asti), katot. Selaimen AR ampuu joka ruudulla 9 hit-test-sädettä näkymän läpi ja käyttää
  lisäksi selaimen omia tasoja (`plane-detection`, jos laite tarjoaa); Android syöttää tunnistimelle
  syvyys- ja piirrepisteosumat 3×3-ruudukosta ARCoren omien tasojen lisäksi (`ArMaailma.mittaa`, DepthMode
  AUTOMATIC). `Paikat.etsi` päättelee pinnoista paikat: NURKKA (kaksi seinää kohtaa), KOLO (lattia 0,5–1,3 m
  korkean tason alla), KORKEA (yli 0,8 m tason päällä) ja VARJO. Varjoisat kohdat mitataan vain Androidissa
  kamerakuvan kirkkaudesta (`ArMaailma.varjot` → `Maailma.varjot`); selaimessa niitä ei ole. Peli käyttää
  paikkoja: erikoispallot päätyvät niihin (`paivitaAarteet`), ujot ukot ja piiloukot hakeutuvat niihin
  (`Peli.piilota`), ja AR:ssä uusista löydöistä kerrotaan pelaajalle (`ilmoitaLoydot`). Selaimen AR piirtää
  tunnistettujen pintojen ääriviivat. **Mitään tästä ei ole ajettu laitteella.**
- `ukko/Ukko.kt` – aivot ja animaatio, `ukko/Nukke.kt` – ragdoll, `Asento`, `Ulkonako` (mitat, luonne, värit).
  Ukko suosii kävelykohteita pelaajan näkökentässä (`katsojanSuunta`).
- `mesh/Verkko.kt` – kiinteän topologian verkko osittain (materiaaleittain). `mesh/UkkoVerkko.kt` – ukko:
  putkiraajat pyöristetyin nivelin, pää ellipsoidina, kasvot pään pinnalla (`kp(a, b, koho)`); osat KEHO,
  VALKOINEN, TUMMA, SUU, KIELI, VARUSTE (tyypin mukaan kilpi, laskuvarjo tai ninjan otsanauha). `PalloVerkko` – kaksivärinen pallo aaltoilevalla saumalla.
- `peli/Heitto.kt` – `Kamera`, `Pyyhkaisy` → `Heitto` (tähtäyspiste, voima, kaarre). `peli/Pallo.kt` – fysiikka.
  `peli/Peli.kt` – ukkojen tuonti, heitto (ballistinen kaari tähtäyspisteeseen + tähtäysapu), osumat
  (pallon askel vs. raajakapselit), vangitseminen ja paluu, `tapahtumat` ääniä varten.
- Ukkotyypit (`ukko/Ulkonako.kt`: `Tyyppi`, `Harvinaisuus`), kaikki issue #1:n tyypit: TAVALLINEN, JUOKSIJA
  (väistää kaiken, kunnes hidastetaan), ISO (3 osumaa: `Ukko.tolaisu`), KULTAINEN (vain kultaisella pallolla),
  KIIPEILIJA (seinillä nopea), LASKUVARJO (leijuu alas, varjo taittuu), NINJA (torjuu kaiken paitsi verkon,
  juoksee seinillä), KILPI (torjuu edestä; takaa tai pompun kautta menee), PIILO (`haamu`: vain kasvot näkyvät
  ja pallot menevät läpi, kunnes tutka paljastaa), LAUMA (kolme pientä, `ryhma`; muut karkaavat kun yksi jää
  kiinni muulla kuin monipallolla), KATTO (roikkuu katossa pää alaspäin).
- **Seinät ja katto:** `Ukko` elää omassa avaruudessaan (`oma`-asento), jossa sen pinta on aina lattia, ja
  `julkaise()` kääntää valmiin asennon maailmaan (`asento`). `ukko.lattia = pinta` vaihtaa pintaa (asento
  liukuu 0,3 s), joten kaikki mitä ukko osaa lattialla toimii seinällä ja katossa. Osuman saanut irtoaa ja
  putoaa lattialle (`maanTaso`). Kiipeilijän ja ninjan reitin (lattia → seinän juuri → seinälle → alas) ohjaa
  `Peli.ohjaaKiipeilijaa`, laumaa `Peli.ohjaaLaumoja`. Jos AR ei tunne kattoa, kattoukko roikkuu kuvitellusta
  katosta (`OLETUSKATTO` 2,1 m lattiasta). Kiipeilijä konttaa seinällä kädet ja jalat pinnassa
  (`Ukko.konttaa`, raajat eivät liu'u), ninja juoksee seinällä pystyssä. Reunalla roikkumista ei vielä ole.
- **Pöydät ja istuminen:** tavallinen ukko ilmestyy välillä pöytätasolle (`PinnanLaji.POYTA`) ja käy istumassa
  sen reunalla jalat roikkuen (`Toiminto.ISTUU`, `Ukko.istuReunalle`, `Pinta.lahinReuna`, `Peli.istuta`).
- **Pyristely:** harvinaisempi ukko voi pyristellä pallossa (`PallonTila.PYRISTELEE`, 2,2 s): pelaajan pitää
  napauttaa palloa (`Peli.napauta`), muuten ukko karkaa. Päällä vain kun `peli.pyristely = true` (alustat
  kytkevät; testeissä ja labrassa pois).
- Erikoisukot lähtevät itse pois `VIERAILU` (40 s) jälkeen, erikoisia on paikalla yksi kerrallaan.
- **Pallot** (`peli/Pallo.kt`: `PalloLaji`, `Reppu`): NORMI (loputon), VERKKO (ei voi väistää/torjua, iso
  osuma-alue), TUTKA (paljastaa piiloukot 4 m säteeltä), HIDASTUS (hidastaa 1,3 m säteeltä), MONI (hajoaa
  kolmeksi), HAKEUTUVA (ohjautuu lähimpään), KULTAINEN, POMPPU (nappaa vielä 5 pompun jälkeen), TAHMA (liimaa
  ukon 5 s). Reppuun mahtuu 5 erikoispalloa. Niitä saa huoneessa lojuvista kimaltavista `Aarre`ista osumalla
  niihin millä tahansa pallolla (useimmiten tarjolla on paikalla olevalle ukolle sopiva pallo:
  `Peli.sopivaPallo`), ja joskus kiinni jääneeltä ukolta. Parit: `Peli.vaadittuPallo` (pakolliset: ninja–verkko,
  kultainen–kultainen) ja `sopivaPallo`. `Tehoste` = pallon laukeamisen kupla, `Tapahtuma.teksti` = pelaajalle
  näytettävä ilmoitus. Piilopaikkojen päivittäistä vaihtumista ei ole.
- Pisteet = tyypin pisteet × `kerroin` (putki peräkkäisiä kiinniottoja, huti nollaa). `peli/Albumi.kt` ja
  `Reppu` tallentuvat tekstinä (selain: localStorage, Android: SharedPreferences).
- `aani/Syntikka.kt` – kaikki äänet PCM:nä (22050 Hz): formanttisiansaksa `Aantely`-kohtaisesti + tehosteet.
  **Kukaan ei ole vielä kuullut näitä**: testi tarkistaa vain, että ne ovat kelvollista ääntä.

`webApp/` (Kotlin/JS, ES-moduulit koska three.js on ESM-only): `Three.kt` (sidonnat vain käytetylle osalle),
`Piirto.kt` (`UkkoPiirto`, `PalloPiirto`, `PeliPiirto`, `Tallennus`, `Nakyma`), `PeliSivu.kt` (huone + WebXR-AR hit-testillä ja
dom-overlaylla, albumi, pallorivi, ilmoitukset), `Labra.kt` (ajaa oikeaa `Peli`ä yhdellä ukolla: tyypin ja
pallon valinta, "Heitä pallo", "Väistää"; kuvatilassa `pinta=seina|katto|poyta`), `Aanet.kt` (Web Audio). Sivut `index.html`, `labra.html`, tyylit `tyyli.css`.

`androidApp/`: `MainActivity.kt` (`PeliTila`, `PeliNakyma`: `ARSceneView` + Compose-HUD), `ArMaailma.kt`
(ARCore-tasot → `Pinta`, kamera → `Kamera`; lattia = suurin ylöspäin osoittava taso alimman lähellä),
`Piirtaja.kt` (ukot ja pallot omina Filament-renderableina suoraan `scene`en; TANGENTS-kvaterniot lasketaan
normaaleista; puskureita kierrätetään, koska Filament lukee ne myöhemmin omassa säikeessään), `Aanet.kt`
(AudioTrack-mikseri). SceneViewin lähteet: `arsceneview-4.66.0-sources.jar` ja `sceneview-4.66.0-sources.jar`
Maven Centralista (`io/github/sceneview/`).

`.github/workflows/`: `apk.yml` (testi-APK rullaavaan `testi`-esijulkaisuun), `pages.yml` (selainversio
GitHub Pagesiin), `julkaisu.yml` (AAB tagista; salaisuudet vielä lisäämättä). Oletushaara on `main`, Pages on
päällä, ja molemmat ajot menivät läpi ensimmäisestä `main`-pushista. Julkaisu: työnnä `main`iin.
**Omistajan toive (22.11.2026): julkaise aina välillä.** Kun jokin kokonaisuus on valmis, testit menevät läpi ja
kaikki kolme rakentuvat, työnnä se kehityshaaran lisäksi `main`iin kysymättä (`git push origin <haara> <haara>:main`)
ja tarkista, että ajot menivät läpi.

## Tarkistettava laitteella (ei voi pilvessä)
- Android: näkyvätkö ukot ja pallot (materiaali `createColorInstance`, TANGENTS-kvaterniot), varjo lattialla
  (`ShadowReceiverPlane`), ukkojen koko ja paikka, kosketuksen suunta (`displayOrientedPose`), äänet.
- WebXR: omistaja on käynnistänyt AR-tilan puhelimella (lattian napautus toimi; se korvattiin sittemmin
  automaattisella haulla, jota ei ole laitteella kokeiltu). Tarkistamatta: osuuko arvattu/löydetty lattia
  kohdalleen, pyyhkäisy dom-overlayn läpi, varjo (`ShadowMaterial`).
- Ympäristön tunnistus: syntyykö pöydistä, seinistä ja katosta pintoja katselemalla, osuvatko ääriviivat
  kohdalleen, löytyykö varjoisia kohtia (Android), häiritseekö DepthMode AUTOMATIC mitään.
- Heiton tuntuma (`Pyyhkaisy.lopeta`, `Peli.heita`: vauhti 3,2–7,5 m/s, `TAHTAYSAPU`, `OSUMA_APU`) ja
  väistöjen määrä (`Luonne.vaistotaito` 0,15–0,65) ovat arvauksia: säädä palautteen mukaan.

## Seuraavaksi
1. Laitepalaute yllä olevista ja korjaukset. Äänien kuuntelu ja viritys.
2. Issue #1:n "Ensimmäinen versio" -listan ruksit, kun omistaja on todennut kohdat laitteella.
3. Issue #1:n seuraavat asiat: reunalla roikkuminen, ukot piiloon esineiden taakse kurkkimaan (paikat ovat
   nyt olemassa: `Maailma.paikat`), ketjut ja
   tornit, hyppiminen pinnalta toiselle, piilopaikat jotka vaihtuvat päivittäin, hatut ja asut, ukkokylä. Albumin vihjeet ja tyyppien yleisyys (`Peli.YLEISYYS`) kaipaavat pelitestausta.
4. Depth API (piiloutuminen, osumat oikeisiin pintoihin), valon arvio selaimen AR:ään.
5. Ehdota pilviympäristön asennusskriptiksi yllä oleva SDK-asennus.
