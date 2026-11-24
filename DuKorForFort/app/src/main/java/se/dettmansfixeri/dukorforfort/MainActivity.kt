package se.dettmansfixeri.dukorforfort

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { App() }
        }
    }
}

private const val PREFS = "installningar"
private const val KEY_DISCLAIMER = "godkant_ansvarsfriskrivning_v1"

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var accepted by remember { mutableStateOf(prefs.getBoolean(KEY_DISCLAIMER, false)) }
    if (!accepted) {
        Disclaimer(onAccept = {
            prefs.edit().putBoolean(KEY_DISCLAIMER, true).apply()
            accepted = true
        })
        return
    }
    DataGate()
}

/**
 * Ser till att hastighetsdatan finns i telefonen. Första gången laddas den ner
 * (ingenting ligger inbyggt i appen). Vid varje start kollas om en nyare version finns.
 */
@Composable
fun DataGate() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dataState by DataManager.state.collectAsStateWithLifecycle()
    val update by DataManager.update.collectAsStateWithLifecycle()

    val appUpdate by AppUpdater.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        DataManager.load(ctx)
        SpeedTracker.loadSettings(ctx)
        AutoStart.apply(ctx, SpeedTracker.settings.value)
        AppUpdater.check()
        if (SpeedTracker.settings.value.voicesOn) VoicePack.sync(ctx)
        DataUpdates.schedule(ctx)
        // Ny data laddas ner direkt på wifi (eller mobildata om det är tillåtet), annars kommer frågan.
        DataManager.autoUpdate(ctx)
    }
    var askMobile by remember { mutableStateOf(false) }
    fun startDownload() {
        DataManager.dismissUpdate()
        if (DataManager.mayDownloadNow(ctx)) scope.launch { DataManager.download(ctx) } else askMobile = true
    }
    // Kolla appversionen igen varje gång appen tas fram (den kan ha legat i bakgrunden länge).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var first = true
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (first) first = false else scope.launch { AppUpdater.check() }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val hasData = DataManager.data != null
    when {
        dataState is DataManager.State.Downloading && !hasData -> {
            val d = dataState as DataManager.State.Downloading
            DownloadScreen(d.progress, null, DataManager.kbText(d.read, d.total)) {}
        }
        !hasData -> DownloadScreen(null, (dataState as? DataManager.State.Failed)?.message) { startDownload() }
        else -> SpeedScreen()
    }

    if (askMobile) MobileDataDialog(
        onDownload = { always ->
            askMobile = false
            if (always) DataManager.setMobileData(ctx, true)
            scope.launch { DataManager.download(ctx) }
        },
        onCancel = { askMobile = false },
    )

    val a = appUpdate
    if (a !is AppUpdater.State.None) {
        AppUpdateDialog(a)
        return
    }

    val u = update
    if (u != null && hasData) {
        AlertDialog(
            onDismissRequest = { DataManager.dismissUpdate() },
            title = { Text("Nya hastighetsgränser") },
            text = { Text("Det finns uppdaterad data från Trafikverket (${DataManager.prettyVersion(u.version)}, ${u.size / 1048576} MB). Ladda ner nu?") },
            confirmButton = { TextButton(onClick = { startDownload() }) { Text("Ladda ner") } },
            dismissButton = { TextButton(onClick = { DataManager.dismissUpdate() }) { Text("Senare") } },
        )
    }
}

/** Ny version av appen: ladda ner och installera. */
@Composable
fun AppUpdateDialog(s: AppUpdater.State) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val canClose = s !is AppUpdater.State.Downloading
    AlertDialog(
        onDismissRequest = { if (canClose) AppUpdater.dismiss() },
        title = { Text("Ny version av appen") },
        text = {
            Column {
                when (s) {
                    is AppUpdater.State.Available -> Text(
                        "Version ${s.remote.version} finns (du har ${BuildConfig.VERSION_NAME}). " +
                            "Ladda ner och installera nu?" +
                            (if (s.remote.size > 0) " (${(s.remote.size + 524288) / 1048576} MB)" else "")
                    )
                    is AppUpdater.State.Downloading -> {
                        Text("Laddar ner version ${s.remote.version}… ${(s.progress * 100).toInt()} %")
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    is AppUpdater.State.Ready -> Text(
                        if (AppUpdater.mayInstall(ctx)) "Klar! Tryck Installera."
                        else "Klar! Telefonen behöver först tillåta att den här appen installerar uppdateringar. " +
                            "Tryck Tillåt, slå på reglaget, gå tillbaka och tryck Installera."
                    )
                    is AppUpdater.State.Failed -> Text(s.message)
                    AppUpdater.State.None -> {}
                }
            }
        },
        confirmButton = {
            when (s) {
                is AppUpdater.State.Available, is AppUpdater.State.Failed -> TextButton(onClick = {
                    val r = if (s is AppUpdater.State.Available) s.remote else (s as AppUpdater.State.Failed).remote
                    scope.launch { AppUpdater.download(ctx, r) }
                }) { Text(if (s is AppUpdater.State.Failed) "Försök igen" else "Ladda ner") }
                is AppUpdater.State.Ready -> {
                    // Läses om när man kommer tillbaka från inställningarna.
                    var tick by remember { mutableIntStateOf(0) }
                    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                    DisposableEffect(lifecycle) {
                        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
                        }
                        lifecycle.addObserver(obs)
                        onDispose { lifecycle.removeObserver(obs) }
                    }
                    val may = remember(tick) { AppUpdater.mayInstall(ctx) }
                    TextButton(onClick = {
                        if (may) AppUpdater.install(ctx, s.file) else AppUpdater.openInstallPermission(ctx)
                    }) { Text(if (may) "Installera" else "Tillåt") }
                }
                else -> {}
            }
        },
        dismissButton = {
            if (canClose) TextButton(onClick = { AppUpdater.dismiss() }) { Text("Senare") }
        },
    )
}

@Composable
fun MobileDataDialog(onDownload: (always: Boolean) -> Unit, onCancel: () -> Unit) {
    var always by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Ladda ner med mobildata?") },
        text = {
            Column {
                Text("Du är inte ansluten till wifi. Hastighetsdatan är ungefär ${DataManager.knownSize / 1048576} MB.")
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().clickable { always = !always }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = always, onCheckedChange = { always = it })
                    Text("Fråga inte igen – använd alltid mobildata")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDownload(always) }) { Text("Ladda ner") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Avbryt") } },
    )
}

@Composable
fun DownloadScreen(progress: Float?, error: String?, detail: String? = null, onDownload: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF101418)).safeDrawingPadding().padding(24.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text("Hastighetsdata", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White)
            Spacer(Modifier.height(16.dp))
            Text(
                "Appen behöver ladda ner Sveriges hastighetsgränser från Trafikverket (ungefär 50 MB). " +
                        "Sedan fungerar den utan internet, och din position lämnar aldrig telefonen. " +
                        "Använd gärna wifi.",
                fontSize = 17.sp, color = Color.White.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(28.dp))
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
                Spacer(Modifier.height(12.dp))
                Text("Laddar ner… ${(progress * 100).toInt()} %" + (detail?.let { "  ·  $it" } ?: ""), color = Color.White.copy(alpha = 0.7f))
            } else {
                if (error != null) {
                    Text(error, color = Color(0xFFFF8A80), fontSize = 15.sp)
                    Spacer(Modifier.height(12.dp))
                }
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(if (error != null) "Försök igen" else "Ladda ner", fontSize = 18.sp)
                }
            }
        }
    }
}

private val Dim = Color.White.copy(alpha = 0.65f)

@Composable
private fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Dim)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

/** En rad i inställningsmenyn: rubrik, vad som är valt nu, och en pil. */
@Composable
private fun MenuRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(summary, fontSize = 13.sp, color = Dim)
        }
        Text("›", fontSize = 26.sp, color = Dim)
    }
}

/** Delarna i inställningarna. */
private enum class SettingsPage(val title: String) {
    WARN("Varning"),
    SOUND("Ljud och röst"),
    VEHICLE("Fordon"),
    AUTO("Starta automatiskt"),
    DATA("Hastighetsdata"),
    TESTS("Testfunktioner"),
}

@Composable
fun SettingsDialog(current: Settings, onDone: (Settings) -> Unit, onDismiss: () -> Unit) {
    var s by remember { mutableStateOf(current) }
    var permTick by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    val ctx = LocalContext.current
    val mobileData by DataManager.mobileData.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { Preview.stop() } } // provlyssningen tystnar när inställningarna stängs
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (page != null) TextButton(onClick = { page = null }, contentPadding = PaddingValues(end = 8.dp)) {
                    Text("‹", fontSize = 26.sp)
                }
                Text(page?.title ?: "Inställningar")
            }
        },
        text = {
            // Bakåtknappen går tillbaka till menyn i stället för att stänga (måste ligga inne i dialogen).
            androidx.activity.compose.BackHandler(enabled = page != null) { page = null }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (page) {
                    null -> {
                        MenuRow(SettingsPage.WARN.title, buildList {
                            add(when {
                                s.margin == 0 -> "Vid gränsen"
                                s.margin > 0 -> "+${s.margin} km/h"
                                else -> "${s.margin} km/h"
                            })
                            if (s.vibrate) add("vibrerar")
                            if (s.gasWarn) add("förvarnar")
                        }.joinToString(" · ")) { page = SettingsPage.WARN }
                        HorizontalDivider()
                        MenuRow(SettingsPage.SOUND.title,
                            (if (!s.beep) "Inget ljud" else s.sound.label.substringBefore(" –")) +
                                " · röst: " + s.heckleOn.label.lowercase()) { page = SettingsPage.SOUND }
                        HorizontalDivider()
                        MenuRow(SettingsPage.VEHICLE.title, s.vehicle.label + if (s.trailer) " + släp" else "") { page = SettingsPage.VEHICLE }
                        HorizontalDivider()
                        MenuRow(SettingsPage.AUTO.title,
                            if (s.auto == AutoMode.CAR && s.carName != null) "${s.auto.label}: ${s.carName}" else s.auto.label) { page = SettingsPage.AUTO }
                        HorizontalDivider()
                        MenuRow(SettingsPage.DATA.title, (if (mobileData) "Wifi och mobildata" else "Bara wifi") +
                            (DataManager.localVersion(ctx)?.let { " · " + DataManager.prettyVersion(it) } ?: "")) { page = SettingsPage.DATA }
                        HorizontalDivider()
                        MenuRow(SettingsPage.TESTS.title, if (s.tests) "På" else "Av") { page = SettingsPage.TESTS }
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = Dim)
                    }

                    SettingsPage.WARN -> {
                        Text("Varna när jag kör", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                s.margin == 0 -> "så fort jag är över gränsen"
                                s.margin > 0 -> "mer än ${s.margin} km/h över gränsen"
                                else -> "mindre än ${-s.margin} km/h under gränsen (för att prova)"
                            },
                            fontSize = 14.sp, color = Dim
                        )
                        Slider(
                            value = s.margin.toFloat(),
                            onValueChange = { s = s.copy(margin = Math.round(it)) },
                            valueRange = SpeedTracker.MIN_MARGIN.toFloat()..SpeedTracker.MAX_MARGIN.toFloat(),
                            steps = SpeedTracker.MAX_MARGIN - SpeedTracker.MIN_MARGIN - 1,
                        )
                        SwitchRow("Vibrera", "Fler pulser ju mer över du är", s.vibrate) { s = s.copy(vibrate = it) }
                        SwitchRow("Förvarna när jag gasar på", "Varnar redan när du är på väg över gränsen, innan du är där", s.gasWarn) {
                            s = s.copy(gasWarn = it)
                        }
                    }

                    SettingsPage.SOUND -> {
                        SwitchRow("Ljud", null, s.beep) { s = s.copy(beep = it) }
                        if (s.beep) {
                            SoundMode.entries.forEach { m -> RadioRow(m.label, s.sound == m) { s = s.copy(sound = m) } }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { Preview.sound(ctx, s.sound) }) {
                                    IkonBild(Ikon.SPELA); Spacer(Modifier.width(8.dp)); Text("Provlyssna ljudet")
                                }
                                OutlinedButton(onClick = { Preview.stop() }) {
                                    IkonBild(Ikon.STOPP); Spacer(Modifier.width(8.dp)); Text("Stopp")
                                }
                            }
                            Text(
                                if (s.sound == SoundMode.SILENT) "Spelar du musik pausas den i 4 sekunder, annars hörs pipet."
                                else "Låter som 2, 5 och sedan 10 km/h över.",
                                fontSize = 12.sp, color = Dim
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("Röst", fontWeight = FontWeight.Bold)
                        Text(if (s.silentOn) "Tyst läge är valt – rösten är av" else "Pratar om du ligger över gränsen mer än 5 sekunder", fontSize = 13.sp, color = Dim)
                        HeckleMode.entries.forEach { m -> RadioRow(m.label, s.heckle == m) { s = s.copy(heckle = m) } }
                        if (s.heckle != HeckleMode.OFF && !s.silentOn) OutlinedButton(onClick = {
                            Preview.stop()
                            SpeedTracker.prepareVoice(ctx).preview(s.heckle, s.voicesOn)
                        }) { IkonBild(Ikon.SPELA); Spacer(Modifier.width(8.dp)); Text("Provlyssna rösten") }
                    }

                    SettingsPage.VEHICLE -> {
                        Text("Vad kör du?", fontWeight = FontWeight.Bold)
                        Vehicle.entries.forEach { v -> RadioRow(v.label, s.vehicle == v) { s = s.copy(vehicle = v) } }
                        SwitchRow("Släp/efterfordon", "Max ${Vehicle.TRAILER_MAX} km/h", s.trailer) { s = s.copy(trailer = it) }
                    }

                    SettingsPage.AUTO -> {
                        AutoMode.entries.forEach { m -> RadioRow(m.label, s.auto == m) { s = s.copy(auto = m) } }
                        when (s.auto) {
                            AutoMode.DRIVING -> Text(
                                "Startar när telefonen märker att du åker, och bevakar när du kör över " +
                                        "${SpeedTracker.AUTO_ARM_KMH} km/h. Åker du buss eller taxi: tryck \"Jag kör inte\" i notisen.",
                                fontSize = 13.sp, color = Dim
                            )
                            AutoMode.CAR -> CarPicker(s, permTick) { addr, name -> s = s.copy(carAddress = addr, carName = name) }
                            AutoMode.OFF -> {}
                        }
                        if (s.auto != AutoMode.OFF) AutoPermissions(s.auto, permTick) { permTick++ }
                    }

                    SettingsPage.DATA -> {
                        Text(DataManager.localVersion(ctx)?.let { "Data från ${DataManager.prettyVersion(it)}" } ?: "Ingen data", fontWeight = FontWeight.Bold)
                        Text("Appen kollar efter ny data en gång per dygn, även när den är stängd. Finns det ny laddas den ner.", fontSize = 13.sp, color = Dim)
                        Spacer(Modifier.height(8.dp))
                        SwitchRow("Ladda ner med mobildata",
                            if (mobileData) "Ny data laddas ner direkt, även på mobildata. Det kostar ungefär " +
                                "${DataManager.knownSize / 1048576} MB, men bara när det finns ny data."
                            else "Ny data laddas bara ner när du är på wifi.", mobileData) {
                            DataManager.setMobileData(ctx, it)
                        }
                    }

                    SettingsPage.TESTS -> {
                        SwitchRow("Testfunktioner", "Saker som provas. De kan ha fel.", s.tests) { s = s.copy(tests = it) }
                        if (s.tests) {
                            Column(Modifier.padding(start = 16.dp)) {
                                SwitchRow("Förvarna om lägre gräns (TEST)",
                                    "Säger t.ex. \"Nu blir det 40 här framme\" när det är dags att släppa gasen inför en lägre gräns (tickar om rösten inte går)", s.testAhead) { s = s.copy(testAhead = it) }
                                if (s.testAhead) {
                                    Column(Modifier.padding(start = 16.dp)) {
                                        SwitchRow("Lär dig bilens inbromsning",
                                            "Mäter hur fort bilen tappar fart när du släpper gasen", s.testLearnBrake) { s = s.copy(testLearnBrake = it) }
                                    }
                                }
                                SwitchRow("Visa vad appen tror (TEST)",
                                    "T.ex. \"Landar på 73 km/h\" eller \"50 om 230 m – släpp om 80 m\"", s.testShowGuess) { s = s.copy(testShowGuess = it) }
                                SwitchRow("Gemini-röster (TEST)",
                                    "Inspelade röster i stället för telefonens talsyntes. Säger böterna och kommentarer som är godkända.",
                                    s.testVoices) { s = s.copy(testVoices = it) }
                                if (s.testVoices) VoicePackStatus()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(s) }) { Text("Klar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Avbryt") } },
    )
}

/** Visar vilken behörighet som saknas för automatiken och en knapp för att ge den. */
@Composable
private fun AutoPermissions(mode: AutoMode, tick: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    check(tick >= 0) // läses så att rutan ritas om när en behörighet ändrats

    data class Need(val permission: String, val text: String, val button: String)
    val need = when {
        mode == AutoMode.DRIVING && !AutoStart.hasActivityPermission(ctx) && Build.VERSION.SDK_INT >= 29 ->
            Need(Manifest.permission.ACTIVITY_RECOGNITION,
                "För att märka när du sitter i en bil behöver appen tillgång till \"Fysisk aktivitet\".", "Ge tillgång")
        mode == AutoMode.CAR && !AutoStart.hasBluetoothPermission(ctx) && Build.VERSION.SDK_INT >= 31 ->
            Need(Manifest.permission.BLUETOOTH_CONNECT,
                "För att se när telefonen kopplas till bilen behöver appen tillgång till \"Enheter i närheten\".", "Ge tillgång")
        !AutoStart.hasBackgroundLocation(ctx) && Build.VERSION.SDK_INT >= 29 ->
            Need(Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                "För att starta medan telefonen ligger i fickan behöver appen plats \"Tillåt alltid\". " +
                        "Välj det på nästa sida.", "Välj \"Tillåt alltid\"")
        else -> null
    }
    Spacer(Modifier.height(6.dp))
    if (need == null) {
        Text("✓ Allt klart för automatisk start", fontSize = 13.sp, color = Color(0xFF81C784))
    } else {
        Text(need.text, fontSize = 13.sp, color = Color(0xFFFFB74D))
        OutlinedButton(onClick = { launcher.launch(need.permission) }) { Text(need.button) }
    }
}

/** Lista över kopplade Bluetooth-enheter att välja bilen från. */
@SuppressLint("MissingPermission")
@Composable
private fun CarPicker(s: Settings, tick: Int, onPick: (String, String) -> Unit) {
    val ctx = LocalContext.current
    if (!AutoStart.hasBluetoothPermission(ctx)) return
    val devices = remember(tick) {
        try {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .map { it.address to (it.name ?: it.address) }.sortedBy { it.second.lowercase() }
        } catch (e: SecurityException) { emptyList() }
    }
    Text("Välj din bil:", fontSize = 13.sp, color = Dim)
    if (devices.isEmpty()) Text("Inga kopplade Bluetooth-enheter. Koppla telefonen till bilen först.", fontSize = 13.sp, color = Dim)
    devices.forEach { (addr, name) -> RadioRow(name, s.carAddress == addr) { onPick(addr, name) } }
}

/** Visas vid första start. Appen går inte att använda förrän man tryckt "Jag förstår". */
@Composable
fun Disclaimer(onAccept: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF101418)).safeDrawingPadding().padding(24.dp)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Innan du kör", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White)
            Spacer(Modifier.height(16.dp))
            val body = Color.White.copy(alpha = 0.85f)
            Text(
                "Appen är ett hjälpmedel och ersätter inte vägmärkena. " +
                        "Det är alltid skyltarna och trafikreglerna på platsen som gäller.",
                fontSize = 17.sp, color = body
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Hastighetsgränserna kommer från Trafikverkets nationella vägdatabas (NVDB). " +
                        "De kan vara inaktuella, felaktiga eller saknas, och tillfälliga gränser " +
                        "(t.ex. vid vägarbeten) finns inte med. Hastigheten mäts med telefonens GPS " +
                        "och kan skilja sig från bilens hastighetsmätare.",
                fontSize = 17.sp, color = body
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Du ansvarar själv för hur du kör. Hantera inte telefonen under körning.",
                fontSize = 17.sp, color = body
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Appen är gratis och tillhandahålls i befintligt skick, utan garantier. " +
                        "Det finns ingen garanti för att appen eller hastighetsdatan alltid är " +
                        "tillgänglig eller fungerar, och appen kan ändras eller läggas ner utan förvarning. " +
                        "Dettmans Fixeri ansvarar inte för böter, olyckor eller andra följder " +
                        "av att appen används. Trafikverket står inte bakom appen.",
                fontSize = 17.sp, color = body
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onAccept, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Jag förstår", fontSize = 18.sp)
            }
        }
    }
}

/** Röd blinkande bakgrund när man kör för fort. */
@Composable
fun warningBackground(tooFast: Boolean): Color {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val t by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "t")
    return if (tooFast) lerp(Color(0xFF7F0000), Color(0xFFE53935), t) else Color(0xFF101418)
}

/**
 * Skylten, ev. förklaring och IDIOT-tavlan. Tavlan syns bara när man kör för fort,
 * men platsen hålls så att skylten inte hoppar. Testsidan: fem tryck på nollan i skylten.
 */
@Composable
fun SignBlock(state: UiState, signSize: androidx.compose.ui.unit.Dp, onSecret: (() -> Unit)? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SpeedSign(state.limitKmh, state.limitStale, signSize, onSecret)
        val signed = state.signedKmh
        if (state.reason != null && signed != null && signed != state.limitKmh) {
            Text(
                "Skyltat $signed · ${state.limitKmh} gäller dig (${state.reason})",
                fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(8.dp))
        if (state.tooFast) UnderSign("IDIOT", signSize)
        else Spacer(Modifier.height(signSize * 0.4f))
    }
}

/** Din hastighet och varningstexten. */
@Composable
fun SpeedBlock(state: UiState, fontSize: TextUnit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = state.speedKmh?.toString() ?: "–",
            fontSize = fontSize, lineHeight = fontSize,
            fontWeight = FontWeight.Black, color = Color.White
        )
        Text("km/h", fontSize = 24.sp, color = Color.White.copy(alpha = 0.7f))
        state.guess?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 15.sp, color = Color(0xFF80DEEA), textAlign = TextAlign.Center)
        }
        if (state.tooFast) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Du kör för fort, IDIOT!",
                fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White, textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun SpeedScreen() {
    val ctx = LocalContext.current
    val state by SpeedTracker.state.collectAsStateWithLifecycle()
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res -> hasPermission = res[Manifest.permission.ACCESS_FINE_LOCATION] == true }

    // Plats krävs. Notiser (Android 13+) behövs för att notisen ska synas.
    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) launcher.launch(wanted.toTypedArray())
    }
    // Öppnar man appen startar bevakningen. Den fortsätter i bakgrunden
    // (med notis) tills man trycker Stopp.
    LaunchedEffect(hasPermission) {
        if (hasPermission) SpeedService.start(ctx)
    }

    val settings by SpeedTracker.settings.collectAsStateWithLifecycle()
    val dataState by DataManager.state.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showTest by remember { mutableStateOf(false) }

    if (showTest) {
        TestScreen(onClose = { showTest = false })
        return
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val bg = warningBackground(state.tooFast)

    @Composable
    fun StatusBlock() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MarginWarning(settings.margin)
            if (settings.anyTestOn) Text(
                "TEST på", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF80DEEA),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                if (!hasPermission) "Appen behöver platsbehörighet" else state.status,
                fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center
            )
            val ds = dataState
            Text(
                when (ds) {
                    is DataManager.State.Ready -> "Data från ${DataManager.prettyVersion(ds.version)}"
                    is DataManager.State.Downloading -> "Uppdaterar data… ${DataManager.kbText(ds.read, ds.total)}"
                    is DataManager.State.Failed -> "Uppdateringen misslyckades: ${ds.message}"
                    else -> ""
                },
                fontSize = 12.sp, color = Color.White.copy(alpha = 0.45f), textAlign = TextAlign.Center
            )
            if (hasPermission) {
                Spacer(Modifier.height(12.dp))
                if (state.running) {
                    OutlinedButton(onClick = { SpeedService.stop(ctx) }) { Text("Stoppa", color = Color.White) }
                } else {
                    Button(onClick = { SpeedService.start(ctx) }) { Text("Starta") }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(bg).safeDrawingPadding().padding(16.dp)) {
        // Inställningar uppe till vänster – visar valt fordon och marginal.
        OutlinedButton(onClick = { showSettings = true }, modifier = Modifier.align(Alignment.TopStart)) {
            Text(
                "⚙ " + settings.vehicle.label + (if (settings.trailer) " + släp" else "") +
                        (if (settings.margin > 0) " · +${settings.margin} km/h" else if (settings.margin < 0) " · ${settings.margin} km/h" else ""),
                color = Color.White
            )
        }
        if (landscape) {
            // Liggande: skylten till vänster, hastigheten till höger.
            Row(
                Modifier.fillMaxSize().padding(top = 44.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SignBlock(state, 150.dp) { showTest = true }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SpeedBlock(state, 110.sp)
                    Spacer(Modifier.height(8.dp))
                    StatusBlock()
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(bottom = 84.dp), // plats för musikknapparna
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                SignBlock(state, 190.dp) { showTest = true }
                Spacer(Modifier.height(24.dp))
                SpeedBlock(state, 140.sp)
                Spacer(Modifier.height(24.dp))
                StatusBlock()
            }
        }
        // Musikknappar längst ner, så man kan byta låt utan att lämna appen.
        MediaControls(Modifier.align(if (landscape) Alignment.BottomEnd else Alignment.BottomCenter))
    }

    if (showSettings) {
        SettingsDialog(
            current = settings,
            onDone = {
                SpeedTracker.setSettings(ctx, it)
                AutoStart.apply(ctx, it)
                showSettings = false
            },
            onDismiss = { showSettings = false },
        )
    }
}

/** Gul varningsruta på framsidan när marginalen inte står på 0, så man inte glömmer att ställa tillbaka den. */
@Composable
private fun MarginWarning(margin: Int) {
    if (margin == 0) return
    Text(
        if (margin < 0) "⚠ Marginal ${margin} km/h – varnar redan under gränsen"
        else "⚠ Marginal +${margin} km/h – varnar först mer än ${margin} km/h över",
        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black, textAlign = TextAlign.Center,
        modifier = Modifier.padding(bottom = 12.dp)
            .background(Color(0xFFFFCC00), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** Röstpaketets läge i testmenyn, med knapp för att hämta det. */
@Composable
private fun VoicePackStatus() {
    val ctx = LocalContext.current
    val st by VoicePack.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { VoicePack.load(ctx); SpeedTracker.syncVoices(ctx) }
    Column(Modifier.padding(start = 16.dp, bottom = 4.dp)) {
        Text(
            when (val v = st) {
                is VoicePack.State.Ready -> "✓ Röstpaket: ${v.clips} klipp"
                is VoicePack.State.Downloading -> "Laddar ner röstpaket… ${(v.progress * 100).toInt()} %"
                is VoicePack.State.Failed -> v.message
                VoicePack.State.None -> "Röstpaketet är inte nedladdat än"
            },
            fontSize = 13.sp, color = if (st is VoicePack.State.Ready) Color(0xFF81C784) else Dim
        )
        if (st !is VoicePack.State.Downloading) Row {
            TextButton(onClick = { SpeedTracker.syncVoices(ctx) }) { Text("Hämta röstpaket") }
            TextButton(onClick = { SpeedTracker.prepareVoice(ctx).previewClip() }) { Text("Provlyssna") }
        }
        val last by VoicePack.lastPlay.collectAsStateWithLifecycle()
        last?.let { Text(it, fontSize = 12.sp, color = Dim) }
    }
}
