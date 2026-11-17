package io.github.brickystudios.freehub

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var granted by mutableStateOf(false)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPermissions(this)
        if (granted) Di2.start(this)
    }
    private val askBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { Di2.start(this) }
    private var autoStart by mutableStateOf(false)
    private var onboarded by mutableStateOf(true)
    private var loc by mutableStateOf(Loc.NONE)
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loc = Gps.access(this) }
    private val askAlways = registerForActivityResult(ActivityResultContracts.RequestPermission()) { loc = Gps.access(this) }
    private val pickDerailleur = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (canAutoStart && r.resultCode == RESULT_OK) autoStart = AutoStart.picked(this, r.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Lang.init(this)
        Garage.init(this)
        // Strava is gone since 0.5: drop the stored client secret and tokens instead of leaving them behind
        deleteSharedPreferences("strava")
        DevLog.on = Prefs.dev(this)
        granted = hasPermissions(this)
        if (canAutoStart) autoStart = AutoStart.on(this)
        onboarded = Prefs.onboarded(this)
        setContent { FreehubTheme { App() } }
        if (Prefs.updates(this) && !BuildConfig.DEBUG) lifecycleScope.launch { Updater.check(this@MainActivity, manual = false) }
    }

    override fun onResume() {
        super.onResume()
        granted = hasPermissions(this)
        if (granted && !Di2.bike.value.demo) Di2.start(this)
        loc = Gps.access(this)
    }

    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { visible = false; super.onStop() }

    /** First "while in use", then (Android 10+) "all the time", which Android only grants as a second step. */
    private fun requestLocation() {
        if (loc == Loc.NONE) askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        else if (Build.VERSION.SDK_INT >= 29) askAlways.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun switchAutoStart(on: Boolean) {
        if (!canAutoStart) return
        if (!on) { AutoStart.off(this); autoStart = false; return }
        AutoStart.pick(
            this,
            onPicker = { pickDerailleur.launch(IntentSenderRequest.Builder(it).build()) },
            onFail = { autoStart = false },
        )
    }

    @Composable
    private fun App() {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val bike by Di2.bike.collectAsStateWithLifecycle()
        val ride by Counter.now.collectAsStateWithLifecycle()
        val totals by Counter.totals.collectAsStateWithLifecycle()
        val update by Updater.state.collectAsStateWithLifecycle()
        val note by Updater.note.collectAsStateWithLifecycle()
        val power by Power.log.collectAsStateWithLifecycle()
        val bikes by Garage.bikes.collectAsStateWithLifecycle()
        val active by Garage.active.collectAsStateWithLifecycle()
        val profile = bikes.firstOrNull { it.address == active }
        val onPaired: (Profile) -> Unit = { if (granted) Di2.start(ctx) }
        val onRemove: (Profile) -> Unit = { p ->
            Garage.remove(p.address)
            if (canAutoStart) AutoStart.forget(ctx, p.address)
            Di2.forget(ctx, p.address)
        }
        // the tabs sit side by side: swipe left or right, or tap the bar
        val pager = rememberPagerState { 3 }
        val tab = pager.currentPage
        val still = reducedMotion()
        // the bike's GPS distance in km, the ride in progress included; null before the first metre
        val km = ((totals.meters + (ride?.meters ?: 0.0)) / 1000).takeIf { it > 0 }
        // so the statistics can name the teeth when the bike is not connected
        val known = bike.gears ?: remember(bike.link, tab) { Prefs.lastGears(ctx) }

        LaunchedEffect(bike.link, bike.demo) {
            if (bike.link == Link.CONNECTED && !bike.demo && Prefs.background(ctx)) {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RideService::class.java))
            }
        }

        if (!onboarded) {
            Onboarding(
                bike, known, granted, autoStart.takeIf { canAutoStart }, loc,
                onAllow = { askPermissions.launch(permissions()) },
                onBluetooth = { askBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                onAutoStart = ::switchAutoStart,
                onLocation = ::requestLocation,
                paired = bikes.isNotEmpty(),
                onPaired = onPaired,
                onDone = { Prefs.setOnboarded(ctx, true); onboarded = true },
            )
            return
        }

        // the screens draw into a layer the floating glass reads from; the glass itself stays outside it
        val backdrop = rememberLayerBackdrop()
        Box(Modifier.fillMaxSize().background(P.bg)) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                Ambient()
                HorizontalPager(pager, Modifier.fillMaxSize().statusBarsPadding(), beyondViewportPageCount = 1) { page ->
                    when (page) {
                        0 -> LiveScreen(
                            bike, profile, bikes.isNotEmpty(), ride, Prefs.wheel(ctx), km, power, needsPermission = !granted && !bike.demo,
                            onAllow = { askPermissions.launch(permissions()) },
                            onBluetooth = { askBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                            onDemo = { Demo.start(lifecycleScope) },
                            onPaired = onPaired,
                        )
                        1 -> StatsScreen(totals, ride, known, km, profile?.name.takeIf { !bike.demo }, onReset = Counter::reset)
                        else -> BikeScreen(
                            bike, known, km, power, loc, onLocation = ::requestLocation,
                            autoStart = autoStart.takeIf { canAutoStart && granted },
                            onAutoStart = ::switchAutoStart,
                            onDemo = { on -> if (on) Demo.start(lifecycleScope) else { Demo.stop(); if (granted) Di2.start(ctx) } },
                            onCheckUpdate = { scope.launch { Updater.check(ctx, manual = true) } },
                            updateNote = note,
                            onSetup = { Prefs.setOnboarded(ctx, false); onboarded = false },
                            profile = profile, bikes = bikes, onPaired = onPaired, onRemove = onRemove,
                        )
                    }
                }
                // scroll edge effect: content fades out before it reaches the floating tabs, so they stay readable
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().height(120.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, P.bg.copy(alpha = 0.92f)))),
                )
            }
            CompositionLocalProvider(LocalBackdrop provides backdrop) {
                update?.let { u ->
                    UpdateBar(
                        u, onInstall = { scope.launch { Updater.install(ctx) } }, onLater = Updater::later,
                        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                NavBar(
                    { pager.currentPage + pager.currentPageOffsetFraction }, tab,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 40.dp, end = 40.dp, bottom = 12.dp),
                ) { scope.launch { if (still) pager.scrollToPage(it) else pager.animateScrollToPage(it) } }
            }
        }
    }

    companion object {
        /** the ride service leaves the link alone while the app is on screen */
        var visible = false
            private set

        fun permissions(): Array<String> = buildList {
            if (Build.VERSION.SDK_INT >= 31) addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
            else add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

        /** Notifications are optional; Bluetooth is not. */
        fun hasPermissions(c: Context) = permissions().filter { it != Manifest.permission.POST_NOTIFICATIONS }
            .all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }
    }
}

/** A floating glass capsule; the tinted pill follows the pages 1:1 while you swipe. */
@Composable
private fun NavBar(position: () -> Float, tab: Int, modifier: Modifier, onTab: (Int) -> Unit) {
    val items = listOf(tr("Live", "Live"), tr("Statistik", "Stats"), tr("Rad", "Bike"))
    BoxWithConstraints(modifier.fillMaxWidth().height(60.dp).glass()) {
        val third = maxWidth / items.size
        Box(Modifier.offset { IntOffset((third * position()).roundToPx(), 0) }.width(third).fillMaxHeight().padding(5.dp).background(P.accent.copy(alpha = 0.16f), Pill))
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, label ->
                val on = i == tab
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .semantics { selected = on }
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onTab(i) },
                    contentAlignment = Alignment.Center,
                ) { Label(label, color = if (on) P.accent else P.muted) }
            }
        }
    }
}

/** A soft glow behind everything, so the glass has light to bend: top right, and low on the left under the tabs. */
@Composable
private fun Ambient() {
    val a = P.accent
    val dark = isSystemInDarkTheme()
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.radialGradient(listOf(a.copy(alpha = if (dark) 0.16f else 0.10f), Color.Transparent), Offset(size.width * 0.9f, 0f), size.width * 0.9f))
        drawRect(Brush.radialGradient(listOf(a.copy(alpha = if (dark) 0.10f else 0.07f), Color.Transparent), Offset(0f, size.height), size.width))
    }
}

@Composable
private fun UpdateBar(u: UpdateState, onInstall: () -> Unit, onLater: () -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth().glass(Card).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BodyText(tr("Version ${u.release.version} ist da", "Version ${u.release.version} is out"), weight = FontWeight.SemiBold, size = 15.sp)
            val p = u.progress
            BodyText(
                when {
                    u.failed -> tr("Download fehlgeschlagen", "Download failed")
                    p != null -> tr("Lädt … ${(p * 100).toInt()} %", "Downloading … ${(p * 100).toInt()} %")
                    else -> "%.1f MB".fmt(u.release.bytes / 1_000_000f)
                },
                color = P.muted, size = 13.sp,
            )
        }
        if (u.progress == null) {
            BodyText(tr("Später", "Later"), Modifier.clickable(onClick = onLater).padding(12.dp), color = P.muted, weight = FontWeight.Medium)
            BodyText(tr("Installieren", "Install"), Modifier.clickable(onClick = onInstall).padding(12.dp), color = P.accent, weight = FontWeight.SemiBold)
        }
    }
}
