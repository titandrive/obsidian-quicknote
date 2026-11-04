package local.obsidianwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Launcher screen: pick the vault once, then add widgets from here or the home screen. */
class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                var vaultName by remember { mutableStateOf(Vault.tree(this)?.let { Vault.name(this) }) }
                Scaffold(topBar = { TopAppBar(title = { Text(getString(R.string.app_name)) }) }) { pad ->
                    Column(
                        Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Spacing,
                    ) {
                        VaultCard(vaultName) { vaultName = Vault.name(this@MainActivity) }
                        val mgr = AppWidgetManager.getInstance(this@MainActivity)
                        if (mgr.isRequestPinAppWidgetSupported) {
                            Button(
                                onClick = { mgr.requestPinAppWidget(ComponentName(this@MainActivity, NoteWidgetProvider::class.java), null, null) },
                                enabled = vaultName != null,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Add a widget to the home screen") }
                        }
                        Text(
                            """
                            You can also long-press the home screen, open Widgets and drag out "Obsidian Widget".

                            On the widget:
                            • Tap the note to edit it right there; it saves when you tap ✓ or outside the card.
                            • Tap a task to tick it off.
                            • Tap the note's name to switch to another note.
                            • ⟳ re-reads the note and theme, ✎ edits, ↗ opens it in Obsidian.
                            • The cog has theme, light/dark, opacity and text size.
                            """.trimIndent(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
