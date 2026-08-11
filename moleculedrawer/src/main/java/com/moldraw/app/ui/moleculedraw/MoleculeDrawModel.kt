package com.moldraw.app.ui.moleculedraw

import androidx.compose.ui.geometry.Offset
import kotlin.math.*

enum class Element(val symbol: String, val valence: Int, val atomicNumber: Int) {
    C("C", 4, 6), H("H", 1, 1), O("O", 2, 8), N("N", 3, 7),
    S("S", 2, 16), P("P", 3, 15), F("F", 1, 9), Cl("Cl", 1, 17),
    Br("Br", 1, 35), I("I", 1, 53), Na("Na", 1, 11), K("K", 1, 19),
    Fe("Fe", 2, 26), Cu("Cu", 2, 29), Zn("Zn", 2, 30), Mg("Mg", 2, 12), Ca("Ca", 2, 20), B("B", 3, 5),
    Si("Si", 4, 14), Se("Se", 2, 34), As("As", 3, 33), Te("Te", 2, 52),
    Li("Li", 1, 3), Be("Be", 2, 4), Al("Al", 3, 13), Ti("Ti", 4, 22),
    Mn("Mn", 2, 25), Co("Co", 2, 27), Ni("Ni", 2, 28), Pd("Pd", 2, 46),
    Pt("Pt", 2, 78), Au("Au", 3, 79), Ag("Ag", 1, 47),
    // 补充常用元素
    He("He", 0, 2), Ne("Ne", 0, 10), Ar("Ar", 0, 18),
    V("V", 5, 23), Cr("Cr", 3, 24), Ga("Ga", 3, 31),
    Ge("Ge", 4, 32), Rb("Rb", 1, 37), Sr("Sr", 2, 38),
    Mo("Mo", 6, 42), Cd("Cd", 2, 48), Sn("Sn", 4, 50),
    Sb("Sb", 3, 51), Cs("Cs", 1, 55), Ba("Ba", 2, 56), W("W", 6, 74),
    Hg("Hg", 2, 80), Tl("Tl", 3, 81), Pb("Pb", 4, 82), Bi("Bi", 3, 83), In("In", 3, 49);
    companion object { fun fromSymbol(s: String): Element = entries.find { it.symbol == s } ?: C }
}

enum class BondType { SINGLE, DOUBLE, TRIPLE, WEDGE_UP, WEDGE_DOWN, AROMATIC }

enum class BenzeneStyle { KEKULE, PAULING }

data class MoleculeAtom(
    val id: Int,
    var x: Float,
    var y: Float,
    var element: Element = Element.C,
    var aromatic: Boolean = false,
    var chiral: String = "",
    /** 如果非空，表示该原子属于某个官能团缩写（如 "NO₂"），渲染时跳过 */
    var funGroupLabel: String? = null,
    /** 对于官能团缩写，true 表示该原子是连接点（如 NO₂ 的 N），false 表示展开成员（如 O） */
    var isFunGroupConnector: Boolean = false
)
data class MoleculeBond(val id: Int, val atom1: Int, val atom2: Int, val type: BondType = BondType.SINGLE)

data class MoleculeAnnotation(val id: Int, val type: AnnotationType, var x: Float, var y: Float, var text: String = "", var endX: Float = 0f, var endY: Float = 0f, var scale: Float = 1f, var subScript: Boolean = false)
enum class AnnotationType { TEXT, ARROW, FUNC_GROUP }

/**
 * 变换组 — 选中即编组。
 * 取消选中时展开变换到原子坐标，自身销毁。
 */
data class TransformGroup(
    val id: Int = 0,  // 动态创建时用递增 id
    var translationX: Float = 0f, var translationY: Float = 0f,
    var rotation: Float = 0f,    // 角度制
    var scaleX: Float = 1f, var scaleY: Float = 1f,
    var pivotX: Float = 0f, var pivotY: Float = 0f,
    val atomIds: MutableSet<Int> = mutableSetOf(),
    val bondIds: MutableSet<Int> = mutableSetOf(),
    val annotationIds: MutableSet<Int> = mutableSetOf()
)

data class MoleculeData(val atoms: List<MoleculeAtom> = emptyList(), val bonds: List<MoleculeBond> = emptyList(), val annotations: List<MoleculeAnnotation> = emptyList(), val selectedIndices: Set<Int> = emptySet(), val benzeneStyle: BenzeneStyle = BenzeneStyle.KEKULE) {
    fun toMol(): String {
        val sb = StringBuilder()
        sb.appendLine("")
        sb.appendLine("  ChemELN")
        sb.appendLine("")
        sb.appendLine("  ${atoms.size}  ${bonds.size}  0  0  0  0  0  0  0  0999 V2000")
        for (a in atoms) {
            // V2000 格式要求坐标固定10字符宽，右对齐
            val xStr = "%10.4f".format(a.x)
            val yStr = "%10.4f".format(a.y)
            val zStr = "    0.0000"  // 固定11字符
            val elemPadded = a.element.symbol.padEnd(3) // 元素符号占3字符
            sb.appendLine("$xStr$yStr$zStr $elemPadded 0  0  0  0  0  0  0  0  0  0  0  0")
        }
        for (b in bonds) {
            val a1 = atoms.indexOfFirst { it.id == b.atom1 } + 1
            val a2 = atoms.indexOfFirst { it.id == b.atom2 } + 1
            val order = when (b.type) { BondType.SINGLE -> 1; BondType.DOUBLE -> 2; BondType.TRIPLE -> 3; BondType.WEDGE_UP -> 1; BondType.WEDGE_DOWN -> 1; BondType.AROMATIC -> 4 }
            val stereo = when (b.type) { BondType.WEDGE_UP -> 1; BondType.WEDGE_DOWN -> 6; else -> 0 }
            sb.appendLine("  $a1  $a2  $order  $stereo  0  0  0")
        }
        sb.appendLine("M  END")
        return sb.toString()
    }

    fun toV2000MolNoCoords(): String {
        val sb = StringBuilder()
        sb.appendLine("")
        sb.appendLine("  ChemELN")
        sb.appendLine("")
        sb.appendLine("  ${atoms.size}  ${bonds.size}  0  0  0  0  0  0  0  0999 V2000")
        for (a in atoms) {
            sb.appendLine("    0.0000    0.0000    0.0000 ${a.element.symbol.padEnd(2)} 0  0  0  0  0  0  0  0  0  0  0  0")
        }
        for (b in bonds) {
            val a1 = atoms.indexOfFirst { it.id == b.atom1 } + 1
            val a2 = atoms.indexOfFirst { it.id == b.atom2 } + 1
            val order = when (b.type) { BondType.SINGLE -> 1; BondType.DOUBLE -> 2; BondType.TRIPLE -> 3; BondType.WEDGE_UP -> 1; BondType.WEDGE_DOWN -> 1; BondType.AROMATIC -> 4 }
            val stereo = when (b.type) { BondType.WEDGE_UP -> 1; BondType.WEDGE_DOWN -> 6; else -> 0 }
            sb.appendLine("  $a1  $a2  $order  $stereo  0  0  0")
        }
        sb.appendLine("M  END")
        return sb.toString()
    }

    /**
     * 输出 Molfile V3000 格式字符串，用于传递给 Indigo/Ketcher 进行布局。
     * V3000 格式支持超大分子和更精确的立体化学描述。
     * 如果有 FUNC_GROUP 标注，会同时写入 SGROUP（缩写基团），
     * Indigo 的 toSmiles 能自动识别并正确展开。
     */
    fun toV3000Mol(scaleToAngstrom: Boolean = true, includeCoords: Boolean = true): String {
        val sb = StringBuilder()
        val funcGroups = annotations.filter { it.type == AnnotationType.FUNC_GROUP && it.text.isNotBlank() }
        sb.appendLine("")
        sb.appendLine("  ChemELN")
        sb.appendLine("")
        sb.appendLine("  0  0  0  0  0  0  0  0  0  0  0 V3000")
        sb.appendLine("M  V30 BEGIN CTAB")
        sb.appendLine("M  V30 COUNTS ${atoms.size} ${bonds.size} ${funcGroups.size} 0 0")
        sb.appendLine("M  V30 BEGIN ATOM")
        // V3000 原子索引从1开始
        val atomIndex = mutableMapOf<Int, Int>()
        val invScale = if (scaleToAngstrom) 1.5f / BOND_LENGTH else 1f // 像素→Å
        for ((i, a) in atoms.withIndex()) {
            val idx = i + 1
            atomIndex[a.id] = idx
            val sym = a.element.symbol
            val charge = 0 // 暂不处理电荷
            val radical = 0
            // 格式: 序号 元素 X Y Z 原子映射(0) 同位素(0) 电荷(0) 立体保留(0) 氢计数(0) 立体盒(0) radical(0) 价态(0)
            val xStr = if (includeCoords) "${a.x * invScale}" else "0.0000"
            val yStr = if (includeCoords) "${a.y * invScale}" else "0.0000"
            // V3000 标准原子行：序号 元素 X Y Z [AAM]
            // Indigo 生成的 V3000 只包含 AAM 映射（单个数字），其他字段省略
            // 只在需要时显式添加 CHG=/RAD=/VAL= 等属性
            val extraProps = mutableListOf<String>()
            if (charge != 0) extraProps.add("CHG=$charge")
            if (radical != 0) extraProps.add("RAD=$radical")
            val extraStr = if (extraProps.isNotEmpty()) " " + extraProps.joinToString(" ") else ""
            sb.appendLine("M  V30 $idx $sym $xStr $yStr 0.0000 0$extraStr")
        }
        sb.appendLine("M  V30 END ATOM")
        sb.appendLine("M  V30 BEGIN BOND")
        var bondIdx = 1
        for (b in bonds) {
            val a1 = atomIndex[b.atom1] ?: continue
            val a2 = atomIndex[b.atom2] ?: continue
            val order = when (b.type) {
                BondType.SINGLE -> 1
                BondType.DOUBLE -> 2
                BondType.TRIPLE -> 3
                BondType.WEDGE_UP -> 1
                BondType.WEDGE_DOWN -> 1
                BondType.AROMATIC -> 4
            }
            val cfg = when (b.type) {
                BondType.WEDGE_UP -> " CFG=1"
                BondType.WEDGE_DOWN -> " CFG=3"
                else -> ""
            }
            sb.appendLine("M  V30 $bondIdx $order $a1 $a2$cfg")
            bondIdx++
        }
        sb.appendLine("M  V30 END BOND")
        // ── SGROUP：官能团缩写 ──
        if (funcGroups.isNotEmpty()) {
            sb.appendLine("M  V30 BEGIN SGROUP")
            var sgIdx = 1
            for (ann in funcGroups) {
                // 找最近的原子作为 SGROUP 成员
                val nearestAtom = atoms.minByOrNull { a ->
                    sqrt((a.x - ann.x) * (a.x - ann.x) + (a.y - ann.y) * (a.y - ann.y))
                }
                if (nearestAtom != null) {
                    val atomIdx = atomIndex[nearestAtom.id] ?: continue
                    val label = ann.text
                    // Indigo V3000 SGROUP 格式：SUP 类型 + ATOMS + LABEL（纯 ASCII，Indigo 自动识别）
                    val plainLabel = label.replace('₂', '2').replace('₃', '3').replace('₄', '4').replace('₅', '5').replace('₆', '6').replace('₇', '7').replace('₈', '8').replace('₉', '9')
                    sb.appendLine("M  V30 $sgIdx SUP ATOMS=($atomIdx) LABEL=$plainLabel")
                    sgIdx++
                }
            }
            sb.appendLine("M  V30 END SGROUP")
        }
        sb.appendLine("M  V30 END CTAB")
        sb.appendLine("M  END")
        return sb.toString()
    }

    /**
     * 导出为 SVG 矢量图字符串。
     * 使用 1px = 1 单位，留 20px 边距。
     */
    fun toSvg(): String {
        if (atoms.isEmpty() && annotations.isEmpty()) return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"/>"

        val pad = 20f
        val allX = mutableListOf<Float>()
        val allY = mutableListOf<Float>()
        allX.addAll(atoms.map { it.x }); allY.addAll(atoms.map { it.y })
        for (ann in annotations) {
            allX.add(ann.x); allY.add(ann.y)
            if (ann.type == AnnotationType.ARROW) { allX.add(ann.endX); allY.add(ann.endY) }
        }
        val minX = (allX.minOrNull() ?: 0f) - pad
        val minY = (allY.minOrNull() ?: 0f) - pad
        val maxX = (allX.maxOrNull() ?: 100f) + pad
        val maxY = (allY.maxOrNull() ?: 100f) + pad
        val w = maxX - minX
        val h = maxY - minY
        val sb = StringBuilder()
        sb.appendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"$minX $minY $w $h\" width=\"${w.toInt()}\" height=\"${h.toInt()}\">")
        sb.appendLine("  <style>")
        sb.appendLine("    .bond { stroke:#333; stroke-width:2; stroke-linecap:round; fill:none; }")
        sb.appendLine("    .dot { fill:#555; }")
        sb.appendLine("    .label { font-family:sans-serif; font-size:14px; fill:#333; text-anchor:middle; dominant-baseline:central; }")
        sb.appendLine("    .wedge { fill:#333; }")
        sb.appendLine("  </style>")

        // 键
        for (b in bonds) {
            val a1 = atoms.find { it.id == b.atom1 } ?: continue
            val a2 = atoms.find { it.id == b.atom2 } ?: continue
            val dx = a2.x - a1.x; val dy = a2.y - a1.y
            val len = sqrt(dx*dx + dy*dy)
            if (len < 1f) continue
            val ux = dx/len; val uy = dy/len
            val r1 = if (a1.element == Element.C) CARBON_DOT_R else HETERO_CIRCLE_R
            val r2 = if (a2.element == Element.C) CARBON_DOT_R else HETERO_CIRCLE_R
            val sx = a1.x + ux * r1; val sy = a1.y + uy * r1
            val ex = a2.x - ux * r2; val ey = a2.y - uy * r2

            when (b.type) {
                BondType.SINGLE -> {
                    sb.appendLine("  <line class=\"bond\" x1=\"$sx\" y1=\"$sy\" x2=\"$ex\" y2=\"$ey\"/>")
                }
                BondType.DOUBLE -> {
                    val px = -uy * 3f; val py = ux * 3f
                    sb.appendLine("  <line class=\"bond\" x1=\"${sx+px}\" y1=\"${sy+py}\" x2=\"${ex+px}\" y2=\"${ey+py}\"/>")
                    sb.appendLine("  <line class=\"bond\" x1=\"${sx-px}\" y1=\"${sy-py}\" x2=\"${ex-px}\" y2=\"${ey-py}\"/>")
                }
                BondType.TRIPLE -> {
                    val px = -uy * 4f; val py = ux * 4f
                    sb.appendLine("  <line class=\"bond\" x1=\"$sx\" y1=\"$sy\" x2=\"$ex\" y2=\"$ey\"/>")
                    sb.appendLine("  <line class=\"bond\" x1=\"${sx+px}\" y1=\"${sy+py}\" x2=\"${ex+px}\" y2=\"${ey+py}\"/>")
                    sb.appendLine("  <line class=\"bond\" x1=\"${sx-px}\" y1=\"${sy-py}\" x2=\"${ex-px}\" y2=\"${ey-py}\"/>")
                }
                BondType.WEDGE_UP -> {
                    val hw = 6f
                    val perpX = -uy * hw; val perpY = ux * hw
                    sb.appendLine("  <polygon class=\"wedge\" points=\"$sx,$sy ${ex+perpX},${ey+perpY} ${ex-perpX},${ey-perpY}\"/>")
                }
                BondType.WEDGE_DOWN -> {
                    val segLen = 8f; val gapLen = 5f
                    var drawn = 0f
                    while (drawn < len - r1 - r2) {
                        val t1 = drawn / (len - r1 - r2)
                        val t2 = (drawn + segLen) / (len - r1 - r2)
                        if (t2 > 1f) break
                        val lsx = sx + (ex - sx) * t1; val lsy = sy + (ey - sy) * t1
                        val lex = sx + (ex - sx) * t2; val ley = sy + (ey - sy) * t2
                        sb.appendLine("  <line class=\"bond\" x1=\"$lsx\" y1=\"$lsy\" x2=\"$lex\" y2=\"$ley\"/>")
                        drawn += segLen + gapLen
                    }
                }
                BondType.AROMATIC -> {
                    // 凯库勒式或鲍林式：暂时不画，后面统一处理
                    // 凯库勒式：环检测后画单双交替
                    // 鲍林式：实线+内切圆
                }
            }
        }

        // 芳香环渲染（凯库勒式单双交替 / 鲍林式实线+内切圆）
        val paulingRings = findPaulingRings(atoms, bonds)
        if (benzeneStyle == BenzeneStyle.PAULING) {
            // 鲍林式：实线 + 内切圆
            for (ring0 in paulingRings) {
                val ring = ring0 as List<Pair<Float, Float>>
                val xs = ring.map { it.first }
                val ys = ring.map { it.second }
                // 画实线键（用所有 AROMATIC 键的原子对）
                for (i in 0 until ring.size) {
                    val j = (i + 1) % ring.size
                    val a1 = ring[i]; val a2 = ring[j]
                    val dx = a2.first - a1.first; val dy = a2.second - a1.second
                    val len = sqrt(dx*dx + dy*dy)
                    if (len < 1f) continue
                    val ux = dx/len; val uy = dy/len
                    val r = CARBON_DOT_R
                    val sx = a1.first + ux * r; val sy = a1.second + uy * r
                    val ex = a2.first - ux * r; val ey = a2.second - uy * r
                    sb.appendLine("  <line class=\"bond\" x1=\"$sx\" y1=\"$sy\" x2=\"$ex\" y2=\"$ey\"/>")
                }
                // 内切圆
                val cx = xs.sum() / xs.size
                val cy = ys.sum() / ys.size
                val dx = xs.max() - xs.min()
                val dy = ys.max() - ys.min()
                val radius = sqrt((dx * dx + dy * dy).toDouble()).toFloat() * 0.29f
                sb.appendLine("  <circle class=\"bond\" cx=\"$cx\" cy=\"$cy\" r=\"$radius\"/>")
            }
        } else {
            // 凯库勒式：单双交替
            for (ring0 in paulingRings) {
                val ring = ring0 as List<Pair<Float, Float>>
                for (i in 0 until ring.size) {
                    val j = (i + 1) % ring.size
                    val a1 = ring[i]; val a2 = ring[j]
                    val dx = a2.first - a1.first; val dy = a2.second - a1.second
                    val len = sqrt(dx*dx + dy*dy)
                    if (len < 1f) continue
                    val ux = dx/len; val uy = dy/len
                    val r = CARBON_DOT_R
                    val sx = a1.first + ux * r; val sy = a1.second + uy * r
                    val ex = a2.first - ux * r; val ey = a2.second - uy * r
                    if (i % 2 == 0) {
                        // 偶数位：单键
                        sb.appendLine("  <line class=\"bond\" x1=\"$sx\" y1=\"$sy\" x2=\"$ex\" y2=\"$ey\"/>")
                    } else {
                        // 奇数位：双键
                        val px = -uy * 3f; val py = ux * 3f
                        sb.appendLine("  <line class=\"bond\" x1=\"${sx+px}\" y1=\"${sy+py}\" x2=\"${ex+px}\" y2=\"${ey+py}\"/>")
                        sb.appendLine("  <line class=\"bond\" x1=\"${sx-px}\" y1=\"${sy-py}\" x2=\"${ex-px}\" y2=\"${ey-py}\"/>")
                    }
                }
            }
        }

        // 原子（跳过 FUNC_GROUP 标注覆盖的原子）
        for (a in atoms) {
            if (annotations.any { it.type == AnnotationType.FUNC_GROUP && abs(it.x - a.x) < 5f && abs(it.y - a.y) < 5f }) continue
            if (a.element == Element.C) {
                sb.appendLine("  <circle class=\"dot\" cx=\"${a.x}\" cy=\"${a.y}\" r=\"2.5\"/>")
            } else {
                val symbol = a.element.symbol
                val connected = bonds.filter { it.atom1 == a.id || it.atom2 == a.id }
                var bondOrderSum = 0
                for (b in connected) {
                    bondOrderSum += when (b.type) {
                        BondType.SINGLE, BondType.WEDGE_UP, BondType.WEDGE_DOWN -> 1
                        BondType.DOUBLE -> 2; BondType.TRIPLE -> 3
                        BondType.AROMATIC -> 1
                    }
                }
                var hCount = (a.element.valence - bondOrderSum).coerceAtLeast(0)
                // 芳香环修正：sp² C/N 在芳香环中有 π 键消耗 1 个价电子
                if (connected.count { it.type == BondType.AROMATIC } >= 2 && hCount > 0) hCount -= 1
                val text = if (hCount == 0) symbol else if (hCount == 1) "${symbol}H" else "${symbol}H${hCount}"
                sb.appendLine("  <text class=\"label\" x=\"${a.x}\" y=\"${a.y}\">$text</text>")
            }
        }

        // 标注（文字、箭头和官能团缩写）
        sb.appendLine("  <style>")
        sb.appendLine("    .ann-text { font-family:sans-serif; fill:#333; text-anchor:middle; dominant-baseline:central; }")
        sb.appendLine("    .ann-arrow { stroke:#333; stroke-width:2.5; stroke-linecap:round; fill:none; }")
        sb.appendLine("    .ann-arrow-head { stroke:#333; stroke-width:2.5; stroke-linecap:round; fill:#333; }")
        sb.appendLine("  </style>")
        for (ann in annotations) {
            if (ann.type == AnnotationType.TEXT || ann.type == AnnotationType.FUNC_GROUP) {
                val fs = 18f * (ann.scale.coerceAtLeast(0.5f))
                sb.appendLine("  <text class=\"ann-text\" font-size=\"$fs\" x=\"${ann.x}\" y=\"${ann.y}\">${ann.text?.escapeXml() ?: ""}</text>")
            } else if (ann.type == AnnotationType.ARROW) {
                sb.appendLine("  <line class=\"ann-arrow\" x1=\"${ann.x}\" y1=\"${ann.y}\" x2=\"${ann.endX}\" y2=\"${ann.endY}\"/>")
                val dx = ann.endX - ann.x; val dy = ann.endY - ann.y
                val len = sqrt(dx*dx + dy*dy)
                if (len > 5f) {
                    val ux = dx/len; val uy = dy/len
                    val headSize = 12f * (ann.scale.coerceAtLeast(0.5f))
                    val angle = Math.toRadians(25.0)
                    val cosA = cos(angle).toFloat(); val sinA = sin(angle).toFloat()
                    val lx = ann.endX - ux * headSize * cosA + uy * headSize * sinA
                    val ly = ann.endY - uy * headSize * cosA - ux * headSize * sinA
                    val rx = ann.endX - ux * headSize * cosA - uy * headSize * sinA
                    val ry = ann.endY - uy * headSize * cosA + ux * headSize * sinA
                    sb.appendLine("  <line class=\"ann-arrow-head\" x1=\"${ann.endX}\" y1=\"${ann.endY}\" x2=\"$lx\" y2=\"$ly\"/>")
                    sb.appendLine("  <line class=\"ann-arrow-head\" x1=\"${ann.endX}\" y1=\"${ann.endY}\" x2=\"$rx\" y2=\"$ry\"/>")
                }
            }
        }

        sb.appendLine("</svg>")
        return sb.toString()
    }
}

/** 转义 XML 特殊字符 */
private fun String.escapeXml(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/**
 * 从 Molfile V3000 字符串中提取原子坐标。
 * 返回 Map<原子序号(从1开始), (x, y)>，或 null 如果解析失败。
 */
fun parseV3000Coords(v3000: String): Map<Int, Pair<Float, Float>>? {
    val coords = mutableMapOf<Int, Pair<Float, Float>>()
    var inAtomBlock = false
    for (line in v3000.lines()) {
        val trimmed = line.trim()
        if (trimmed.contains("M  V30 BEGIN ATOM")) {
            inAtomBlock = true
            continue
        }
        if (trimmed.contains("M  V30 END ATOM")) {
            inAtomBlock = false
            continue
        }
        if (!inAtomBlock) continue
        // V30 原子行格式: "M  V30 1 C 0.0 0.0 0.0 0 ..."
        // 尝试解析
        val parts = trimmed.split("\\s+".toRegex())
        // 需要找到: M, V30, seqNum, element, x, y, z, ...
        // parts[0]="M", parts[1]="V30", parts[2]=序号, parts[3]=元素, parts[4]=X, parts[5]=Y
        if (parts.size < 6) continue
        if (parts[0] != "M" || parts[1] != "V30") continue
        val seqNum = parts[2].toIntOrNull() ?: continue
        val x = parts[4].toFloatOrNull() ?: continue
        val y = parts[5].toFloatOrNull() ?: continue
        coords[seqNum] = Pair(x, y)
    }
    return if (coords.isEmpty()) null else coords
}

var CARBON_DOT_R = 5f
var HETERO_CIRCLE_R = 16f
var BOND_LENGTH = 55f
const val HIT_THRESHOLD = 28f
var STROKE_WIDTH = 3.5f
var FONT_SIZE = 18f
const val PREVIEW_ALPHA = 0.35f
const val MERGE_THRESHOLD = 22f
var ANN_TEXT_SIZE = 18f
var ARROW_HEAD_SIZE = 12f

/** 自动化学下标转换：H2O → H₂O, CH4 → CH₄, C6H12O6 → C₆H₁₂O₆ */
fun autoSubscript(text: String): String {
    val subscripts = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉'
    )
    val sb = StringBuilder()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isDigit() && i > 0) {
            val prev = text[i - 1]
            if (prev.isLetter() || prev in subscripts.values || prev == ')' || prev == ']' || prev == '}') {
                sb.append(subscripts[c] ?: c)
            } else {
                sb.append(c)
            }
        } else {
            sb.append(c)
        }
        i++
    }
    return sb.toString()
}

/**
 * 选中集合 — 将对选中元素（原子+标注）的常见操作封装为统一方法。
 * 原子 id > 0，标注 id 用负值（-annotationId）区分。
 * 所有修改直接作用在传入的 [atoms]/[annotations] 列表上，触发 Compose 重组。
 */
class Selection(
    val selectedIds: MutableSet<Int>,
    private val atoms: MutableList<MoleculeAtom>,
    private val bonds: MutableList<MoleculeBond>,
    private val annotations: MutableList<MoleculeAnnotation>
) {
    val size get() = selectedIds.size
    val isEmpty get() = selectedIds.isEmpty()
    val isNotEmpty get() = selectedIds.isNotEmpty()

    /** 选中原子 id 列表（正数） */
    val atomIds: Set<Int> get() = selectedIds.filter { it > 0 }.toSet()
    /** 选中标注 id 列表（原始正 id） */
    val annotationIds: Set<Int> get() = selectedIds.filter { it < 0 }.map { -it }.toSet()

    /** 获取选中的原子对象列表 */
    fun getAtoms(): List<MoleculeAtom> = atoms.filter { it.id in atomIds }
    /** 获取选中的标注对象列表 */
    fun getAnnotations(): List<MoleculeAnnotation> = annotations.filter { it.id in annotationIds }
    /** 获取选中原子间的键 */
    fun getInternalBonds(): List<MoleculeBond> {
        val ids = atomIds
        return bonds.filter { it.atom1 in ids && it.atom2 in ids }
    }

    /** 所有选中元素所有坐标点（原子中心+标注点+箭头端点） */
    fun allPoints(): List<Pair<Float, Float>> {
        val pts = mutableListOf<Pair<Float, Float>>()
        pts.addAll(getAtoms().map { Pair(it.x, it.y) })
        for (ann in getAnnotations()) {
            pts.add(Pair(ann.x, ann.y))
            if (ann.type == AnnotationType.ARROW) {
                pts.add(Pair(ann.endX, ann.endY))
            }
        }
        return pts
    }

    /** 包围盒中心 */
    fun center(): Pair<Float, Float> {
        val pts = allPoints()
        if (pts.isEmpty()) return 0f to 0f
        return pts.map { it.first }.average().toFloat() to
               pts.map { it.second }.average().toFloat()
    }

    /** 包围盒 minX, minY, maxX, maxY */
    fun boundingBox(): Pair<Pair<Float, Float>, Pair<Float, Float>>? {
        val pts = allPoints()
        if (pts.isEmpty()) return null
        val xs = pts.map { it.first }; val ys = pts.map { it.second }
        return (xs.min() to ys.min()) to (xs.max() to ys.max())
    }

    /** 辅助线位置（中心十字） */
    fun alignGuidePos(): Pair<Float, Float>? {
        if (isEmpty) return null
        val (cx, cy) = center()
        return cx to cy
    }

    // ── 操作 ──

    /** 平移所有选中元素 (dx, dy) */
    fun moveBy(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        for (aid in atomIds) {
            val idx = atoms.indexOfFirst { it.id == aid }; if (idx < 0) continue
            val old = atoms[idx]
            atoms[idx] = old.copy(x = old.x + dx, y = old.y + dy)
        }
        for (aid in annotationIds) {
            val idx = annotations.indexOfFirst { it.id == aid }; if (idx < 0) continue
            val old = annotations[idx]
            annotations[idx] = old.copy(
                x = old.x + dx, y = old.y + dy,
                endX = if (old.type == AnnotationType.ARROW) old.endX + dx else old.endX,
                endY = if (old.type == AnnotationType.ARROW) old.endY + dy else old.endY
            )
        }
    }

    /** 以整体中心为基点缩放所有选中元素 (factor) */
    fun pinchZoom(sf: Float) {
        val sAtoms = getAtoms(); val sAnn = getAnnotations()
        if (sAtoms.isEmpty() && sAnn.isEmpty()) return
        val (cx, cy) = center()
        for (a in sAtoms) {
            val idx = atoms.indexOfFirst { it.id == a.id }; if (idx < 0) continue
            atoms[idx] = MoleculeAtom(a.id, cx + (a.x - cx) * sf, cy + (a.y - cy) * sf, a.element, a.aromatic, a.chiral, a.funGroupLabel, a.isFunGroupConnector)
        }
        for (ann in sAnn) {
            val idx = annotations.indexOfFirst { it.id == ann.id }; if (idx < 0) continue
            ann.scale *= sf
            val acx = if (ann.type == AnnotationType.ARROW) (ann.x + ann.endX) / 2f else ann.x
            val acy = if (ann.type == AnnotationType.ARROW) (ann.y + ann.endY) / 2f else ann.y
            ann.x = acx + (ann.x - acx) * sf
            ann.y = acy + (ann.y - acy) * sf
            if (ann.type == AnnotationType.ARROW) {
                ann.endX = acx + (ann.endX - acx) * sf
                ann.endY = acy + (ann.endY - acy) * sf
            }
        }
    }

    /** 水平翻转（仅原子） */
    fun flipHorizontal(): String {
        val ids = atomIds
        if (ids.size < 2) return "至少选中两个原子"
        val midX = atoms.filter { it.id in ids }.map { it.x }.average().toFloat()
        for (aid in ids) {
            val idx = atoms.indexOfFirst { it.id == aid }; if (idx < 0) continue
            val old = atoms[idx]
            atoms[idx] = old.copy(x = midX + (midX - old.x))
        }
        var msg = ""
        for (i in bonds.indices) {
            val b = bonds[i]
            if (b.atom1 in ids && b.atom2 in ids) {
                when (b.type) {
                    BondType.WEDGE_UP -> { bonds[i] = b.copy(type = BondType.WEDGE_DOWN); msg = "已自动交换楔形键方向" }
                    BondType.WEDGE_DOWN -> { bonds[i] = b.copy(type = BondType.WEDGE_UP); msg = "已自动交换楔形键方向" }
                    else -> {}
                }
            }
        }
        return if (msg.isNotEmpty()) "水平翻转：$msg" else "水平翻转完成"
    }

    /** 垂直翻转（仅原子） */
    fun flipVertical(): String {
        val ids = atomIds
        if (ids.size < 2) return "至少选中两个原子"
        val midY = atoms.filter { it.id in ids }.map { it.y }.average().toFloat()
        for (aid in ids) {
            val idx = atoms.indexOfFirst { it.id == aid }; if (idx < 0) continue
            val old = atoms[idx]
            atoms[idx] = old.copy(y = midY + (midY - old.y))
        }
        var msg = ""
        for (i in bonds.indices) {
            val b = bonds[i]
            if (b.atom1 in ids && b.atom2 in ids) {
                when (b.type) {
                    BondType.WEDGE_UP -> { bonds[i] = b.copy(type = BondType.WEDGE_DOWN); msg = "已自动交换楔形键方向" }
                    BondType.WEDGE_DOWN -> { bonds[i] = b.copy(type = BondType.WEDGE_UP); msg = "已自动交换楔形键方向" }
                    else -> {}
                }
            }
        }
        return if (msg.isNotEmpty()) "垂直翻转：$msg" else "垂直翻转完成"
    }

    /** 框选：从屏幕矩形（未偏移）更新选中集合 */
    fun updateFromRect(rectStart: Offset, rectEnd: Offset, canvasOffsetX: Float, canvasOffsetY: Float) {
        val minX = minOf(rectStart.x, rectEnd.x) - canvasOffsetX
        val maxX = maxOf(rectStart.x, rectEnd.x) - canvasOffsetX
        val minY = minOf(rectStart.y, rectEnd.y) - canvasOffsetY
        val maxY = maxOf(rectStart.y, rectEnd.y) - canvasOffsetY
        selectedIds.clear()
        selectedIds.addAll(atoms.filter { a -> a.x in minX..maxX && a.y in minY..maxY }.map { it.id })
        selectedIds.addAll(annotations.filter { ann ->
            val cx = if (ann.type == AnnotationType.ARROW) (ann.x + ann.endX) / 2f else ann.x
            val cy = if (ann.type == AnnotationType.ARROW) (ann.y + ann.endY) / 2f else ann.y
            cx in minX..maxX && cy in minY..maxY
        }.map { -it.id })
    }

    /** 清除选中 */
    fun clear() { selectedIds.clear() }

    /** 切换某个 id 的选中状态 */
    fun toggle(id: Int) {
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
    }

    /** 判断是否选中 */
    fun contains(id: Int): Boolean = id in selectedIds
}

/** 键长配置对象 */
object BondLengthConfig {
    var value: Float
        get() = BOND_LENGTH
        set(v) { BOND_LENGTH = v.coerceIn(30f, 100f) }
}

/**
 * 官能团缩写预设 — 插入为文字标注，生成 SMILES 时展开为实际原子。
 * @param name 显示名称
 * @param label 显示标签（如 "NO₂"），直接用作标注文本
 * @param expandAtoms 展开为原子列表：(元素符号, 连接键型=单键)
 * @param connectIndex 哪个展开原子是连接点（索引，默认0）
 */
data class FunctionalGroup(
    val name: String,
    val label: String,
    val expandAtoms: List<Pair<String, BondType>>, // (元素符号, 与连接点之间的键型)
    val connectIndex: Int = 0
)

/**
 * 常见官能团缩写预设列表。
 * 这些缩写作为文字标注显示在画布上。
 * 生成 SMILES / 导出 Mol 时通过 [expandFunctionalGroups] 展开为实际原子。
 */
val FUNCTIONAL_GROUPS = listOf(
    FunctionalGroup("硝基", "NO₂", listOf(
        "N" to BondType.SINGLE,
        "O" to BondType.DOUBLE,
        "O" to BondType.DOUBLE
    )),
    FunctionalGroup("甲基", "CH₃", listOf(
        "C" to BondType.SINGLE,
        "H" to BondType.SINGLE,
        "H" to BondType.SINGLE,
        "H" to BondType.SINGLE
    )),
    FunctionalGroup("乙基", "C₂H₅", listOf(
        "C" to BondType.SINGLE,
        "C" to BondType.SINGLE,
        "H" to BondType.SINGLE,
        "H" to BondType.SINGLE,
        "H" to BondType.SINGLE,
        "H" to BondType.SINGLE,
        "H" to BondType.SINGLE
    )),
    FunctionalGroup("羟基", "OH", listOf(
        "O" to BondType.SINGLE,
        "H" to BondType.SINGLE
    )),
    FunctionalGroup("羰基", "C=O", listOf(
        "C" to BondType.SINGLE,
        "O" to BondType.DOUBLE
    )),
    FunctionalGroup("羧基", "COOH", listOf(
        "C" to BondType.SINGLE,
        "O" to BondType.DOUBLE,
        "O" to BondType.SINGLE,
        "H" to BondType.SINGLE
    )),
    FunctionalGroup("氨基", "NH₂", listOf(
        "N" to BondType.SINGLE,
        "H" to BondType.SINGLE,
        "H" to BondType.SINGLE
    )),
    FunctionalGroup("苯基", "Ph", listOf(
        "C" to BondType.AROMATIC,
        "C" to BondType.AROMATIC,
        "C" to BondType.AROMATIC,
        "C" to BondType.AROMATIC,
        "C" to BondType.AROMATIC,
        "C" to BondType.AROMATIC
    )),
    FunctionalGroup("醛基", "CHO", listOf(
        "C" to BondType.SINGLE,
        "O" to BondType.DOUBLE,
        "H" to BondType.SINGLE
    )),
    FunctionalGroup("磺酸基", "SO₃H", listOf(
        "S" to BondType.SINGLE,
        "O" to BondType.DOUBLE,
        "O" to BondType.DOUBLE,
        "O" to BondType.SINGLE,
        "H" to BondType.SINGLE
    )),
    FunctionalGroup("氰基", "CN", listOf(
        "C" to BondType.SINGLE,
        "N" to BondType.TRIPLE
    ))
)

/**
 * 将官能团缩写标注展开为实际原子+键，用于生成 SMILES。
 * @param atoms 现有原子列表（展开时重定向连接键）
 * @param bonds 现有键列表（展开时追加新键）
 * @param annotations 标注列表（只处理 FUNC_GROUP 类型）
 * @return 展开后的完整原子和键列表（不修改原列表）
 */
fun expandFunctionalGroups(
    atoms: List<MoleculeAtom>,
    bonds: List<MoleculeBond>,
    annotations: List<MoleculeAnnotation>
): Pair<List<MoleculeAtom>, List<MoleculeBond>> {
    val resultAtoms = atoms.toMutableList()
    val resultBonds = bonds.toMutableList()

    // 查找所有官能团标注
    val funcAnn = annotations.filter { it.type == AnnotationType.FUNC_GROUP && it.text.isNotBlank() }
    if (funcAnn.isEmpty()) return resultAtoms to resultBonds

    // 对每个官能团标注，展开为原子
    for (ann in funcAnn) {
        val fg = FUNCTIONAL_GROUPS.find { it.label == ann.text } ?: continue
        // 找连接对象：官能团标注位置最近的原子
        val connectAtom = atoms.minByOrNull { a ->
            sqrt((a.x - ann.x) * (a.x - ann.x) + (a.y - ann.y) * (a.y - ann.y))
        } ?: continue

        // 检查距离是否合理（不能太远）
        val dist = sqrt((connectAtom.x - ann.x) * (connectAtom.x - ann.x) + (connectAtom.y - ann.y) * (connectAtom.y - ann.y))
        if (dist > BOND_LENGTH * 2) continue

        // 判断是否要跳过连接点：连接点元素与 connectAtom 相同（如 CH₃ 的 C 连到已有 C）
        val connectElem = Element.fromSymbol(fg.expandAtoms[fg.connectIndex].first)
        val skipConnect = (connectElem == connectAtom.element)

        // 检测标注是否精确覆盖在原子位置（拖拽替换模式）
        val isReplaceMode = dist < 1f

        // 创建展开原子（跳过 H 原子——让 Indigo 自动计算隐式氢）
        val newAtoms = mutableListOf<MoleculeAtom>()
        val startId = (resultAtoms.maxOfOrNull { it.id } ?: 0) + 1
        val nonConnectList = fg.expandAtoms.filterIndexed { i, _ -> i != fg.connectIndex }
        var newIdx = 0
        for ((j, pair) in nonConnectList.withIndex()) {
            val (elemSym, _) = pair
            val elem = Element.fromSymbol(elemSym)
            if (elem == Element.H) continue // 跳过 H，Indigo 自动补隐式氢
            val angle = if (skipConnect) newIdx * (360.0 / nonConnectList.filter { Element.fromSymbol(it.first) != Element.H }.size.coerceAtLeast(1)) 
                        else (j + if (j >= fg.connectIndex) 1 else 0) * (360.0 / fg.expandAtoms.size)
            val rad = Math.toRadians(angle)
            val offset = BOND_LENGTH * 0.6f
            val ax = ann.x + offset * cos(rad).toFloat()
            val ay = ann.y + offset * sin(rad).toFloat()
            newAtoms.add(MoleculeAtom(startId + newIdx, ax, ay, elem))
            newIdx++
        }
        resultAtoms.addAll(newAtoms)

        // 实际连接点 id
        val actualConnectId: Int
        if (skipConnect) {
            // 跳过连接点，直接复用 connectAtom
            actualConnectId = connectAtom.id
            // 如果是替换模式（标注覆盖原子），删除 connectAtom 但保留其键连接
            if (isReplaceMode) {
                // connectAtom 被跳过（本身就是连接点原子），不需要额外处理
                // 但 connectAtom 仍然保留在 resultAtoms 中，drawAtom 会跳过它
                // SMILES 生成时它会正常参与
            }
        } else {
            // 创建连接点原子
            val connElem = Element.fromSymbol(fg.expandAtoms[fg.connectIndex].first)
            val angle = 0.0
            val rad = Math.toRadians(angle)
            val offset = BOND_LENGTH * 0.6f
            val ax = ann.x + offset * cos(rad).toFloat()
            val ay = ann.y + offset * sin(rad).toFloat()
            val connAtom = MoleculeAtom(startId + fg.expandAtoms.size - 1, ax, ay, connElem)
            resultAtoms.add(connAtom)
            
            if (isReplaceMode) {
                // 替换模式：删除 connectAtom，把所有连到 connectAtom 的键重连到 connAtom
                val bondsToRedirect = resultBonds.filter { it.atom1 == connectAtom.id || it.atom2 == connectAtom.id }
                for (b in bondsToRedirect) {
                    val idx = resultBonds.indexOf(b)
                    if (idx >= 0) {
                        val newAtom1 = if (b.atom1 == connectAtom.id) connAtom.id else b.atom1
                        val newAtom2 = if (b.atom2 == connectAtom.id) connAtom.id else b.atom2
                        resultBonds[idx] = b.copy(atom1 = newAtom1, atom2 = newAtom2)
                    }
                }
                resultAtoms.remove(connectAtom)
                actualConnectId = connAtom.id
            } else {
                // 非替换模式：连接点原子通过键连到 connectAtom
                resultBonds.add(MoleculeBond(
                    (resultBonds.maxOfOrNull { it.id } ?: 0) + 1,
                    connectAtom.id, connAtom.id
                ))
                actualConnectId = connAtom.id
            }
        }

        // 连接点与其他展开重原子之间的键
        // newAtoms 中只包含非 H 的重原子，顺序与 nonConnectList 中非 H 原子一致
        val heavyNonConnect = nonConnectList.filter { Element.fromSymbol(it.first) != Element.H }
        for ((hi, pair) in heavyNonConnect.withIndex()) {
            if (hi < newAtoms.size) {
                resultBonds.add(MoleculeBond(
                    (resultBonds.maxOfOrNull { it.id } ?: 0) + 1,
                    actualConnectId, newAtoms[hi].id, pair.second
                ))
            }
        }
    }

    return resultAtoms to resultBonds
}

/**
 * 检测含 AROMATIC 键的六元环（苯环），返回每个环的顶点坐标列表。
 * 用于导出 SVG/位图/PDF 时绘制鲍林式内切圆。
 */
fun findPaulingRings(atoms: List<MoleculeAtom>, bonds: List<MoleculeBond>): List<List<Pair<Float, Float>>> {
    val aromaticBonds = bonds.filter { it.type == BondType.AROMATIC }
    if (aromaticBonds.size < 6) return emptyList()
    val adj = mutableMapOf<Int, MutableList<Int>>()
    for (b in aromaticBonds) {
        adj.getOrPut(b.atom1) { mutableListOf() }.add(b.atom2)
        adj.getOrPut(b.atom2) { mutableListOf() }.add(b.atom1)
    }
    val seenRings = mutableSetOf<Set<Int>>()
    val result = mutableListOf<List<Pair<Float, Float>>>()
    for (b in aromaticBonds) {
        val a1 = b.atom1; val a2 = b.atom2
        val parent = mutableMapOf<Int, Int>()
        val queue = ArrayDeque<Int>(); queue.addLast(a2)
        parent[a2] = -1
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur == a1) break
            for (nb in adj[cur].orEmpty()) {
                if (cur == a2 && nb == a1) continue
                if (cur == a1 && nb == a2) continue
                if (nb !in parent) {
                    parent[nb] = cur
                    if (parent.size < 12) queue.addLast(nb)
                }
            }
        }
        if (a1 in parent) {
            val ring = mutableListOf<Int>()
            var c = a1
            while (c != -1 && c != a2) { ring.add(c); c = parent[c] ?: -1 }
            if (c == a2) ring.add(a2)
            if (ring.size == 6) {
                val key = ring.toSortedSet()
                if (key !in seenRings) {
                    seenRings.add(key)
                    val verts = ring.mapNotNull { id -> atoms.find { it.id == id }?.let { a -> a.x to a.y } }
                    if (verts.size == 6) result.add(verts)
                }
            }
        }
    }
    return result
}
