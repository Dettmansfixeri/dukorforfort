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
const stil = (process.env.STIL || '').trim();
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

async function tala(text, rost, modell) {
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
if (FRASLISTA && modeller.length) {
  const rader = fs.readFileSync(FRASLISTA, 'utf8').split('\n').map((r) => r.trim()).filter((r) => r && !r.startsWith('#'));
  let tur = 0;
  for (const rad of rader) {
    const m = /^(\d+)\s*\|\s*(.+)$/.exec(rad);
    const antal = m ? Math.min(+m[1], roster.length) : roster.length;
    const fras = m ? m[2] : rad;
    for (let i = 0; i < antal; i++) {
      const rost = roster[tur++ % roster.length];
      for (const modell of modeller) for (let t = 1; t <= tagningar; t++) jobb.push({ fras, rost, modell, t });
    }
  }
} else {
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
    try { ljuden[i] = await tala(j.fras, j.rost, j.modell); }
    catch (e) { ljuden[i] = { fel: e.message }; }
    if (++klara % 20 === 0) console.log(`… ${klara}/${jobb.length}`);
  }
}));

let fel = 0;
let nya = 0;
for (let i = 0; i < jobb.length; i++) {
  const { fras, rost, modell, t } = jobb[i];
  const ljud = ljuden[i];
  if (!ljud || ljud.fel) { fel++; console.log('FEL', rost, modell, t, fras.slice(0, 40), ljud && ljud.fel); continue; }
  while (await finnsPaServern(nummer(bib.nasta))) bib.nasta++;
  const nr = nummer(bib.nasta);
  const fil = `ljud/${nr}.${ljud.ext}`;
  fs.writeFileSync(path.join(UT, fil), ljud.data);
  bib.nasta++; // numret är förbrukat nu, även om klippet skulle tas bort senare
  bib.klipp.push({ nr, fil, fras, rost, modell: MODELL[modell].namn, tagning: t,
    stil: MODELL[modell].gemini ? stil : '', tempo: MODELL[modell].gemini ? null : tempo, skapad: korning });
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
let matta = 0;
for (const k of bib.klipp) {
  if (typeof k.sek === 'number') continue;
  let lokal = path.join(UT, k.fil);
  if (!fs.existsSync(lokal)) {
    if (process.env.LOKALT) continue;
    const r = await fetch(SIDA + k.fil).catch(() => null);
    if (!r || !r.ok) continue;
    lokal = path.join(UT, 'tmp-' + path.basename(k.fil));
    fs.writeFileSync(lokal, Buffer.from(await r.arrayBuffer()));
  }
  const s = langdAv(lokal);
  if (lokal.includes('tmp-')) fs.rmSync(lokal);
  if (s != null) { k.sek = s; matta++; }
}
console.log(`Mätte längden på ${matta} klipp.`);

// Klipp som inte ska användas (verktyg/rostbort.txt) döljs på sidan. Filerna och numren ligger kvar.
const bortFil = new URL('rostbort.txt', import.meta.url);
const bort = new Set(fs.existsSync(bortFil) ? fs.readFileSync(bortFil, 'utf8').split('\n')
  .filter((l) => !l.startsWith('#')).join(',').split(',').map((s) => s.trim()).filter(Boolean) : []);
for (const k of bib.klipp) { if (bort.has(k.nr)) k.bort = true; else delete k.bort; }
console.log(`${bort.size} klipp markerade som borttagna.`);

fs.writeFileSync(path.join(UT, 'bibliotek.json'), JSON.stringify(bib, null, 1));
fs.copyFileSync(new URL('rostprov-sida.html', import.meta.url), path.join(UT, 'index.html'));
console.log(`Klart. ${nya} nya klipp (totalt ${bib.klipp.length}), ${fel} fel.`);
// Inga modeller valda = bara uppdatera lyssningssidan. Annars är det fel om inget klipp blev av.
if (nya === 0 && modeller.length > 0) process.exit(1);
