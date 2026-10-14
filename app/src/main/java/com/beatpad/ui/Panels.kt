package com.beatpad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.beatpad.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One section of a screen.
 * fill = true: the content fills whatever height the section has (pads, piano roll, keyboard).
 * fill = false: the section is as tall as its content; once resized, extra content scrolls inside it.
 */
class PanelDef(
    val id: String,
    val title: String,
    val fill: Boolean = false,
    val defaultHeight: Dp = 300.dp,
    val content: @Composable () -> Unit,
)

private const val MIN_PANEL_DP = 90f
private const val MAX_PANEL_DP = 1600f

/**
 * A screen made of stacked sections. A bar at the top jumps to any section and holds the
 * lock. Unlocked, each section can be dragged by its grip to a new place and resized from
 * its bottom edge. Tapping a section's title folds it. Layouts are saved per screen.
 */
@Composable
fun PanelStack(vm: MainViewModel, screen: String, panels: List<PanelDef>, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val ids = panels.map { it.id }
    val currentIds by rememberUpdatedState(ids)
    val defs = panels.associateBy { it.id }
    val layout = vm.layoutFor(screen, ids)
    val locked = vm.layoutLocked
    val measured = remember(screen) { mutableStateMapOf<String, Int>() }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var viewH by remember { mutableIntStateOf(0) }
    val gap = with(density) { 10.dp.toPx() }
    val padTop = with(density) { 6.dp.toPx() }
    val edge = with(density) { 64.dp.toPx() }
    val grabY = with(density) { 22.dp.toPx() }

    fun topOf(id: String, ord: List<String>): Float {
        var y = 0f
        for (o in ord) {
            if (o == id) return y
            y += (measured[o] ?: 0) + gap
        }
        return y
    }

    Column(modifier.fillMaxWidth()) {
        // section bar: jump to a section, lock / unlock the layout
        Row(
            Modifier.fillMaxWidth().background(Bg).padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (id in layout.order) {
                    val def = defs[id] ?: continue
                    val folded = id in layout.collapsed
                    SmallChip(def.title, PadOff, textColor = if (folded) TextDim else Color.White) {
                        scope.launch {
                            if (id in vm.layoutFor(screen, currentIds).collapsed) {
                                vm.setCollapsed(screen, currentIds, id, false)
                                delay(80)
                            }
                            val target = padTop + topOf(id, vm.layoutFor(screen, currentIds).order)
                            scroll.animateScrollTo(target.toInt())
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            SmallChip(
                if (locked) "🔒 Locked" else "🔓 Unlocked",
                if (locked) PadOff else Accent
            ) { vm.toggleLayoutLock() }
        }
        if (!locked) {
            Row(
                Modifier.fillMaxWidth().background(Bg).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Drag the dots to move a section. Drag the bar under it to resize.",
                    color = TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f)
                )
                val allFolded = ids.all { it in layout.collapsed }
                SmallChip(if (allFolded) "Open all" else "Fold all", PadOff) {
                    vm.collapseAll(screen, currentIds, !allFolded)
                }
                SmallChip("Reset", PadOff) { vm.resetLayout(screen) }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged { viewH = it.height }
                .verticalScroll(scroll)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            for (id in layout.order) {
                val def = defs[id] ?: continue
                key(id) {
                    PanelFrame(
                        def = def,
                        collapsed = id in layout.collapsed,
                        locked = locked,
                        savedHeight = layout.heights[id],
                        dragging = dragId == id,
                        dragOffset = { if (dragId == id) dragOffset else 0f },
                        onMeasured = { measured[id] = it },
                        onToggle = {
                            val folded = id in vm.layoutFor(screen, currentIds).collapsed
                            vm.setCollapsed(screen, currentIds, id, !folded)
                        },
                        onDragStart = {
                            dragId = id
                            dragOffset = 0f
                        },
                        onDrag = { dy ->
                            dragOffset += dy
                            val ord = vm.layoutFor(screen, currentIds).order
                            val idx = ord.indexOf(id)
                            val h = (measured[id] ?: 0).toFloat()
                            val center = topOf(id, ord) + dragOffset + h / 2f
                            if (idx in 0 until ord.size - 1) {
                                val next = ord[idx + 1]
                                val nh = (measured[next] ?: 0).toFloat()
                                if (center > topOf(next, ord) + nh / 2f) {
                                    vm.movePanel(screen, currentIds, id, idx + 1)
                                    dragOffset -= nh + gap
                                }
                            }
                            if (idx > 0) {
                                val prev = ord[idx - 1]
                                val ph = (measured[prev] ?: 0).toFloat()
                                if (center < topOf(prev, ord) + ph / 2f) {
                                    vm.movePanel(screen, currentIds, id, idx - 1)
                                    dragOffset += ph + gap
                                }
                            }
                            // scroll when the dragged section reaches the top or bottom of the screen
                            val now = vm.layoutFor(screen, currentIds).order
                            val fingerY = padTop + topOf(id, now) + dragOffset + grabY - scroll.value
                            val step = when {
                                fingerY < edge -> -24f
                                fingerY > viewH - edge -> 24f
                                else -> 0f
                            }
                            if (step != 0f) scroll.dispatchRawDelta(step)
                        },
                        onDragEnd = {
                            dragId = null
                            dragOffset = 0f
                        },
                        onResized = { dp -> vm.setPanelHeight(screen, currentIds, id, dp) },
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun PanelFrame(
    def: PanelDef,
    collapsed: Boolean,
    locked: Boolean,
    savedHeight: Float?,
    dragging: Boolean,
    dragOffset: () -> Float,
    onMeasured: (Int) -> Unit,
    onToggle: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onResized: (Float) -> Unit,
) {
    val density = LocalDensity.current
    var liveH by remember { mutableStateOf<Float?>(null) }   // dp, while the bottom bar is dragged
    var contentH by remember { mutableIntStateOf(0) }        // px, natural height of a fit section
    val hDp: Float? = liveH ?: savedHeight ?: if (def.fill) def.defaultHeight.value else null
    val heightNow by rememberUpdatedState(hDp)
    val contentNow by rememberUpdatedState(contentH)
    val startDrag by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val endDrag by rememberUpdatedState(onDragEnd)
    val resized by rememberUpdatedState(onResized)
    val innerScroll = rememberScrollState()
    val shape = RoundedCornerShape(16.dp)

    Column(
        Modifier.fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffset()
                if (dragging) {
                    scaleX = 1.02f
                    scaleY = 1.02f
                }
            }
            .onSizeChanged { onMeasured(it.height) }
            .clip(shape)
            .background(Panel)
            .border(if (dragging) 2.dp else 1.dp, if (dragging) Accent else PadOff, shape)
    ) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onToggle).padding(start = 14.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(def.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (!locked) {
                Box(
                    Modifier.size(44.dp).pointerInput(def.id) {
                        detectDragGestures(
                            onDragStart = { startDrag() },
                            onDragEnd = { endDrag() },
                            onDragCancel = { endDrag() },
                        ) { change, amount ->
                            change.consume()
                            drag(amount.y)
                        }
                    },
                    contentAlignment = Alignment.Center
                ) { GripIcon(if (dragging) Accent else TextDim) }
            }
            Text(if (collapsed) "▸" else "▾", color = TextDim, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 12.dp))
        }
        if (!collapsed) {
            val sized = hDp
            val body = if (sized != null) {
                Modifier.fillMaxWidth().height(sized.dp).then(if (def.fill) Modifier else Modifier.verticalScroll(innerScroll))
            } else {
                Modifier.fillMaxWidth().onSizeChanged { contentH = it.height }
            }
            Box(body) { def.content() }
            if (!locked) {
                Box(
                    Modifier.fillMaxWidth().height(26.dp).pointerInput(def.id) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                liveH = heightNow ?: with(density) { contentNow.toDp().value }
                            },
                            onDragEnd = {
                                liveH?.let { resized(it) }
                                liveH = null
                            },
                            onDragCancel = {
                                liveH?.let { resized(it) }
                                liveH = null
                            },
                        ) { change, dy ->
                            change.consume()
                            val cur = liveH
                            if (cur != null) {
                                liveH = (cur + with(density) { dy.toDp().value }).coerceIn(MIN_PANEL_DP, MAX_PANEL_DP)
                            }
                        }
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.width(56.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF6A6A78)))
                }
            }
        }
    }
}

/** Six dots: the handle you drag to move a section. */
@Composable
private fun GripIcon(color: Color) {
    Canvas(Modifier.size(width = 14.dp, height = 20.dp)) {
        val r = size.width * 0.13f
        for (c in 0..1) for (row in 0..2) {
            drawCircle(color, radius = r, center = Offset(size.width * (0.28f + 0.44f * c), size.height * (0.2f + 0.3f * row)))
        }
    }
}
