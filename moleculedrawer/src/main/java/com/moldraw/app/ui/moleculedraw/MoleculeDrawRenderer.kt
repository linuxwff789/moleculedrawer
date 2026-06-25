package com.moldraw.app.ui.moleculedraw

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.*

// ═══════════════════════════════════════════════
// 分子绘制器 — 独立渲染函数
// 从 MoleculeDrawUtils.kt 拆分出来
// ═══════════════════════════════════════════════

/** 绘制单个键 */
fun DrawScope.drawBond(a1: MoleculeAtom, a2: MoleculeAtom, type: BondType, kekuleIndex: Int = -1) {
    val p1 = Offset(a1.x, a1.y); val p2 = Offset(a2.x, a2.y)
    val dx = p2.x - p1.x; val dy = p2.y - p1.y
    val len = sqrt(dx * dx + dy * dy); if (len == 0f) return
    val ux = dx / len; val uy = dy / len

    // 从原子边缘开始画
    val r1 = if (a1.element == Element.C) CARBON_DOT_R else HETERO_CIRCLE_R
    val r2 = if (a2.element == Element.C) CARBON_DOT_R else HETERO_CIRCLE_R
    val s1 = Offset(p1.x + ux * r1, p1.y + uy * r1)
    val s2 = Offset(p2.x - ux * r2, p2.y - uy * r2)

    val bondColor = Color(0xFF333333)
    when (type) {
        BondType.SINGLE -> {
            drawLine(bondColor, s1, s2, strokeWidth = STROKE_WIDTH)
        }
        BondType.WEDGE_UP -> {
            val wedgeHalfW = 5f
            val perpX = -uy * wedgeHalfW; val perpY = ux * wedgeHalfW
            val path = Path().apply {
                moveTo(s1.x, s1.y)
                lineTo(s2.x + perpX, s2.y + perpY)
                lineTo(s2.x - perpX, s2.y - perpY)
                close()
            }
            drawPath(path, bondColor)
        }
        BondType.WEDGE_DOWN -> {
            val segLen = 8f; val gapLen = 5f
            val totalLen = len - r1 - r2
            var drawn = 0f
            while (drawn < totalLen) {
                val start = drawn / totalLen
                var end = (drawn + segLen) / totalLen
                if (end > 1f) end = 1f
                val dashSx = s1.x + (s2.x - s1.x) * start
                val dashSy = s1.y + (s2.y - s1.y) * start
                val dashEx = s1.x + (s2.x - s1.x) * end
                val dashEy = s1.y + (s2.y - s1.y) * end
                val wedgeHalfW = 4.5f * end
                val perpX = -uy * wedgeHalfW; val perpY = ux * wedgeHalfW
                drawLine(bondColor, Offset(dashSx, dashSy), Offset(dashEx, dashEy), strokeWidth = 2.5f)
                drawn += segLen + gapLen
                if (end >= 1f) break
            }
        }
        BondType.DOUBLE -> {
            val px = -uy * 3f; val py = ux * 3f
            drawLine(bondColor, Offset(s1.x + px, s1.y + py), Offset(s2.x + px, s2.y + py), strokeWidth = STROKE_WIDTH - 0.5f)
            drawLine(bondColor, Offset(s1.x - px, s1.y - py), Offset(s2.x - px, s2.y - py), strokeWidth = STROKE_WIDTH - 0.5f)
        }
        BondType.TRIPLE -> {
            drawLine(bondColor, s1, s2, strokeWidth = STROKE_WIDTH - 1f)
            val px = -uy * 4f; val py = ux * 4f
            drawLine(bondColor, Offset(s1.x + px, s1.y + py), Offset(s2.x + px, s2.y + py), strokeWidth = STROKE_WIDTH - 1f)
            drawLine(bondColor, Offset(s1.x - px, s1.y - py), Offset(s2.x - px, s2.y - py), strokeWidth = STROKE_WIDTH - 1f)
        }
        BondType.AROMATIC -> {
            if (kekuleIndex >= 0) {
                // 凯库勒式：按奇偶画单双交替
                if (kekuleIndex % 2 == 0) {
                    drawLine(bondColor, s1, s2, strokeWidth = STROKE_WIDTH)
                } else {
                    val px = -uy * 3f; val py = ux * 3f
                    drawLine(bondColor, Offset(s1.x + px, s1.y + py), Offset(s2.x + px, s2.y + py), strokeWidth = STROKE_WIDTH - 0.5f)
                    drawLine(bondColor, Offset(s1.x - px, s1.y - py), Offset(s2.x - px, s2.y - py), strokeWidth = STROKE_WIDTH - 0.5f)
                }
            } else {
                // 鲍林式：实线绘制（内切圆单独画在环中心）
                drawLine(bondColor, s1, s2, strokeWidth = STROKE_WIDTH)
            }
        }
    }
}

/** 绘制单个原子 */
fun DrawScope.drawAtom(a: MoleculeAtom, bonds: List<MoleculeBond> = emptyList(), annotations: List<MoleculeAnnotation> = emptyList()) {
    val c = Offset(a.x, a.y)
    val p = Paint().apply {
        color = android.graphics.Color.BLACK; textSize = FONT_SIZE
        textAlign = Paint.Align.CENTER; isAntiAlias = true
    }
    // 如果该原子属于 FUNC_GROUP（有 funGroupLabel），跳过绘制（标注文字替代）
    if (a.funGroupLabel != null) return
    // 诊断日志：记录实际被绘制的非C原子
    if (a.element != Element.C) android.util.Log.d("MOLDRAW_DEBUG", "[drawAtom] drawing ${a.id}(${a.element.symbol}) at (${a.x.toInt()},${a.y.toInt()})")
    if (a.element == Element.C) {
drawCircle(Color(0xFF666666), CARBON_DOT_R, c)
} else {
val symbol = a.element.symbol
val hCount = calcImplicitH(a, bonds)
val displayText = if (hCount == 0) symbol
else if (hCount == 1) "${symbol}H"
else "${symbol}H${hCount}"
drawContext.canvas.nativeCanvas.drawText(displayText, c.x, c.y + FONT_SIZE/3f, p)
}
}

/** 绘制文字标注 */
fun DrawScope.drawAnnotationText(ann: MoleculeAnnotation, selected: Boolean = false) {
    val p = android.graphics.Paint().apply {
        color = if (selected) android.graphics.Color.parseColor("#1976D2") else android.graphics.Color.parseColor("#333333")
        textSize = ANN_TEXT_SIZE * ann.scale
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    val fm = p.fontMetrics
    val textCenterY = ann.y - (fm.ascent + fm.descent) / 2f
    if (ann.subScript && ann.text.any { it.isDigit() }) {
        // 自动下标模式：字母正常大小，数字缩小并下沉
        val parts = mutableListOf<Pair<String, Boolean>>() // text, isSub
        val current = StringBuilder()
        var inDigit = false
        for (c in ann.text) {
            if (c.isDigit()) {
                if (!inDigit && current.isNotEmpty()) { parts.add(Pair(current.toString(), false)); current.clear() }
                inDigit = true; current.append(c)
            } else {
                if (inDigit && current.isNotEmpty()) { parts.add(Pair(current.toString(), true)); current.clear() }
                inDigit = false; current.append(c)
            }
        }
        if (current.isNotEmpty()) parts.add(Pair(current.toString(), inDigit))
        var xPos = ann.x
        val normalSize = ANN_TEXT_SIZE * ann.scale
        val subSize = normalSize * 0.65f
        val subOffset = normalSize * 0.35f // 下沉量
        for ((text, isSub) in parts) {
            val size = if (isSub) subSize else normalSize
            p.textSize = size
            val yOffset = if (isSub) subOffset else 0f
            drawContext.canvas.nativeCanvas.drawText(text, xPos, textCenterY + yOffset, p)
            xPos += p.measureText(text)
        }
    } else {
        drawContext.canvas.nativeCanvas.drawText(ann.text, ann.x, textCenterY, p)
    }
}

/** 绘制箭头标注（从(x,y)到(endX,endY)） */
fun DrawScope.drawAnnotationArrow(ann: MoleculeAnnotation, selected: Boolean = false) {
    val sx = ann.x; val sy = ann.y
    val ex = ann.endX; val ey = ann.endY
    val dx = ex - sx; val dy = ey - sy
    val len = sqrt(dx*dx + dy*dy)
    if (len < 1f) return
    val ux = dx/len; val uy = dy/len
    val bc = if (selected) Color(0xFF1976D2) else Color(0xFF333333)
    drawLine(bc, Offset(sx, sy), Offset(ex, ey), strokeWidth = 2.5f)
    val headSize = ARROW_HEAD_SIZE * ann.scale
    val angle = Math.toRadians(25.0)
    val cosA = cos(angle).toFloat(); val sinA = sin(angle).toFloat()
    val lx = ex - ux * headSize * cosA + uy * headSize * sinA
    val ly = ey - uy * headSize * cosA - ux * headSize * sinA
    val rx = ex - ux * headSize * cosA - uy * headSize * sinA
    val ry = ey - uy * headSize * cosA + ux * headSize * sinA
    drawLine(bc, Offset(ex, ey), Offset(lx, ly), strokeWidth = 2.5f)
    drawLine(bc, Offset(ex, ey), Offset(rx, ry), strokeWidth = 2.5f)
}

/**
 * 绘制鲍林式苯环内切圆。
 * 给定苯环6个顶点（世界坐标），在环内绘制实线圆。
 */
fun DrawScope.drawPaulingCircle(vertices: List<Offset>) {
    if (vertices.size != 6) return
    // 计算6个顶点的几何中心
    val cx = vertices.map { it.x }.average().toFloat()
    val cy = vertices.map { it.y }.average().toFloat()
    // 内切圆半径 = 顶点到中心距离的最小值（稍小一点避免碰到键）
    val radius = vertices.minOf { sqrt((it.x - cx)*(it.x - cx) + (it.y - cy)*(it.y - cy)) } * 0.58f
    drawCircle(Color(0xFF333333), radius, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
}
