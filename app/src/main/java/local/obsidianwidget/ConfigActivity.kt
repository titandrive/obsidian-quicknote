package local.obsidianwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sets up a widget: which note, theme, light/dark, background opacity and text size. */
class ConfigActivity : ComponentActivity() {
    companion object {
        /** Opened by tapping the widget's note name: just the note list, saved on tap. */
        const val EXTRA_PICK_ONLY = "pick_only"

        private val MODES = listOf("vault", "system", "light", "dark")
        private val OPACITIES = listOf(0, 25, 50, 70, 85, 100)
        private val TEXT_SIZES = listOf(12f, 13f, 14f, 16f, 18f)
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
        setContent { Screen(existing, pickOnly) }
    }

    private suspend fun save(cfg: WidgetConfig) {
        val full = if (cfg.colors != null) cfg
        else cfg.copy(colors = withContext(Dispatchers.IO) { ObsidianTheme.load(this@ConfigActivity, cfg.theme) })
        WidgetStore.save(this, widgetId, full)
        withContext(Dispatchers.IO) { NoteWidgetProvider.update(this@ConfigActivity, AppWidgetManager.getInstance(this@ConfigActivity), widgetId) }
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    @Composable
    private fun Screen(existing: WidgetConfig?, pickOnly: Boolean) {
        val scope = rememberCoroutineScope()
        var vaultName by remember { mutableStateOf(Vault.tree(this)?.let { Vault.name(this) }) }
        var vaultVersion by remember { mutableIntStateOf(0) }
        var notes by remember { mutableStateOf<List<Note>?>(null) }
        var themes by remember { mutableStateOf<List<String>>(emptyList()) }
        var vaultTheme by remember { mutableStateOf("") }
        // Each theme's colours, filled in as they're worked out, for the swatches in the theme sheet.
        val swatches = remember { mutableStateMapOf<String, ThemeColors>() }

        var notePath by remember { mutableStateOf(existing?.notePath) }
        var noteUri by remember { mutableStateOf(existing?.noteUri) }
        var theme by remember { mutableStateOf(existing?.theme ?: ObsidianTheme.THEME_VAULT) }
        var mode by remember { mutableStateOf(existing?.mode ?: "vault") }
        var opacity by remember { mutableIntStateOf(existing?.opacity ?: 100) }
        var textSize by remember { mutableStateOf(existing?.textSize ?: 14f) }
        var colors by remember { mutableStateOf(existing?.colors) }
        var noteText by remember { mutableStateOf<String?>(null) }
        var picking by remember { mutableStateOf(pickOnly) }
        var pickingTheme by remember { mutableStateOf(false) }

        val pickVault = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                Vault.set(this, uri)
                vaultName = Vault.name(this)
                vaultVersion++
                notePath = null
                noteUri = null
            }
        }

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
            colors?.let { swatches[theme] = it }
        }
        LaunchedEffect(pickingTheme) {
            if (!pickingTheme) return@LaunchedEffect
            for (t in listOf(ObsidianTheme.THEME_VAULT, ObsidianTheme.THEME_DEFAULT) + themes) {
                if (t !in swatches) swatches[t] = withContext(Dispatchers.IO) { ObsidianTheme.load(this@ConfigActivity, t) }
            }
        }
        LaunchedEffect(noteUri) {
            noteText = noteUri?.let { u -> withContext(Dispatchers.IO) { Vault.read(this@ConfigActivity, android.net.Uri.parse(u)) } }
        }

        fun current() = WidgetConfig(noteUri.orEmpty(), notePath.orEmpty(), theme, mode, opacity, textSize, colors)
        val cfg = current()

        // The screen wears the widget's own theme and font, so changes show on it as they're made.
        AppTheme(palette = colors?.let { cfg.palette(this) }, font = remember(cfg.fontFile) { Fonts.compose(cfg.fontFile) }) {
            if (picking) {
                BackHandler { if (pickOnly) finish() else picking = false }
                NotePicker(notes, noteUri, onBack = { if (pickOnly) finish() else picking = false }) { note ->
                    notePath = note.path
                    noteUri = note.uri.toString()
                    picking = false
                    if (pickOnly) scope.launch { save(current()) }
                }
                return@AppTheme
            }

            fun themeName(t: String) = when (t) {
                ObsidianTheme.THEME_VAULT -> "Same as Obsidian"
                ObsidianTheme.THEME_DEFAULT -> "Obsidian default"
                else -> t
            }

            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = { PageHeader(if (existing == null) "New widget" else "Widget settings", onBack = { finish() }) },
                bottomBar = {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        val tick = rememberHaptic()
                        Button(
                            onClick = { tick(); scope.launch { save(current()) } },
                            enabled = notePath != null && colors != null,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                disabledContentColor = noteColor(),
                            ),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Text(if (existing == null) "Add widget" else "Save", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
            ) { pad ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(pad)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (vaultName == null) {
                        SectionCard("Vault", R.drawable.ic_folder) {
                            Text(
                                "Pick your vault's folder. With Obsidian Sync it's usually in Documents.",
                                style = MaterialTheme.typography.bodyMedium, color = noteColor(),
                            )
                            Button(onClick = { pickVault.launch(null) }, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                                Text("Choose vault folder")
                            }
                        }
                        return@Column
                    }

                    Preview(cfg, noteText)

                    SectionCard("Note", R.drawable.ic_note) {
                        NavRow(
                            title = notePath?.substringAfterLast('/')?.removeSuffix(".md") ?: "Choose a note",
                            subtitle = notePath?.substringBeforeLast('/', "")?.ifEmpty { "Vault root" } ?: "Nothing picked yet",
                            onClick = { picking = true },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        NavRow(
                            title = vaultName!!,
                            subtitle = "Vault folder",
                            leading = { IconTile(R.drawable.ic_folder, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            onClick = { pickVault.launch(null) },
                        )
                    }

                    SectionCard("Appearance", R.drawable.ic_palette) {
                        NavRow(
                            title = themeName(theme),
                            subtitle = if (theme == ObsidianTheme.THEME_VAULT) vaultTheme.ifEmpty { "Default" } else "Theme",
                            leading = { ThemeSwatch(swatches[theme]?.let { if (cfg.isDark(this@ConfigActivity)) it.dark else it.light }) },
                            onClick = { pickingTheme = true },
                        )
                        SettingLabel("Light or dark")
                        PillSegmented(MODES, mode, { if (it == "vault") "Obsidian" else it.replaceFirstChar(Char::uppercase) }) { mode = it }
                        SettingLabel("Background", "How much of the wallpaper shows through")
                        PillSegmented(OPACITIES, opacity, { "$it%" }) { opacity = it }
                        SettingLabel("Text size")
                        PillSegmented(TEXT_SIZES, textSize, { "${it.toInt()}" }) { textSize = it }
                    }

                    Spacer(Modifier.height(8.dp))
                }
            }

            if (pickingTheme) {
                PickerSheet(
                    title = "Theme",
                    options = listOf(ObsidianTheme.THEME_VAULT, ObsidianTheme.THEME_DEFAULT) + themes,
                    selected = theme,
                    name = ::themeName,
                    detail = { if (it == ObsidianTheme.THEME_VAULT) vaultTheme.ifEmpty { "Default" } else null },
                    leading = { t -> ThemeSwatch(swatches[t]?.let { if (cfg.isDark(this@ConfigActivity)) it.dark else it.light }) },
                    onDismiss = { pickingTheme = false },
                ) {
                    theme = it
                    pickingTheme = false
                }
            }
        }
    }

    /** The widget as it will look, on a stand-in wallpaper so the opacity shows. */
    @Composable
    private fun Preview(cfg: WidgetConfig, text: String?) {
        val p = cfg.palette(this)
        val indent = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, cfg.textSize * 1.2f, resources.displayMetrics).toInt()
        val blocks = remember(text, p, indent) {
            Markdown.render(text ?: "# Pick a note\nIt will show up here.", p, indent).take(40)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF3A6EA5), Color(0xFF8E6BBF), Color(0xFFE07A5F))))
                .padding(18.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(p.background).copy(alpha = cfg.opacity / 100f))
                    .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 12.dp)
                    .heightIn(max = 230.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(34.dp)) {
                    Text(
                        cfg.title.ifEmpty { "Note" }, color = Color(p.title), fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        fontFamily = Fonts.compose(cfg.fontFile), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    for (icon in listOf(R.drawable.ic_refresh, R.drawable.ic_open, R.drawable.ic_settings, R.drawable.ic_edit)) {
                        Icon(painterResource(icon), null, tint = Color(p.muted), modifier = Modifier.padding(8.dp).size(18.dp))
                    }
                }
                NotePreview(blocks, p, cfg.textSize, Fonts.typeface(cfg.fontFile), Modifier.fillMaxWidth().padding(end = 6.dp))
            }
        }
    }

    @Composable
    private fun NotePicker(notes: List<Note>?, current: String?, onBack: () -> Unit, onPick: (Note) -> Unit) {
        var query by remember { mutableStateOf("") }
        val tick = rememberHaptic()
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { PageHeader("Choose a note", onBack) },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                SearchField(query, { query = it }, "Search notes", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                if (notes == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    return@Column
                }
                val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
                val shown = notes.filter { n -> words.all { n.path.lowercase().contains(it) } }
                if (shown.isEmpty()) {
                    Text(
                        if (notes.isEmpty()) "No notes found in this vault." else "No matches.",
                        Modifier.padding(24.dp), color = noteColor(),
                    )
                }
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.navigationBarsPadding(),
                ) {
                    items(shown, key = { it.path }) { n ->
                        val on = n.uri.toString() == current
                        val accent = MaterialTheme.colorScheme.primary
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (on) accent.copy(alpha = 0.14f) else Color.Transparent)
                                .clickable { tick(); onPick(n) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            IconTile(R.drawable.ic_note, size = 36.dp, tint = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    n.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (on) accent else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                val folder = n.path.substringBeforeLast('/', "")
                                if (folder.isNotEmpty()) {
                                    Text(folder, style = MaterialTheme.typography.bodySmall, color = noteColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (on) Icon(painterResource(R.drawable.ic_check), "Current note", tint = accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}
