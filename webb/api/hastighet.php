<?php
/*
 * Proxy mellan appen "Du kör för fort, IDIOT!" och Trafikverkets öppna API.
 * Appen skickar en position i SWEREF 99 TM (POST e=..., n=...).
 * Servern lägger till API-nyckeln, frågar Trafikverket och svarar med
 *   {"limit": 70}       hastighetsgräns hittad
 *   {"limit": null}     ingen gräns inom 30 m
 *   {"error": "..."}    något gick fel
 * Positioner loggas inte.
 */

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

function svara($data, $kod = 200) {
    http_response_code($kod);
    echo json_encode($data, JSON_UNESCAPED_UNICODE);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    svara(['error' => 'Använd POST'], 405);
}

$config = require __DIR__ . '/config.php';
$nyckel = trim($config['trafikverket_api_key'] ?? '');
if ($nyckel === '' || strpos($nyckel, 'KLISTRA') !== false) {
    svara(['error' => 'Servern saknar API-nyckel'], 500);
}

$e = filter_input(INPUT_POST, 'e', FILTER_VALIDATE_FLOAT);
$n = filter_input(INPUT_POST, 'n', FILTER_VALIDATE_FLOAT);
// Grov kontroll att punkten ligger i Sverige (SWEREF 99 TM)
if ($e === false || $e === null || $n === false || $n === null
    || $e < 180000 || $e > 1000000 || $n < 6100000 || $n > 7700000) {
    svara(['error' => 'Ogiltig position'], 400);
}

$punkt = sprintf('%.1f %.1f', $e, $n);
$nyckelXml = htmlspecialchars($nyckel, ENT_XML1 | ENT_QUOTES, 'UTF-8');

$fraga = <<<XML
<REQUEST>
  <LOGIN authenticationkey="$nyckelXml" />
  <QUERY objecttype="Hastighetsgräns" namespace="Vägdata.NVDB_DK_O" schemaversion="1.4" limit="5">
    <FILTER>
      <NEAR name="Geometry.WKT-SWEREF99TM-3D" value="$punkt" mindistance="0" maxdistance="30" />
    </FILTER>
    <INCLUDE>Högsta_tillåtna_hastighet</INCLUDE>
    <INCLUDE>Fordon</INCLUDE>
  </QUERY>
</REQUEST>
XML;

$ctx = stream_context_create(['http' => [
    'method'        => 'POST',
    'header'        => "Content-Type: application/xml; charset=utf-8\r\nAccept: application/json\r\n",
    'content'       => $fraga,
    'timeout'       => 8,
    'ignore_errors' => true,
]]);
$svar = @file_get_contents('https://api.trafikinfo.trafikverket.se/v2/data.json', false, $ctx);
if ($svar === false) {
    svara(['error' => 'Kunde inte nå Trafikverket'], 502);
}

$json = json_decode($svar, true);
$resultat = $json['RESPONSE']['RESULT'][0] ?? null;
if ($resultat === null) {
    svara(['error' => 'Oväntat svar från Trafikverket'], 502);
}
if (isset($resultat['ERROR'])) {
    svara(['error' => 'Trafikverket: ' . ($resultat['ERROR']['MESSAGE'] ?? 'okänt fel')], 502);
}

// NEAR ger närmaste först. Hoppa över gränser som bara gäller vissa fordon.
$reserv = null;
foreach ($resultat['Hastighetsgräns'] ?? [] as $g) {
    $kmh = filter_var(trim((string)($g['Högsta_tillåtna_hastighet'] ?? '')), FILTER_VALIDATE_INT);
    if ($kmh === false) continue;
    if (trim((string)($g['Fordon'] ?? '')) === '') svara(['limit' => $kmh]);
    if ($reserv === null) $reserv = $kmh;
}
svara(['limit' => $reserv]);
