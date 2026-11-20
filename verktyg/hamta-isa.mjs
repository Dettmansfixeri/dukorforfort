// Hämtar senaste "ISA hastighet" (GeoPackage) från Trafikverkets Lastkajen och packar upp den.
//
// Kör:  node verktyg/hamta-isa.mjs
// Inloggning tas från miljövariablerna LASTKAJEN_USER och LASTKAJEN_PASSWORD
// (på GitHub: repots secrets). Lokalt frågar skriptet om de saknas.
//
// Resultat: isa-test/ISA.gpkg  och  isa-test/kalla.txt (namnet på zipfilen)
//
// API-beskrivning: Trafikverket, "Lastkajen API" v1.4 (2023-01-24).

import { createInterface } from "node:readline/promises";
import { stdin, stdout } from "node:process";
import { mkdirSync, createWriteStream, writeFileSync, statSync } from "node:fs";
import { pipeline } from "node:stream/promises";
import { Readable } from "node:stream";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const BASE = "https://lastkajen.trafikverket.se/api";
const PAKET_ID = 5052; // "Datapaket för ISA hastighet/ISA hastighet"
const OUT_DIR = fileURLToPath(new URL("../isa-test/", import.meta.url));

async function ask(q) {
  const rl = createInterface({ input: stdin, output: stdout });
  const a = (await rl.question(q)).trim();
  rl.close();
  return a;
}
const user = (process.env.LASTKAJEN_USER ?? "").trim() || await ask("Lastkajen användarnamn: ");
const pass = process.env.LASTKAJEN_PASSWORD ?? await ask("Lastkajen lösenord: ");
if (!user || !pass) { console.error("Användarnamn och lösenord krävs."); process.exit(1); }

// ---------- 1. Logga in ----------
// Dokumentationen säger bara "parametrar UserName och Password", så vi provar JSON först
// och formulärformat som reserv.
async function login() {
  const attempts = [
    { headers: { "Content-Type": "application/json" }, body: JSON.stringify({ UserName: user, Password: pass }) },
    { headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ UserName: user, Password: pass }).toString() },
  ];
  let last = "";
  for (const a of attempts) {
    const res = await fetch(`${BASE}/Identity/Login`, { method: "POST", ...a });
    const text = await res.text();
    if (res.ok) {
      try {
        const token = JSON.parse(text).access_token;
        if (token) return token;
      } catch { /* inte JSON */ }
    }
    last = `HTTP ${res.status}`; // skriv aldrig ut svaret, det kan innehålla känsligt
  }
  throw new Error(`Inloggningen till Lastkajen misslyckades (${last}). Kontrollera användarnamn och lösenord.`);
}

async function getJson(path, token) {
  const res = await fetch(`${BASE}${path}`, { headers: { Authorization: `Bearer ${token}` } });
  if (!res.ok) throw new Error(`${path}: HTTP ${res.status}`);
  return res.json();
}

const token = await login();
console.log("Inloggad på Lastkajen.");

// ---------- 2. Hitta senaste filen i paketet ----------
const files = await getJson(`/DataPackage/GetDataPackageFiles?id=${PAKET_ID}`, token);
const zips = files
  .filter((f) => !f.isFolder && /isa.*gpkg.*\.zip$/i.test(f.name))
  .sort((a, b) => String(b.dateTime).localeCompare(String(a.dateTime)));
if (!zips.length) throw new Error("Hittade ingen ISA-zip i paketet. Filer: " + files.map((f) => f.name).join(", "));
const latest = zips[0];
console.log(`Senaste fil: ${latest.name} (${latest.size}, ${latest.dateTime})`);

// ---------- 3. Engångstoken (gäller 60 s) och nedladdning ----------
const dlRes = await fetch(
  `${BASE}/file/GetDataPackageDownloadToken?id=${PAKET_ID}&fileName=${encodeURIComponent(latest.name)}`,
  { headers: { Authorization: `Bearer ${token}` } }
);
if (!dlRes.ok) throw new Error(`GetDataPackageDownloadToken: HTTP ${dlRes.status}`);
const dlToken = String(await dlRes.json());

mkdirSync(OUT_DIR, { recursive: true });
const zipPath = OUT_DIR + latest.name;
const fileRes = await fetch(`${BASE}/File/GetDataPackageFile?token=${encodeURIComponent(dlToken)}`);
if (!fileRes.ok || !fileRes.body) throw new Error(`GetDataPackageFile: HTTP ${fileRes.status}`);
console.log("Laddar ner…");
await pipeline(Readable.fromWeb(fileRes.body), createWriteStream(zipPath));
console.log(`Nedladdad: ${(statSync(zipPath).size / 1048576).toFixed(1)} MB`);

// ---------- 4. Packa upp ----------
if (process.platform === "win32") execFileSync("tar", ["-xf", zipPath, "-C", OUT_DIR], { stdio: "inherit" });
else execFileSync("unzip", ["-o", "-q", zipPath, "-d", OUT_DIR], { stdio: "inherit" });
writeFileSync(OUT_DIR + "kalla.txt", latest.name);
console.log(`Uppackad till ${OUT_DIR}ISA.gpkg`);
