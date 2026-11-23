// Skapar röstprov med Googles röster och lägger dem i röstbiblioteket (ut/):
//   ut/ljud/NNN.mp3     klippen, med löpnummer som aldrig återanvänds
//   ut/bibliotek.json   alla klipp från alla körningar (BIBLIOTEK = förra versionen, hämtas från webbsidan)
//   ut/index.html       lyssningssidan (verktyg/rostprov-sida.html) med kryssrutor
// Körs av .github/workflows/rostprov.yml. LOKALT=1 gör påhittade pip i stället för att fråga Google.
//
// Miljövariabler (från workflow-knappen):
//   ROSTER     kommaseparerade röstnamn, t.ex. "Zephyr,Charon"
//   MODELLER   kommaseparerade: chirp, gemini-flash, gemini-pro
//   FRASER     fraser åtskilda med |
//   STIL       stilinstruktion (bara Gemini)
//   TAGNINGAR  antal versioner per klipp
//   TEMPO      talhastighet, 1 = normal (bara Chirp)
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

// GOOGLE_TTS_KEY: Chirp (Cloud Text-to-Speech). GOOGLE_GEMINI_KEY: Gemini (Agent Platform, bunden till tjänstekonto).
const KEY = process.env.GOOGLE_TTS_KEY;
const GEMINI_KEY = process.env.GOOGLE_GEMINI_KEY;

const lista = (s) => (s || '').split(/[,|]/).map((x) => x.trim()).filter(Boolean);
const roster = lista(process.env.ROSTER);
const modeller = lista(process.env.MODELLER);
const fraser = (process.env.FRASER || '').split('|').map((x) => x.trim()).filter(Boolean);
const STIL_STANDARD = (process.env.STIL || '').trim();
const stil = STIL_STANDARD;
const tagningar = Math.max(1, Math.min(5, parseInt(process.env.TAGNINGAR || '1', 10) || 1));
const tempo = Math.max(0.5, Math.min(2, parseFloat(process.env.TEMPO || '1') || 1));

const MODELL = {
  chirp: { namn: 'Chirp 3 HD', gemini: null },
  'gemini-flash': { namn: 'Gemini 2.5 Flash', gemini: 'gemini-2.5-flash-tts' },
  'gemini-pro': { namn: 'Gemini 2.5 Pro', gemini: 'gemini-2.5-pro-tts' },
};

const UT = 'ut';
fs.rmSync(UT, { recursive: true, force: true });
fs.mkdirSync(path.join(UT, 'ljud'), { recursive: true });

// Biblioteket: alla klipp från alla körningar, med löpnummer som aldrig återanvänds.
// Workflowet hämtar det från webbsidan innan. Saknas det där börjar vi från de 96 första klippen
// (2026-11-21, nr 001–096, filerna ligger redan på servern) i rostprov-gamla.json.
const BIBLIOTEK = process.env.BIBLIOTEK || '';
const SIDA = 'https://dukorforfort.dettmansfixeri.se/rostprov/';
const kalla = BIBLIOTEK && fs.existsSync(BIBLIOTEK) ? BIBLIOTEK : new URL('rostprov-gamla.json', import.meta.url);
const bib = JSON.parse(fs.readFileSync(kalla, 'utf8'));
if (!Number.isInteger(bib.nasta) || !Array.isArray(bib.klipp)) { console.error('bibliotek.json är trasig'); process.exit(1); }
const nummer = (n) => String(n).padStart(3, '0');
// Extra skydd: ett nummer som redan har en fil på servern hoppas över (t.ex. om förra körningen
// laddade upp klippen men inte hann spara biblioteket). Så återanvänds ett nummer aldrig.
async function finnsPaServern(nr) {
  if (process.env.LOKALT) return false;
  for (const ext of ['mp3', 'wav']) {
    const r = await fetch(`${SIDA}ljud/${nr}.${ext}`, { method: 'HEAD' }).catch(() => null);
    if (r && r.ok) return true;
  }
  return false;
}
const korning = new Date().toISOString().slice(0, 16).replace('T', ' ');

const vila = (ms) => new Promise((r) => setTimeout(r, ms));

// Rå PCM (16 bit mono) -> MP3 med ffmpeg, eller WAV om ffmpeg saknas.
function pcmTillLjud(pcm, rate) {
  const r = spawnSync('ffmpeg', ['-loglevel', 'error', '-f', 's16le', '-ar', String(rate), '-ac', '1', '-i', 'pipe:0', '-b:a', '96k', '-f', 'mp3', 'pipe:1'], { input: pcm, maxBuffer: 64 << 20 });
  if (r.status === 0 && r.stdout?.length) return { data: r.stdout, ext: 'mp3' };
  const h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + pcm.length, 4); h.write('WAVE', 8); h.write('fmt ', 12);
  h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22); h.writeUInt32LE(rate, 24);
  h.writeUInt32LE(rate * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34); h.write('data', 36); h.writeUInt32LE(pcm.length, 40);
  return { data: Buffer.concat([h, pcm]), ext: 'wav' };
}

async function tala(text, rost, modell, stil = STIL_STANDARD) {
  const m = MODELL[modell];
  if (process.env.LOKALT) {
    const pcm = Buffer.alloc(24000);
    for (let i = 0; i < 12000; i++) pcm.writeInt16LE(Math.round(8000 * Math.sin(i * (0.05 + roster.indexOf(rost) * 0.01))), i * 2);
    return pcmTillLjud(pcm, 24000);
  }
  let url, nyckel, body;
  if (m.gemini) {
    // Gemini via Agent Platform (Vertex AI) med nyckel bunden till tjänstekontot "rostprov".
    if (!GEMINI_KEY) throw new Error('GOOGLE_GEMINI_KEY saknas');
    url = `https://aiplatform.googleapis.com/v1/publishers/google/models/${m.gemini}:generateContent`;
    nyckel = GEMINI_KEY;
    body = {
      // Stilen skrivs som en instruktion på engelska och texten inom citattecken, annars läser
      // rösten ibland upp stilen ("Det här kostar dig TORR irriterad …").
      contents: [{ role: 'user', parts: [{ text: stil
        ? `Read the following Swedish text aloud. Speaking style (do NOT read this part aloud): ${stil}\nSay only this, exactly as written:\n"${text}"`
        : text }] }],
      generationConfig: {
        responseModalities: ['AUDIO'],
        speechConfig: { languageCode: 'sv-SE', voiceConfig: { prebuiltVoiceConfig: { voiceName: rost } } },
      },
    };
  } else {
    if (!KEY) throw new Error('GOOGLE_TTS_KEY saknas');
    url = 'https://texttospeech.googleapis.com/v1/text:synthesize';
    nyckel = KEY;
    body = {
      input: { text },
      voice: { languageCode: 'sv-SE', name: `sv-SE-Chirp3-HD-${rost}` },
      audioConfig: { audioEncoding: 'MP3', speakingRate: tempo },
    };
  }
  for (let forsok = 1; ; forsok++) {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Goog-Api-Key': nyckel },
      body: JSON.stringify(body),
    });
    if (res.ok) {
      const j = await res.json();
      if (!m.gemini) return { data: Buffer.from(j.audioContent, 'base64'), ext: 'mp3' };
      const del = j.candidates?.[0]?.content?.parts?.find((p) => p.inlineData);
      if (!del) throw new Error('inget ljud i svaret: ' + JSON.stringify(j).slice(0, 300));
      const rate = +(/rate=(\d+)/.exec(del.inlineData.mimeType || '')?.[1] || 24000);
      return pcmTillLjud(Buffer.from(del.inlineData.data, 'base64'), rate);
    }
    const fel = await res.text();
    if ((res.status === 429 || res.status >= 500) && forsok < 6) {
      await vila(5000 * forsok);
      continue;
    }
    throw new Error(`${res.status}: ${fel.slice(0, 300)}`);
  }
}

// Vad som ska spelas in: { fras, rost, modell, t }.
// FRASLISTA = fil med "antal röster | fras" per rad: varje fras får så många röster, fördelade
// jämnt i tur och ordning så att alla röster får lika många. Annars: varje fras × varje röst.
const jobb = [];
const FRASLISTA = (process.env.FRASLISTA || '').trim();
// GOR_OM = nummer på klipp som ska spelas in igen (samma fras, röst och modell). Det gamla klippet
// märks som ersatt och döljs på sidan.
const GOR_OM = (process.env.GOR_OM || '').split(/[\s,]+/).map((s) => s.trim()).filter(Boolean).map((s) => s.padStart(3, '0'));
if (GOR_OM.length) {
  for (const nr of GOR_OM) {
    const k = bib.klipp.find((x) => x.nr === nr);
    if (!k) { console.log('Finns inte:', nr); continue; }
    const modell = Object.keys(MODELL).find((m) => MODELL[m].namn === k.modell) || 'gemini-pro';
    jobb.push({ fras: k.fras, rost: k.rost, modell, t: (k.tagning || 1) + 1, grupp: k.grupp || '', ersatter: nr, stil: k.stil || STIL_STANDARD });
  }
}
if (FRASLISTA && modeller.length) {
  const rader = fs.readFileSync(FRASLISTA, 'utf8').split('\n').map((r) => r.trim()).filter((r) => r && (!r.startsWith('#') || r.startsWith('##')));
  let tur = 0;
  let grupp = '';
  let gruppStil = STIL_STANDARD;
  // GRUPPER = bara de här grupperna ur listan (tomt = alla), t.ex. "Skylten, 30-väg".
  const bara = (process.env.GRUPPER || '').split(',').map((g) => g.trim().toLowerCase()).filter(Boolean);
  for (const rad of rader) {
    if (rad.startsWith('##')) {
      const [namn, egen] = rad.replace(/^#+\s*/, '').split(' @ ');
      grupp = namn.trim();
      gruppStil = egen ? egen.trim() : STIL_STANDARD;
      continue;
    }
    // 'Snäll' tar med alla Snäll-grupper ('Snäll tätort' osv.).
    const gl = grupp.toLowerCase();
    if (bara.length && !bara.some((b) => gl === b || gl.startsWith(b + ' '))) continue;
    const m = /^(\d+)\s*\|\s*(.+)$/.exec(rad);
    // Fler än antalet röster = flera tagningar per röst (t.ex. 16 med 8 röster = 2 var).
    const antal = m ? Math.min(+m[1], roster.length * 5) : roster.length;
    const fras = m ? m[2] : rad;
    for (let i = 0; i < antal; i++) {
      const rost = roster[tur++ % roster.length];
      const varv = Math.floor(i / roster.length) * tagningar;
      for (const modell of modeller) for (let t = 1; t <= tagningar; t++) jobb.push({ fras, rost, modell, t: varv + t, grupp, stil: gruppStil });
    }
  }
} else if (!GOR_OM.length) {
  for (const fras of fraser) for (const rost of roster) for (const modell of modeller)
    for (let t = 1; t <= tagningar; t++) jobb.push({ fras, rost, modell, t });
}
console.log(`${jobb.length} klipp att spela in.`);

// Spela in några åt gången (snabbare), numrera sedan i listans ordning.
const PARALLELLT = Math.max(1, parseInt(process.env.PARALLELLT || '3', 10) || 1);
const ljuden = new Array(jobb.length);
let nasta = 0;
let klara = 0;
await Promise.all(Array.from({ length: PARALLELLT }, async () => {
  while (nasta < jobb.length) {
    const i = nasta++;
    const j = jobb[i];
    try { ljuden[i] = await tala(j.fras, j.rost, j.modell, j.stil ?? STIL_STANDARD); }
    catch (e) { ljuden[i] = { fel: e.message }; }
    if (++klara % 20 === 0) console.log(`… ${klara}/${jobb.length}`);
  }
}));

let fel = 0;
let nya = 0;
for (let i = 0; i < jobb.length; i++) {
  const { fras, rost, modell, t, grupp, ersatter, stil } = jobb[i];
  const ljud = ljuden[i];
  if (!ljud || ljud.fel) { fel++; console.log('FEL', rost, modell, t, fras.slice(0, 40), ljud && ljud.fel); continue; }
  while (await finnsPaServern(nummer(bib.nasta))) bib.nasta++;
  const nr = nummer(bib.nasta);
  const fil = `ljud/${nr}.${ljud.ext}`;
  fs.writeFileSync(path.join(UT, fil), ljud.data);
  bib.nasta++; // numret är förbrukat nu, även om klippet skulle tas bort senare
  bib.klipp.push({ nr, fil, fras, rost, modell: MODELL[modell].namn, tagning: t,
    stil: MODELL[modell].gemini ? stil : '', tempo: MODELL[modell].gemini ? null : tempo, skapad: korning,
    ...(grupp ? { grupp } : {}) });
  if (ersatter) { const gammal = bib.klipp.find((x) => x.nr === ersatter); if (gammal) gammal.ersatt = nr; }
  nya++;
  console.log('ok ', nr, rost, modell, t);
}

// Längd (sekunder) på varje klipp, så lyssningssidan kan sortera och dölja för långa direkt.
// Nya klipp mäts här; äldre utan längd hämtas från webbsidan och mäts (bara en gång).
function langdAv(fil) {
  const r = spawnSync('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', fil], { encoding: 'utf8' });
  const s = parseFloat(r.stdout);
  return r.status === 0 && isFinite(s) ? Math.round(s * 100) / 100 : null;
}
// Hur starkt klippet slutar (dB, starkaste ljudet de sista 50 ms). Normalt tonar rösten ut till
// −40 dB eller tystare; högre än −30 dB betyder att klippet troligen huggits av mitt i ett ord.
function slutDb(fil) {
  const r = spawnSync('ffmpeg', ['-v', 'error', '-i', fil, '-f', 's16le', '-ac', '1', '-ar', '24000', 'pipe:1'], { maxBuffer: 64 << 20 });
  if (r.status !== 0 || !r.stdout?.length) return null;
  const pcm = r.stdout;
  const n = pcm.length >> 1;
  let m = 0;
  for (let i = Math.max(0, n - 1200); i < n; i++) m = Math.max(m, Math.abs(pcm.readInt16LE(i * 2)));
  return Math.round(20 * Math.log10(m / 32768 + 1e-9));
}
let matta = 0;
for (const k of bib.klipp) {
  if (typeof k.sek === 'number' && typeof k.slut === 'number') continue;
  let lokal = path.join(UT, k.fil);
  if (!fs.existsSync(lokal)) {
    if (process.env.LOKALT) continue;
    const r = await fetch(SIDA + k.fil).catch(() => null);
    if (!r || !r.ok) continue;
    lokal = path.join(UT, 'tmp-' + path.basename(k.fil));
    fs.writeFileSync(lokal, Buffer.from(await r.arrayBuffer()));
  }
  const s = langdAv(lokal);
  const db = slutDb(lokal);
  if (lokal.includes('tmp-')) fs.rmSync(lokal);
  if (s != null) k.sek = s;
  if (db != null) k.slut = db;
  if (s != null || db != null) matta++;
}
console.log(`Mätte längden på ${matta} klipp.`);

// Klipp som inte ska användas (verktyg/rostbort.txt) döljs på sidan. Filerna och numren ligger kvar.
const bortFil = new URL('rostbort.txt', import.meta.url);
const bort = new Set(fs.existsSync(bortFil) ? fs.readFileSync(bortFil, 'utf8').split('\n')
  .filter((l) => !l.startsWith('#')).join(',').split(',').map((s) => s.trim()).filter(Boolean) : []);
for (const k of bib.klipp) { if (bort.has(k.nr)) k.bort = true; else delete k.bort; }
console.log(`${bort.size} klipp markerade som borttagna.`);

// Grupp (rubrik på sidan) efter nuvarande fraslista, ifall en fras flyttat till en annan väg.
const listFil = new URL('fraser-nya.txt', import.meta.url);
if (fs.existsSync(listFil)) {
  const gruppFor = new Map();
  let g = '';
  for (const rad of fs.readFileSync(listFil, 'utf8').split('\n').map((r) => r.trim())) {
    if (rad.startsWith('##')) g = rad.replace(/^#+\s*/, '').split(' @ ')[0].trim();
    else if (/^\d+\s*\|/.test(rad)) gruppFor.set(rad.replace(/^\d+\s*\|\s*/, ''), g);
  }
  for (const k of bib.klipp) if (gruppFor.has(k.fras)) k.grupp = gruppFor.get(k.fras);
}
// Äldre klipp utan grupp: böter, CYKLA (körkortsgränsen), övriga är fasta kommentarer.
for (const k of bib.klipp) {
  if (k.fras.includes('kronor i böter')) k.grupp = 'Böter';
  else if (k.fras.includes('DÅ DU MÅSTE CYKLA')) k.grupp = 'Körkort';
  else if (k.fras === 'Tack. Var det så svårt?') k.grupp = 'Tack';
  else if (!k.grupp) k.grupp = 'Fasta kommentarer';
}

// Godkända klipp (verktyg/rostval.txt) är redan klara – sidan visar dem bara om man vill.
const valFil = new URL('rostval.txt', import.meta.url);
const godkanda = new Set(fs.existsSync(valFil) ? fs.readFileSync(valFil, 'utf8').split('\n')
  .filter((l) => !l.startsWith('#')).join(',').split(',').map((s) => s.trim()).filter(Boolean) : []);
for (const k of bib.klipp) { if (godkanda.has(k.nr)) k.godkand = true; else delete k.godkand; }
console.log(`${godkanda.size} klipp godkända.`);

// Klipp som ska göras om (verktyg/rostgorom.txt) döljs också – de ersätts av nya.
const goromFil = new URL('rostgorom.txt', import.meta.url);
const gorom = new Set(fs.existsSync(goromFil) ? fs.readFileSync(goromFil, 'utf8').split('\n')
  .filter((l) => !l.startsWith('#')).join(',').split(',').map((x) => x.trim()).filter(Boolean) : []);
for (const k of bib.klipp) { if (gorom.has(k.nr)) k.gorom = true; else delete k.gorom; }

fs.writeFileSync(path.join(UT, 'bibliotek.json'), JSON.stringify(bib, null, 1));

// Jämn volym: alla klipp i paketet får samma upplevda ljudstyrka (EBU R128, -16 LUFS, topp högst -1,5 dB).
// Två varv: först mäts klippet, sedan justeras det med en fast förstärkning (linear) så att rösten inte pumpar.
const VOLYM = 'I=-16:TP=-1.5:LRA=11';
function volymFilter(fil) {
  const m = spawnSync('ffmpeg', ['-hide_banner', '-nostats', '-i', fil, '-af', `loudnorm=${VOLYM}:print_format=json`, '-f', 'null', '-'],
    { maxBuffer: 16 << 20 });
  const json = String(m.stderr).match(/{[^{}]*"input_i"[^{}]*}/);
  if (json) {
    try {
      const v = JSON.parse(json[0]);
      if (Number.isFinite(+v.input_i) && Number.isFinite(+v.input_tp)) {
        return `loudnorm=${VOLYM}:measured_I=${v.input_i}:measured_TP=${v.input_tp}:measured_LRA=${v.input_lra}` +
          `:measured_thresh=${v.input_thresh}:offset=${v.target_offset}:linear=true`;
      }
    } catch { /* mätningen gick inte att läsa, kör ett varv */ }
  }
  return `loudnorm=${VOLYM}`;
}

// Röstpaket till appen: de godkända klippen som Opus 32 kbit/s (WebM, spelas av Android 5+)
// i en zip med manifest.json. Appen laddar ner rostpaket.zip när rostpaket.json säger ny version.
if (!process.env.LOKALT) {
  const PAKET = path.join(UT, 'paket');
  fs.mkdirSync(PAKET, { recursive: true });
  const manifest = { version: new Date().toISOString(), klipp: [] };
  // Fraser som appen inte säger längre följer inte med i paketet (klippen ligger kvar i biblioteket).
  const UTGATT = [/^Där du hade stannat i /];   // ersatt 2026-11-23 av "Behöver du tvärnita nu …"
  for (const k of bib.klipp.filter((x) => x.godkand && !x.ersatt && !UTGATT.some((r) => r.test(x.fras)))) {
    let kalla = path.join(UT, k.fil);
    let tmp = false;
    if (!fs.existsSync(kalla)) {
      const r = await fetch(SIDA + k.fil).catch(() => null);
      if (!r || !r.ok) { console.log('Röstpaket: kunde inte hämta', k.nr); continue; }
      kalla = path.join(UT, 'tmp-' + path.basename(k.fil));
      fs.writeFileSync(kalla, Buffer.from(await r.arrayBuffer()));
      tmp = true;
    }
    const fil = `${k.nr}.webm`;
    const r = spawnSync('ffmpeg', ['-v', 'error', '-y', '-i', kalla, '-af', volymFilter(kalla), '-ar', '48000', '-ac', '1',
      '-c:a', 'libopus', '-b:a', '32k', '-application', 'voip', '-f', 'webm', path.join(PAKET, fil)]);
    if (tmp) fs.rmSync(kalla);
    if (r.status !== 0) { console.log('Röstpaket: ffmpeg misslyckades', k.nr, String(r.stderr).slice(0, 200)); continue; }
    manifest.klipp.push({ nr: k.nr, fil, fras: k.fras, rost: k.rost, grupp: k.grupp || '' });
  }
  fs.writeFileSync(path.join(PAKET, 'manifest.json'), JSON.stringify(manifest));
  fs.rmSync(path.join(UT, 'rostpaket.zip'), { force: true });
  const z = spawnSync('zip', ['-q', '-r', '../rostpaket.zip', '.'], { cwd: PAKET });
  if (z.status === 0) {
    const size = fs.statSync(path.join(UT, 'rostpaket.zip')).size;
    fs.writeFileSync(path.join(UT, 'rostpaket.json'), JSON.stringify({ version: manifest.version, size, antal: manifest.klipp.length }));
    console.log(`Röstpaket: ${manifest.klipp.length} klipp, ${Math.round(size / 1024)} kB.`);
  } else console.log('Röstpaket: zip misslyckades');
  fs.rmSync(PAKET, { recursive: true, force: true });
}
fs.copyFileSync(new URL('rostprov-sida.html', import.meta.url), path.join(UT, 'index.html'));
fs.copyFileSync(new URL('rostprov-godkanda.html', import.meta.url), path.join(UT, 'godkanda.html'));
console.log(`Klart. ${nya} nya klipp (totalt ${bib.klipp.length}), ${fel} fel.`);
// Inga modeller valda = bara uppdatera lyssningssidan. Annars är det fel om inget klipp blev av.
if (nya === 0 && modeller.length > 0) process.exit(1);
