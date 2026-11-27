<?php
// Tar emot val från lyssningssidan (knappen "Skicka val") och lämnar ut dem till Röstprov.
//   POST {kod, text}  sparar en ny valfil. Första gången (när oppen.txt finns) bestäms kodordet (bara ett hash sparas).
//   GET               alla sparade valfiler som JSON [{namn, text}], äldst först.
// Filerna ligger utanför webbroten. Glömt kodord: ta bort filen kod.txt i mappen nedan.
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
date_default_timezone_set('Europe/Stockholm');

$mapp = dirname(__DIR__, 2) . '/rostval';
if (!is_dir($mapp)) mkdir($mapp, 0700, true);
$kodfil = "$mapp/kod.txt";

function svar($kod, $data) { http_response_code($kod); echo json_encode($data, JSON_UNESCAPED_UNICODE); exit; }

if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    $ut = [];
    $filer = glob("$mapp/*.txt");
    sort($filer);
    foreach ($filer as $f) {
        if (basename($f) === 'kod.txt' || basename($f) === 'oppen.txt') continue;
        $ut[] = ['namn' => basename($f), 'text' => file_get_contents($f)];
    }
    svar(200, $ut);
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') svar(405, ['fel' => 'Bara GET och POST']);

$in = json_decode(file_get_contents('php://input'), true);
$kod = is_array($in) ? trim((string)($in['kod'] ?? '')) : '';
$text = is_array($in) ? (string)($in['text'] ?? '') : '';
if (strlen($kod) < 4) svar(400, ['fel' => 'Kodordet måste vara minst 4 tecken']);
if ($text === '' || strlen($text) > 100000) svar(400, ['fel' => 'Inga val']);

// Ett nytt kodord tas bara emot när filen oppen.txt finns i mappen (läggs dit för hand just när
// kodordet ska väljas). Den tas bort så fort kodordet är satt, så att ingen annan hinner före.
if (!file_exists($kodfil)) {
    if (!file_exists("$mapp/oppen.txt")) svar(423, ['fel' => 'Inget kodord är valt än. Servern är låst.']);
    file_put_contents($kodfil, password_hash($kod, PASSWORD_DEFAULT), LOCK_EX);
    chmod($kodfil, 0600);
    unlink("$mapp/oppen.txt");
} elseif (!password_verify($kod, trim(file_get_contents($kodfil)))) {
    sleep(2);
    svar(403, ['fel' => 'Fel kodord']);
}

// Bara raderna Röstprov läser, med siffror och kommatecken.
$rader = ["# Val från lyssningssidan " . date('Y-m-d H:i:s')];
foreach (preg_split('/\r?\n/', $text) as $rad) {
    if (preg_match('/^(godkända|gör om|ta bort):([\d,\s]*)$/u', trim($rad), $m)) $rader[] = "$m[1]:$m[2]";
}
if (count($rader) < 2) svar(400, ['fel' => 'Inga val']);

$namn = date('Y-m-d-His') . '-' . bin2hex(random_bytes(2)) . '.txt';
file_put_contents("$mapp/$namn", implode("\n", $rader) . "\n", LOCK_EX);
svar(200, ['ok' => true, 'namn' => $namn]);
