package app.mycelium.app

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
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
    val terms = stringArrayResource(R.array.help_terms)
    val defs = stringArrayResource(R.array.help_defs)
    val qs = stringArrayResource(R.array.help_howto_q)
    val answers = stringArrayResource(R.array.help_howto_a)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.help_intro), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.help_howto))
            for ((q, a) in qs.zip(answers)) {
                Text(q, style = MaterialTheme.typography.titleSmall, color = pal.ink)
                Text(a, style = MaterialTheme.typography.bodyMedium, color = pal.inkMuted)
            }
        }
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.help_words))
            for ((t, d) in terms.zip(defs)) {
                Text(t, style = MaterialTheme.typography.titleSmall, color = pal.ink)
                Text(d, style = MaterialTheme.typography.bodyMedium, color = pal.inkMuted)
            }
        }
    }
}

@Composable
fun AboutScreen() {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    var license by remember { mutableStateOf<String?>(null) }
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0L)).versionName }
            .recoverCatching { @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        PixelSprite(R.drawable.sprite_mushroom_happy, artPixelDp = 4f)
        Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.titleMedium, color = pal.ink, textAlign = TextAlign.Center)
        for ((t, b) in listOf(R.string.about_stays_t to R.string.about_stays_b, R.string.about_leaves_t to R.string.about_leaves_b,
            R.string.about_limits_t to R.string.about_limits_b, R.string.about_credits_t to R.string.about_credits_b)) {
            PixelPanel(Modifier.fillMaxWidth(), style = if (t == R.string.about_leaves_t) PanelStyle.Paper else PanelStyle.Quiet) {
                SectionHeader(stringResource(t))
                Text(stringResource(b), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
            }
        }
        PixelButton(stringResource(R.string.about_license), {
            license = runCatching { ctx.assets.open("licenses/OFL-PixelifySans.txt").bufferedReader().use { it.readText() } }.getOrNull()
        }, kind = ButtonKind.Secondary)
        Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
    }
    license?.let { text ->
        PixelDialog(stringResource(R.string.about_license), onDismiss = { license = null },
            actions = { PixelButton(stringResource(R.string.close), { license = null }, kind = ButtonKind.Secondary) }) {
            Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = pal.ink)
        }
    }
}
