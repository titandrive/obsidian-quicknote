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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
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
        /** A tapped task's line as the widget drew it. */
        const val EXTRA_SOURCE = "source"

        /**
         * Ticks a task on the widget. One at a time: two quick taps each reading the note and
         * writing it back could lose the first tick.
         */
        fun toggleTask(ctx: Context, cfg: WidgetConfig, line: Int, expected: String? = null) {
            synchronized(TICKS) {
                val text = Vault.read(ctx, cfg.uri) ?: return
                val next = Markdown.toggleTask(text, line, expected) ?: return
                Vault.write(ctx, cfg.uri, next)
            }
            NoteWidgetProvider.refreshNote(ctx, cfg.noteUri)
        }

        private val TICKS = Any()
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
    /** Folded regions, by start line of the text being edited; saved with the note. */
    private var folded by mutableStateOf<Set<Int>>(emptySet())
    /** The folds as last saved, to tell whether folding changed with no edit to save. */
    private var savedFolds: Set<Int> = emptySet()
    /** Whether the formatting toolbar shows; remembered per widget. */
    private var showToolbar by mutableStateOf(true)
    /** The table cell being edited, by its table's header line, row (0 the header) and column. */
    private class CellEdit(val table: Int, val row: Int, val col: Int)
    private var cellEdit by mutableStateOf<CellEdit?>(null)
    /** What's in the cell being edited, as typed. */
    private var cellValue by mutableStateOf(TextFieldValue(""))

    /** The vault's mobile toolbar buttons, once read. */
    private var toolbar by mutableStateOf<List<Toolbar.Command>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        widgetId = id
        cfg = WidgetStore.load(this, id) ?: return finish()
        showToolbar = cfg.showToolbar

        enableEdgeToEdge()
        val line = intent.getIntExtra(EXTRA_LINE, -1)
        lifecycleScope.launch { toolbar = withContext(Dispatchers.IO) { Toolbar.load(this@EditorActivity, cfg.vaultTree) } }
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { Vault.read(this@EditorActivity, cfg.uri) }.orEmpty()
            original = text
            savedFolds = withContext(Dispatchers.IO) { Folds.folded(this@EditorActivity, widgetId, text) }
            folded = savedFolds
                // A tapped line inside a fold opens it, as in Obsidian.
                .let { f -> if (line >= 0) f.filterNot { from -> Folds.regions(text)[from]?.let { line in it.from + 1..it.to } == true }.toSet() else f }
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
            Markdown.toggleTask(value.text, line, intent.getStringExtra(EXTRA_SOURCE))?.let { value = value.copy(text = it) }
        } else if (line >= 0) {
            // Another line tapped while the editor was still open in the background: go there.
            value = value.copy(selection = TextRange(cursorFor(value.text, line)))
            scrollToCursor = true
        }
    }

    override fun onPause() {
        super.onPause()
        // Leaving by home or recents still keeps the edit, and any folding.
        if (loaded && !isFinishing) {
            closeCell()
            if (value.text != original) save(force = true)
            saveFolds()
        }
    }

    private fun done() {
        closeCell()
        if (!loaded || value.text == original || save(force = false)) {
            saveFolds()
            finish()
        }
    }

    /**
     * Saves folding done without editing (an edit saves its folds along with the text), so the
     * widget shows the same folds; folds are the widget's, shared between it and the editor.
     */
    private fun saveFolds() {
        if (!loaded || folded == savedFolds || value.text != original) return
        Folds.set(this, widgetId, value.text, folded)
        savedFolds = folded
        NoteWidgetProvider.refreshNote(this, cfg.noteUri)
    }

    /** Writes the note; without [force], refuses if it was changed elsewhere since it was opened. */
    private fun save(force: Boolean): Boolean {
        val text = value.text
        val disk = Vault.read(this, cfg.uri)
        if (!force && disk != null && disk != original && disk != text) {
            conflict = true
            return false
        }
        runCatching { Vault.write(this, cfg.uri, text) }.onSuccess {
            // The folds as they are in the text just saved.
            Folds.set(this, widgetId, text, folded)
            savedFolds = folded
        }.onFailure {
            android.widget.Toast.makeText(this, "Couldn't save: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
            return false
        }
        original = text
        NoteWidgetProvider.refreshNote(this, cfg.noteUri)
        return true
    }

    private fun tableAt(start: Int) = Tables.find(value.text).firstOrNull { it.start == start }

    /** Starts editing cell [c] of row [r] of table [t], saving any cell being edited first. */
    private fun openCell(t: Tables.Table, r: Int, c: Int) {
        closeCell()
        val cell = Tables.unescape(t.rows[r][c])
        cellEdit = CellEdit(t.start, r, c)
        cellValue = TextFieldValue(cell, TextRange(cell.length))
    }

    /** Writes the cell being edited into the table, if it changed. */
    private fun commitCell() {
        val e = cellEdit ?: return
        val t = tableAt(e.table)
        if (t == null || e.row >= t.rows.size || e.col >= t.columns) {
            cellEdit = null
            return
        }
        if (Tables.unescape(t.rows[e.row][e.col]) != cellValue.text) {
            applyTable(t, Tables.setCell(value.text, t, e.row, e.col, cellValue.text))
        }
    }

    private fun closeCell() {
        commitCell()
        cellEdit = null
    }

    /** Tab: on to the next cell, or a new row after the last one, as in Obsidian. */
    private fun nextCell() {
        val e = cellEdit ?: return
        commitCell()
        var t = tableAt(e.table) ?: return
        var r = e.row
        var c = e.col + 1
        if (c >= t.columns) {
            c = 0
            r++
            if (r >= t.rows.size) {
                applyTable(t, Tables.addRow(value.text, t, r - 1))
                t = tableAt(e.table) ?: return
            }
        }
        cellEdit = null
        openCell(t, r, c)
    }

    /** A row or column change from the table bar, for the cell being edited. */
    private fun tableOp(op: String) {
        val e = cellEdit ?: return
        commitCell()
        val t = tableAt(e.table) ?: return
        val (text, r, c) = when (op) {
            "row+" -> Triple(Tables.addRow(value.text, t, e.row), if (e.row == 0) 1 else e.row + 1, e.col)
            "row-" -> Triple(Tables.deleteRow(value.text, t, e.row), (e.row - 1).coerceAtLeast(0), e.col)
            "col+" -> Triple(Tables.addColumn(value.text, t, e.col), e.row, e.col + 1)
            "col-" -> Triple(Tables.deleteColumn(value.text, t, e.col), e.row, (e.col - 1).coerceAtLeast(0))
            else -> Triple(Tables.cycleAlign(value.text, t, e.col), e.row, e.col)
        }
        applyTable(t, text)
        cellEdit = null
        tableAt(e.table)?.let { openCell(it, r.coerceAtMost(it.rows.size - 1), c.coerceAtMost(it.columns - 1)) }
    }

    /** Puts [text], with table [t] changed, in the editor, keeping the cursor and folds where they were. */
    private fun applyTable(t: Tables.Table, text: String) {
        val old = value
        if (text == old.text) return
        val at = lineStart(old.text, t.start) ?: 0
        val delta = text.length - old.text.length
        fun shift(i: Int) = if (i > at) (i + delta).coerceIn(at, text.length) else i
        val next = TextFieldValue(text, TextRange(shift(old.selection.start), shift(old.selection.end)))
        history.record(old, next, action = true)
        if (text.count { it == '\n' } != old.text.count { it == '\n' }) folded = Folds.moved(folded, old.text, text)
        value = next
    }

    /** A table drawn as a grid: column lefts and widths, row heights, and each cell laid out, in px. */
    private class Grid(
        val table: Tables.Table, val xs: IntArray, val ws: IntArray, val hs: IntArray,
        val cells: List<List<androidx.compose.ui.text.TextLayoutResult>>,
    ) {
        val width get() = xs.last() + ws.last()
        fun rowTop(r: Int) = (0 until r).sumOf { hs[it] }
    }

    private fun cursorFor(text: String, line: Int): Int {
        // No line (the edit button): the top of the note.
        if (line < 0) return 0
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
        val regions = remember(value.text) { Folds.regions(value.text) }
        val collapsed = remember(value.text, folded) { Folds.hiddenLines(value.text, folded, regions) }
        val density = LocalDensity.current
        // Tables are drawn as grids, as in Obsidian, except while the cursor is in one.
        val measurer = rememberTextMeasurer()
        val cellStyle = TextStyle(color = text, fontSize = cfg.textSize.sp, lineHeight = 1.45.em, fontFamily = font)
        val styler = remember { LivePreview(p, cfg.textSize, IntRange.EMPTY, -1) }
        val tables = remember(value.text, collapsed) { Tables.find(value.text, collapsed) }
        val drawn = tables.filter { t -> active.last < t.start || active.first > t.end }
        val textWidth = layout?.layoutInput?.constraints?.maxWidth ?: 0
        val cellPadX = with(density) { 6.dp.roundToPx() }
        val cellPadY = with(density) { 3.dp.roundToPx() }
        // The cell being edited is laid out with what's typed so far, so its column and row grow as you type.
        val editing = cellEdit
        val typed = if (editing != null) cellValue.text else null
        val grids = remember(tables, drawn.map { it.start }, textWidth, editing, typed) {
            if (textWidth <= 0) emptyList() else drawn.map { t ->
                val styled = t.rows.mapIndexed { r, row ->
                    row.mapIndexed { c, cell ->
                        val live = editing?.takeIf { it.table == t.start && it.row == r && it.col == c }?.let { typed }
                        styler.styleCell(live ?: Tables.unescape(cell), header = r == 0)
                    }
                }
                // Columns as wide as their contents, then the widest narrowed until the table fits.
                val ws = IntArray(t.columns) { c ->
                    styled.maxOf { row -> measurer.measure(row[c], cellStyle, softWrap = false, maxLines = 1).size.width } + 2 * cellPadX
                }
                val minWidth = 3 * cellPadX + with(density) { cfg.textSize.sp.roundToPx() }
                while (ws.sum() > textWidth - 1) {
                    val widest = ws.indices.maxBy { ws[it] }
                    if (ws[widest] <= minWidth) break
                    ws[widest] = maxOf(minWidth, ws[widest] - maxOf(1, (ws.sum() - textWidth + 1).coerceAtMost(ws[widest] / 4)))
                }
                val xs = IntArray(t.columns).also { for (c in 1 until t.columns) it[c] = it[c - 1] + ws[c - 1] }
                val cells = styled.map { row ->
                    row.mapIndexed { c, cell -> measurer.measure(cell, cellStyle, constraints = Constraints(maxWidth = (ws[c] - 2 * cellPadX).coerceAtLeast(1))) }
                }
                Grid(t, xs, ws, IntArray(cells.size) { r -> cells[r].maxOf { it.size.height } + 2 * cellPadY }, cells)
            }
        }
        val tableRows = remember(grids) {
            grids.flatMap { g -> g.hs.indices.map { r -> g.table.line(r) to with(density) { g.hs[r].toSp() } } }.toMap()
        }
        val tableRules = remember(grids) { grids.map { it.table.start + 1 }.toSet() }
        val preview = remember(active, cursor, collapsed, tableRows) { LivePreview(p, cfg.textSize, active, cursor, collapsed, tableRows, tableRules) }
        val callouts = remember(value.text, collapsed) { LivePreview.calloutBoxes(value.text, p, collapsed) }
        val gutter = with(density) { 22.dp.toPx() }
        val tick = rememberHaptic()
        val padTop = with(density) { 8.dp.toPx() }

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
                        // Saved onto the widget's latest settings, which may have changed since opening.
                        WidgetStore.load(this@EditorActivity, widgetId)?.let {
                            WidgetStore.save(this@EditorActivity, widgetId, it.copy(showToolbar = showToolbar))
                        }
                    }
                    val spin by rememberInfiniteTransition(label = "refresh").animateFloat(
                        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin",
                    )
                    HeaderButton(R.drawable.ic_refresh, "Reload note", muted, enabled = loaded, rotation = if (reloading) spin else 0f) {
                        if (!reloading) reload()
                    }
                    HeaderButton(R.drawable.ic_open, "Open in Obsidian", muted) {
                        leaveFor(Intent(Intent.ACTION_VIEW, Vault.openUri(cfg.vaultName, cfg.notePath)))
                    }
                    if (cfg.showSettings) HeaderButton(R.drawable.ic_settings, "Widget settings", muted) {
                        leaveFor(NoteWidgetProvider.configIntent(this@EditorActivity, widgetId, pickOnly = false))
                    }
                    HeaderButton(R.drawable.ic_check, "Done", accent, size = 22.dp) { done() }
                }
                CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(accent, accent.copy(alpha = 0.35f))) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                    // Scrolled here rather than inside the field, so it can be moved to the tapped line.
                    BasicTextField(
                        value = value,
                        onValueChange = {
                            val next = LivePreview.continueList(value, it)
                            history.record(value, next)
                            // Folds follow their lines when lines are added or removed above them.
                            if (next.text.count { c -> c == '\n' } != value.text.count { c -> c == '\n' }) {
                                folded = Folds.moved(folded, value.text, next.text)
                            }
                            value = next
                        },
                        enabled = loaded,
                        onTextLayout = { layout = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            // Fold arrows in a gutter at the left, as in Obsidian.
                            .drawBehind {
                                val l = layout ?: return@drawBehind
                                val text = value.text
                                drawCallouts(l, text, preview, callouts, padTop, with(density) { 22.dp.toPx() }, size.width - with(density) { 18.dp.toPx() }, with(density) { cfg.textSize.sp.toPx() }, p)
                                // Tables as grids: the header shaded, one-pixel lines in the faint colour.
                                for (g in grids) {
                                    val y0 = gridTop(l, text, preview, g, padTop) ?: continue
                                    val x0 = gutter
                                    val h = g.hs.sum().toFloat()
                                    drawRect(Color(p.backgroundAlt), Offset(x0, y0), androidx.compose.ui.geometry.Size(g.width.toFloat(), g.hs[0].toFloat()))
                                    val line = Color(p.faint)
                                    var y = y0
                                    for (r in g.hs.indices) {
                                        drawRect(line, Offset(x0, y), androidx.compose.ui.geometry.Size(g.width.toFloat(), 1f))
                                        for (c in g.ws.indices) {
                                            val e = cellEdit
                                            if (e != null && e.table == g.table.start && e.row == r && e.col == c) continue
                                            val cell = g.cells[r][c]
                                            val room = g.ws[c] - 2 * cellPadX - cell.size.width
                                            val dx = when (Tables.align(g.table, c)) { "right" -> room; "center" -> room / 2; else -> 0 }.coerceAtLeast(0)
                                            drawText(cell, topLeft = Offset(x0 + g.xs[c] + cellPadX + dx, y + cellPadY))
                                        }
                                        y += g.hs[r]
                                    }
                                    drawRect(line, Offset(x0, y0 + h), androidx.compose.ui.geometry.Size(g.width.toFloat(), 1f))
                                    for (x in g.xs) drawRect(line, Offset(x0 + x, y0), androidx.compose.ui.geometry.Size(1f, h + 1))
                                    drawRect(line, Offset(x0 + g.width - 1, y0), androidx.compose.ui.geometry.Size(1f, h + 1))
                                }
                                for ((from, _) in regions) {
                                    if (from in collapsed) continue
                                    val start = lineStart(text, from) ?: continue
                                    val vl = l.getLineForOffset(preview.toShown(start).coerceAtMost(l.layoutInput.text.length))
                                    val cy = padTop + (l.getLineTop(vl) + l.getLineBottom(vl)) / 2
                                    drawPath(foldArrow(gutter / 2, cy, with(density) { 4.dp.toPx() }, from in folded), muted)
                                }
                            }
                            // Taps in the gutter fold or unfold, before the text field sees them.
                            .pointerInput(regions, grids, preview) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                                    // A tap on a drawn table edits the cell under it, as in Obsidian.
                                    val hit = layout?.let { l -> cellAt(l, value.text, preview, grids, padTop, gutter, down.position) }
                                    if (down.position.x > gutter && hit == null) return@awaitEachGesture
                                    down.consume()
                                    val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                                    up.consume()
                                    if (hit != null) {
                                        tick()
                                        openCell(hit.first.table, hit.second, hit.third)
                                        return@awaitEachGesture
                                    }
                                    val l = layout ?: return@awaitEachGesture
                                    val offset = preview.toText(l.getOffsetForPosition(Offset(gutter, up.position.y - padTop)))
                                    val line = value.text.substring(0, offset.coerceAtMost(value.text.length)).count { it == '\n' }
                                    if (line in regions) {
                                        tick()
                                        folded = if (line in folded) folded - line else folded + line
                                    }
                                }
                            }
                            .padding(start = 22.dp, end = 18.dp, top = 8.dp, bottom = 8.dp)
                            .focusRequester(focus),
                        textStyle = TextStyle(color = text, fontSize = cfg.textSize.sp, lineHeight = 1.45.em, fontFamily = font),
                        cursorBrush = SolidColor(accent),
                        visualTransformation = preview,
                    )
                    // The cell being edited: a field of its own over the drawn cell.
                    val e = cellEdit
                    val g = grids.firstOrNull { it.table.start == e?.table }
                    val l = layout
                    if (e != null && g != null && l != null && e.row < g.hs.size && e.col < g.ws.size) {
                        val y = (gridTop(l, value.text, preview, g, padTop) ?: 0f) + g.rowTop(e.row)
                        val cellFocus = remember { FocusRequester() }
                        var focused by remember { mutableStateOf(false) }
                        BasicTextField(
                            value = cellValue,
                            // Wraps like the drawn cell; a line break moves on, since a cell can't hold one.
                            onValueChange = {
                                if ('\n' in it.text) {
                                    cellValue = it.copy(text = it.text.replace("\n", ""))
                                    nextCell()
                                } else {
                                    cellValue = it
                                }
                            },
                            textStyle = cellStyle.copy(fontWeight = if (e.row == 0) FontWeight.Bold else null),
                            cursorBrush = SolidColor(accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { nextCell() }),
                            modifier = Modifier
                                .offset { IntOffset((gutter + g.xs[e.col]).toInt(), (y - scroll.value).toInt()) }
                                .width(with(density) { g.ws[e.col].toDp() })
                                .heightIn(min = with(density) { g.hs[e.row].toDp() })
                                .background(bg)
                                .border(2.dp, accent)
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                .focusRequester(cellFocus)
                                .onFocusChanged {
                                    // Tapping elsewhere in the note finishes the cell.
                                    if (focused && !it.isFocused) closeCell()
                                    focused = it.isFocused
                                }
                                .onPreviewKeyEvent {
                                    if (it.key == Key.Tab && it.type == KeyEventType.KeyDown) { nextCell(); true } else false
                                },
                        )
                        LaunchedEffect(e) { cellFocus.requestFocus() }
                    }
                    }
                }
                if (cellEdit != null) TableBar(p) else if (showToolbar && toolbar.isNotEmpty()) ToolbarRow(p)
            }
        }
        LaunchedEffect(loaded) { if (loaded) focus.requestFocus() }
        LaunchedEffect(scrollToCursor, layout) {
            val l = layout ?: return@LaunchedEffect
            if (!scrollToCursor || !loaded) return@LaunchedEffect
            // A quarter of the way down, so the line stays in view once the keyboard opens.
            val top = l.getCursorRect(preview.toShown(value.selection.start).coerceIn(0, l.layoutInput.text.length)).top
            scroll.scrollTo((top - scroll.viewportSize / 4).toInt().coerceAtLeast(0))
            scrollToCursor = false
        }
    }

    /** Where grid [g]'s header row starts, in the field's coordinates. */
    private fun gridTop(l: TextLayoutResult, text: String, preview: LivePreview, g: Grid, top: Float): Float? {
        val start = lineStart(text, g.table.start) ?: return null
        return top + l.getLineTop(l.getLineForOffset(preview.toShown(start).coerceAtMost(l.layoutInput.text.length)))
    }

    /** The drawn table cell at [pos]: its table's grid, row and column. */
    private fun cellAt(l: TextLayoutResult, text: String, preview: LivePreview, grids: List<Grid>, top: Float, left: Float, pos: Offset): Triple<Grid, Int, Int>? {
        for (g in grids) {
            val y0 = gridTop(l, text, preview, g, top) ?: continue
            if (pos.x < left || pos.x >= left + g.width || pos.y < y0 || pos.y >= y0 + g.hs.sum()) continue
            val r = g.hs.indices.first { pos.y < y0 + g.rowTop(it + 1) }
            val c = g.ws.indices.last { pos.x >= left + g.xs[it] }
            return Triple(g, r, c)
        }
        return null
    }

    /** In place of the toolbar while a cell is edited: rows, columns and alignment, as Obsidian's table menu. */
    @Composable
    private fun TableBar(p: Palette) {
        val tick = rememberHaptic()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(p.backgroundAlt))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            for ((op, label) in listOf("row+" to "+ Row", "row-" to "− Row", "col+" to "+ Column", "col-" to "− Column", "align" to "Align")) {
                TextButton(onClick = { tick(); tableOp(op) }) { Text(label, color = Color(p.muted), fontSize = 14.sp) }
            }
            TextButton(onClick = { tick(); closeCell() }) { Text("Done", color = Color(p.accent), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
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

    /** Where line [line] of [text] starts, or null past the end. */
    private fun lineStart(text: String, line: Int): Int? {
        var offset = 0
        repeat(line) {
            val nl = text.indexOf('\n', offset)
            if (nl < 0) return null
            offset = nl + 1
        }
        return offset
    }

    /** Obsidian's fold arrow: pointing down when open, right when folded. */
    /**
     * Callout boxes behind their text, as Obsidian draws them: the callout's colour at 10%, rounded,
     * with the theme's border, and the type's icon beside the title.
     */
    private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCallouts(
        l: TextLayoutResult, text: String, preview: LivePreview, boxes: List<LivePreview.Companion.CalloutBox>,
        top: Float, left: Float, right: Float, em: Float, p: Palette,
    ) {
        val length = l.layoutInput.text.length
        val radius = p.calloutRadius * em / 16
        for (box in boxes) {
            val from = lineStart(text, box.from)?.let(preview::toShown)?.coerceAtMost(length) ?: continue
            val toStart = lineStart(text, box.to) ?: continue
            val toEnd = preview.toShown(text.indexOf('\n', toStart).let { if (it < 0) text.length else it }).coerceAtMost(length)
            val titleLine = l.getLineForOffset(from)
            val y0 = top + l.getLineTop(titleLine)
            // The text has no room for padding below the last line, so the box takes some of the gap after it.
            val y1 = top + l.getLineBottom(l.getLineForOffset(toEnd)) + em * 0.4f
            val color = Color(box.color)
            drawRoundRect(color.copy(alpha = 0.1f), Offset(left, y0), androidx.compose.ui.geometry.Size(right - left, y1 - y0), androidx.compose.ui.geometry.CornerRadius(radius))
            if (p.calloutBorder > 0f) {
                val w = maxOf(1f, p.calloutBorder * em / 16)
                drawRoundRect(
                    color.copy(alpha = p.calloutBorderOpacity), Offset(left + w / 2, y0 + w / 2),
                    androidx.compose.ui.geometry.Size(right - left - w, y1 - y0 - w), androidx.compose.ui.geometry.CornerRadius(radius),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(w),
                )
            }
            // The icon, level with the title's text, which sits at the bottom of its padded line.
            val icon = em * LivePreview.CALLOUT_ICON
            val mid = top + l.getLineBottom(titleLine) - em * LivePreview.LINE_HEIGHT / 2
            val path = Callouts.icon(box.icon)
            withTransform({
                translate(left + em * LivePreview.CALLOUT_LEFT, mid - icon / 2)
                scale(icon / 24f, icon / 24f, Offset.Zero)
            }) {
                drawPath(path.asComposePath(), color, style = androidx.compose.ui.graphics.drawscope.Stroke(2f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
    }

    private fun foldArrow(cx: Float, cy: Float, r: Float, isFolded: Boolean) = Path().apply {
        if (isFolded) {
            moveTo(cx - r * 0.6f, cy - r); lineTo(cx + r * 0.8f, cy); lineTo(cx - r * 0.6f, cy + r)
        } else {
            moveTo(cx - r, cy - r * 0.6f); lineTo(cx + r, cy - r * 0.6f); lineTo(cx, cy + r * 0.8f)
        }
        close()
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
            saveFolds()
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
