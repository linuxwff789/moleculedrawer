package com.moldraw.app.ui.moleculedraw

import android.graphics.Paint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/**
 * 绘制风格。
 * CLASSIC   ：碳画灰点，双键两根等长平行线，H 不缩小
 * PROFESSIONAL：碳不画点（键交于顶点），双键内侧线两端缩进，杂原子氢数排成下标，标签带白底
 * 两套样式共用同一份数据，切换只影响渲染，不影响保存/导出的结构。
 */
enum class DrawStyle(val label: String) { CLASSIC("经典"), PROFESSIONAL("专业") }

object DrawStyleState {
    private const val PREF_NAME = "moldraw"
    private const val KEY_STYLE = "drawStyle"

    var style by mutableStateOf(DrawStyle.PROFESSIONAL)

    fun load(context: android.content.Context) {
        val v = context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
            .getString(KEY_STYLE, DrawStyle.PROFESSIONAL.name)
        style = runCatching { DrawStyle.valueOf(v ?: DrawStyle.PROFESSIONAL.name) }
            .getOrDefault(DrawStyle.PROFESSIONAL)
    }

    fun toggle(context: android.content.Context) {
        style = if (style == DrawStyle.PROFESSIONAL) DrawStyle.CLASSIC else DrawStyle.PROFESSIONAL
        context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_STYLE, style.name).apply()
    }
}

fun isProStyle(): Boolean = DrawStyleState.style == DrawStyle.PROFESSIONAL

/** 碳原子的绘制半径：经典样式画灰点、键端让开；专业样式不画点、键交于顶点 */
fun carbonRadius(): Float = if (isProStyle()) 0f else CARBON_DOT_R

/** 双键第二根线的垂直偏移（单侧）与两端缩进比例 */
fun doubleBondParams(len: Float): Pair<Float, Float> =
    if (isProStyle()) Pair((len * 0.095f).coerceIn(3.2f, 6.5f), 0.13f) else Pair(3f, 0f)

/** 杂原子标签宽度（专业样式把氢数排成下标，占位比整字号小） */
fun atomLabelHalfExtentsFor(symbol: String, hCount: Int, fontSize: Float): Pair<Float, Float> {
    if (!isProStyle()) {
        val t = if (hCount == 0) symbol else if (hCount == 1) "${symbol}H" else "${symbol}H$hCount"
        return atomLabelHalfExtents(t, fontSize)
    }
    val p = Paint().apply { isAntiAlias = true; textSize = fontSize }
    val wSym = p.measureText(symbol)
    val wH = if (hCount > 0) p.measureText("H") else 0f
    p.textSize = fontSize * 0.62f
    val wSub = if (hCount > 1) p.measureText(hCount.toString()) else 0f
    p.textSize = fontSize
    val fm = p.fontMetrics
    return Pair((wSym + wH + wSub) / 2f + 3.5f, (fm.descent - fm.ascent) / 2f + 2.5f)
}

/**
 * 画杂原子标签（Compose 与两套导出渲染共用，都是 android.graphics.Canvas）。
 * 专业样式：元素符号 + H 同字号，氢原子个数缩小下沉成下标，并在文字底下垫白底遮住键端。
 */
fun drawHeteroAtomLabel(
    canvas: android.graphics.Canvas,
    cx: Float,
    cy: Float,
    symbol: String,
    hCount: Int,
    fontSize: Float,
    paint: Paint
) {
    if (!isProStyle()) {
        val text = if (hCount == 0) symbol else if (hCount == 1) "${symbol}H" else "${symbol}H$hCount"
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = fontSize
        canvas.drawText(text, cx, cy + fontSize / 3f, paint)
        return
    }
    paint.textSize = fontSize
    val wSym = paint.measureText(symbol)
    val wH = if (hCount > 0) paint.measureText("H") else 0f
    val sub = if (hCount > 1) hCount.toString() else ""
    val subSize = fontSize * 0.62f
    paint.textSize = subSize
    val wSub = if (sub.isNotEmpty()) paint.measureText(sub) else 0f
    paint.textSize = fontSize
    val fm = paint.fontMetrics
    val baseY = cy - (fm.ascent + fm.descent) / 2f
    val total = wSym + wH + wSub
    val x0 = cx - total / 2f
    // 白底：盖住键端与环线，标签像"贴"在结构上（ChemDraw 的做法）
    val oldColor = paint.color
    paint.color = android.graphics.Color.WHITE
    canvas.drawRect(x0 - 1.5f, baseY + fm.ascent - 1f, x0 + total + 1.5f, baseY + fm.descent + 1.5f, paint)
    paint.color = oldColor
    paint.textAlign = Paint.Align.LEFT
    canvas.drawText(symbol, x0, baseY, paint)
    if (hCount > 0) canvas.drawText("H", x0 + wSym, baseY, paint)
    if (sub.isNotEmpty()) {
        paint.textSize = subSize
        canvas.drawText(sub, x0 + wSym + wH, baseY + fontSize * 0.17f, paint)
        paint.textSize = fontSize
    }
}

/** 芳香环上的凯库勒双键（两套导出渲染共用，样式与屏幕一致） */
fun drawRingDoubleBond(
    canvas: android.graphics.Canvas,
    sx: Float, sy: Float, ex: Float, ey: Float,
    ux: Float, uy: Float, paint: Paint,
    ringCenter: Pair<Float, Float>? = null
) {
    val len = hypot(ex - sx, ey - sy)
    val (off, trim) = doubleBondParams(len)
    if (trim <= 0f) {
        // 经典样式：两根等长平行线，对称偏在键轴两侧
        val px = -uy * off; val py = ux * off
        canvas.drawLine(sx + px, sy + py, ex + px, ey + py, paint)
        canvas.drawLine(sx - px, sy - py, ex - px, ey - py, paint)
        return
    }
    // 专业样式：长线沿键轴（单键接到它上面，不留空隙），短线偏一侧并两端缩进
    canvas.drawLine(sx, sy, ex, ey, paint)
    val side = offsetSide(sx, sy, ex, ey, ux, uy, ringCenter)
    val px = -uy * off * side; val py = ux * off * side
    canvas.drawLine(
        sx + (ex - sx) * trim + px, sy + (ey - sy) * trim + py,
        ex - (ex - sx) * trim + px, ey - (ey - sy) * trim + py, paint
    )
}

/** 偏移方向：有环心时朝环内，否则固定朝键的右手侧（同一条键总是同一侧） */
fun offsetSide(
    sx: Float, sy: Float, ex: Float, ey: Float,
    ux: Float, uy: Float,
    ringCenter: Pair<Float, Float>?
): Float {
    if (ringCenter == null) return 1f
    val mx = (sx + ex) / 2f; val my = (sy + ey) / 2f
    val along = (ringCenter.first - mx) * (-uy) + (ringCenter.second - my) * ux
    return if (along >= 0f) 1f else -1f
}

/**
 * 该键是否成环；成环则返回环上其余原子的质心（用作双键短线偏移方向）。
 * 用 BFS 找 a1→a2 的最短替代路径，3~8 个原子视为环。
 */
fun ringCentroidForBond(
    atoms: List<MoleculeAtom>,
    bonds: List<MoleculeBond>,
    id1: Int, id2: Int,
    maxPath: Int = 8
): Pair<Float, Float>? {
    if (!isProStyle()) return null
    val adj = HashMap<Int, MutableList<Int>>()
    for (b in bonds) {
        if ((b.atom1 == id1 && b.atom2 == id2) || (b.atom1 == id2 && b.atom2 == id1)) continue
        adj.getOrPut(b.atom1) { mutableListOf() }.add(b.atom2)
        adj.getOrPut(b.atom2) { mutableListOf() }.add(b.atom1)
    }
    val prev = HashMap<Int, Int>()
    val queue = ArrayDeque<Int>()
    queue.add(id1); prev[id1] = Int.MIN_VALUE
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        if (cur == id2) break
        for (n in adj[cur] ?: continue) {
            if (!prev.containsKey(n)) { prev[n] = cur; queue.add(n) }
        }
    }
    if (!prev.containsKey(id2)) return null
    val path = mutableListOf<Int>()
    var cur = id2
    while (cur != Int.MIN_VALUE) {
        path.add(cur)
        cur = prev[cur] ?: break
    }
    if (path.size < 3 || path.size > maxPath) return null
    val pts = path.mapNotNull { id -> atoms.find { it.id == id } }
    if (pts.size != path.size) return null
    return Pair(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat())
}

/** 把缩写拆成"元素+紧跟的下标"词元，例如 NO₂ → [N, O₂] */
fun functionalLabelTokens(text: String): List<String> {
    val tokens = mutableListOf<String>()
    var i = 0
    while (i < text.length) {
        val sb = StringBuilder()
        sb.append(text[i]); i++
        while (i < text.length && (text[i].isDigit() || text[i] in '\u2080'..'\u2089')) {
            sb.append(text[i]); i++
        }
        tokens.add(sb.toString())
    }
    return tokens
}

/** 反序显示：NO₂ → O₂N、SO₃H → HO₃S（下标仍跟在各自元素后面） */
fun reverseFunctionalLabel(text: String): String = functionalLabelTokens(text).reversed().joinToString("")

/** 文字标签的包围盒（半宽, 半高），用于键端避让 */
fun atomLabelHalfExtents(text: String, fontSize: Float): Pair<Float, Float> {
    val p = android.graphics.Paint().apply { isAntiAlias = true; textSize = fontSize }
    val fm = p.fontMetrics
    val h = fm.descent - fm.ascent
    return Pair(p.measureText(text) / 2f + 3f, h / 2f + 2f)
}

/** 沿单位方向 (ux,uy) 从椭圆中心到边界的距离：用来把键缩到文字框外，避免穿模 */
fun ellipseTrim(halfW: Float, halfH: Float, ux: Float, uy: Float): Float {
    val a = if (halfW > 0.1f) ux / halfW else 0f
    val b = if (halfH > 0.1f) uy / halfH else 0f
    val d = sqrt(a * a + b * b)
    return if (d > 1e-4f) 1f / d else 0f
}

fun DrawScope.drawBond(
    a1: MoleculeAtom, a2: MoleculeAtom, type: BondType, kekuleIndex: Int = -1,
    box1: Pair<Float, Float>? = null, box2: Pair<Float, Float>? = null,
    ringCenter: Pair<Float, Float>? = null
) {
    val p1 = Offset(a1.x, a1.y); val p2 = Offset(a2.x, a2.y)
    val dx = p2.x - p1.x; val dy = p2.y - p1.y
    val len = sqrt(dx * dx + dy * dy); if (len == 0f) return
    val ux = dx / len; val uy = dy / len

    // 从原子边缘开始画：有文字标签时按标签椭圆边界缩进，避免放大后键穿过字母
    val r1 = box1?.let { ellipseTrim(it.first, it.second, ux, uy) } ?: carbonRadius()
    val r2 = box2?.let { ellipseTrim(it.first, it.second, -ux, -uy) } ?: carbonRadius()
    val s1 = Offset(p1.x + ux * r1, p1.y + uy * r1)
    val s2 = Offset(p2.x - ux * r2, p2.y - uy * r2)

    val bondColor = Color(0xFF333333)
    // 专业样式线更细一点，接近印刷稿
    val sw = if (isProStyle()) (STROKE_WIDTH - 0.7f).coerceAtLeast(1.8f) else STROKE_WIDTH
    val (off, trim) = doubleBondParams(len)
    when (type) {
        BondType.SINGLE -> {
            drawLine(bondColor, s1, s2, strokeWidth = sw)
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
            if (trim > 0f) {
                // 专业样式：长线压在键轴上（单键才能接住），短线偏移到一侧并两端缩进
                drawLine(bondColor, s1, s2, strokeWidth = sw - 0.3f)
                val side = offsetSide(s1.x, s1.y, s2.x, s2.y, ux, uy, ringCenter)
                val px = -uy * off * side; val py = ux * off * side
                val ax = s1.x + (s2.x - s1.x) * trim + px; val ay = s1.y + (s2.y - s1.y) * trim + py
                val bx = s2.x - (s2.x - s1.x) * trim + px; val by = s2.y - (s2.y - s1.y) * trim + py
                drawLine(bondColor, Offset(ax, ay), Offset(bx, by), strokeWidth = sw - 0.3f)
            } else {
                val px = -uy * off; val py = ux * off
                drawLine(bondColor, Offset(s1.x + px, s1.y + py), Offset(s2.x + px, s2.y + py), strokeWidth = sw - 0.3f)
                drawLine(bondColor, Offset(s1.x - px, s1.y - py), Offset(s2.x - px, s2.y - py), strokeWidth = sw - 0.5f)
            }
        }
        BondType.TRIPLE -> {
            drawLine(bondColor, s1, s2, strokeWidth = sw - 0.8f)
            val px = -uy * max(off, 4f); val py = ux * max(off, 4f)
            drawLine(bondColor, Offset(s1.x + px, s1.y + py), Offset(s2.x + px, s2.y + py), strokeWidth = sw - 0.8f)
            drawLine(bondColor, Offset(s1.x - px, s1.y - py), Offset(s2.x - px, s2.y - py), strokeWidth = sw - 0.8f)
        }
        BondType.AROMATIC -> {
            if (kekuleIndex >= 0) {
                // 凯库勒式：按奇偶画单双交替
                if (kekuleIndex % 2 == 0) {
                    drawLine(bondColor, s1, s2, strokeWidth = sw)
                } else {
                    val px = -uy * off; val py = ux * off
                    drawLine(bondColor, Offset(s1.x + px, s1.y + py), Offset(s2.x + px, s2.y + py), strokeWidth = sw - 0.4f)
                    if (trim > 0f) {
                        val ax = s1.x + (s2.x - s1.x) * trim - px; val ay = s1.y + (s2.y - s1.y) * trim - py
                        val bx = s2.x - (s2.x - s1.x) * trim - px; val by = s2.y - (s2.y - s1.y) * trim - py
                        drawLine(bondColor, Offset(ax, ay), Offset(bx, by), strokeWidth = sw - 0.4f)
                    } else {
                        drawLine(bondColor, Offset(s1.x - px, s1.y - py), Offset(s2.x - px, s2.y - py), strokeWidth = sw - 0.5f)
                    }
                }
            } else {
                // 鲍林式：实线绘制（内切圆单独画在环中心）
                drawLine(bondColor, s1, s2, strokeWidth = sw)
            }
        }
    }
}

/** 绘制单个原子 */
fun DrawScope.drawAtom(
    a: MoleculeAtom,
    bonds: List<MoleculeBond> = emptyList(),
    annotations: List<MoleculeAnnotation> = emptyList(),
    fontScale: Float = 1f
) {
    val c = Offset(a.x, a.y)
    // 字号跟随键长（分子放大/缩小时同步变化）
    val fsz = FONT_SIZE * fontScale * (if (isProStyle()) 0.95f else 1f)
    val p = Paint().apply {
        color = android.graphics.Color.BLACK; textSize = fsz
        textAlign = Paint.Align.CENTER; isAntiAlias = true
    }
    // 如果该原子属于 FUNC_GROUP（有 funGroupLabel），跳过绘制（标注文字替代）
    if (a.funGroupLabel != null) return
    if (a.element == Element.C) {
        // 专业样式不画碳点，键直接交于顶点；但孤立碳（没有任何键）必须画出来，
        // 否则整颗原子隐形：既看不见也点不中，还会被分子量标注单独标成 CH4
        val isolated = bonds.none { it.atom1 == a.id || it.atom2 == a.id }
        if (!isProStyle() || isolated) drawCircle(Color(0xFF666666), CARBON_DOT_R, c)
    } else {
        val symbol = a.element.symbol
        val hCount = calcImplicitH(a, bonds)
        drawHeteroAtomLabel(drawContext.canvas.nativeCanvas, c.x, c.y, symbol, hCount, fsz, p)
    }
}

/** 绘制文字标注 */
fun DrawScope.drawAnnotationText(
    ann: MoleculeAnnotation,
    selected: Boolean = false,
    anchorFirstGlyph: Boolean = false,
    flipLeft: Boolean = false,
    mirrorText: Boolean = false,
    fontScale: Float = 1f
) {
    val p = android.graphics.Paint().apply {
        color = if (selected) android.graphics.Color.parseColor("#1976D2") else android.graphics.Color.parseColor("#333333")
        textSize = ANN_TEXT_SIZE * ann.scale * fontScale
        isAntiAlias = true
    }
    // 官能团缩写：连接点那个字母要正好压在原子位置上，整串朝远离基团原子的一侧展开；
    // 分子被水平翻转后（成员原子跑到连接点左边）整串也跟着反序，NO₂ → ₂ON，保证 O 在 O 的那一侧
    val tokens = functionalLabelTokens(ann.text)
    val display = if (anchorFirstGlyph && mirrorText) tokens.reversed().joinToString("") else ann.text
    // 连接点元素 = 原标号第一个词元里的字母；反序后它落在整串末尾
    val connGlyph = when {
        !anchorFirstGlyph -> ""
        else -> tokens.firstOrNull()?.take(1) ?: ""
    }
    val alignRight = anchorFirstGlyph && (mirrorText || flipLeft)
    p.textAlign = when {
        !anchorFirstGlyph -> Paint.Align.CENTER
        alignRight -> Paint.Align.RIGHT
        else -> Paint.Align.LEFT
    }
    val fm = p.fontMetrics
    val textCenterY = ann.y - (fm.ascent + fm.descent) / 2f
    val wConn = if (connGlyph.isEmpty()) 0f else p.measureText(connGlyph)
    val textStartX = when {
        !anchorFirstGlyph -> ann.x
        alignRight -> ann.x + wConn / 2f
        else -> ann.x - wConn / 2f
    }
    if (ann.subScript && display.any { it.isDigit() }) {
        // 自动下标模式：字母正常大小，数字缩小并下沉
        val parts = mutableListOf<Pair<String, Boolean>>() // text, isSub
        val current = StringBuilder()
        var inDigit = false
        for (c in display) {
            if (c.isDigit()) {
                if (!inDigit && current.isNotEmpty()) { parts.add(Pair(current.toString(), false)); current.clear() }
                inDigit = true; current.append(c)
            } else {
                if (inDigit && current.isNotEmpty()) { parts.add(Pair(current.toString(), true)); current.clear() }
                inDigit = false; current.append(c)
            }
        }
        if (current.isNotEmpty()) parts.add(Pair(current.toString(), inDigit))
        var xPos = textStartX
        val normalSize = ANN_TEXT_SIZE * ann.scale * fontScale
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
        drawContext.canvas.nativeCanvas.drawText(display, textStartX, textCenterY, p)
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
