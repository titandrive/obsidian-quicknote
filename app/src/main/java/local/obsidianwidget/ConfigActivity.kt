package local.obsidianwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sets up a widget: which note, theme, light/dark, background opacity and text size. */
class ConfigActivity : ComponentActivity() {
    companion object {
        /** Opened by the widget's change-note button: just the note list, saved on tap. */
        const val EXTRA_PICK_ONLY = "pick_only"
    }

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        // Backing out of the first setup removes the widget again.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))

        val existing = WidgetStore.load(this, widgetId)
        val pickOnly = intent.getBooleanExtra(EXTRA_PICK_ONLY, false) && existing != null
        enableEdgeToEdge()
        setContent { AppTheme { Screen(existing, pickOnly) } }
    }

    private suspend fun save(cfg: WidgetConfig) {
        val full = if (cfg.colors != null) cfg
        else cfg.copy(colors = withContext(Dispatchers.IO) { ObsidianTheme.load(this@ConfigActivity, cfg.theme) })
        WidgetStore.save(this, widgetId, full)
        val mgr = AppWidgetManager.getInstance(this)
        NoteWidgetProvider.update(this, mgr, widgetId)
        mgr.notifyAppWidgetViewDataChanged(widgetId, R.id.list)
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen(existing: WidgetConfig?, pickOnly: Boolean) {
        val scope = rememberCoroutineScope()
        var vaultName by remember { mutableStateOf(Vault.tree(this)?.let { Vault.name(this) }) }
        var vaultVersion by remember { mutableIntStateOf(0) }
        var notes by remember { mutableStateOf<List<Note>?>(null) }
        var themes by remember { mutableStateOf<List<String>>(emptyList()) }
        var vaultTheme by remember { mutableStateOf("") }

        var notePath by remember { mutableStateOf(existing?.notePath) }
        var noteUri by remember { mutableStateOf(existing?.noteUri) }
        var theme by remember { mutableStateOf(existing?.theme ?: ObsidianTheme.THEME_VAULT) }
        var mode by remember { mutableStateOf(existing?.mode ?: "vault") }
        var opacity by remember { mutableFloatStateOf((existing?.opacity ?: 100).toFloat()) }
        var textSize by remember { mutableFloatStateOf(existing?.textSize ?: 14f) }
        var colors by remember { mutableStateOf(existing?.colors) }
        var noteText by remember { mutableStateOf<String?>(null) }
        var picking by remember { mutableStateOf(pickOnly) }

        LaunchedEffect(vaultName, vaultVersion) {
            if (vaultName == null) return@LaunchedEffect
            notes = null
            withContext(Dispatchers.IO) {
                themes = Vault.themes(this@ConfigActivity)
                vaultTheme = Vault.appearance(this@ConfigActivity)?.optString("cssTheme").orEmpty()
                notes = Vault.notes(this@ConfigActivity).sortedByDescending { it.modified }
            }
            if (notePath == null) picking = true
        }
        LaunchedEffect(theme, vaultName) {
            if (vaultName != null) colors = withContext(Dispatchers.IO) { ObsidianTheme.load(this@ConfigActivity, theme) }
        }
        LaunchedEffect(noteUri) {
            noteText = noteUri?.let { u -> withContext(Dispatchers.IO) { Vault.read(this@ConfigActivity, android.net.Uri.parse(u)) } }
        }

        fun current() = WidgetConfig(
            noteUri.orEmpty(), notePath.orEmpty(), theme, mode, opacity.toInt(), textSize, colors,
        )

        if (picking) {
            BackHandler { if (pickOnly) finish() else picking = false }
            NotePicker(notes, onBack = { if (pickOnly) finish() else picking = false }) { note ->
                notePath = note.path
                noteUri = note.uri.toString()
                picking = false
                if (pickOnly) scope.launch { save(current()) }
            }
            return
        }

        Scaffold(topBar = { TopAppBar(title = { Text(if (existing == null) "New note widget" else "Widget settings") }) }) { pad ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Spacing,
            ) {
                VaultCard(vaultName) {
                    vaultName = Vault.name(this@ConfigActivity)
                    vaultVersion++
                    notePath = null
                    noteUri = null
                }
                if (vaultName == null) return@Column

                Card(Modifier.fillMaxWidth().clickable { picking = true }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Note", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(notePath?.removeSuffix(".md") ?: "None chosen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(onClick = { picking = true }) { Text("Choose") }
                    }
                }

                SectionLabel("Preview")
                Preview(current(), noteText)

                SectionLabel("Theme")
                ThemeMenu(theme, themes, vaultTheme) { theme = it }

                SectionLabel("Light or dark")
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    for ((key, label) in listOf("vault" to "Like vault", "system" to "System", "dark" to "Dark", "light" to "Light")) {
                        FilterChip(selected = mode == key, onClick = { mode = key }, label = { Text(label) })
                    }
                }

                SectionLabel("Background opacity: ${opacity.toInt()}%")
                Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0f..100f)

                SectionLabel("Text size: ${"%.0f".format(textSize)}sp")
                Slider(value = textSize, onValueChange = { textSize = it }, valueRange = 10f..22f, steps = 11)

                Button(
                    onClick = { scope.launch { save(current()) } },
                    enabled = notePath != null && colors != null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(if (existing == null) "Add widget" else "Save") }
            }
        }
    }

    @Composable
    private fun Preview(cfg: WidgetConfig, text: String?) {
        val p = cfg.palette(this)
        val indent = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, cfg.textSize * 1.2f, resources.displayMetrics).toInt()
        val blocks = remember(text, p, indent) {
            Markdown.render(text ?: "# Pick a note\nIt will show up here.", p, indent).take(40)
        }
        // A stand-in wallpaper so the opacity setting is visible.
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF3A6EA5), Color(0xFFE07A5F), Color(0xFF81B29A))))
                .padding(14.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(p.background).copy(alpha = cfg.opacity / 100f))
                    .padding(14.dp)
                    .heightIn(max = 260.dp),
            ) {
                Text(cfg.title.ifEmpty { "Note" }, color = Color(p.title), fontWeight = FontWeight.Bold, fontFamily = Fonts.compose(cfg.fontFile))
                NotePreview(blocks, p, cfg.textSize, Fonts.typeface(cfg.fontFile), Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }

    @Composable
    private fun ThemeMenu(theme: String, themes: List<String>, vaultTheme: String, onPick: (String) -> Unit) {
        var open by remember { mutableStateOf(false) }
        fun label(t: String) = when (t) {
            ObsidianTheme.THEME_VAULT -> "Same as vault (${vaultTheme.ifEmpty { "Default" }})"
            ObsidianTheme.THEME_DEFAULT -> "Obsidian default"
            else -> t
        }
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(label(theme), Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (t in listOf(ObsidianTheme.THEME_VAULT, ObsidianTheme.THEME_DEFAULT) + themes) {
                    DropdownMenuItem(text = { Text(label(t)) }, onClick = { onPick(t); open = false })
                }
            }
        }
        if (themes.isEmpty()) {
            Text(
                "No community themes found in this vault's .obsidian/themes folder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NotePicker(notes: List<Note>?, onBack: () -> Unit, onPick: (Note) -> Unit) {
        var query by remember { mutableStateOf("") }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Choose a note") },
                    navigationIcon = {
                        @Suppress("DEPRECATION")
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                    },
                )
            },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Search notes") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (notes == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    return@Column
                }
                val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
                val shown = notes.filter { n -> words.all { n.path.lowercase().contains(it) } }
                if (shown.isEmpty()) {
                    Text(
                        if (notes.isEmpty()) "No notes found in this vault." else "No matches.",
                        Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(shown, key = { it.path }) { n ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(n) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            Text(n.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val folder = n.path.substringBeforeLast('/', "")
                            if (folder.isNotEmpty()) {
                                Text(folder, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
