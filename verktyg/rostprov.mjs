// Skapar röstprov med Google Cloud Text-to-Speech och en lyssningssida (ut/index.html).
// Körs av .github/workflows/rostprov.yml. Nyckeln kommer från miljövariabeln GOOGLE_TTS_KEY.
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
  let url, nyckel, body;
  if (m.gemini) {
    // Gemini via Agent Platform (Vertex AI) med nyckel bunden till tjänstekontot "rostprov".
    if (!GEMINI_KEY) throw new Error('GOOGLE_GEMINI_KEY saknas');
    url = `https://aiplatform.googleapis.com/v1/publishers/google/models/${m.gemini}:generateContent`;
    nyckel = GEMINI_KEY;
    body = {
      contents: [{ role: 'user', parts: [{ text: stil ? `${stil}\n\n${text}` : text }] }],
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

const esc = (s) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

let fel = 0;
const sektioner = [];
for (const [fi, fras] of fraser.entries()) {
  let rader = '';
  for (const rost of roster) {
    let celler = '';
    for (const modell of modeller) {
      for (let t = 1; t <= tagningar; t++) {
        let fil = `ljud/f${fi + 1}-${rost}-${modell}-${t}`;
        try {
          const ljud = await tala(fras, rost, modell);
          fil += `.${ljud.ext}`;
          fs.writeFileSync(path.join(UT, fil), ljud.data);
          celler += `<td><audio controls preload="none" src="${fil}"></audio></td>`;
          console.log('ok ', fil);
        } catch (e) {
          fel++;
          celler += `<td class="fel" title="${esc(e.message)}">fel</td>`;
          console.log('FEL', fil, e.message);
        }
      }
    }
    rader += `<tr><th>${esc(rost)}</th>${celler}</tr>\n`;
  }
  const rubriker = modeller
    .flatMap((m) => Array.from({ length: tagningar }, (_, i) => `<th>${MODELL[m].namn}${tagningar > 1 ? ` #${i + 1}` : ''}</th>`))
    .join('');
  sektioner.push(`<h2>${esc(fras)}</h2>\n<div class="tab"><table><tr><th>Röst</th>${rubriker}</tr>\n${rader}</table></div>`);
}

const html = `<!doctype html>
<html lang="sv"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>Röstprov</title>
<style>
body{background:#111;color:#eee;font-family:system-ui,sans-serif;margin:0;padding:16px;max-width:1400px}
h1{font-size:1.4em}h2{font-size:1.05em;margin-top:2em;color:#ffcc00}
.info{color:#aaa;font-size:.9em}
.tab{overflow-x:auto}table{border-collapse:collapse}
th,td{padding:4px 8px;border-bottom:1px solid #333;text-align:left;white-space:nowrap}
audio{height:32px;width:220px}.fel{color:#f66}
</style></head><body>
<h1>Röstprov</h1>
<p class="info">Skapat ${new Date().toISOString().slice(0, 16).replace('T', ' ')} UTC.
Stil (Gemini): ${stil ? esc(stil) : '–'}. Tempo (Chirp): ${tempo}.</p>
${sektioner.join('\n')}
</body></html>`;
fs.writeFileSync(path.join(UT, 'index.html'), html);
console.log(`Klart. ${fel} fel.`);
if (fel > 0 && fel === fraser.length * roster.length * modeller.length * tagningar) process.exit(1);
