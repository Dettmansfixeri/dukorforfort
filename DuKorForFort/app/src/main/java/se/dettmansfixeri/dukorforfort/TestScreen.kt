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
                    Text(Warner.describe(over), color = Color.White)
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
            val voice = remember(settings.voiceEngine) { SpeedTracker.prepareVoice(ctx) }
            val voiceReady by voice.ready.collectAsStateWithLifecycle()
            var voiceN by remember { mutableIntStateOf(0) }
            Info("Läge", settings.heckle.label)
            Info("Status", when (voiceReady) {
                null -> "startar…"
                true -> "redo"
                false -> "ingen svensk röst – välj en nedan eller installera i telefonens talsyntes"
            })
            Info("Röst nu", voice.currentVoice()?.name ?: "–")
            val testSpeed = speed.toInt().coerceAtLeast(signed + 1)
            Text("Nästa: \"${Heckler.line(voiceN, if (settings.heckle == HeckleMode.OFF) HeckleMode.NICE else settings.heckle, testSpeed, signed)}\"",
                fontSize = 13.sp, color = dim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = {
                    val mode = if (settings.heckle == HeckleMode.OFF) HeckleMode.NICE else settings.heckle
                    voice.say(Heckler.line(voiceN, mode, testSpeed, signed), interrupt = true)
                    voiceN++
                }) { Text("Säg nästa") }
                OutlinedButton(onClick = { voiceN = 0 }) { Text("Börja om", color = Color.White) }
            }
            Text("Med \"Låtsas köra\" på börjar den prata själv efter 5 s över gränsen.", fontSize = 13.sp, color = dim)

            VoicePicker(voice, voiceReady, settings)

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

/** Alla talmotorer och röster i telefonen – prova och välj. */
@Composable
private fun VoicePicker(voice: Heckler, ready: Boolean?, settings: Settings) {
    val ctx = LocalContext.current
    val dim = Color.White.copy(alpha = 0.65f)
    var allLanguages by remember { mutableStateOf(false) }

    // Talmotorer
    val engines = remember(ready) { voice.engines() }
    if (engines.size > 1) {
        Text("Talmotor", fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 12.dp))
        val current = settings.voiceEngine
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(selected = current == null, label = { Text("Standard") },
                onClick = { SpeedTracker.setSettings(ctx, settings.copy(voiceEngine = null, voiceName = null)) })
            engines.forEach { e ->
                FilterChip(selected = current == e.name, label = { Text(e.label) },
                    onClick = { SpeedTracker.setSettings(ctx, settings.copy(voiceEngine = e.name, voiceName = null)) })
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text("Röster", fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
        Text("Alla språk", fontSize = 13.sp, color = dim)
        Spacer(Modifier.width(6.dp))
        Switch(checked = allLanguages, onCheckedChange = { allLanguages = it })
    }
    if (ready == null) { Text("Startar talsyntesen…", fontSize = 13.sp, color = dim); return }

    val voices = remember(ready, allLanguages, settings.voiceEngine) {
        voice.voices()
            .filter { allLanguages || Heckler.isSwedish(it) }
            .sortedWith(compareBy<android.speech.tts.Voice>({ !Heckler.isSwedish(it) }, { Heckler.needsNetwork(it) }, { -it.quality }, { it.name }))
    }
    val auto = Heckler.bestSwedish(voice.voices())
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        RadioButton(selected = settings.voiceName == null,
            onClick = { SpeedTracker.setSettings(ctx, settings.copy(voiceName = null)) })
        Text("Automatiskt (bästa svenska): ${auto?.name ?: "ingen hittad"}", fontSize = 14.sp, color = Color.White)
    }
    if (voices.isEmpty()) Text("Inga röster hittades.", fontSize = 13.sp, color = dim)
    voices.forEach { v ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            RadioButton(selected = settings.voiceName == v.name,
                onClick = { SpeedTracker.setSettings(ctx, settings.copy(voiceName = v.name)) })
            Column(Modifier.weight(1f)) {
                Text(v.name, fontSize = 14.sp, color = Color.White)
                val tags = buildList {
                    add(v.locale.displayName)
                    add("kvalitet: ${Heckler.qualityLabel(v.quality)}")
                    if (Heckler.needsNetwork(v)) add("kräver internet – texten skickas till motorns tillverkare")
                    if (Heckler.notInstalled(v)) add("inte nedladdad")
                }
                Text(tags.joinToString(" · "), fontSize = 12.sp, color = dim)
            }
            TextButton(onClick = {
                voice.tryVoice(v, if (Heckler.isSwedish(v)) Heckler.SAMPLE else "Hello! You are driving too fast, idiot.")
            }) { Text("Prova") }
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
