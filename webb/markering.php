<?php
// Tar emot markeringar och skyltar.csv från appens testsida (knappen "Skicka till servern").
//   POST {kod, namn, text}  sparar filen. Samma kodord som "Skicka val" på lyssningssidan.
// Filerna ligger utanför webbroten och går inte att hämta härifrån (de innehåller positioner).
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
date_default_timezone_set('Europe/Stockholm');

$bas = dirname(__DIR__);
$mapp = "$bas/markeringar";
$kodmapp = "$bas/rostval";
$kodfil = "$kodmapp/kod.txt";

function svar($kod, $data) { http_response_code($kod); echo json_encode($data, JSON_UNESCAPED_UNICODE); exit; }

if ($_SERVER['REQUEST_METHOD'] !== 'POST') svar(405, ['fel' => 'Bara POST']);

$in = json_decode(file_get_contents('php://input'), true);
$kod = is_array($in) ? trim((string)($in['kod'] ?? '')) : '';
$namn = is_array($in) ? (string)($in['namn'] ?? '') : '';
$text = is_array($in) ? (string)($in['text'] ?? '') : '';
if (strlen($kod) < 4) svar(400, ['fel' => 'Kodordet måste vara minst 4 tecken']);
// Bara appens egna filnamn: 2026-11-25_135100.txt eller skyltar.csv.
if (!preg_match('/^(\d{4}-\d{2}-\d{2}_\d{6}\.txt|skyltar\.csv)$/', $namn)) svar(400, ['fel' => 'Fel filnamn']);
if ($text === '' || strlen($text) > 2000000) svar(400, ['fel' => 'Tom eller för stor fil']);

if (!is_dir($kodmapp)) mkdir($kodmapp, 0700, true);
if (!file_exists($kodfil)) {
    file_put_contents($kodfil, password_hash($kod, PASSWORD_DEFAULT), LOCK_EX);
    chmod($kodfil, 0600);
} elseif (!password_verify($kod, trim(file_get_contents($kodfil)))) {
    sleep(2);
    svar(403, ['fel' => 'Fel kodord']);
}

if (!is_dir($mapp)) mkdir($mapp, 0700, true);
// Svara ok bara om hela filen blev skriven (t.ex. inte vid fullt diskutrymme).
if (file_put_contents("$mapp/$namn", $text, LOCK_EX) !== strlen($text)) svar(500, ['fel' => 'Kunde inte spara filen']);
chmod("$mapp/$namn", 0600);
svar(200, ['ok' => true, 'namn' => $namn]);
