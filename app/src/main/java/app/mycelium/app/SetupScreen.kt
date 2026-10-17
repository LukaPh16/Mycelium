package app.mycelium.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mycelium.app.ui.ButtonKind
import app.mycelium.app.ui.Info
import app.mycelium.app.ui.InfoButton
import app.mycelium.app.ui.LocalPal
import app.mycelium.app.ui.PanelStyle
import app.mycelium.app.ui.PixelButton
import app.mycelium.app.ui.PixelPanel
import app.mycelium.app.ui.PixelSprite
import app.mycelium.app.ui.Protection
import kotlinx.coroutines.delay

private enum class Check { Good, Bad, Manual }

/** A checklist of what helps Mycelium see clearly and keep running, each with a one-tap fix. Re-checks every 2 s. */
@Composable
fun SetupScreen(protection: Protection, start: () -> Unit) {
    val ctx = LocalContext.current
    val go = LocalGo.current
    var tick by remember { mutableIntStateOf(0) }
    var watched by remember { mutableIntStateOf(0) }
    var usage by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            watched = Api.io("watched").optJSONArray("apps")?.length() ?: 0
            usage = Api.io("usage_access").optBoolean("granted")
            tick++
            delay(2000)
        }
    }
    val notif = remember(tick) { Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }
    val privateDns = remember(tick) { strictPrivateDns(ctx) }
    val battery = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val xiaomi = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")
    val running = protection != Protection.Paused
    val allGood = running && watched > 0 && usage && notif && privateDns == null && battery
    val pal = LocalPal.current

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelSprite(if (allGood) R.drawable.sprite_mushroom_happy else R.drawable.sprite_mushroom_wow, artPixelDp = 2f)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(if (allGood) R.string.setup_all_good else R.string.setup_intro), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
        }
        Item(stringResource(R.string.setup_vpn_t), if (running) Check.Good else Check.Bad,
            stringResource(if (running) R.string.setup_vpn_ok else R.string.setup_vpn_bad), stringResource(R.string.prot_start), onAction = start)
        Item(stringResource(R.string.setup_apps_t), if (watched > 0) Check.Good else Check.Bad,
            if (watched > 0) stringResource(R.string.setup_apps_ok, pluralStringResource(R.plurals.watching_apps, watched, watched))
            else stringResource(R.string.setup_apps_bad), stringResource(R.string.home_choose)) { go(Routes.APPS) }
        Item(stringResource(R.string.setup_usage_t), if (usage) Check.Good else Check.Bad,
            stringResource(if (usage) R.string.setup_usage_ok else R.string.setup_usage_bad), stringResource(R.string.setup_fix)) {
            Api.call("usage_access_settings")
        }
        Item(stringResource(R.string.setup_notif_t), if (notif) Check.Good else Check.Bad,
            stringResource(if (notif) R.string.setup_notif_ok else R.string.setup_notif_bad), stringResource(R.string.setup_fix)) {
            open(ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }
        Item(stringResource(R.string.setup_dns_t), if (privateDns == null) Check.Good else Check.Bad,
            if (privateDns == null) stringResource(R.string.setup_dns_ok) else stringResource(R.string.setup_dns_bad, privateDns),
            stringResource(R.string.setup_fix), info = Info(R.string.info_dns_t, R.string.info_dns_b)) {
            open(ctx, Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
        Item(stringResource(R.string.setup_battery_t), if (battery) Check.Good else Check.Bad,
            stringResource(if (battery) R.string.setup_battery_ok else R.string.setup_battery_bad), stringResource(R.string.setup_fix)) {
            open(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        Item(stringResource(R.string.setup_chrome_t), Check.Manual, stringResource(R.string.setup_chrome_body), null) {}
        if (xiaomi) Item(stringResource(R.string.setup_xiaomi_t), Check.Manual, stringResource(R.string.setup_xiaomi_body),
            stringResource(R.string.setup_open_app_info)) {
            open(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        }
    }
}

@Composable
private fun Item(title: String, check: Check, body: String, action: String?, info: Info? = null, onAction: () -> Unit) {
    val pal = LocalPal.current
    PixelPanel(Modifier.fillMaxWidth(), style = if (check == Check.Bad) PanelStyle.Paper else PanelStyle.Quiet) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mark(check, when (check) { Check.Good -> pal.leaf; Check.Bad -> pal.berry; Check.Manual -> pal.gold })
            Spacer(Modifier.width(10.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = pal.ink)
            if (info != null) InfoButton(info)
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = pal.inkMuted)
        if (check == Check.Bad && action != null) PixelButton(action, onAction, Modifier.fillMaxWidth())
        if (check == Check.Manual && action != null) PixelButton(action, onAction, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
    }
}

/** A pixel check mark, cross or question mark. */
@Composable
private fun Mark(check: Check, color: Color) {
    val rows = when (check) {
        Check.Good -> listOf(".......", "......X", ".....XX", "X...XX.", "XX.XX..", ".XXX...", "..X....")
        Check.Bad -> listOf("X.....X", "XX...XX", ".XX.XX.", "..XXX..", ".XX.XX.", "XX...XX", "X.....X")
        Check.Manual -> listOf(".XXXXX.", "XX...XX", "....XX.", "...XX..", "...XX..", ".......", "...XX..")
    }
    Canvas(Modifier.size(21.dp)) {
        val c = (size.width / 7).toInt().toFloat()
        for ((y, r) in rows.withIndex()) for ((x, ch) in r.withIndex()) if (ch == 'X') drawRect(color, Offset(x * c, y * c), Size(c, c))
    }
}

/** The Private DNS hostname when the phone forces one ("strict" mode), else null. Our app is not inside the VPN. */
private fun strictPrivateDns(ctx: Context): String? {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
    return lp.privateDnsServerName
}

private fun open(ctx: Context, intent: Intent) {
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
