package app.mycelium.app

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import app.mycelium.app.ui.Info
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mycelium.app.ui.ButtonKind
import app.mycelium.app.ui.LocalPal
import app.mycelium.app.ui.PanelStyle
import app.mycelium.app.ui.PixelButton
import app.mycelium.app.ui.PixelDialog
import app.mycelium.app.ui.PixelPanel
import app.mycelium.app.ui.PixelSprite
import app.mycelium.app.ui.SectionHeader

@Composable
fun HelpScreen() {
    val pal = LocalPal.current
    val go = LocalGo.current
    val qs = stringArrayResource(R.array.help_q)
    val answers = stringArrayResource(R.array.help_a)
    val terms = stringArrayResource(R.array.help_terms)
    val defs = stringArrayResource(R.array.help_defs)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelSprite(R.drawable.sprite_gardener, artPixelDp = 2f)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.help_intro), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
        }
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.help_faq))
            for ((q, a) in qs.zip(answers)) {
                Text(q, style = MaterialTheme.typography.titleSmall, color = pal.ink)
                Text(a, style = MaterialTheme.typography.bodyMedium, color = pal.inkMuted)
            }
        }
        PixelButton(stringResource(R.string.nav_limits), { go(Routes.LIMITS) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.help_words))
            for ((t, d) in terms.zip(defs)) {
                Text(t, style = MaterialTheme.typography.titleSmall, color = pal.ink)
                Text(d, style = MaterialTheme.typography.bodyMedium, color = pal.inkMuted)
            }
        }
    }
}

/** Honest limits, in plain words. */
@Composable
fun LimitsScreen() {
    val pal = LocalPal.current
    val titles = stringArrayResource(R.array.limits_t)
    val bodies = stringArrayResource(R.array.limits_b)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelSprite(R.drawable.sprite_mushroom_wow, artPixelDp = 2f)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.limits_intro), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
        }
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.limits_sees))
            Text(stringResource(R.string.limits_sees_b), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
        }
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.limits_cant))
            for ((t, b) in titles.zip(bodies)) {
                Text(t, style = MaterialTheme.typography.titleSmall, color = pal.ink)
                Text(b, style = MaterialTheme.typography.bodyMedium, color = pal.inkMuted)
            }
        }
    }
}

@Composable
fun AboutScreen() {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val go = LocalGo.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0L)).versionName }
            .recoverCatching { @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }
    val perms = stringArrayResource(R.array.perm_t)
    val why = stringArrayResource(R.array.perm_b)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        PixelSprite(R.drawable.sprite_mushroom_happy, artPixelDp = 4f)
        Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.titleMedium, color = pal.ink, textAlign = TextAlign.Center)
        for ((t, b) in listOf(R.string.about_stays_t to R.string.about_stays_b, R.string.about_leaves_t to R.string.about_leaves_b)) {
            PixelPanel(Modifier.fillMaxWidth()) {
                SectionHeader(stringResource(t))
                Text(stringResource(b), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
            }
        }
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.about_perms), Info(R.string.info_perms_t, R.string.info_perms_b))
            for ((t, b) in perms.zip(why)) {
                Text(t, style = MaterialTheme.typography.titleSmall, color = pal.ink)
                Text(b, style = MaterialTheme.typography.bodyMedium, color = pal.inkMuted)
            }
        }
        PixelButton(stringResource(R.string.nav_limits), { go(Routes.LIMITS) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        PixelButton(stringResource(R.string.nav_licenses), { go(Routes.LICENSES) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        PixelPanel(Modifier.fillMaxWidth(), style = PanelStyle.Quiet) {
            SectionHeader(stringResource(R.string.about_credits_t))
            Text(stringResource(R.string.about_credits_b), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
        }
        Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
    }
}

/** Open-source parts of the app and their license texts (bundled, readable offline). */
@Composable
fun LicensesScreen() {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    var shown by remember { mutableStateOf<Pair<String, String>?>(null) }
    val names = stringArrayResource(R.array.lic_names)
    val kinds = stringArrayResource(R.array.lic_kinds)
    val files = listOf("licenses/OFL-PixelifySans.txt", "licenses/Apache-2.0.txt", "licenses/Apache-2.0.txt", "licenses/Apache-2.0.txt")
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.lic_intro), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
        for (i in names.indices) {
            PixelPanel(Modifier.fillMaxWidth()) {
                Text(names[i], style = MaterialTheme.typography.titleSmall, color = pal.ink)
                Text(kinds[i], style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
                PixelButton(stringResource(R.string.lic_read), {
                    val text = runCatching { ctx.assets.open(files[i]).bufferedReader().use { it.readText() } }.getOrNull()
                    shown = names[i] to (text ?: ctx.getString(R.string.lic_missing))
                }, kind = ButtonKind.Secondary)
            }
        }
    }
    shown?.let { (name, text) ->
        PixelDialog(name, onDismiss = { shown = null }, actions = { PixelButton(stringResource(R.string.close), { shown = null }, kind = ButtonKind.Secondary) }) {
            Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = pal.ink)
        }
    }
}
