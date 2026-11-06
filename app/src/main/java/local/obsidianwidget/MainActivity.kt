package local.obsidianwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the app's icon opens: the widgets on the home screen, each to its settings, a button to add
 * another, and the vault new widgets start with.
 */
class MainActivity : ComponentActivity() {
    /** The widgets placed and set up, by id; read again whenever the screen comes back. */
    private var widgets by mutableStateOf<List<Pair<Int, WidgetConfig>>>(emptyList())

    override fun onResume() {
        super.onResume()
        widgets = NoteWidgetProvider.allIds(this).toList().mapNotNull { id -> WidgetStore.load(this, id)?.let { id to it } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var vault by remember { mutableStateOf(Vault.defaultTree(this)) }
            // Dressed in the vault's own theme once it's read, like the widget settings.
            var colors by remember { mutableStateOf<ThemeColors?>(null) }
            LaunchedEffect(vault) {
                val tree = vault ?: return@LaunchedEffect
                colors = withContext(Dispatchers.IO) { ObsidianTheme.load(this@MainActivity, tree, ObsidianTheme.THEME_VAULT) }
            }
            val pickVault = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    Vault.setDefault(this, uri, Vault.take(this, uri))
                    vault = uri
                }
            }
            val look = colors?.let { WidgetConfig("", "", colors = it) }
            AppTheme(palette = look?.palette(this), font = remember(look?.fontFile) { Fonts.compose(look?.fontFile) }) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(pad)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(Modifier.statusBarsPadding().padding(start = 4.dp, top = 16.dp, bottom = 4.dp)) {
                            Text(getString(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        }

                        SectionCard("Widgets", R.drawable.ic_widget) {
                            if (widgets.isEmpty()) {
                                Text(
                                    "None on the home screen yet.",
                                    style = MaterialTheme.typography.bodyMedium, color = noteColor(),
                                )
                            }
                            widgets.forEachIndexed { i, (id, cfg) ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                NavRow(
                                    title = cfg.title.ifEmpty { "No note chosen" },
                                    subtitle = if (cfg.swaps) "Swaps with " + cfg.swapNotes.filter { it.uri != cfg.noteUri }.joinToString(", ") { it.title }
                                    else Vault.displayPath(cfg.vaultTree),
                                    leading = { IconTile(R.drawable.ic_note, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    onClick = { startActivity(NoteWidgetProvider.configIntent(this@MainActivity, id, pickOnly = false)) },
                                )
                            }
                        }

                        val mgr = AppWidgetManager.getInstance(this@MainActivity)
                        if (mgr.isRequestPinAppWidgetSupported) {
                            Button(
                                onClick = { mgr.requestPinAppWidget(ComponentName(this@MainActivity, NoteWidgetProvider::class.java), null, null) },
                                shape = CircleShape,
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) { Text("Add a widget", fontWeight = FontWeight.SemiBold) }
                        }

                        SectionCard("Default vault", R.drawable.ic_folder) {
                            NavRow(
                                title = vault?.let(Vault::displayPath) ?: "Choose your vault folder",
                                subtitle = "New widgets start with this vault",
                                onClick = { pickVault.launch(null) },
                            )
                        }
                    }
                }
            }
        }
    }
}
