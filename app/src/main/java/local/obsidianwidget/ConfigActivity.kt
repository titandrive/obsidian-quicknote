package local.obsidianwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sets up a widget: which note, theme, light/dark, background opacity and text size. */
class ConfigActivity : ComponentActivity() {
    companion object {
        /** Opened by tapping the widget's note name: just the note list, saved on tap. */
        const val EXTRA_PICK_ONLY = "pick_only"
        /** With [EXTRA_PICK_ONLY]: pick the note to swap with, rather than the widget's note. */

        private val MODES = listOf("vault", "system", "light", "dark")
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
        // A new widget starts with the settings of the one removed last, so replacing a widget is quick.
        setContent { Screen(existing ?: WidgetStore.lastRemoved(this), pickOnly) }
    }

    private suspend fun save(cfg: WidgetConfig) {
        val full = if (cfg.colors != null) cfg
        else cfg.copy(colors = withContext(Dispatchers.IO) { ObsidianTheme.load(this@ConfigActivity, cfg.vaultTree, cfg.theme) })
        // Folds belong to the note: a different note brings back its own, as swapping does.
        WidgetStore.load(this, widgetId)?.takeIf { it.noteUri != full.noteUri }?.let { Folds.swap(this, widgetId, it.notePath, full.notePath) }
        WidgetStore.save(this, widgetId, full)
        ChangeCheck.schedule(this, widgetId)
        withContext(Dispatchers.IO) { NoteWidgetProvider.update(this@ConfigActivity, AppWidgetManager.getInstance(this@ConfigActivity), widgetId) }
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    @Composable
    private fun Screen(existing: WidgetConfig?, pickOnly: Boolean) {
        val scope = rememberCoroutineScope()
        // This widget's vault; a new widget starts with the app's default one.
        var vault by remember { mutableStateOf(existing?.vault?.takeIf { it.isNotEmpty() } ?: Vault.defaultTree(this)?.toString()) }
        var vaultName by remember { mutableStateOf(if (vault == null) null else existing?.vaultName?.takeIf { it.isNotEmpty() } ?: Vault.defaultName(this)) }
        var notes by remember { mutableStateOf<List<Note>?>(null) }
        var themes by remember { mutableStateOf<List<String>>(emptyList()) }
        var vaultTheme by remember { mutableStateOf("") }
        // Each theme's colours, filled in as they're worked out, for the swatches in the theme sheet.
        val swatches = remember { mutableStateMapOf<String, ThemeColors>() }

        // The widget's notes, and which of them it shows.
        var swapNotes by remember {
            mutableStateOf(existing?.swapNotes?.ifEmpty { null } ?: listOfNotNull(existing?.takeIf { it.noteUri.isNotEmpty() }?.let { SwapNote(it.noteUri, it.notePath) }))
        }
        var noteUri by remember { mutableStateOf(existing?.noteUri) }
        var theme by remember { mutableStateOf(existing?.theme ?: ObsidianTheme.THEME_VAULT) }
        var mode by remember { mutableStateOf(existing?.mode ?: "vault") }
        var opacity by remember { mutableIntStateOf(existing?.opacity ?: 100) }
        var textSize by remember { mutableStateOf(existing?.textSize ?: 14f) }
        var showHeader by remember { mutableStateOf(existing?.showHeader ?: true) }
        var showSettings by remember { mutableStateOf(existing?.showSettings ?: true) }
        var showSourceButton by remember { mutableStateOf(existing?.showSourceButton ?: true) }
        var checkMinutes by remember { mutableIntStateOf(existing?.checkMinutes ?: 30) }
        var colors by remember { mutableStateOf(existing?.colors) }
        var noteText by remember { mutableStateOf<String?>(null) }
        // Picking a note: which one it replaces, or -1 to add one. From the widget, it's the one shown.
        var picking by remember { mutableStateOf(if (pickOnly) swapNotes.indexOfFirst { it.uri == noteUri } else null) }
        var pickingTheme by remember { mutableStateOf(false) }
        var previewBounds by remember { mutableStateOf<Rect?>(null) }

        // Back from the All files access screen: switch to Live if access was turned on.
        val allFilesAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (ChangeCheck.canInstant()) checkMinutes = ChangeCheck.INSTANT
        }

        val pickVault = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val name = Vault.take(this, uri)
                // The first vault picked anywhere also becomes the default for new widgets.
                if (Vault.defaultTree(this) == null) Vault.setDefault(this, uri, name)
                vault = uri.toString()
                vaultName = name
                noteUri = null
                swapNotes = emptyList()
                swatches.clear()
            }
        }

        LaunchedEffect(vault) {
            val tree = vault?.let(Uri::parse) ?: return@LaunchedEffect
            notes = withContext(Dispatchers.IO) { Vault.cachedNotes(this@ConfigActivity, tree)?.sortedByDescending { it.modified } }
            withContext(Dispatchers.IO) {
                themes = Vault.themes(this@ConfigActivity, tree)
                vaultTheme = Vault.appearance(this@ConfigActivity, tree)?.optString("cssTheme").orEmpty()
                notes = Vault.notes(this@ConfigActivity, tree).sortedByDescending { it.modified }
            }
            if (swapNotes.isEmpty()) picking = -1
        }
        LaunchedEffect(theme, vault) {
            val tree = vault?.let(Uri::parse) ?: return@LaunchedEffect
            colors = withContext(Dispatchers.IO) { ObsidianTheme.load(this@ConfigActivity, tree, theme) }
            colors?.let { swatches[theme] = it }
        }
        // Swatches for the theme sheet, worked out as soon as the vault's themes are known (cached
        // ones come back at once), so they're ready by the time the sheet opens.
        LaunchedEffect(themes) {
            val tree = vault?.let(Uri::parse) ?: return@LaunchedEffect
            for (t in listOf(ObsidianTheme.THEME_VAULT, ObsidianTheme.THEME_DEFAULT) + themes) {
                if (t !in swatches) swatches[t] = withContext(Dispatchers.IO) { ObsidianTheme.load(this@ConfigActivity, tree, t) }
            }
        }
        fun shown() = swapNotes.firstOrNull { it.uri == noteUri } ?: swapNotes.firstOrNull()
        LaunchedEffect(shown()?.uri) {
            noteText = shown()?.uri?.let { u -> withContext(Dispatchers.IO) { Vault.read(this@ConfigActivity, android.net.Uri.parse(u)) } }
        }

        fun current() = WidgetConfig(
            noteUri = shown()?.uri.orEmpty(), notePath = shown()?.path.orEmpty(), theme = theme, mode = mode,
            opacity = opacity, textSize = textSize, colors = colors, showHeader = showHeader,
            vault = vault.orEmpty(), vaultName = vaultName.orEmpty(), checkMinutes = checkMinutes,
            showToolbar = existing?.showToolbar ?: true,
            readOnly = existing?.readOnly ?: false, showSettings = showSettings,
            showSourceButton = showSourceButton, sourceMode = (existing?.sourceMode ?: false) && showSourceButton,
            swapNotes = swapNotes,
        )
        val cfg = current()

        // The screen wears the widget's own theme and font, so changes show on it as they're made.
        AppTheme(palette = colors?.let { cfg.palette(this) }, font = remember(cfg.fontFile) { Fonts.compose(cfg.fontFile) }) {
            picking?.let { at ->
                val done = { if (pickOnly) finish() else picking = null }
                BackHandler { done() }
                NotePicker(notes, swapNotes.getOrNull(at)?.uri, onBack = done, title = if (at < 0 && swapNotes.isNotEmpty()) "Add a note" else "Choose a note") { note ->
                    val picked = SwapNote(note.uri.toString(), note.path)
                    val was = swapNotes.getOrNull(at)
                    // A note already in the list stays where it is.
                    if (swapNotes.none { it.uri == picked.uri }) {
                        swapNotes = if (was == null) swapNotes + picked else swapNotes.toMutableList().apply { set(at, picked) }
                    }
                    // Picking in place of the note shown, or the first note, shows the one picked.
                    if (was == null && swapNotes.size == 1 || was != null && was.uri == shown()?.uri) noteUri = picked.uri
                    // Picked from the widget: stay on the picker while saving, rather than flash the settings.
                    if (pickOnly) scope.launch { save(current()) } else picking = null
                }
                return@AppTheme
            }

            fun themeName(t: String) = when (t) {
                ObsidianTheme.THEME_VAULT -> "Same as Obsidian"
                ObsidianTheme.THEME_DEFAULT -> "Obsidian default"
                else -> t
            }

            // The page is painted here rather than by the window, with a hole where the preview is: the
            // window shows the wallpaper, so the preview sits on the real one, as the widget does.
            val page = MaterialTheme.colorScheme.background
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawBehind {
                        drawRect(page)
                        previewBounds?.let {
                            drawRoundRect(Color.Black, it.topLeft, it.size, CornerRadius(24.dp.toPx()), blendMode = BlendMode.Clear)
                        }
                    },
            ) {
                Scaffold(
                    containerColor = Color.Transparent,
                    // Transparent has no matching text colour, so give the page's own.
                    contentColor = MaterialTheme.colorScheme.onBackground,
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
                                enabled = swapNotes.isNotEmpty() && colors != null,
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

                        Preview(cfg, noteText) { previewBounds = it }

                        SectionCard("Note", R.drawable.ic_note) {
                            // The vault first, since it's picked first.
                            NavRow(
                                title = vault?.let { Vault.displayPath(Uri.parse(it)) } ?: vaultName!!,
                                subtitle = "Vault folder",
                                leading = { IconTile(R.drawable.ic_folder, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = { pickVault.launch(null) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            // More than one note, and the widget's title swaps between them.
                            SwapList(
                                swapNotes, shown = shown()?.uri,
                                onMove = { from, to -> swapNotes = swapNotes.toMutableList().apply { add(to, removeAt(from)) } },
                                onRemove = { i -> swapNotes = swapNotes.toMutableList().apply { removeAt(i) } },
                                onPick = { i -> picking = i },
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
                            SettingLabel("Background opacity", value = "$opacity%")
                            StepSlider(opacity, 0..100, step = 5) { opacity = it }
                            SettingLabel("Text size", value = "${textSize.toInt()}")
                            StepSlider(textSize.toInt(), 10..19, step = 1) { textSize = it.toFloat() }
                            SwitchRow("Show header", null, showHeader) { showHeader = it }
                            // The settings button is in the header, so it only applies while the header shows.
                            if (showHeader) SwitchRow("Show settings button", null, showSettings) { showSettings = it }
                            SwitchRow("Show source mode button", "Shows source mode button in edit modal", showSourceButton) { showSourceButton = it }
                        }

                        SectionCard("Updates", R.drawable.ic_refresh) {
                            val live = checkMinutes == ChangeCheck.INSTANT
                            SettingLabel(
                                "Check for changes",
                                when {
                                    live -> "Detects changes instantly. Requires All files access"
                                    checkMinutes == 0 -> "Changes only detected manually"
                                    checkMinutes == 60 -> "Checks every hour for changes"
                                    checkMinutes > 60 -> "Checks every ${checkMinutes / 60} hours for changes"
                                    else -> "Checks every $checkMinutes minutes for changes"
                                },
                            )
                            PillSegmented(ChangeCheck.CHOICES, checkMinutes, {
                                when (it) {
                                    0 -> "Off"
                                    ChangeCheck.INSTANT -> "Live"
                                    in 1..59 -> "${it}m"
                                    else -> "${it / 60}h"
                                }
                            }) {
                                if (it == ChangeCheck.INSTANT && !ChangeCheck.canInstant()) {
                                    // Ask for the access first; Live is picked once it's granted.
                                    allFilesAccess.launch(
                                        Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                                    )
                                } else {
                                    checkMinutes = it
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                    }
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

    /** The widget as it will look, over the real wallpaper (see the hole in the page behind it). */
    @Composable
    private fun Preview(cfg: WidgetConfig, text: String?, onBounds: (Rect) -> Unit) {
        val p = cfg.palette(this)
        val indent = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, cfg.textSize * 1.2f, resources.displayMetrics).toInt()
        val blocks = remember(text, p, indent) {
            Markdown.render(text ?: "# Pick a note\nIt will show up here.", p, indent).take(40)
        }
        Box(
            Modifier
                .fillMaxWidth()
                // Unclipped bounds: when scrolled partly under the header, the header covers the rest.
                .onGloballyPositioned { onBounds(Rect(it.positionInRoot(), it.size.toSize())) }
                .padding(12.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(p.background).copy(alpha = cfg.opacity / 100f))
                    .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 12.dp)
                    .heightIn(max = 170.dp),
            ) {
                if (cfg.showHeader) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(38.dp)) {
                    Text(
                        cfg.title.ifEmpty { "Note" }, color = Color(p.title), fontWeight = FontWeight.Bold, fontSize = 16.5.sp,
                        fontFamily = Fonts.compose(cfg.fontFile), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(painterResource(if (cfg.swaps) R.drawable.ic_swap else R.drawable.ic_folder_open), null, tint = Color(p.muted), modifier = Modifier.padding(start = 6.dp).size(16.5.dp))
                    Spacer(Modifier.weight(1f))
                    for (icon in listOfNotNull(if (cfg.readOnly) R.drawable.ic_read else R.drawable.ic_editing, R.drawable.ic_refresh, R.drawable.ic_open, R.drawable.ic_settings.takeIf { cfg.showSettings }, R.drawable.ic_edit)) {
                        Icon(painterResource(icon), null, tint = Color(p.muted), modifier = Modifier.padding(9.dp).size(20.dp))
                    }
                }
                NotePreview(blocks, p, cfg.textSize, Fonts.typeface(cfg.fontFile), Modifier.fillMaxWidth().padding(end = 6.dp))
            }
        }
    }

    /**
     * The widget's notes, in the order its title swaps through them: each changed by tapping it,
     * and with more than one, moved by dragging its handle or removed; and a row to add another,
     * up to [WidgetConfig.MAX_SWAP].
     */
    @Composable
    private fun SwapList(notes: List<SwapNote>, shown: String?, onMove: (Int, Int) -> Unit, onRemove: (Int) -> Unit, onPick: (Int) -> Unit) {
        val tick = rememberHaptic()
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        // The drag outlives recompositions, so it reads the list and the mover as they are now.
        val latest by rememberUpdatedState(notes)
        val move by rememberUpdatedState(onMove)
        // The row being dragged, by its note, and how far it's been dragged from where it sits now.
        var dragging by remember { mutableStateOf<String?>(null) }
        var offset by remember { mutableFloatStateOf(0f) }
        val heights = remember { mutableStateMapOf<String, Int>() }
        Column {
            notes.forEachIndexed { i, note ->
                key(note.uri) {
                    val lifted = dragging == note.uri
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { heights[note.uri] = it.height }
                            .zIndex(if (lifted) 1f else 0f)
                            .graphicsLayer { translationY = if (lifted) offset else 0f }
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (lifted) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .clickable { tick(); onPick(i) }
                            .padding(vertical = 4.dp),
                    ) {
                        // A lone note has nothing to change places with.
                        if (notes.size > 1) Icon(
                            painterResource(R.drawable.ic_grip), "Drag to reorder", tint = muted,
                            modifier = Modifier
                                .size(40.dp)
                                .pointerInput(note.uri) {
                                    val drop = { dragging = null; offset = 0f }
                                    detectVerticalDragGestures(
                                        onDragStart = { dragging = note.uri; offset = 0f; tick() },
                                        onDragEnd = drop, onDragCancel = drop,
                                    ) { change, dy ->
                                        change.consume()
                                        offset += dy
                                        // Past half of the row next to it, the two change places.
                                        val at = latest.indexOfFirst { it.uri == note.uri }
                                        val below = latest.getOrNull(at + 1)?.let { heights[it.uri] }
                                        val above = latest.getOrNull(at - 1)?.let { heights[it.uri] }
                                        if (below != null && offset > below / 2f) { move(at, at + 1); offset -= below; tick() }
                                        else if (above != null && offset < -above / 2f) { move(at, at - 1); offset += above; tick() }
                                    }
                                }
                                .padding(10.dp),
                        )
                        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                            Text(note.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (notes.size > 1 && note.uri == shown) Text("Showing now", style = MaterialTheme.typography.bodySmall, color = noteColor())
                        }
                        // One note is always left, as the one shown; it changes with a tap instead.
                        if (notes.size > 1) Icon(
                            painterResource(R.drawable.ic_close), "Remove", tint = muted,
                            modifier = Modifier.size(40.dp).clip(CircleShape).clickable { tick(); onRemove(i) }.padding(10.dp),
                        ) else Icon(painterResource(R.drawable.ic_chevron_right), null, tint = muted, modifier = Modifier.padding(10.dp).size(20.dp))
                    }
                }
            }
            if (notes.size < WidgetConfig.MAX_SWAP) Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { tick(); onPick(-1) }
                    .padding(vertical = 4.dp),
            ) {
                Icon(painterResource(R.drawable.ic_plus), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp).padding(10.dp))
                Column(Modifier.padding(horizontal = 4.dp)) {
                    Text(if (notes.isEmpty()) "Choose a note" else "Add a note", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    if (notes.size == 1) Text("Tap the widget's title to swap between notes", style = MaterialTheme.typography.bodySmall, color = noteColor())
                }
            }
        }
    }

    @Composable
    private fun NotePicker(notes: List<Note>?, current: String?, onBack: () -> Unit, title: String = "Choose a note", onPick: (Note) -> Unit) {
        var query by remember { mutableStateOf("") }
        val tick = rememberHaptic()
        // Opened onto the current note's folder, as Obsidian reveals the open file.
        val currentPath = notes?.firstOrNull { it.uri.toString() == current }?.path
        var open by remember(currentPath) { mutableStateOf(currentPath?.let(NoteTree::ancestors).orEmpty()) }
        val accent = MaterialTheme.colorScheme.primary
        val guide = MaterialTheme.colorScheme.outlineVariant
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { PageHeader(title, onBack) },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                val hits = remember(notes, query) { notes?.let { NoteTree.search(it, query) }.orEmpty() }
                SearchField(
                    query, { query = it }, "Search notes", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    // Enter picks the best match, as in Obsidian's quick switcher.
                    onGo = { hits.firstOrNull()?.let { tick(); onPick(it.note) } },
                )
                if (notes == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    return@Column
                }
                if (notes.isEmpty() || (query.isNotBlank() && hits.isEmpty())) {
                    Text(
                        if (notes.isEmpty()) "No notes found in this vault." else "No matches.",
                        Modifier.padding(24.dp), color = noteColor(),
                    )
                }
                val step = 20.dp
                LazyColumn(
                    contentPadding = PaddingValues(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                    modifier = Modifier.navigationBarsPadding(),
                ) {
                    if (query.isNotBlank()) {
                        // Searching: the matches, best first, with the letters that matched in the accent colour.
                        items(hits, key = { it.note.path }) { hit ->
                            val n = hit.note
                            val on = n.uri.toString() == current
                            val title = androidx.compose.ui.text.buildAnnotatedString {
                                append(n.title)
                                for (i in hit.matched) addStyle(androidx.compose.ui.text.SpanStyle(color = accent, fontWeight = FontWeight.Bold), i, i + 1)
                            }
                            PickerRow(on, onClick = { tick(); onPick(n) }) {
                                Icon(painterResource(R.drawable.ic_note), null, tint = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (on) accent else MaterialTheme.colorScheme.onSurface)
                                    val folder = n.path.substringBeforeLast('/', "")
                                    if (folder.isNotEmpty()) Text(folder, style = MaterialTheme.typography.bodySmall, color = noteColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    } else {
                        // The vault's folders, as in Obsidian's file explorer.
                        items(NoteTree.rows(notes, open), key = { e -> if (e is NoteTree.Folder) "d:" + e.path else "f:" + (e as NoteTree.File).note.path }) { e ->
                            val indent = Modifier.drawBehind {
                                // Indent guides, one per level, as Obsidian draws them.
                                for (d in 0 until e.depth) {
                                    val x = (step * d + 17.dp).toPx()
                                    drawRect(guide, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height))
                                }
                            }
                            when (e) {
                                is NoteTree.Folder -> PickerRow(false, indent, onClick = {
                                    tick()
                                    open = if (e.open) open - e.path else open + e.path
                                }) {
                                    Spacer(Modifier.width(step * e.depth))
                                    Icon(
                                        painterResource(R.drawable.ic_chevron_right), if (e.open) "Collapse" else "Expand",
                                        tint = noteColor(),
                                        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = if (e.open) 90f else 0f },
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(e.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                                }
                                is NoteTree.File -> {
                                    val on = e.note.uri.toString() == current
                                    PickerRow(on, indent, onClick = { tick(); onPick(e.note) }) {
                                        Spacer(Modifier.width(step * e.depth + 24.dp))
                                        Text(
                                            e.note.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            color = if (on) accent else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (on) Icon(painterResource(R.drawable.ic_check), "Current note", tint = accent, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** A line of the note picker: the current note shaded in the accent colour. */
    @Composable
    private fun PickerRow(on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            content = content,
        )
    }
}
