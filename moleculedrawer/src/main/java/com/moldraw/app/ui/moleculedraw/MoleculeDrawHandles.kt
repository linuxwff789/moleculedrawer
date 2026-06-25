package com.moldraw.app.ui.moleculedraw

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.*

// ═══════════════════════════════════════════════
// 变换组操作手柄 — 绘制 + 命中检测
// 选中多原子后显示 8 个手柄（四角缩放 + 四边平移）+ 顶部旋转手柄
// ═══════════════════════════════════════════════

/** 手柄类型 */
enum class HandleKind { CORNER, EDGE, ROTATE }

/** 手柄在世界空间的包围盒（用于命中检测）*/
data class HandleBox(val kind: HandleKind, val index: Int, val center: Offset, val halfSize: Float)

/** 手柄半尺寸（px）— 基础值，会被缩放因子放大 */
private const val HANDLE_HS_BASE = 24f
/** 旋转手柄到选框顶部的距离 */
private const val ROTATE_HANDLE_OFFSET_BASE = 50f

/**
 * 给定变换组，计算选框四角（世界坐标）和所有手柄位置。
 * 应用 group 变换到原子坐标后再计算包围盒。
 */
fun computeHandles(g: TransformGroup, atoms: List<MoleculeAtom>, annotations: List<MoleculeAnnotation> = emptyList()): Pair<List<Offset>, List<HandleBox>> {
    // 计算组内变换后的原子坐标
    val rad = Math.toRadians(g.rotation.toDouble())
    val cosR = cos(rad).toFloat(); val sinR = sin(rad).toFloat()
    val selAtoms = atoms.filter { it.id in g.atomIds }
    // 收集变换后的坐标（原子+标注）
    val allPoints = mutableListOf<Offset>()
    for (a in selAtoms) {
        val lx = (a.x - g.pivotX) * g.scaleX
        val ly = (a.y - g.pivotY) * g.scaleY
        val rx = lx * cosR - ly * sinR
        val ry = lx * sinR + ly * cosR
        allPoints.add(Offset(g.pivotX + rx + g.translationX, g.pivotY + ry + g.translationY))
    }
    // 标注坐标
    for (aid in g.annotationIds) {
        val ann = annotations.find { it.id == aid } ?: continue
        val lx = (ann.x - g.pivotX) * g.scaleX
        val ly = (ann.y - g.pivotY) * g.scaleY
        val rx = lx * cosR - ly * sinR
        val ry = lx * sinR + ly * cosR
        allPoints.add(Offset(g.pivotX + rx + g.translationX, g.pivotY + ry + g.translationY))
        if (ann.type == AnnotationType.ARROW) {
            val lx2 = (ann.endX - g.pivotX) * g.scaleX
            val ly2 = (ann.endY - g.pivotY) * g.scaleY
            val rx2 = lx2 * cosR - ly2 * sinR
            val ry2 = lx2 * sinR + ly2 * cosR
            allPoints.add(Offset(g.pivotX + rx2 + g.translationX, g.pivotY + ry2 + g.translationY))
        }
    }
    if (allPoints.isEmpty()) return Pair(emptyList(), emptyList())
    val minX = allPoints.minOf { it.x }
    val maxX = allPoints.maxOf { it.x }
    val minY = allPoints.minOf { it.y }
    val maxY = allPoints.maxOf { it.y }
    // 计算标注的最大缩放比例，用于包围盒和手柄尺寸
    val maxAnnScale = g.annotationIds.mapNotNull { aid -> annotations.find { it.id == aid }?.scale }.maxOrNull() ?: 1f
    val scaleFactor = maxAnnScale.coerceAtLeast(1f)
    // 包围盒最小尺寸，随标注缩放比例变化
    val BOX_MIN_SIZE = 60f * scaleFactor
    // 手柄和旋转手柄偏移也随缩放变化
    val HANDLE_HS = HANDLE_HS_BASE * scaleFactor
    val ROTATE_HANDLE_OFFSET = ROTATE_HANDLE_OFFSET_BASE * scaleFactor
    val finalMinX = minOf(minX, maxX - BOX_MIN_SIZE)
    val finalMaxX = maxOf(maxX, minX + BOX_MIN_SIZE)
    val finalMinY = minOf(minY, maxY - BOX_MIN_SIZE)
    val finalMaxY = maxOf(maxY, minY + BOX_MIN_SIZE)
    val corners = listOf(
        Offset(finalMinX, finalMinY), // 0: 左上
        Offset(finalMaxX, finalMinY), // 1: 右上
        Offset(finalMaxX, finalMaxY), // 2: 右下
        Offset(finalMinX, finalMaxY)  // 3: 左下
    )
    val midTop = Offset((finalMinX + finalMaxX) / 2f, finalMinY)
    val midRight = Offset(finalMaxX, (finalMinY + finalMaxY) / 2f)
    val midBottom = Offset((finalMinX + finalMaxX) / 2f, finalMaxY)
    val midLeft = Offset(finalMinX, (finalMinY + finalMaxY) / 2f)

    val handles = listOf(
        HandleBox(HandleKind.CORNER, 0, corners[0], HANDLE_HS), // 左上角
        HandleBox(HandleKind.CORNER, 1, corners[1], HANDLE_HS), // 右上角
        HandleBox(HandleKind.CORNER, 2, corners[2], HANDLE_HS), // 右下角
        HandleBox(HandleKind.CORNER, 3, corners[3], HANDLE_HS), // 左下角
        HandleBox(HandleKind.EDGE, 4, midTop, HANDLE_HS),       // 上边中点
        HandleBox(HandleKind.EDGE, 5, midRight, HANDLE_HS),     // 右边中点
        HandleBox(HandleKind.EDGE, 6, midBottom, HANDLE_HS),    // 下边中点
        HandleBox(HandleKind.EDGE, 7, midLeft, HANDLE_HS),      // 左边中点
        HandleBox(HandleKind.ROTATE, 8, Offset(midTop.x, midTop.y - ROTATE_HANDLE_OFFSET), HANDLE_HS)
    )
    return Pair(corners, handles)
}

/** 检测触摸点是否在手柄上，返回手柄索引（0-8），或 -1 */
fun hitHandle(handles: List<HandleBox>, pos: Offset): Int {
    for (h in handles) {
        val dx = pos.x - h.center.x; val dy = pos.y - h.center.y
        if (abs(dx) <= h.halfSize + 4f && abs(dy) <= h.halfSize + 4f) return h.index
    }
    return -1
}

/** 绘制操作手柄 + 选框（支持原子+标注混合选中） */
fun DrawScope.drawHandles(
    g: TransformGroup,
    atoms: List<MoleculeAtom>,
    annotations: List<MoleculeAnnotation> = emptyList(),
    tx: (Float) -> Float,
    ty: (Float) -> Float
) {
    val (corners, handles) = computeHandles(g, atoms, annotations)
    if (corners.isEmpty()) return

    val sc = { p: Offset -> Offset(tx(p.x), ty(p.y)) }

    // ── 选框虚线 ──
    val boxColor = Color(0xFF2196F3).copy(alpha = 0.7f)
    val dashLen = 6f; val gapLen = 4f
    val edges = listOf(
        Pair(corners[0], corners[1]), Pair(corners[1], corners[2]),
        Pair(corners[2], corners[3]), Pair(corners[3], corners[0])
    )
    for ((p1, p2) in edges) {
        val s = sc(p1); val e = sc(p2)
        val dx = e.x - s.x; val dy = e.y - s.y
        val total = sqrt(dx * dx + dy * dy)
        if (total == 0f) continue
        val ux = dx / total; val uy = dy / total
        var drawn = 0f
        while (drawn < total) {
            val segEnd = (drawn + dashLen).coerceAtMost(total)
            drawLine(boxColor,
                Offset(s.x + ux * drawn, s.y + uy * drawn),
                Offset(s.x + ux * segEnd, s.y + uy * segEnd),
                strokeWidth = 2f)
            drawn = segEnd + gapLen
        }
    }

    // ── 旋转手柄连接线 ──
    val midTopWorld = Offset((corners[0].x + corners[1].x) / 2f, corners[0].y)
    val firstHandleHalfSize = handles.firstOrNull()?.halfSize ?: HANDLE_HS_BASE
    val rotHandleOffset = ROTATE_HANDLE_OFFSET_BASE * (firstHandleHalfSize / HANDLE_HS_BASE)
    val rotWorld = Offset(midTopWorld.x, midTopWorld.y - rotHandleOffset)
    drawLine(boxColor, sc(midTopWorld), sc(rotWorld), strokeWidth = 1.5f)

    // ── 手柄 ──
    for (h in handles) {
        val c = sc(h.center)
        val hs = h.halfSize
        when (h.kind) {
            HandleKind.CORNER -> {
                drawRect(Color.White, c - Offset(hs, hs),
                    androidx.compose.ui.geometry.Size(hs * 2, hs * 2),
                    style = Stroke(2f))
                drawRect(boxColor, c - Offset(hs, hs),
                    androidx.compose.ui.geometry.Size(hs * 2, hs * 2),
                    style = Stroke(2f))
            }
            HandleKind.EDGE -> {
                drawCircle(Color.White, hs, c, style = Stroke(2f))
                drawCircle(boxColor, hs * 0.7f, c)
            }
            HandleKind.ROTATE -> {
                drawCircle(Color(0xFFFF9800), hs, c)
                drawCircle(Color.White, hs * 0.5f, c)
            }
        }
    }
}