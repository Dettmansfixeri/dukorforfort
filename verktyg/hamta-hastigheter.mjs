// Laddar ner alla hastighetsgränser i Sverige från Trafikverkets öppna API (NVDB)
// och mäter hur stor en datafil för appen skulle bli.
//
// Kör:  node verktyg/hamta-hastigheter.mjs
// Du blir ombedd att skriva in din API-nyckel. Den sparas ingenstans.
// På GitHub körs skriptet av .github/workflows/hastighetsdata.yml med nyckeln
// i miljövariabeln TRAFIKVERKET_API_KEY (från repots secrets).
//
// Resultat i mappen data/:
//   hastigheter.ndjson     en rad per vägsträcka: {"v":70,"p":[x0,y0,dx1,dy1,...]}
//   hastigheter.ndjson.gz  samma fil komprimerad (ungefär så stor den blir i appen)

import { createInterface } from "node:readline/promises";
import {
  mkdirSync, createWriteStream, statSync, createReadStream,
  readFileSync, writeFileSync, appendFileSync,
} from "node:fs";
import { createHash } from "node:crypto";
import { createGzip } from "node:zlib";
import { pipeline } from "node:stream/promises";
import { stdin, stdout } from "node:process";

const ENDPOINT = "https://api.trafikinfo.trafikverket.se/v2/data.json";
const BATCH = 5000;
const OUT_DIR = new URL("../data/", import.meta.url);

// På GitHub kommer nyckeln från en hemlighet (secret). Lokalt frågar skriptet efter den.
let key = (process.env.TRAFIKVERKET_API_KEY ?? "").trim();
if (!key) {
  const rl = createInterface({ input: stdin, output: stdout });
  key = (await rl.question("Klistra in din Trafikverket-nyckel och tryck Enter: ")).trim();
  rl.close();
}
if (!key) { console.error("Ingen nyckel angiven."); process.exit(1); }

const esc = (s) => s.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;");

async function fetchBatch(skip) {
  const body = `<REQUEST>
  <LOGIN authenticationkey="${esc(key)}" />
  <QUERY objecttype="Hastighetsgräns" namespace="Vägdata.NVDB_DK_O" schemaversion="1.4" limit="${BATCH}" skip="${skip}" orderby="GID">
    <FILTER />
    <INCLUDE>GID</INCLUDE>
    <INCLUDE>Högsta_tillåtna_hastighet</INCLUDE>
    <INCLUDE>Fordon</INCLUDE>
    <INCLUDE>Geometry.WKT-SWEREF99TM-3D</INCLUDE>
  </QUERY>
</REQUEST>`;
  for (let attempt = 1; ; attempt++) {
    try {
      const res = await fetch(ENDPOINT, {
        method: "POST",
        headers: { "Content-Type": "application/xml; charset=utf-8" },
        body,
      });
      const json = await res.json();
      const result = json?.RESPONSE?.RESULT?.[0];
      if (result?.ERROR) throw new Error("Trafikverket: " + result.ERROR.MESSAGE);
      if (!result) throw new Error(`Oväntat svar (HTTP ${res.status})`);
      return result["Hastighetsgräns"] ?? [];
    } catch (e) {
      if (attempt >= 4 || String(e.message).startsWith("Trafikverket")) throw e;
      console.log(`  försök ${attempt} misslyckades (${e.message}), försöker igen…`);
      await new Promise((r) => setTimeout(r, 2000 * attempt));
    }
  }
}

// "LINESTRING Z (x y z, x y z, ...)" -> [x0, y0, dx1, dy1, ...] i hela meter
function encodeLine(wkt) {
  const inner = wkt?.match(/\(([^()]*)\)/)?.[1];
  if (!inner) return null;
  const out = [];
  let px = 0, py = 0, first = true;
  for (const pt of inner.split(",")) {
    const [xs, ys] = pt.trim().split(/\s+/);
    const x = Math.round(+xs), y = Math.round(+ys);
    if (first) { out.push(x, y); first = false; }
    else if (x !== px || y !== py) out.push(x - px, y - py);
    else continue;
    px = x; py = y;
  }
  return out;
}

mkdirSync(OUT_DIR, { recursive: true });
const ndjsonPath = new URL("hastigheter.ndjson", OUT_DIR);
const out = createWriteStream(ndjsonPath);

let total = 0, kept = 0, vehicleSpecific = 0, skip = 0;
const perLimit = {};
const started = Date.now();

while (true) {
  const items = await fetchBatch(skip);
  for (const it of items) {
    total++;
    const v = parseInt(String(it["Högsta_tillåtna_hastighet"] ?? "").trim(), 10);
    if (!Number.isFinite(v)) continue;
    if (String(it.Fordon ?? "").trim() !== "") { vehicleSpecific++; continue; }
    const p = encodeLine(it.Geometry?.["WKT-SWEREF99TM-3D"]);
    if (!p) continue;
    out.write(JSON.stringify({ v, p }) + "\n");
    kept++;
    perLimit[v] = (perLimit[v] ?? 0) + 1;
  }
  skip += items.length;
  const sek = ((Date.now() - started) / 1000).toFixed(0);
  console.log(`Hämtat ${skip} sträckor (${sek} s)`);
  if (items.length < BATCH) break;
}
await new Promise((r) => out.end(r));

const gzPath = new URL("hastigheter.ndjson.gz", OUT_DIR);
await pipeline(createReadStream(ndjsonPath), createGzip({ level: 9 }), createWriteStream(gzPath));

// version.json: det appen kollar för att se om det finns nyare data.
// Versionen är UTC-tid som ÅÅÅÅMMDD-TTMM, så den går att jämföra som text.
const now = new Date();
const pad = (n) => String(n).padStart(2, "0");
const version = `${now.getUTCFullYear()}${pad(now.getUTCMonth() + 1)}${pad(now.getUTCDate())}` +
  `-${pad(now.getUTCHours())}${pad(now.getUTCMinutes())}`;
const gzBytes = readFileSync(gzPath);
const versionInfo = {
  version,
  fil: "hastigheter.ndjson.gz",
  storlek: gzBytes.length,
  sha256: createHash("sha256").update(gzBytes).digest("hex"),
  strackor: kept,
};
writeFileSync(new URL("version.json", OUT_DIR), JSON.stringify(versionInfo, null, 2) + "\n");

const mb = (p) => (statSync(p).size / 1024 / 1024).toFixed(1) + " MB";
const rows = [
  ["Sträckor totalt", total],
  ["Använda (gäller alla fordon)", kept],
  ["Bara för vissa fordon", vehicleSpecific],
  ["Okomprimerad fil", mb(ndjsonPath)],
  ["Komprimerad fil (.gz)", mb(gzPath)],
];
console.log("\n===== RESULTAT =====");
for (const [k, v] of rows) console.log(`${(k + ":").padEnd(30)}${v}`);
console.log("Fördelning per hastighet:", perLimit);

// Sammanfattning som release-text på GitHub (och i Actions-översikten).
const md = [
  `Hastighetsgränser från Trafikverket (NVDB), hämtade ${now.toISOString().slice(0, 16).replace("T", " ")} UTC.`,
  "",
  "| | |", "|---|---|",
  ...rows.map(([k, v]) => `| ${k} | ${v} |`),
  "",
  "Fördelning per hastighet: " + Object.entries(perLimit).map(([k, v]) => `${k}: ${v}`).join(", "),
  "",
  "Data: Trafikverket, CC0.",
].join("\n");
writeFileSync(new URL("sammanfattning.md", OUT_DIR), md + "\n");
if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, md + "\n");
