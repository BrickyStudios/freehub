package io.github.brickystudios.freehub

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Lang.init(this)
        Counter.init(this)
        granted = hasPermissions(this)
        setContent { FreehubTheme { App() } }
        if (Prefs.updates(this) && !BuildConfig.DEBUG) lifecycleScope.launch { Updater.check(this@MainActivity, manual = false) }
    }

    override fun onResume() {
        super.onResume()
        granted = hasPermissions(this)
        if (granted && !Di2.bike.value.demo) Di2.start(this)
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
        var tab by rememberSaveable { mutableIntStateOf(0) }
        // so the statistics can name the teeth when the bike is not connected
        val known = bike.gears ?: remember(bike.link) { Prefs.lastGears(ctx) }

        LaunchedEffect(bike.link, bike.demo) {
            if (bike.link == Link.CONNECTED && !bike.demo && Prefs.background(ctx)) {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RideService::class.java))
            }
        }

        Column(Modifier.fillMaxSize().background(P.bg).statusBarsPadding()) {
            update?.let { u ->
                UpdateBar(u, onInstall = { scope.launch { Updater.install(ctx) } }, onLater = Updater::later)
            }
            // a quick crossfade: tabs change often, so no slide and no delay
            Crossfade(tab, Modifier.weight(1f), animationSpec = tween(120), label = "tab") { tab ->
                when (tab) {
                    0 -> LiveScreen(
                        bike, ride, Prefs.wheel(ctx), needsPermission = !granted && !bike.demo,
                        onAllow = { askPermissions.launch(permissions()) },
                        onBluetooth = { askBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                        onDemo = { Demo.start(lifecycleScope) },
                    )
                    1 -> StatsScreen(totals, ride, known, onReset = Counter::reset)
                    else -> BikeScreen(
                        bike, known,
                        onDemo = { on -> if (on) Demo.start(lifecycleScope) else { Demo.stop(); if (granted) Di2.start(ctx) } },
                        onCheckUpdate = { scope.launch { Updater.check(ctx, manual = true) } },
                        updateNote = note,
                    )
                }
            }
            NavBar(tab) { tab = it }
        }
    }

    companion object {
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

@Composable
private fun NavBar(tab: Int, onTab: (Int) -> Unit) {
    val items = listOf(tr("Live", "Live"), tr("Statistik", "Stats"), tr("Rad", "Bike"))
    Column(Modifier.fillMaxWidth().background(P.bg).navigationBarsPadding()) {
        Divider()
        // one pink marker that slides to the chosen tab instead of three that blink
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val third = maxWidth / items.size
            val x by animateDpAsState(third * tab + (third - 28.dp) / 2, tween(200), label = "tabmark")
            Box(Modifier.offset(x = x).width(28.dp).height(3.dp).background(P.accent))
        }
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, label ->
                val on = i == tab
                Column(
                    Modifier.weight(1f).heightIn(min = 60.dp)
                        .semantics { selected = on }
                        .clickable(role = Role.Tab) { onTab(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Label(label, color = if (on) P.ink else P.faint)
                }
            }
        }
    }
}

@Composable
private fun UpdateBar(u: UpdateState, onInstall: () -> Unit, onLater: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(P.raised).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
