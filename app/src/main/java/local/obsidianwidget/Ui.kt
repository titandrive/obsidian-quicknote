package local.obsidianwidget

import android.os.Build
import android.text.SpannableStringBuilder
import android.widget.TextView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/*
 * The parts the app's screens are built from, after Paid to Poop's and PurgeBot's: a plain page
 * header, cards per topic with an icon tile, rows that open a page or sheet, and sliding pill
 * pickers. Colours and font come from the vault's Obsidian theme when one is loaded.
 */

/**
 * The app's theme. With a [palette] the screens take the vault theme's colours (and [font]), so
 * settings look like the widget they're for; without one, the system's dynamic colours.
 */
@Composable
fun AppTheme(palette: Palette? = null, font: FontFamily = FontFamily.Default, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        palette != null -> schemeFor(palette)
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val base = Typography()
    val type = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = font), displayMedium = base.displayMedium.copy(fontFamily = font),
        displaySmall = base.displaySmall.copy(fontFamily = font), headlineLarge = base.headlineLarge.copy(fontFamily = font),
        headlineMedium = base.headlineMedium.copy(fontFamily = font), headlineSmall = base.headlineSmall.copy(fontFamily = font),
        titleLarge = base.titleLarge.copy(fontFamily = font), titleMedium = base.titleMedium.copy(fontFamily = font),
        titleSmall = base.titleSmall.copy(fontFamily = font), bodyLarge = base.bodyLarge.copy(fontFamily = font),
        bodyMedium = base.bodyMedium.copy(fontFamily = font), bodySmall = base.bodySmall.copy(fontFamily = font),
        labelLarge = base.labelLarge.copy(fontFamily = font), labelMedium = base.labelMedium.copy(fontFamily = font),
        labelSmall = base.labelSmall.copy(fontFamily = font),
    )
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}

/** A Material colour scheme from an Obsidian palette: page, cards a step off it, the accent as primary. */
fun schemeFor(p: Palette): ColorScheme {
    val bg = Color(p.background)
    val dark = bg.luminance() < 0.5f
    // Cards need to stand off the page even when a theme's secondary background is the same colour.
    val card = Color(p.backgroundAlt).takeIf { it != bg } ?: if (dark) Color.White.copy(alpha = 0.06f).over(bg) else Color.Black.copy(alpha = 0.04f).over(bg)
    val accent = Color(p.accent)
    val onAccent = if (accent.luminance() > 0.4f) Color(0xFF111111) else Color.White
    val text = Color(p.text)
    val muted = Color(p.muted)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = onAccent,
        primaryContainer = accent.copy(alpha = 0.2f).over(card), onPrimaryContainer = text,
        secondary = accent, onSecondary = onAccent,
        secondaryContainer = accent.copy(alpha = 0.2f).over(card), onSecondaryContainer = text,
        tertiary = Color(p.link), onTertiary = onAccent,
        background = bg, onBackground = text,
        surface = bg, onSurface = text,
        surfaceVariant = card, onSurfaceVariant = muted,
        surfaceContainerLowest = bg, surfaceContainerLow = card, surfaceContainer = card,
        surfaceContainerHigh = card, surfaceContainerHighest = card,
        outline = Color(p.faint), outlineVariant = Color(p.faint).copy(alpha = 0.5f),
    )
}

private fun Color.over(under: Color) = Color(androidx.core.graphics.ColorUtils.compositeColors(
    android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()),
    android.graphics.Color.argb(255, (under.red * 255).toInt(), (under.green * 255).toInt(), (under.blue * 255).toInt()),
))

/** A light tick, as Paid to Poop gives every tap. */
@Composable
fun rememberHaptic(): () -> Unit {
    val ctx = LocalContext.current
    return { Haptics.tick(ctx) }
}

/** Description text under a setting: softer than its label, so it never reads as a heading. */
@Composable
fun noteColor() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)

/** A page's header: the title on the page's own background, with a back arrow before it. */
@Composable
fun PageHeader(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    val tick = rememberHaptic()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(start = 4.dp, end = 12.dp, top = 4.dp),
    ) {
        // Colours set outright: on a see-through page (settings), the inherited text colour is black.
        IconButton(onClick = { tick(); onBack() }) {
            Icon(painterResource(R.drawable.ic_back), "Back", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onBackground)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

/** A settings card: an icon in a small tile and a title, then its settings. */
@Composable
fun SectionCard(title: String, icon: Int, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                IconTile(icon)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

/** A Lucide icon on a small rounded tile of the page colour. */
@Composable
fun IconTile(icon: Int, size: Dp = 32.dp, tint: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.56f))
    }
}

/** A row that opens a page or sheet: its label and value, an optional thing before, a chevron. */
@Composable
fun NavRow(
    title: String,
    subtitle: String?,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val tick = rememberHaptic()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { tick(); onClick() }
            .padding(vertical = 8.dp),
    ) {
        leading?.let {
            it()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = noteColor(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** A setting that's on or off: the whole row toggles, the switch shows which. */
@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val tick = rememberHaptic()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch) { tick(); onChange(it) }
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = noteColor()) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A setting's label and description, above its control, with its current [value] at the right. */
@Composable
fun SettingLabel(title: String, description: String? = null, value: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = noteColor()) }
        }
        value?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
    }
}

/** A slider that snaps to every [step] in [range], with tick marks and a light tick on each step. */
@Composable
fun StepSlider(value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    val tick = rememberHaptic()
    Slider(
        value = value.toFloat(),
        onValueChange = { v ->
            val snapped = ((v / step).roundToInt() * step).coerceIn(range.first, range.last)
            if (snapped != value) {
                tick()
                onChange(snapped)
            }
        },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first) / step - 1,
        colors = SliderDefaults.colors(
            inactiveTrackColor = MaterialTheme.colorScheme.background,
            activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f),
            inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        ),
    )
}

/** A row of options on a rounded track, the picked one on a sliding accent pill. For a few short choices. */
@Composable
fun <T> PillSegmented(options: List<T>, selected: T, name: (T) -> String, onSelect: (T) -> Unit) {
    val tick = rememberHaptic()
    val index = options.indexOf(selected).coerceAtLeast(0)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background)
            .padding(4.dp),
    ) {
        val segment = maxWidth / options.size
        val offset by animateDpAsState(segment * index, label = "pill")
        Box(
            Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { option ->
                val on = option == selected
                val color by animateColorAsState(
                    if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "pillText",
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(selected = on, role = Role.RadioButton) {
                            if (!on) tick()
                            onSelect(option)
                        },
                ) {
                    Text(
                        name(option),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

/** A bottom sheet listing [options], the picked one highlighted and ticked. Tapping one picks it and closes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> PickerSheet(
    title: String,
    options: List<T>,
    selected: T,
    name: (T) -> String,
    detail: (T) -> String? = { null },
    leading: (@Composable (T) -> Unit)? = null,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    val tick = rememberHaptic()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        // The title fixed, the options scrolling below it. The list keeps whatever scroll or fling
        // is left over at its ends: passed on, it moved the sheet, which snapped back, so it bounced.
        val keepScroll = remember {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = available
                override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available
            }
        }
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.75f).dp
        Column(Modifier.heightIn(max = maxHeight).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .nestedScroll(keepScroll)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                options.forEach { option ->
                    val on = option == selected
                    val accent = MaterialTheme.colorScheme.primary
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) accent.copy(alpha = 0.14f) else Color.Transparent)
                            .border(2.dp, if (on) accent else Color.Transparent, RoundedCornerShape(16.dp))
                            .clickable { tick(); onSelect(option) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        leading?.let {
                            it(option)
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                name(option),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (on) accent else MaterialTheme.colorScheme.onSurface,
                            )
                            detail(option)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = noteColor()) }
                        }
                        if (on) Icon(painterResource(R.drawable.ic_check), "Selected", tint = accent, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/** A theme in miniature: its page colour, a line of text, and its accent and heading dots. */
@Composable
fun ThemeSwatch(p: Palette?, modifier: Modifier = Modifier.size(width = 46.dp, height = 38.dp)) {
    val shape = RoundedCornerShape(10.dp)
    if (p == null) {
        Box(modifier.clip(shape).background(MaterialTheme.colorScheme.background))
        return
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(shape)
            .background(Color(p.background))
            .border(1.dp, Color(p.backgroundAlt), shape)
            .padding(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color(p.text).copy(alpha = 0.7f)))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(p.accent, p.headings[0], p.link).forEach {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(it)))
            }
        }
    }
}

/** A search box on a rounded track, like the pill pickers. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp),
    ) {
        Icon(painterResource(R.drawable.ic_search), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = noteColor())
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Renders [blocks] the way the widget will, using a real TextView so the spans match. */
@Composable
fun NotePreview(blocks: List<Block>, p: Palette, textSize: Float, typeface: android.graphics.Typeface, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { TextView(it).apply { setLineSpacing(0f, 1.15f) } },
        update = { tv ->
            val sb = SpannableStringBuilder()
            blocks.forEachIndexed { i, b ->
                if (i > 0) sb.append('\n')
                sb.append(if (b.rule) "———" else b.text)
            }
            tv.text = sb
            tv.setTextColor(p.text)
            tv.textSize = textSize
            tv.typeface = typeface
        },
        modifier = modifier,
    )
}
