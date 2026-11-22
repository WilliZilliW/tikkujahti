# Tikkujahti

Android-AR-peli, jossa oikeassa huoneessa liikkuu ilmeikkäitä tikku-ukkoja. Niihin heitetään palloja
pyyhkäisemällä: osumasta ukko käpertyy pallon sisään ja pallo palaa pelaajalle. Kehittäjä Otsosoft.

Peli on tehty kokonaan koodilla Claude Coden pilviympäristössä: ukkojen malli, animaatio, ilmeet ja
äänet lasketaan ohjelmallisesti, eikä mukana ole yhtään piirrettyä kuvaa tai äänitiedostoa.
Koko suunnitelma on [issuessa #1](../../issues/1).

## Kolme tapaa pelata

| | Missä | Mitä tarvitaan |
|---|---|---|
| **Android-sovellus** | Ukot oikealla lattialla puhelimen kameran läpi | ARCorea tukeva Android-puhelin (Android 8+) |
| **Selain, AR-tila** | Sama selaimessa (WebXR) | Androidin Chrome |
| **Selain, huone** | Ukot 3D-huoneessa | Mikä tahansa laite |

Selainversiossa on lisäksi **ukkolabra** (`labra.html`), jossa yhtä ukkoa voi pyöritellä ja jokaista
liikettä ja ilmettä kokeilla napista, myös hidastettuna.

- Testi-APK: [Releases → testi](../../releases/tag/testi) (päivittyy jokaisesta `main`-haaran muutoksesta)
- Selainversio: GitHub Pages (päivittyy samoin)

## Pelaaminen

- **Heitto:** pyyhkäise pallosta ylöspäin. Pallo lentää sinne, minne pyyhkäisy osoittaa, ja nopea
  pyyhkäisy lentää suorempaan. Kaarella pyyhkäisy saa pallon kaartamaan.
- Pallo pomppii lattiasta, seinistä ja huonekaluista, joten ukkoon voi osua myös pompun kautta.
- Ukot näkevät pallon tulevan: taitavat väistävät hyppäämällä tai kyykistymällä ja tuulettavat
  tai kiusoittelevat perään. Läheltä mennyt pallo säikäyttää.
- **Ukkoja on yksitoista erilaista.** Nopea juoksija väistää kaiken, kunnes sen hidastaa. Iso ukko vaatii
  kolme osumaa. Kiipeilijä ja ninja nousevat seinille, kattoukko roikkuu katossa pää alaspäin ja
  laskuvarjo-ukko leijuu alas. Kilpi-ukkoon pitää osua takaa tai pompun kautta. Piiloukosta näkyvät vain
  silmät. Lauma-ukot kulkevat kolmistaan, ja kun yksi jää kiinni, muut karkaavat. Ninja torjuu kaiken paitsi
  verkon, ja kultaisen saa vain kultaisella pallolla. Erikoiset ukot viipyvät vain hetken.
- **Erikoispalloja** löytyy huoneesta kimaltelemasta: osu sellaiseen millä tahansa pallolla, niin saat sen
  reppuusi (enintään viisi kerrallaan). Verkkopalloa ei voi väistää, tutka paljastaa piiloukot, hidastuspallo
  hidastaa, monipallo hajoaa kolmeksi, hakeutuva kääntyy itse kohti ukkoa, pomppupallo nappaa vielä monen
  pompun jälkeen ja tahmapallo liimaa ukon paikalleen. Pallo valitaan ruudun oikeasta alakulmasta.
- Harvinainen ukko voi **pyristellä pallossa**: napauta palloa nopeasti, tai se karkaa.
- Peräkkäiset kiinniotot ilman hutia kasvattavat pistekerrointa, ja löydetyt ukot kertyvät albumiin, jossa
  löytymättömistä on vihje.
- AR:ssä ei tarvitse tehdä mitään alkuun: katsele puhelimella lattiaa, niin ukot alkavat ilmestyä. Peli
  etsii lattian itse ja tarkentaa sen paikkaa pelin aikana.
- Selaimen huoneessa: vedä ruudun yläosasta katsellaksesi ympärillesi.

## Rakenne

Kotlin Multiplatform, kolme osaa:

- `shared/` – koko peli ilman alustakoodia: ukkojen aivot ja animaatio (`ukko/`), 3D-mallin
  generointi (`mesh/`), pallot, heitto ja osumat (`peli/`), pinnat (`maailma/`), äänisyntikka (`aani/`).
- `androidApp/` – ARCore + SceneView (Filament), Compose.
- `webApp/` – Kotlin/JS + three.js: 3D-huone, WebXR-AR ja ukkolabra.

## Rakentaminen

Tarvitaan JDK 21 ja Android SDK (platform 37, build-tools 37.0.0); `local.properties`-tiedostoon
`sdk.dir=<polku>`.

```bash
./gradlew :shared:jvmTest                 # pelilogiikan testit
./gradlew :androidApp:assembleDebug       # APK: androidApp/build/outputs/apk/debug/
./gradlew :webApp:jsBrowserDistribution   # selainversio: webApp/build/dist/js/productionExecutable/
node tools/kuvat.mjs                      # kuvat ukon jokaisesta liikkeestä kansioon kuvat/ (vaatii Playwrightin)
```

Selainversiota voi kokeilla paikallisesti avaamalla rakennetun kansion millä tahansa
staattisella palvelimella, esim. `npx serve webApp/build/dist/js/productionExecutable`.
AR-tila vaatii HTTPS:n (GitHub Pages käy).

## Julkaisu (GitHub Actions)

- `apk.yml`: jokainen `main`-haaran push ajaa testit, rakentaa testi-APK:n ja julkaisee sen rullaavana
  `testi`-esijulkaisuna. Testiversiot allekirjoitetaan repossa olevalla testiavaimella
  (`androidApp/testiavain.keystore`, salasana `android`), joten uusi versio asentuu aina vanhan päälle.
- `julkaisu.yml`: tagi `v1.2.3` rakentaa allekirjoitetun AAB:n Google Playta varten. Tarvitsee
  salaisuudet `TIKKU_UPLOAD_KEYSTORE_BASE64`, `TIKKU_KEYSTORE_PASSWORD`, `TIKKU_KEY_ALIAS` ja
  `TIKKU_KEY_PASSWORD`.
- `pages.yml`: selainversio GitHub Pagesiin. Pages pitää kytkeä kerran päälle:
  Settings → Pages → Source: **GitHub Actions**.
