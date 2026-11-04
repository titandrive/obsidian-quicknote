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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Launcher screen: pick the vault once, then add widgets from here or the home screen. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var vaultName by remember { mutableStateOf(Vault.tree(this)?.let { Vault.name(this) }) }
            var vaultVersion by remember { mutableIntStateOf(0) }
            // Dressed in the vault's own theme once it's read, like the widget settings.
            var colors by remember { mutableStateOf<ThemeColors?>(null) }
            LaunchedEffect(vaultName, vaultVersion) {
                if (vaultName != null) colors = withContext(Dispatchers.IO) { ObsidianTheme.load(this@MainActivity, ObsidianTheme.THEME_VAULT) }
            }
            val pickVault = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    Vault.set(this, uri)
                    vaultName = Vault.name(this)
                    vaultVersion++
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

                        SectionCard("Vault", R.drawable.ic_folder) {
                            if (vaultName == null) {
                                Text(
                                    "Pick your vault's folder. With Obsidian Sync it's usually in Documents.",
                                    style = MaterialTheme.typography.bodyMedium, color = noteColor(),
                                )
                                Button(onClick = { pickVault.launch(null) }, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                                    Text("Choose vault folder")
                                }
                            } else {
                                NavRow(vaultName!!, "Tap to change folder", onClick = { pickVault.launch(null) })
                            }
                        }

                        val mgr = AppWidgetManager.getInstance(this@MainActivity)
                        if (vaultName != null && mgr.isRequestPinAppWidgetSupported) {
                            Button(
                                onClick = { mgr.requestPinAppWidget(ComponentName(this@MainActivity, NoteWidgetProvider::class.java), null, null) },
                                shape = CircleShape,
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) { Text("Add a widget to the home screen", fontWeight = FontWeight.SemiBold) }
                        }

                        SectionCard("Using the widget", R.drawable.ic_widget) {
                            for ((what, does) in listOf(
                                "Tap a line" to "Edit the note there; it saves when you tap ✓ or outside",
                                "Tap a task" to "Tick it off",
                                "Tap the note's name" to "Switch to another note",
                                "Refresh" to "Re-read the note, theme and font",
                                "Cog" to "Theme, light/dark, opacity and text size",
                            )) {
                                Column(Modifier.padding(vertical = 2.dp)) {
                                    Text(what, style = MaterialTheme.typography.bodyLarge)
                                    Text(does, style = MaterialTheme.typography.bodySmall, color = noteColor())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
