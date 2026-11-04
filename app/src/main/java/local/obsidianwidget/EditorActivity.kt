package local.obsidianwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Edits the widget's note in place: a window laid exactly over the widget, in the same colours,
 * opacity and font, so the widget appears to turn editable. Widgets themselves can't hold a text
 * field. Also handles ticking off a task row, which it does without showing anything.
 */
class EditorActivity : ComponentActivity() {
    companion object {
        const val EXTRA_LINE = "line"
        const val EXTRA_TOGGLE = "toggle"
        /** Which part of the widget was tapped; with the launcher's tap bounds it locates the widget. */
        const val EXTRA_SOURCE = "source"
        const val SOURCE_BUTTON = "button"
        const val SOURCE_ROW = "row"
        const val SOURCE_LIST = "list"

        fun toggleTask(ctx: Context, cfg: WidgetConfig, line: Int) {
            val text = Vault.read(ctx, cfg.uri) ?: return
            val next = Markdown.toggleTask(text, line) ?: return
            Vault.write(ctx, cfg.uri, next)
            NoteWidgetProvider.refreshNote(ctx, cfg.noteUri)
        }
    }

    private lateinit var cfg: WidgetConfig
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var value by mutableStateOf(TextFieldValue(""))
    private var loaded by mutableStateOf(false)
    private var conflict by mutableStateOf(false)
    /** The note as it was on disk when it was last loaded or saved. */
    private var original: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noTransition()
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        widgetId = id
        cfg = WidgetStore.load(this, id) ?: return finish()

        if (intent.getBooleanExtra(EXTRA_TOGGLE, false)) {
            val line = intent.getIntExtra(EXTRA_LINE, -1)
            val app = applicationContext
            thread { toggleTask(app, cfg, line) }
            finish()
            return
        }

        enableEdgeToEdge()
        val line = intent.getIntExtra(EXTRA_LINE, -1)
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { Vault.read(this@EditorActivity, cfg.uri) }.orEmpty()
            original = text
            value = TextFieldValue(text, TextRange(cursorFor(text, line)))
            loaded = true
        }

        val p = cfg.palette(this)
        val rect = widgetRect(id)
        setContent {
            BackHandler { done() }
            Editor(p, rect)
            if (conflict) ConflictDialog(p)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A task tapped while the editor is already open: tick it in the text being edited.
        if (intent.getBooleanExtra(EXTRA_TOGGLE, false) && loaded) {
            Markdown.toggleTask(value.text, intent.getIntExtra(EXTRA_LINE, -1))?.let { value = value.copy(text = it) }
        }
    }

    override fun finish() {
        super.finish()
        noTransition()
    }

    // Appearing and disappearing instantly is what makes it look like the widget itself.
    private fun noTransition() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /**
     * The widget's bounds on screen. The launcher only reports the bounds of the tapped view, so
     * this works back from where that view sits in the widget's layout (see widget_note.xml).
     */
    private fun widgetRect(id: Int): Rect? {
        val cached = WidgetStore.loadRect(this, id)
        val src = intent.sourceBounds ?: return cached
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val opts = AppWidgetManager.getInstance(this).getAppWidgetOptions(id)
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val width = cached?.width() ?: dp(opts.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH))
        val height = cached?.height() ?: dp(opts.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT))
        // Header: 6dp top padding plus a 34dp row. Sides: 14dp start padding; 6dp end padding plus the list's 8dp.
        val header = dp(6 + 34)
        val rect = when (intent.getStringExtra(EXTRA_SOURCE)) {
            // The empty-note text fills the whole list area, so this one is exact.
            SOURCE_LIST -> Rect(src.left - dp(14), src.top - header, src.right + dp(14), src.bottom + dp(10))
            // The edit button is the third of four 34dp buttons at the header's end.
            SOURCE_BUTTON -> {
                val right = src.right + dp(34 + 6)
                val top = src.top - dp(6)
                Rect(right - width, top, right, top + height)
            }
            SOURCE_ROW -> {
                val left = src.left - dp(14)
                val right = src.right + dp(14)
                // A row says nothing about how far the list is scrolled, so trust the last known top when it fits.
                val top = cached?.top?.takeIf { abs(cached.left - left) < dp(4) && src.top >= it && src.top < it + height }
                    ?: (src.top - header)
                return Rect(left, top, right, top + height).also { if (cached == null) WidgetStore.saveRect(this, id, it) }
            }
            else -> return cached
        }
        WidgetStore.saveRect(this, id, rect)
        return rect
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
    private fun Editor(p: Palette, rect: Rect?) {
        val text = Color(p.text)
        val accent = Color(p.accent)
        val font = remember { Fonts.compose(cfg.fontFile) }
        val focus = remember { FocusRequester() }
        val noRipple = remember { MutableInteractionSource() }
        val view = LocalView.current
        val density = LocalDensity.current
        var origin by remember { mutableStateOf(IntOffset.Zero) }
        var rootHeight by remember { mutableIntStateOf(0) }
        val ime = WindowInsets.ime.getBottom(density)
        val statusBar = WindowInsets.statusBars.getTop(density)

        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    val loc = IntArray(2)
                    view.getLocationOnScreen(loc)
                    origin = IntOffset(loc[0], loc[1])
                    rootHeight = it.size.height
                }
                .clickable(interactionSource = noRipple, indication = null) { done() },
        ) {
            val card = if (rect != null && rootHeight > 0) {
                // Sit on the widget; if the keyboard would cover it, shrink it, and if that leaves too little, move it up.
                with(density) {
                    var top = rect.top - origin.y
                    var height = rect.height()
                    val limit = rootHeight - ime - 8.dp.roundToPx()
                    if (top + height > limit) {
                        val min = minOf(height, 220.dp.roundToPx())
                        if (limit - top < min) top = maxOf(statusBar, limit - min)
                        height = limit - top
                    }
                    Modifier
                        .offset { IntOffset(rect.left - origin.x, top) }
                        .size(rect.width().toDp(), height.toDp())
                }
            } else {
                Modifier.systemBarsPadding().imePadding().padding(12.dp).fillMaxWidth().fillMaxHeight(0.6f).align(Alignment.Center)
            }
            Column(
                card
                    .clip(RoundedCornerShape(dimensionResource(R.dimen.widget_radius)))
                    .background(Color(p.background).copy(alpha = cfg.opacity / 100f))
                    // Swallow taps on the card so they don't count as tapping outside it.
                    .clickable(interactionSource = noRipple, indication = null) {},
            ) {
                // Same padding and sizes as the widget's header, so nothing shifts when it opens.
                Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp).height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        cfg.title, Modifier.weight(1f), color = Color(p.title), fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        fontFamily = font, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = {
                        if (value.text == original || save(force = false)) {
                            startActivity(Intent(Intent.ACTION_VIEW, Vault.openUri(this@EditorActivity, cfg.notePath)))
                            finish()
                        }
                    }, Modifier.size(34.dp)) {
                        Icon(painterResource(R.drawable.ic_open), "Open in Obsidian", Modifier.size(18.dp), tint = Color(p.muted))
                    }
                    IconButton(onClick = { done() }, Modifier.size(34.dp)) {
                        Icon(Icons.Default.Check, "Done", Modifier.size(22.dp), tint = accent)
                    }
                }
                CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(accent, accent.copy(alpha = 0.35f))) {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        enabled = loaded,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
                            .focusRequester(focus),
                        textStyle = TextStyle(color = text, fontSize = cfg.textSize.sp, lineHeight = 1.45.em, fontFamily = font),
                        cursorBrush = SolidColor(accent),
                    )
                }
            }
        }
        LaunchedEffect(loaded) { if (loaded) focus.requestFocus() }
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
