package local.obsidianwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/** A card that floats over the wallpaper for editing the widget's note, styled as it's typed. */
class EditorActivity : ComponentActivity() {
    companion object {
        const val EXTRA_LINE = "line"
        const val EXTRA_TOGGLE = "toggle"

        fun toggleTask(ctx: Context, cfg: WidgetConfig, line: Int) {
            val text = Vault.read(ctx, cfg.uri) ?: return
            val next = Markdown.toggleTask(text, line) ?: return
            Vault.write(ctx, cfg.uri, next)
            NoteWidgetProvider.refreshNote(ctx, cfg.noteUri)
        }
    }

    private lateinit var cfg: WidgetConfig
    private var value by mutableStateOf(TextFieldValue(""))
    private var loaded by mutableStateOf(false)
    /** Set when the cursor was put on a tapped line, until the editor has scrolled to it. */
    private var scrollToCursor by mutableStateOf(false)
    private var conflict by mutableStateOf(false)
    /** The conflict came from refreshing, so resolving it stays in the editor instead of closing. */
    private var conflictOnRefresh = false
    /** A refresh is running; the button spins. */
    private var reloading by mutableStateOf(false)
    /** The note as it was on disk when it was last loaded or saved. */
    private var original: String? = null
    private val history = Toolbar.History()
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    /** Whether the formatting toolbar shows; remembered between edits. */
    private var showToolbar by mutableStateOf(true)
    /** The vault's mobile toolbar buttons, once read. */
    private var toolbar by mutableStateOf<List<Toolbar.Command>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        widgetId = id
        cfg = WidgetStore.load(this, id) ?: return finish()
        showToolbar = prefs().getBoolean("toolbar", true)

        enableEdgeToEdge()
        val line = intent.getIntExtra(EXTRA_LINE, -1)
        lifecycleScope.launch { toolbar = withContext(Dispatchers.IO) { Toolbar.load(this@EditorActivity) } }
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { Vault.read(this@EditorActivity, cfg.uri) }.orEmpty()
            original = text
            value = TextFieldValue(text, TextRange(cursorFor(text, line)))
            scrollToCursor = true
            loaded = true
        }

        val p = cfg.palette(this)
        setContent {
            BackHandler { done() }
            Editor(p)
            if (conflict) ConflictDialog(p)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!loaded) return
        val line = intent.getIntExtra(EXTRA_LINE, -1)
        if (intent.getBooleanExtra(EXTRA_TOGGLE, false)) {
            // A task tapped while the editor is already open: tick it in the text being edited.
            Markdown.toggleTask(value.text, line)?.let { value = value.copy(text = it) }
        } else if (line >= 0) {
            // Another line tapped while the editor was still open in the background: go there.
            value = value.copy(selection = TextRange(cursorFor(value.text, line)))
            scrollToCursor = true
        }
    }

    override fun onPause() {
        super.onPause()
        // Leaving by home or recents still keeps the edit.
        if (loaded && !isFinishing && value.text != original) save(force = true)
    }

    private fun done() {
        if (!loaded || value.text == original || save(force = false)) finish()
    }

    /** Writes the note; without [force], refuses if it was changed elsewhere since it was opened. */
    private fun save(force: Boolean): Boolean {
        val text = value.text
        val disk = Vault.read(this, cfg.uri)
        if (!force && disk != null && disk != original && disk != text) {
            conflict = true
            return false
        }
        runCatching { Vault.write(this, cfg.uri, text) }.onFailure {
            android.widget.Toast.makeText(this, "Couldn't save: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
            return false
        }
        original = text
        NoteWidgetProvider.refreshNote(this, cfg.noteUri)
        return true
    }

    private fun cursorFor(text: String, line: Int): Int {
        if (line < 0) return text.length
        var offset = 0
        repeat(line) {
            val nl = text.indexOf('\n', offset)
            if (nl < 0) return text.length
            offset = nl + 1
        }
        val end = text.indexOf('\n', offset)
        return (if (end < 0) text.length else end).let { if (it > 0 && text[it - 1] == '\r') it - 1 else it }
    }

    @Composable
    private fun Editor(p: Palette) {
        val bg = Color(p.background)
        val text = Color(p.text)
        val accent = Color(p.accent)
        val muted = Color(p.muted)
        val font = remember { Fonts.compose(cfg.fontFile) }
        val focus = remember { FocusRequester() }
        val noRipple = remember { MutableInteractionSource() }
        val scroll = rememberScrollState()
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        // Restyled whenever the cursor moves to another line, so that line's markup shows.
        val active = LivePreview.activeLines(value.text, value.selection.start, value.selection.end)
        val cursor = value.selection.start
        val preview = remember(active, cursor) { LivePreview(p, cfg.textSize, active, cursor) }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = noRipple, indication = null) { done() }
                .systemBarsPadding()
                .imePadding()
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // All the room between the status bar and the keyboard.
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(20.dp))
                    .background(bg)
                    // Swallow taps on the card so they don't count as tapping outside it.
                    .clickable(interactionSource = noRipple, indication = null) {},
            ) {
                // The widget's header, button for button (widget_note.xml), with done where the widget has edit.
                Row(
                    Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp).heightIn(min = 34.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        cfg.title, color = Color(p.title), fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        fontFamily = font, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(interactionSource = noRipple, indication = null) {
                                leaveFor(NoteWidgetProvider.configIntent(this@EditorActivity, widgetId, pickOnly = true))
                            },
                    )
                    HeaderButton(R.drawable.ic_toolbar, if (showToolbar) "Hide toolbar" else "Show toolbar", if (showToolbar) accent else muted) {
                        showToolbar = !showToolbar
                        prefs().edit().putBoolean("toolbar", showToolbar).apply()
                    }
                    val spin by rememberInfiniteTransition(label = "refresh").animateFloat(
                        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin",
                    )
                    HeaderButton(R.drawable.ic_refresh, "Reload note", muted, enabled = loaded, rotation = if (reloading) spin else 0f) {
                        if (!reloading) reload()
                    }
                    HeaderButton(R.drawable.ic_open, "Open in Obsidian", muted) {
                        leaveFor(Intent(Intent.ACTION_VIEW, Vault.openUri(this@EditorActivity, cfg.notePath)))
                    }
                    HeaderButton(R.drawable.ic_settings, "Widget settings", muted) {
                        leaveFor(NoteWidgetProvider.configIntent(this@EditorActivity, widgetId, pickOnly = false))
                    }
                    HeaderButton(R.drawable.ic_check, "Done", accent, size = 22.dp) { done() }
                }
                CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(accent, accent.copy(alpha = 0.35f))) {
                    // Scrolled here rather than inside the field, so it can be moved to the tapped line.
                    BasicTextField(
                        value = value,
                        onValueChange = {
                            val next = LivePreview.continueList(value, it)
                            history.record(value, next)
                            value = next
                        },
                        enabled = loaded,
                        onTextLayout = { layout = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(scroll)
                            .padding(horizontal = 18.dp, vertical = 8.dp)
                            .focusRequester(focus),
                        textStyle = TextStyle(color = text, fontSize = cfg.textSize.sp, lineHeight = 1.45.em, fontFamily = font),
                        cursorBrush = SolidColor(accent),
                        visualTransformation = preview,
                    )
                }
                if (showToolbar && toolbar.isNotEmpty()) ToolbarRow(p)
            }
        }
        LaunchedEffect(loaded) { if (loaded) focus.requestFocus() }
        LaunchedEffect(scrollToCursor, layout) {
            val l = layout ?: return@LaunchedEffect
            if (!scrollToCursor || !loaded) return@LaunchedEffect
            // A quarter of the way down, so the line stays in view once the keyboard opens.
            val top = l.getCursorRect(value.selection.start.coerceIn(0, l.layoutInput.text.length)).top
            scroll.scrollTo((top - scroll.viewportSize / 4).toInt().coerceAtLeast(0))
            scrollToCursor = false
        }
    }

    /** Obsidian's mobile toolbar along the bottom of the card, just above the keyboard. */
    @Composable
    private fun ToolbarRow(p: Palette) {
        val tick = rememberHaptic()
        // Read so the row redraws on every edit, keeping undo and redo's enabled state current.
        @Suppress("UNUSED_VARIABLE") val current = value
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(p.backgroundAlt))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            for (cmd in toolbar) {
                val enabled = when (cmd.id) {
                    "editor:undo" -> history.canUndo
                    "editor:redo" -> history.canRedo
                    else -> loaded
                }
                IconButton(
                    onClick = {
                        tick()
                        value = when (cmd.id) {
                            "editor:undo" -> history.undo(value) ?: value
                            "editor:redo" -> history.redo(value) ?: value
                            else -> Toolbar.apply(cmd.id, value).also { history.record(value, it, action = true) }
                        }
                    },
                    enabled = enabled,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        painterResource(cmd.icon), cmd.label, Modifier.size(20.dp),
                        tint = Color(p.muted).copy(alpha = if (enabled) 1f else 0.35f),
                    )
                }
            }
        }
    }

    /** A 34dp header button, the same size as the widget's. */
    @Composable
    private fun HeaderButton(
        icon: Int, label: String, tint: Color, enabled: Boolean = true, size: androidx.compose.ui.unit.Dp = 18.dp,
        rotation: Float = 0f, onClick: () -> Unit,
    ) {
        val tick = rememberHaptic()
        IconButton(onClick = { tick(); onClick() }, enabled = enabled, modifier = Modifier.size(34.dp)) {
            Icon(painterResource(icon), label, Modifier.size(size).rotate(rotation), tint = tint.copy(alpha = if (enabled) 1f else 0.35f))
        }
    }

    /** Saves (asking first if the note changed elsewhere), then goes to [intent] and closes. */
    private fun leaveFor(intent: Intent) {
        if (value.text == original || save(force = false)) {
            startActivity(intent)
            finish()
        }
    }

    /**
     * Picks up changes made in Obsidian. Unsaved edits are saved first; if the note also changed
     * elsewhere, the conflict dialog asks which to keep, and refreshing carries on from there.
     */
    private fun reload() {
        if (value.text != original && !save(force = false)) {
            conflictOnRefresh = true
            return
        }
        reloading = true
        lifecycleScope.launch {
            val started = SystemClock.uptimeMillis()
            val text = withContext(Dispatchers.IO) { Vault.read(this@EditorActivity, cfg.uri) }
            if (text != null && text != value.text) {
                val next = TextFieldValue(text, TextRange(value.selection.start.coerceAtMost(text.length)))
                history.record(value, next, action = true)
                value = next
            }
            if (text != null) original = text
            // Long enough to see it turn, even when the file is read in an instant.
            delay((600 - (SystemClock.uptimeMillis() - started)).coerceAtLeast(0))
            reloading = false
        }
    }

    private fun prefs() = getSharedPreferences("editor", MODE_PRIVATE)

    @Composable
    private fun ConflictDialog(p: Palette) {
        AlertDialog(
            onDismissRequest = { conflict = false; conflictOnRefresh = false },
            title = { Text("Note changed elsewhere") },
            text = { Text("${cfg.title} was changed (probably in Obsidian or by sync) since you started editing. Keep your version?") },
            confirmButton = {
                TextButton(onClick = {
                    conflict = false
                    if (save(force = true) && !conflictOnRefresh) finish()
                    conflictOnRefresh = false
                }) { Text("Overwrite with mine") }
            },
            dismissButton = {
                TextButton(onClick = {
                    conflict = false
                    if (conflictOnRefresh) {
                        // Take the version on disk instead of closing.
                        conflictOnRefresh = false
                        original = value.text
                        reload()
                    } else {
                        original = value.text
                        finish()
                    }
                }) { Text("Discard my edits") }
            },
            containerColor = Color(p.backgroundAlt),
            titleContentColor = Color(p.text),
            textContentColor = Color(p.muted),
        )
    }
}
