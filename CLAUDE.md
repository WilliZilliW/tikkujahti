# Tikkujahti – ohjeet jatkajalle

Android-AR-peli (Otsosoft): oikeassa huoneessa liikkuu ilmeikkäitä tikku-ukkoja, joihin heitetään palloja
pyyhkäisemällä. Koko suunnitelma ja päätökset: issue #1. Peli tehdään kokonaan Claude Coden pilviympäristössä,
joten kaikki grafiikka ja äänet tehdään koodilla. Kommentit koodissa englanniksi, nimet suomeksi.

## Pelin tarkoitus (omistajan sanoin 22.11.2026)
Kuljet puhelin kädessä ympäri taloa etsimässä ukkoja ja erilaisia palloja ja nappaat jokaista eri ukkoa
mahdollisimman monta. Peli on siis etsintäpeli: ukkojen ja pallojen pitää olla löydettävissä eri puolilta,
ei tarjoiltuna suoraan eteen. Alussa ei saa joutua tekemään mitään (ei lattian napautusta), ja pelin pitää
tunnistaa ympäristöä aktiivisesti. Arvioi uudet ominaisuudet tätä vasten.

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
  alle, kunnes oikea löytyy. Molemmat alustat syöttävät sille syvyyskuvassa nähdyn lattian (`Tunnistin.syvyys`),
  selain lisäksi keskisäteen hit-testin; Android käyttää ARCoren lattiatasoa heti kun sellainen on.
- **Ympäristön tunnistus** (omistajan toive 22.11.2026: pelin pitää tunnistaa pintoja ja paikkoja aktiivisesti).
  **Kirjoitettu uusiksi 23.11.2026**, koska vanha (9 säteen osumaviuhka, pisteet pysyvästi maailman
  koordinaateissa, kupera kuori kaikista pisteistä) sekoili laitteella. Syyt: selaimen hit-test osuu oletuksena
  vain selaimen jo tuntemiin tasoihin (viuhka ei löytänyt mitään uutta), seurannan korjautuessa ARCoren omat
  tasot siirtyivät mutta meidän pisteemme eivät (tuplat ja haamut), eikä mikään unohtunut.
  Nyt `maailma/Tunnistin.kt` oppii huoneen **syvyyskuvista**: alusta antaa ruudukon maailmapisteitä
  (`syvyys(aika, kamera, eteen, pisteet, leveys, korkeus)`), tunnistin laskee pisteiden normaalit naapureista,
  etsii yhdestä kuvasta tasot histogrammiäänestyksellä (vaakapinnat korkeuden mukaan; seinät ensin suunnan,
  sitten etäisyyden mukaan, ja seinän suunta sovitetaan lopuksi pisteiden sijainneista PCA:lla) ja yhdistää ne
  tunnettuihin. Tunnetut pinnat muistavat 10 cm:n ruudut, joissa ne on nähty (ruutu kelpaa 2–3 eri kuvan
  jälkeen; yhtenäiset ruuturyhmät ovat omia pintojaan, joten kaksi pöytää pysyy kahtena), ja elävät
  `Ankkuri`n suhteen (ARCore `createAnchor`, WebXR `createAnchor`), joten ne siirtyvät seurannan korjauksen
  mukana. Ruutu, jonka läpi nähdään (mitattu pinta on selvästi tason takana), pyyhkiytyy pois. Rinnakkaiset
  koskettavat pinnat alle 12 cm:n päässä yhdistetään (`YHDISTYS`). Kun seiniä on nähty tarpeeksi, huoneen
  suunta opitaan ja alle 8° vinot seinähavainnot suoristetaan siihen (`SUORA`). Mittaus hylätään, jos puhelin
  kääntyy yli 1,2 rad/s tai liikkuu yli 0,9 m/s tai kameran paikka hyppää (0,75 s tauko). Katon korkeus
  (`kattoY`) menee `Maailma.kattoY`:hin, ja kattoukko käyttää sitä, kun kohdalla ei ole tunnistettua kattoa.
  `syvyys` palauttaa näkemänsä lattian korkeuden `LattianHaku`un.
  **Simulaattoritesti** `TunnistinTest`: puhelin kävelee mallihuoneessa (seinät, pöytä, kaappi, katto) ja
  syvyyskuvat lasketaan säteillä kohinan (1,2 % + 4 mm), aukkojen (seinät 45 %), harhamittausten ja seurannan
  ryöminnän kanssa, joka 40 s kohdalla korjautuu hypyllä; ankkureilla ja ilman. Tarkistaa lattian, katon,
  pöydät, jokaisen seinän kerran ja ettei haamuja synny. Kesti myös kaksinkertaisen kohinan 15 kävelyllä.
  Android (`ArMaailma.syvyys`): ARCoren raakasyvyys + luotettavuus (alle 100/255 pois; jos raakasyvyyttä ei
  ole, tasoitettu syvyys), 48 saraketta, enintään ~8 kuvaa/s, purku Raw Depth -codelabin kaavoilla
  sensoriasennolla `camera.pose`. ARCoren omat ylöspäiset tasot luokitellaan lattian korkeuden mukaan (toinen
  lattiapala on lattiaa, ei pöytä). Selain (`PeliSivu.arSyvyys`): `depth-sensing` (cpu-optimized) ja
  `anchors` valinnaisina, 40 × 30 ruudukko joka kolmas ruutu; hit-testistä on jäljellä yksi keskisäde
  lattian hakuun. Jos selain ei anna syvyyttä, tunnistus nojaa selaimen omiin tasoihin (`plane-detection`).
  `Paikat.etsi` (nyt `maailma/Paikat.kt`) päättelee pinnoista paikat: NURKKA (kaksi seinää kohtaa), KOLO
  (lattia 0,5–1,3 m korkean tason alla), KORKEA (yli 0,8 m tason päällä) ja VARJO (vain Androidissa
  kamerakuvan kirkkaudesta, `ArMaailma.varjot`). Peli käyttää paikkoja: erikoispallot päätyvät niihin
  (`paivitaAarteet`), ujot ukot ja piiloukot hakeutuvat niihin (`Peli.piilota`), ja AR:ssä uusista löydöistä
  kerrotaan (`ilmoitaLoydot`). Selaimen AR piirtää tunnistettujen pintojen ääriviivat.
  **Uutta tunnistusta ei ole vielä ajettu laitteella.**
- **Ukot eivät näy seinien läpi** (omistajan vaatimus 23.11.2026), **mutta eivät myöskään piiloudu turhaan**
  (laitepalaute 23.11.2026: ensimmäinen versio piilotti ukkoja ilman estettä). Pikselitarkka syvyyspeitto
  (SceneViewin `isDepthOcclusionEnabled`, selaimen oma varjostin) ja peittolevyt poistettiin: niissä ei ollut
  varaa syvyyden kohinalle, ja selain luki syvyyskuvan luultavasti väärin päin (spesifikaatio mittaa
  näkymäkoordinaatit ylhäältä, Chromen oma esimerkki syöttää ne alhaalta). Nyt `Peli.paivitaPeitot` päättää
  koko ukosta kerrallaan (`Ukko.peitossa`, piirtäjät jättävät piirtämättä): pään, rinnan, lantion ja polvien
  kohdalta katsotaan `Peli.syvyyskartta` (`maailma/Syvyyskartta`) ja tunnetut seinät (ukon oma seinä ei
  peitä). Piste on peitossa, jos syvyys mitattiin yli `PEITTOVARA` 0,2 m + 6 % etäisyydestä sen edestä tai
  seinä on välissä (vähintään 8 cm). Ukko katoaa, kun ≥ 74 % mitatuista pisteistä on peitossa 0,25 s, ja palaa,
  kun ≤ 50 % on 0,15 s. Android: `ArMaailma.kartta` (tasoitettu syvyys, 3×3-mediaani, ei yli 1 s vanhaa).
  Selain: `web/XrSyvyys` kopioi CPU-syvyyskuvan ja **oppii sen suunnan** vertaamalla lattiaa katsoessa
  syvyyttä osumatestin lattiaan molemmin päin luettuna; ennen kuin suunta on selvä (6 ääntä), syvyyttä ei
  käytetä peittoon eikä tunnistukseen. Kuvatilan koe `index.html?kuva=1&t=9&siemen=9&syvyyskoe=1` (tai `=2`,
  alhaalta ylös) kirjaa konsoliin SYVYYSKOE-rivit: suunta opittiin oikein molemmilla, pelkkä lattia piilotti
  0/2 ukkoa, metrin päässä oleva seinä 2/2. Ukot eivät valitse kävelykohdetta tunnetun seinän takaa
  (`Ukko.esteeton`, `Peli.seinaton`).
- **Android: lattian päälle ilmestyi umpinainen levy** (laitepalaute 23.11.2026, turkoosi ja sitten kultainen:
  ukkojen värejä). Syy: ukon poistuessa sen värimateriaali tuhottiin, Filament antoi kahvan uudelleen, ja
  SceneViewin `ShadowReceiverPlane` piirtyi toisen ukon värillä. Nyt ukkojen materiaalit jaetaan värin mukaan
  eikä niitä tuhota pelin aikana (`Piirtaja.kehoAineet`; **älä tuhoa MaterialInstanceja kesken pelin**),
  `ShadowReceiverPlane` on poistettu, ja jokaisella ukolla on oma läikkävarjo pinnallaan (`Piirtaja.asetaVarjo`,
  kolme läpikuultavaa kiekkoa). Kamerakuva piirretään ensin (`isOccluderMeshEnabled = true`).
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
- **Etsintäpeli** (`Peli.etsinta`, päällä AR:ssä selaimessa ja sovelluksessa, 4 ukkoa kerralla): ukot
  ilmestyvät pelaajan ympärille 1,2–4 m päähän, useimmiten näkymän ulkopuolelle ja mielellään tunnistettuihin
  paikkoihin, ei tunnetun seinän taakse (`paikkaEtsittava`). Jokaisella on `Ukko.koti`, jonka lähellä se
  pysyy. Yli `JATTOMATKA` (6,5 m) taakse jääneet ukot ja pallot poistuvat ja uusia tulee sinne, missä pelaaja
  on. Lattia on 7 m säteinen kiekko pelaajan mukana (`LattianHaku.SADE`). Kun näkymän ulkopuolella oleva ukko
  ääntelee tai pallo kimaltaa, ruudun reunaan välähtää nuoli sen suuntaan (`Kamera.ruudulla`). Selaimen
  huoneessa etsintä on pois: ukot tulevat pelaajan eteen.
- **Tasot** (`peli/Taso.kt`, `Peli.tasot`, päällä selaimessa ja sovelluksessa; omistajan toive: vaikeustaso
  määrää, miten ukot on piilotettu): taso läpäistään nappaamalla `tavoite` ukkoa (5, 7, 9 … enintään 15).
  Tason mukaan kasvavat: kuinka usein ukko ilmestyy näkymän ulkopuolelle (10 % → 90 %) ja piilopaikkaan,
  kuinka kauas, erikoisten osuus, väistötaito ja ukkojen määrä; ja pienenevät reunanuolien todennäköisyys
  (100 % → 25 %, `Tapahtuma.nuoli`) ja erikoisten viipymä. Lajit avautuvat tasoittain (`Taso.AVAUTUU`: taso 1
  vain tavallinen … taso 8 kultainen). Taso ja edistyminen tallentuvat albumin mukana (`Albumi.taso`).
  Kuvatilassa `tyyppi=`-parametri kytkee tasot pois.
- Erikoisukot lähtevät itse pois `VIERAILU` (40 s) jälkeen, erikoisia on paikalla yksi kerrallaan.
- Ninja ilmestyy ninjamaisesti (omistajan toive): varjosta, nurkasta tai kolosta savupilven kera
  (`Peli.ninjanPaikka`, `savu`), muuten pudottautuu katosta tai ylhäältä. Lähtiessään se katoaa savuna
  (`Ukko.katoa`) eikä kävele pois.
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
  (oma läikkävarjo), ukkojen koko ja paikka, kosketuksen suunta (`displayOrientedPose`), äänet.
- WebXR: omistaja on käynnistänyt AR-tilan puhelimella (lattian napautus toimi; se korvattiin sittemmin
  automaattisella haulla, jota ei ole laitteella kokeiltu). Tarkistamatta: osuuko arvattu/löydetty lattia
  kohdalleen, pyyhkäisy dom-overlayn läpi, varjo (`ShadowMaterial`).
- Etsintäpeli: tuntuuko etsiminen hyvältä talossa (matkat 1,2–4 m, 6,5 m, 4 ukkoa), ilmestyykö ukkoja seinien
  läpi viereiseen huoneeseen (tunnistamattoman seinän taakse voi ilmestyä), auttavatko reunanuolet sopivasti.
- Piiloutuminen: katoavatko ukot seinien takana, eivätkä katoa avoimella lattialla; oppiiko selain syvyyskuvan
  suunnan (vaatii lattiaa katsomista); onko `PEITTOVARA` sopiva (liian iso: sohvan taakse ei piiloudu).
- Ympäristön tunnistus (uusi, syvyyskuvista): syntyykö pöydistä, seinistä ja katosta pintoja katselemalla,
  osuvatko ääriviivat kohdalleen, pysyvätkö ne paikallaan kävellessä. Androidissa: antaako puhelin raakasyvyyden
  `DepthMode.AUTOMATIC`-tilassa (muuten käytetään tasoitettua), riittääkö nopeus (`ArMaailma.syvyys` ~8/s).
  Selaimessa: antaako Chrome `depth-sensing`-syvyyden (cpu-optimized) ja ankkurit; jos ei, tunnistus jää
  selaimen omiin tasoihin. Kynnysarvot (`Tunnistin`-companion) ovat simulaattorissa viritettyjä.
- Heiton tuntuma (`Pyyhkaisy.lopeta`, `Peli.heita`: vauhti 3,2–7,5 m/s, `TAHTAYSAPU`, `OSUMA_APU`) ja
  väistöjen määrä (`Luonne.vaistotaito` 0,15–0,65) ovat arvauksia: säädä palautteen mukaan.

## Seuraavaksi
1. Laitepalaute yllä olevista ja korjaukset. Äänien kuuntelu ja viritys.
2. Issue #1:n "Ensimmäinen versio" -listan ruksit, kun omistaja on todennut kohdat laitteella.
3. Issue #1:n seuraavat asiat: reunalla roikkuminen, ukot piiloon esineiden taakse kurkkimaan (paikat ovat
   nyt olemassa: `Maailma.paikat`), ketjut ja
   tornit, hyppiminen pinnalta toiselle, piilopaikat jotka vaihtuvat päivittäin, hatut ja asut, ukkokylä. Albumin vihjeet ja tyyppien yleisyys (`Peli.YLEISYYS`) kaipaavat pelitestausta.
4. Depth API piiloutumiseen (okkluusio) ja pallon osumiin oikeisiin pintoihin, valon arvio selaimen AR:ään.
5. Ehdota pilviympäristön asennusskriptiksi yllä oleva SDK-asennus.
