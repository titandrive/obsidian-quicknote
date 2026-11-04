package local.obsidianwidget

import android.net.Uri
import android.os.Build
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.text.SpannableStringBuilder

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Shows the chosen vault and a button to pick its folder; [onPicked] runs after it's saved. */
@Composable
fun VaultCard(name: String?, onPicked: (Uri) -> Unit) {
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            Vault.set(ctx, uri)
            onPicked(uri)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Vault", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    name ?: "Not chosen yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (name == null) {
                    Text(
                        "Pick your vault's folder. With Obsidian Sync it's usually in Documents.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = { pick.launch(null) }) { Text(if (name == null) "Choose" else "Change") }
        }
    }
}

/** Renders [blocks] the way the widget will, using a real TextView so the spans match. */
@Composable
fun NotePreview(blocks: List<Block>, p: Palette, textSize: Float, typeface: android.graphics.Typeface, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { TextView(it).apply { setLineSpacing(0f, 1.15f) } },
        update = { tv ->
            val sb = SpannableStringBuilder()
            blocks.forEachIndexed { i, b ->
                if (i > 0) sb.append('\n')
                sb.append(if (b.rule) "———" else b.text)
            }
            tv.text = sb
            tv.setTextColor(p.text)
            tv.textSize = textSize
            tv.typeface = typeface
        },
        modifier = modifier,
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

val Spacing = Arrangement.spacedBy(12.dp)
