package se.dettmansfixeri.dukorforfort

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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

    LaunchedEffect(Unit) {
        DataManager.load(ctx)
        SpeedTracker.loadSettings(ctx)
        DataManager.checkForUpdate(ctx)
    }

    val hasData = DataManager.data != null
    when {
        dataState is DataManager.State.Downloading && !hasData ->
            DownloadScreen((dataState as DataManager.State.Downloading).progress, null) {}
        !hasData -> DownloadScreen(null, (dataState as? DataManager.State.Failed)?.message) {
            scope.launch { DataManager.download(ctx) }
        }
        else -> SpeedScreen()
    }

    val u = update
    if (u != null && hasData) {
        AlertDialog(
            onDismissRequest = { DataManager.dismissUpdate() },
            title = { Text("Nya hastighetsgränser") },
            text = { Text("Det finns uppdaterad data från Trafikverket (${DataManager.prettyVersion(u.version)}, ${u.size / 1048576} MB). Ladda ner nu?") },
            confirmButton = { TextButton(onClick = { scope.launch { DataManager.download(ctx) } }) { Text("Ladda ner") } },
            dismissButton = { TextButton(onClick = { DataManager.dismissUpdate() }) { Text("Senare") } },
        )
    }
}

@Composable
fun DownloadScreen(progress: Float?, error: String?, onDownload: () -> Unit) {
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
                Text("Laddar ner… ${(progress * 100).toInt()} %", color = Color.White.copy(alpha = 0.7f))
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

@Composable
fun VehicleDialog(current: Settings, onDone: (Settings) -> Unit, onDismiss: () -> Unit) {
    var vehicle by remember { mutableStateOf(current.vehicle) }
    var trailer by remember { mutableStateOf(current.trailer) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vad kör du?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Vehicle.entries.forEach { v ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vehicle = v }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = vehicle == v, onClick = { vehicle = v })
                        Text(v.label)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { trailer = !trailer }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Släp/efterfordon")
                        Text("Max ${Vehicle.TRAILER_MAX} km/h", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
                    }
                    Switch(checked = trailer, onCheckedChange = { trailer = it })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(Settings(vehicle, trailer)) }) { Text("Klar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Avbryt") } },
    )
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

    // Blinkande röd bakgrund när man kör för fort
    val pulse = rememberInfiniteTransition(label = "pulse")
    val t by pulse.animateFloat(
        0f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "t"
    )
    val bg = if (state.tooFast) lerp(Color(0xFF7F0000), Color(0xFFE53935), t) else Color(0xFF101418)

    val settings by SpeedTracker.settings.collectAsStateWithLifecycle()
    val dataState by DataManager.state.collectAsStateWithLifecycle()
    var showVehicle by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(bg).safeDrawingPadding().padding(16.dp)) {
        // Valt fordon uppe till vänster – tryck för att ändra.
        OutlinedButton(
            onClick = { showVehicle = true },
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Text(settings.vehicle.label + if (settings.trailer) " + släp" else "", color = Color.White)
        }
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            SpeedSign(state.limitKmh, state.limitStale)
            val signed = state.signedKmh
            if (state.reason != null && signed != null && signed != state.limitKmh) {
                Text(
                    "Skyltat $signed · ${state.limitKmh} gäller dig (${state.reason})",
                    fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f)
                )
            }
            Spacer(Modifier.height(8.dp))
            UnderSign("IDIOT")
            Spacer(Modifier.height(24.dp))
            Text(
                text = state.speedKmh?.toString() ?: "–",
                fontSize = 140.sp,
                fontWeight = FontWeight.Black,
                color = Color.White
            )
            Text("km/h", fontSize = 24.sp, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(24.dp))
            if (state.tooFast) {
                Text(
                    "Du kör för fort, IDIOT!",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                if (!hasPermission) "Appen behöver platsbehörighet" else state.status,
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
            val ds = dataState
            Text(
                when (ds) {
                    is DataManager.State.Ready -> "Data från ${DataManager.prettyVersion(ds.version)}"
                    is DataManager.State.Downloading -> "Uppdaterar data… ${(ds.progress * 100).toInt()} %"
                    is DataManager.State.Failed -> "Uppdateringen misslyckades: ${ds.message}"
                    else -> ""
                },
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.45f),
                textAlign = TextAlign.Center
            )
            if (hasPermission) {
                Spacer(Modifier.height(16.dp))
                if (state.running) {
                    OutlinedButton(onClick = { SpeedService.stop(ctx) }) { Text("Stoppa", color = Color.White) }
                } else {
                    Button(onClick = { SpeedService.start(ctx) }) { Text("Starta") }
                }
            }
        }
    }

    if (showVehicle) {
        VehicleDialog(
            current = settings,
            onDone = { SpeedTracker.setSettings(ctx, it); showVehicle = false },
            onDismiss = { showVehicle = false },
        )
    }
}

/*
 * Svenska vägmärken ritade efter förlaga, i en koordinatyta på 100 enheter i bredd
 * (samma mått som SVG:erna på webbsidan):
 *  - C31 Hastighetsbegränsning: röd ring ut till kanten, gul platta (r 38.4), svarta Tratex-siffror.
 *  - Tilläggstavla: röd kant med rundade hörn, gul botten, svart Tratex-text.
 */
private const val SIGN_RED = 0xFFDC0028.toInt()
private const val PLATE_RED = 0xFFE8202E.toInt()
private const val SIGN_YELLOW = 0xFFFFC800.toInt()

@Composable
private fun rememberTratex(): Typeface? {
    val ctx = LocalContext.current
    return remember { ResourcesCompat.getFont(ctx, R.font.tratex_svart) }
}

@Composable
fun SpeedSign(limit: Int?, stale: Boolean) {
    val tf = rememberTratex()
    val text = limit?.toString() ?: "?"
    Canvas(Modifier.size(190.dp)) {
        val u = size.width / 100f
        drawCircle(Color(SIGN_RED), radius = 50f * u)
        drawCircle(Color(SIGN_YELLOW), radius = 38.4f * u)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            color = if (stale) android.graphics.Color.GRAY else android.graphics.Color.BLACK
            textAlign = Paint.Align.CENTER
        }
        // Skyltsiffrorna är smalare än Tratex normalbredd (scaleX 0.766).
        // Tresiffriga (100–120) görs lite lägre så de ryms i den gula plattan.
        val threeDigits = text.length >= 3
        val textSize = if (threeDigits) 48f else 56.8f
        val maxWidth = if (threeDigits) 60f else 66f
        paint.textSize = textSize * u
        paint.textScaleX = minOf(0.766f, maxWidth * u / paint.measureText(text))
        // Baslinjen läggs så att siffrornas höjd (≈0.8 em i Tratex) blir centrerad.
        drawContext.canvas.nativeCanvas.drawText(text, 50f * u, (50f + 0.4f * textSize) * u, paint)
    }
}

@Composable
fun UnderSign(text: String) {
    val tf = rememberTratex()
    Canvas(Modifier.width(190.dp).height(76.dp)) {
        val u = size.width / 100f
        drawRoundRect(Color(PLATE_RED), cornerRadius = CornerRadius(4.5f * u))
        drawRoundRect(
            Color(SIGN_YELLOW),
            topLeft = Offset(2.6f * u, 2.6f * u),
            size = Size(94.8f * u, 34.8f * u),
            cornerRadius = CornerRadius(2.6f * u)
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            color = android.graphics.Color.BLACK
            textAlign = Paint.Align.CENTER
            textSize = 24.5f * u
        }
        val w = paint.measureText(text)
        if (w > 88f * u) paint.textScaleX = 88f * u / w
        drawContext.canvas.nativeCanvas.drawText(text, 50f * u, 30.1f * u, paint)
    }
}
