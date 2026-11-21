package com.wuyi.kebiao

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 导航栏的状态桥。
 *
 * <p>状态放在 Kotlin 侧（Compose 的 State），Java 只调 [setSelected] / [setGlassEnabled]，
 * 这样切换页面时珠子可以平滑滑过去，而不是重建整棵 Compose 树。
 */
class GlassNavController(private val labels: Array<String>, val onSelect: (Int) -> Unit) {

    val tabLabels: Array<String> get() = labels

    internal val selected = mutableIntStateOf(0)
    internal var glassEnabled by mutableStateOf(true)

    fun setSelected(index: Int) {
        if (index in labels.indices && selected.intValue != index) {
            selected.intValue = index
        }
    }

    fun setGlassEnabled(enabled: Boolean) {
        glassEnabled = enabled
    }
}

/** 从主题里取出来的颜色，Java 侧用 MaterialColors 解析后传进来。 */
class NavPalette(
    val selectedColor: Int,
    val normalColor: Int,
    val barColor: Int,
    val rimTop: Int
)

/** Java 侧入口：传进 ComposeView 和状态桥，剩下的都在 Compose 里。 */
fun installGlassNav(
    view: ComposeView,
    controller: GlassNavController,
    palette: NavPalette
) {
    view.setContent {
        GlassNavBar(controller, palette)
    }
}

private val barH = 64.dp
private val lensW = 84.dp
private val lensH = 56.dp

@Composable
private fun GlassNavBar(controller: GlassNavController, palette: NavPalette) {
    val backdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var barWidthPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    val centers = remember(barWidthPx, controller.tabLabels.size) {
        val n = controller.tabLabels.size
        if (barWidthPx <= 0f) List(n) { 0f }
        else List(n) { barWidthPx * (it + 0.5f) / n }
    }

    val lensX = remember { Animatable(0f) }
    val lensScale = remember { Animatable(1f) }
    var liveX by remember { mutableFloatStateOf(0f) }

    val lensHalfW = with(density) { lensW.toPx() } / 2f

    // 第一次量出宽度时直接把珠子放到位，不要从 0 动画过去 ——
    // 否则首帧会看到珠子从左边滑到选中项，像"进场动画"。
    var lensInited by remember { mutableStateOf(false) }
    LaunchedEffect(centers) {
        if (!lensInited && centers.isNotEmpty() && centers.last() > 0f) {
            lensX.snapTo(centers[controller.selected.intValue.coerceIn(0, centers.size - 1)])
            lensInited = true
        }
    }

    // 外部（点列表按钮、返回键）改了选中项时，珠子滑过去
    LaunchedEffect(controller.selected.intValue, centers, lensInited) {
        if (lensInited && centers.isNotEmpty() && !dragging) {
            lensX.animateTo(centers[controller.selected.intValue.coerceIn(0, centers.size - 1)],
                spring(dampingRatio = 0.75f, stiffness = 400f))
        }
    }

    // 外层不记录层叠：珠子必须画在"被记录的那一层"外面。
    // 之前把珠子包在 layerBackdrop 里，它绘制时会再去采样自己 →
    // 无限递归，RenderThread 直接 SIGSEGV（真机实测 fatal signal 11）。
    Box(Modifier.fillMaxSize()) {

        // 这一层才是被采样的内容：胶囊底 + 三个图标文字
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) {
            // 胶囊底
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(barH)
                    .background(Color(palette.barColor), RoundedCornerShape(barH / 2))
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(rimShades(palette.rimTop)),
                        shape = RoundedCornerShape(barH / 2)
                    )
                    .onGloballyPositioned { barWidthPx = it.size.width.toFloat() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                controller.tabLabels.forEachIndexed { i, label ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        TabItem(label, i, i == controller.selected.intValue, palette) {
                            if (i != controller.selected.intValue) controller.onSelect(i)
                        }
                    }
                }
            }
        }

        // 玻璃珠
        if (controller.glassEnabled) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(barH)
                    .pointerInput(controller.tabLabels.size) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var last = down.position.x.coerceIn(lensHalfW, size.width - lensHalfW)
                            dragging = true
                            liveX = last
                            scope.launch {
                                lensScale.animateTo(1.16f, spring(dampingRatio = 0.55f, stiffness = 900f))
                            }
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        change.consume()
                                        break
                                    }
                                    last = change.position.x.coerceIn(lensHalfW, size.width - lensHalfW)
                                    liveX = last
                                    change.consume()
                                }
                            } finally {
                                dragging = false
                            }
                            val target = nearestIndex(last, centers)
                            scope.launch { lensScale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 600f)) }
                            scope.launch {
                                lensX.snapTo(last)
                                lensX.animateTo(centers[target], spring(dampingRatio = 0.72f, stiffness = 420f))
                            }
                            if (target != controller.selected.intValue) {
                                controller.onSelect(target)
                            }
                        }
                    }
            ) {
                val shownX = if (dragging) liveX else lensX.value
                Box(
                    Modifier
                        .offset {
                            IntOffset(
                                (shownX - lensHalfW).roundToInt(),
                                with(density) { ((barH - lensH) / 2).toPx() }.roundToInt()
                            )
                        }
                        .size(lensW, lensH)
                        .graphicsLayer {
                            scaleX = lensScale.value
                            scaleY = lensScale.value
                            transformOrigin = TransformOrigin.Center
                        }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { AbsoluteRoundedCornerShape(lensH / 2) },
                            effects = {
                                lens(
                                    refractionHeight = with(density) { 16.dp.toPx() },
                                    refractionAmount = with(density) { 18.dp.toPx() },
                                    depthEffect = true,
                                    chromaticAberration = true
                                )
                            }
                        )
                        .background(Color(0x14FFFFFF), AbsoluteRoundedCornerShape(lensH / 2))
                        .border(
                            width = 1.dp,
                            brush = Brush.verticalGradient(rimShades(palette.rimTop)),
                            shape = AbsoluteRoundedCornerShape(lensH / 2)
                        )
                )
            }
        }
    }
}


private fun nearestIndex(x: Float, centers: List<Float>): Int {
    if (centers.isEmpty()) return 0
    var best = 0
    var bestD = Float.MAX_VALUE
    centers.forEachIndexed { i, c ->
        val d = abs(x - c)
        if (d < bestD) {
            bestD = d
            best = i
        }
    }
    return best
}

@Composable
private fun TabItem(
    label: String,
    index: Int,
    selected: Boolean,
    palette: NavPalette,
    onClick: () -> Unit
) {
    val color = Color(if (selected) palette.selectedColor else palette.normalColor)
    Column(
        // 玻璃珠那一层只在开关打开时存在，它不在的时候就靠这里接管点击。
        // 否则关掉液态玻璃后底栏会彻底点不动（真机踩过）。
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(Modifier.size(20.dp)) { drawTabIcon(index, color) }
        Spacer(Modifier.height(3.dp))
        BasicText(
            label,
            style = TextStyle(
                color = color,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        )
    }
}

/** 三个图标都用 Canvas 画，省掉引入 Compose Material 的图标依赖。 */
private fun DrawScope.drawTabIcon(index: Int, color: Color) {
    val s = size.minDimension
    when (index) {
        // 本日：一块圆角方块
        0 -> drawRoundRect(
            color = color,
            topLeft = Offset(s * 0.16f, s * 0.16f),
            size = Size(s * 0.68f, s * 0.68f),
            cornerRadius = CornerRadius(s * 0.2f, s * 0.2f)
        )
        // 周次：2×2 四宫格
        1 -> {
            val cell = s * 0.3f
            val gap = s * 0.08f
            val origin = (s - cell * 2 - gap) / 2f
            for (r in 0..1) {
                for (c in 0..1) {
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(origin + c * (cell + gap), origin + r * (cell + gap)),
                        size = Size(cell, cell),
                        cornerRadius = CornerRadius(cell * 0.18f, cell * 0.18f)
                    )
                }
            }
        }
        // 账号：头 + 肩
        else -> {
            drawCircle(
                color = color,
                radius = s * 0.17f,
                center = Offset(s / 2f, s * 0.33f)
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.22f, s * 0.58f),
                size = Size(s * 0.56f, s * 0.26f),
                cornerRadius = CornerRadius(s * 0.13f, s * 0.13f)
            )
        }
    }
}

/**
 * 把描边基色按 alpha 递减，做成"上实下虚"的受光边。
 *
 * 之前三档颜色写死成白色（0x33/0x14/0x08FFFFFF），深色底好看，
 * 但浅色主题下白压白等于没画。改成从基色派生：深色主题传白、
 * 浅色主题传深灰，形状保持一致。
 */
private fun rimShades(base: Int): List<Color> {
    val baseAlpha = (base ushr 24) and 0xFF
    val r = ((base shr 16) and 0xFF) / 255f
    val g = ((base shr 8) and 0xFF) / 255f
    val b = (base and 0xFF) / 255f
    fun shade(f: Float) = Color(r, g, b, (baseAlpha * f) / 255f)
    return listOf(shade(1f), shade(0.35f), shade(0.12f))
}
