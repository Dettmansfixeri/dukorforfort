package se.dettmansfixeri.dukorforfort

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

/** Gömd testsida – öppnas med fem snabba tryck på sista O:et i IDIOT. */
@Composable
fun TestScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onClose)

    val state by SpeedTracker.state.collectAsStateWithLifecycle()
    val settings by SpeedTracker.settings.collectAsStateWithLifecycle()
    val debug by SpeedTracker.debug.collectAsStateWithLifecycle()
    val activity by AutoStart.activity.collectAsStateWithLifecycle()
    val car by AutoStart.carConnected.collectAsStateWithLifecycle()
    val registered by AutoStart.registered.collectAsStateWithLifecycle()
    val dataState by DataManager.state.collectAsStateWithLifecycle()

    var simulate by remember { mutableStateOf(false) }
    var signed by remember { mutableIntStateOf(70) }
    var speed by remember { mutableFloatStateOf(60f) }

    LaunchedEffect(simulate, signed, speed.toInt(), settings) {
        if (simulate) SpeedTracker.simulate(ctx, signed, speed.toInt()) else SpeedTracker.endSimulation()
    }
    DisposableEffect(Unit) { onDispose { SpeedTracker.endSimulation() } }

    val bg = warningBackground(simulate && state.tooFast)
    val dim = Color.White.copy(alpha = 0.65f)

    Box(Modifier.fillMaxSize().background(bg).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Testsida", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White,
                    modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onClose) { Text("Stäng", color = Color.White) }
            }

            // ---------- Simulera körning ----------
            Section("Simulera körning")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Låtsas köra (vibrerar och piper på riktigt)", Modifier.weight(1f), color = Color.White)
                Switch(checked = simulate, onCheckedChange = { simulate = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(30, 50, 70, 90, 110).forEach { v ->
                    FilterChip(selected = signed == v, onClick = { signed = v }, label = { Text("$v") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                SpeedSign(if (simulate) state.limitKmh else signed, false, 90.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("${speed.toInt()} km/h", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Color.White)
                    val limit = if (simulate) state.limitKmh ?: signed else signed
                    val over = speed.toInt() - (limit + settings.margin)
                    Text(Warner.describe(over, Beeper.licenseOver(limit) - settings.margin, settings.sound == SoundMode.TONE), color = Color.White)
                    val röst by VoicePack.lastPlay.collectAsStateWithLifecycle()
                    Text("Röst: " + (röst ?: "inget spelat än"), fontSize = 13.sp, color = dim)
                    if (simulate && state.reason != null) Text("Gräns $limit för dig (${state.reason})", fontSize = 13.sp, color = dim)
                }
            }
            Slider(value = speed, onValueChange = { speed = it }, valueRange = 0f..150f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { speed = (speed - 1).coerceAtLeast(0f) }) { Text("−1", color = Color.White) }
                OutlinedButton(onClick = { speed = (speed + 1).coerceAtMost(150f) }) { Text("+1", color = Color.White) }
            }
            Text(
                "Marginal +${settings.margin} km/h · ${settings.vehicle.label}" + (if (settings.trailer) " + släp" else "") +
                        " · vibration ${if (settings.vibrate) "på" else "av"} · pip ${if (settings.beep) "på" else "av"}",
                fontSize = 13.sp, color = dim
            )

            // ---------- Röst ----------
            Section("Röst")
            val voice = remember { SpeedTracker.prepareVoice(ctx) }
            val pack by VoicePack.state.collectAsStateWithLifecycle()
            Info("Läge", settings.heckle.label)
            Info("Röstpaket", when (val p = pack) {
                is VoicePack.State.Ready -> "${p.clips} klipp"
                else -> "inte nedladdat än"
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = { voice.preview(if (settings.heckle == HeckleMode.OFF) HeckleMode.NICE else settings.heckle) }) { Text("Säg en kommentar") }
                OutlinedButton(onClick = { voice.previewClip() }) { Text("Säg böterna", color = Color.White) }
            }
            Text("Med \"Låtsas köra\" på börjar den prata själv efter 2 s över gränsen.", fontSize = 13.sp, color = dim)

            // ---------- Markeringar ----------
            val marks by EventLog.files.collectAsStateWithLifecycle()
            Section("Markeringar (${marks.size})")
            Text("Knappen Markera på framsidan sparar 30 s före och 15 s efter: position, fart, gräns, väg och vilka röstklipp som spelades. " +
                "Filerna ligger bara i telefonen tills du trycker Dela.", fontSize = 13.sp, color = dim)
            val signs by EventLog.signs.collectAsStateWithLifecycle()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                if (marks.isNotEmpty()) Button(onClick = { EventLog.share(ctx, marks) }) { Text("Dela alla (${marks.size})") }
                if (signs > 0) Button(onClick = { EventLog.share(ctx, listOf(EventLog.signFile)) }) { Text("Dela skyltar ($signs)") }
            }
            // Skicka till den egna servern (samma kodord som "Skicka val" på lyssningssidan).
            val prefs = remember { ctx.getSharedPreferences("server", android.content.Context.MODE_PRIVATE) }
            var askCode by remember { mutableStateOf(false) }
            val upload by EventLog.uploadStatus.collectAsStateWithLifecycle()
            fun send(kod: String) = EventLog.upload(kod) { prefs.edit().remove("kod").apply() }
            if (marks.isNotEmpty() || signs > 0) OutlinedButton(onClick = {
                val k = prefs.getString("kod", null)
                if (k == null) askCode = true else send(k)
            }, modifier = Modifier.padding(top = 6.dp)) { Text("Skicka till servern", color = Color.White) }
            upload?.let { Text(it, fontSize = 13.sp, color = dim) }
            if (askCode) {
                var input by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { askCode = false },
                    title = { Text("Kodord") },
                    text = {
                        Column {
                            Text("Samma kodord som på lyssningssidan. Sparas i telefonen.", fontSize = 13.sp)
                            OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                        }
                    },
                    confirmButton = {
                        TextButton(enabled = input.trim().length >= 4, onClick = {
                            val k = input.trim()
                            prefs.edit().putString("kod", k).apply()
                            askCode = false
                            send(k)
                        }) { Text("Skicka") }
                    },
                    dismissButton = { TextButton(onClick = { askCode = false }) { Text("Avbryt") } },
                )
            }
            for (f in marks.take(20)) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.nameWithoutExtension.replace('_', ' '), color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = { EventLog.share(ctx, listOf(f)) }) { Text("Dela") }
                TextButton(onClick = { EventLog.delete(f) }) { Text("Ta bort", color = dim) }
            }

            // ---------- Automatik ----------
            Section("Automatisk start")
            Info("Läge", settings.auto.label)
            Info("Rörelseavkänning", when {
                settings.auto != AutoMode.DRIVING -> "används inte"
                registered -> "påslagen"
                else -> "inte påslagen (behörighet saknas?)"
            })
            Info("Telefonen tror att du", activity ?: "– (ingen händelse än)")
            Info("Bilens Bluetooth", when {
                settings.carAddress == null -> "ingen bil vald"
                car != null -> "ansluten ($car)"
                else -> "inte ansluten (${settings.carName})"
            })
            Info("\"Jag kör inte\"", if (AutoStart.isPaused(ctx)) "pausad" else "–")
            Info("Plats \"Tillåt alltid\"", if (AutoStart.hasBackgroundLocation(ctx)) "ja" else "nej")
            Info("Bevakning", when {
                !state.running -> "av"
                state.waiting -> "väntar på fart (automatisk)"
                state.auto -> "igång (automatisk)"
                else -> "igång"
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = { AutoStart.vehicleEnter(ctx, test = true) }) { Text("Låtsas sätta mig i bilen") }
            }
            Row(modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { AutoStart.vehicleExit(ctx, test = true) }) { Text("Låtsas kliva ur", color = Color.White) }
            }

            // ---------- Notis ----------
            Section("Notis")
            Info("Live-uppdatering", when (SpeedService.canPromote(ctx)) {
                null -> "stöds inte (kräver Android 16)"
                true -> "tillåten"
                false -> "avstängd i inställningarna"
            })
            OutlinedButton(onClick = {
                ctx.startActivity(
                    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text("Öppna notisinställningar", color = Color.White) }

            // ---------- Info ----------
            Section("GPS och väg")
            Info("Noggrannhet", debug.accuracy?.let { "± ${it.toInt()} m" } ?: "–")
            Info("Satelliter", "${debug.satsUsed} används av ${debug.satsTotal}")
            Info("Position", if (debug.lat != null && debug.lon != null)
                String.format(Locale.ROOT, "%.5f, %.5f", debug.lat, debug.lon) else "–")
            Info("Senaste position", if (debug.fixTime > 0)
                "${(System.currentTimeMillis() - debug.fixTime) / 1000} s sedan" else "–")
            Info("Väg", debug.road ?: "ingen hittad")
            Info("Data", (dataState as? DataManager.State.Ready)?.let { DataManager.prettyVersion(it.version) } ?: "–")
            if (!state.running) Text("Starta bevakningen för att se GPS-info.", fontSize = 13.sp, color = dim)
            Spacer(Modifier.height(24.dp))
        }
    }
}


@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 20.dp, bottom = 8.dp), color = Color.White.copy(alpha = 0.2f))
    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(0.45f), fontSize = 14.sp, color = Color.White.copy(alpha = 0.65f))
        Text(value, Modifier.weight(0.55f), fontSize = 14.sp, color = Color.White)
    }
}
