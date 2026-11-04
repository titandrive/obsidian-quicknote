package local.obsidianwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * A card that floats over the home screen for editing the widget's note as plain markdown.
 * Also handles ticking off a task row, which it does without showing anything.
 */
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
    /** The note as it was on disk when it was last loaded or saved. */
    private var original: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        cfg = WidgetStore.load(this, id) ?: return finish()

        if (intent.getBooleanExtra(EXTRA_TOGGLE, false)) {
            val line = intent.getIntExtra(EXTRA_LINE, -1)
            val app = applicationContext
            thread { toggleTask(app, cfg, line) }
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            return
        }

        enableEdgeToEdge()
        val line = intent.getIntExtra(EXTRA_LINE, -1)
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
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(bg)
                    // Swallow taps on the card so they don't count as tapping outside it.
                    .clickable(interactionSource = noRipple, indication = null) {},
            ) {
                Row(Modifier.padding(start = 18.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        cfg.title, Modifier.weight(1f), color = Color(p.title), fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        fontFamily = font, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = {
                        if (value.text == original || save(force = false)) {
                            startActivity(Intent(Intent.ACTION_VIEW, Vault.openUri(this@EditorActivity, cfg.notePath)))
                            finish()
                        }
                    }) {
                        Icon(painterResource(R.drawable.ic_open), "Open in Obsidian", Modifier.size(20.dp), tint = Color(p.muted))
                    }
                    IconButton(onClick = { done() }) {
                        Icon(Icons.Default.Check, "Done", tint = accent)
                    }
                }
                CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(accent, accent.copy(alpha = 0.35f))) {
                    // Scrolled here rather than inside the field, so it can be moved to the tapped line.
                    BasicTextField(
                        value = value,
                        onValueChange = { value = LivePreview.continueList(value, it) },
                        enabled = loaded,
                        onTextLayout = { layout = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(horizontal = 18.dp, vertical = 8.dp)
                            .focusRequester(focus),
                        textStyle = TextStyle(color = text, fontSize = cfg.textSize.sp, lineHeight = 1.45.em, fontFamily = font),
                        cursorBrush = SolidColor(accent),
                        visualTransformation = preview,
                    )
                }
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

    @Composable
    private fun ConflictDialog(p: Palette) {
        AlertDialog(
            onDismissRequest = { conflict = false },
            title = { Text("Note changed elsewhere") },
            text = { Text("${cfg.title} was changed (probably in Obsidian or by sync) since you started editing. Keep your version?") },
            confirmButton = {
                TextButton(onClick = { conflict = false; if (save(force = true)) finish() }) { Text("Overwrite with mine") }
            },
            dismissButton = {
                TextButton(onClick = { conflict = false; original = value.text; finish() }) { Text("Discard my edits") }
            },
            containerColor = Color(p.backgroundAlt),
            titleContentColor = Color(p.text),
            textContentColor = Color(p.muted),
        )
    }
}
