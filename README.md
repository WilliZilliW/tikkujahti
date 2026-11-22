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
- Ukkoja on erilaisia: nopea juoksija ei pysy paikallaan, iso ukko vaatii kolme osumaa ja harvinainen
  kultainen väistää melkein kaiken. Peräkkäiset kiinniotot ilman hutia kasvattavat pistekerrointa, ja
  löydetyt ukot kertyvät albumiin.
- Sovelluksessa: liikuttele puhelinta hitaasti ja osoita lattiaa, niin ukot ilmestyvät, kun lattia on
  tunnistettu. Selaimen AR-tilassa napauta lattiaa, kun rengas näkyy.
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
