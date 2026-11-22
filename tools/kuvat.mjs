// Screenshots of the browser version in headless Chromium, for checking how the figures look.
//
//   ./gradlew :webApp:jsBrowserDistribution
//   node tools/kuvat.mjs                      every action in the lab at a few moments -> kuvat/labra-*.png
//   node tools/kuvat.mjs VILKUTTAA 0.4 0.9    one action at the given times
//   ETAISYYS=0.2 KORKEUS=0.2 node tools/kuvat.mjs KIUSOITTELEE 1   a close-up of the face
//   node tools/kuvat.mjs --sivu 'index.html?kuva=1&t=3'   any page that sets window.kuvaValmis
//
// Each lab picture is a contact sheet: one row per action, one column per moment.
import { createServer } from 'node:http'
import { readFile, mkdir } from 'node:fs/promises'
import { extname, join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const { chromium } = require('playwright')

const juuri = join(dirname(fileURLToPath(import.meta.url)), '..')
const dist = join(juuri, 'webApp/build/dist/js/productionExecutable')
const ulos = join(juuri, 'kuvat')
const tyypit = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.map': 'application/json' }

const palvelin = createServer(async (req, res) => {
  try {
    const polku = decodeURIComponent(new URL(req.url, 'http://x').pathname)
    const data = await readFile(join(dist, polku === '/' ? 'index.html' : polku))
    res.writeHead(200, { 'content-type': tyypit[extname(polku)] ?? 'application/octet-stream' })
    res.end(data)
  } catch {
    res.writeHead(404).end()
  }
})
await new Promise(r => palvelin.listen(0, '127.0.0.1', r))
const osoite = `http://127.0.0.1:${palvelin.address().port}/`
await mkdir(ulos, { recursive: true })

const selain = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] })

async function kuva(sivu, url, tiedosto, clip) {
  await sivu.goto(osoite + url)
  await sivu.waitForFunction('window.kuvaValmis === true', null, { timeout: 60000 })
  await sivu.screenshot({ path: tiedosto, clip })
}

const args = process.argv.slice(2)
if (args[0] === '--sivu') {
  const sivu = await selain.newPage({ viewport: { width: 420, height: 800 }, deviceScaleFactor: 1 })
  sivu.on('console', m => console.log('selain:', m.text()))
  sivu.on('pageerror', e => console.log('virhe:', e.message))
  await kuva(sivu, args[1], join(ulos, args[2] ?? 'sivu.png'))
  console.log(join(ulos, args[2] ?? 'sivu.png'))
} else {
  const kaikki = {
    SAAPUU: [0.2, 0.5, 0.9, 1.5], SEISOO: [0.5, 1.5, 3, 5], KAVELEE: [0.3, 0.6, 0.9, 1.2], JUOKSEE: [0.2, 0.4, 0.6, 0.8],
    KATSELEE: [0.5, 1.2, 1.9, 2.5], VILKUTTAA: [0.3, 0.7, 1.1, 1.8], RAAPII_PAATA: [0.4, 0.9, 1.4, 2.0],
    HAUKOTTELEE: [0.5, 1.1, 1.7, 2.6], TUULETTAA: [0.25, 0.6, 1.0, 1.5], SAIKAHTAA: [0.1, 0.3, 0.6, 1.0],
    SUUTTUU: [0.3, 0.7, 1.1, 1.5], KIUSOITTELEE: [0.4, 1.0, 1.6, 2.3], VAISTAA_HYPPY: [0.1, 0.25, 0.45, 0.8],
    VAISTAA_KYYKKY: [0.1, 0.3, 0.6, 0.85], OSUMA: [0.1, 0.3, 0.6, 0.9],
  }
  const valitut = args.length ? { [args[0]]: args.slice(1).map(Number) } : kaikki
  const w = 300, h = 340
  const sivu = await selain.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1 })
  sivu.on('pageerror', e => console.log('virhe:', e.message))
  const rivit = Object.entries(valitut)
  const ruudut = []
  for (const [toiminto, ajat] of rivit) {
    for (const t of ajat) {
      const tiedosto = join(ulos, `_${toiminto}-${t}.png`)
      await kuva(sivu, `labra.html?kuva=1&toiminto=${toiminto}&t=${t}&kulma=22&etaisyys=${process.env.ETAISYYS ?? 0.5}&korkeus=${process.env.KORKEUS ?? 0.125}`, tiedosto)
      ruudut.push({ toiminto, t, data: (await readFile(tiedosto)).toString('base64') })
    }
  }
  // Contact sheets: a few rows per picture so that each stays readable
  const riveja = 4
  for (let i = 0; i < rivit.length; i += riveja) {
    const osa = rivit.slice(i, i + riveja)
    const sarakkeita = Math.max(...osa.map(([, a]) => a.length))
    const html = `<body style="margin:0;background:#fff;font:12px sans-serif">` + osa.map(([toiminto]) =>
      `<div style="display:flex">` + ruudut.filter(r => r.toiminto === toiminto).map(r =>
        `<div style="position:relative"><img src="data:image/png;base64,${r.data}" width="${w}" height="${h}">` +
        `<span style="position:absolute;left:4px;top:2px">${r.toiminto} ${r.t}s</span></div>`).join('') + `</div>`).join('')
    const arkki = await selain.newPage({ viewport: { width: w * sarakkeita, height: h * osa.length }, deviceScaleFactor: 1 })
    await arkki.setContent(html)
    const nimi = join(ulos, `labra-${osa[0][0]}.png`)
    await arkki.screenshot({ path: nimi })
    await arkki.close()
    console.log(nimi)
  }
}
await selain.close()
palvelin.close()
