package com.moldraw.app.ui.moleculedraw

import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.*

// ── IDs ──
internal var gNextId = 1
fun nextId() = gNextId++
fun resetId() { gNextId = 1 }

fun snapAngle(deg: Float): Float = (Math.round(deg / 45f) * 45f).let { if (it < 0) it + 360f else it % 360f }

/** 查找距离pos最近的原子（排除官能团组内原子），如果小于HIT_THRESHOLD则返回 */
fun hitAtom(atoms: List<MoleculeAtom>, pos: Offset): MoleculeAtom? =
    atoms.filter { it.funGroupLabel == null } // 排除官能团组内原子（不可交互）
        .minByOrNull { val d = it.x - pos.x; val e = it.y - pos.y; d*d + e*e }
        ?.takeIf { sqrt((it.x-pos.x)*(it.x-pos.x) + (it.y-pos.y)*(it.y-pos.y)) < HIT_THRESHOLD }

/** 查找距离pos最近的原子，如果小于MERGE_THRESHOLD则返回（用于合并） */
fun findMergeAtom(atoms: List<MoleculeAtom>, pos: Offset): MoleculeAtom? =
atoms.minByOrNull { val d = it.x - pos.x; val e = it.y - pos.y; d*d + e*e }
?.takeIf { sqrt((it.x-pos.x)*(it.x-pos.x) + (it.y-pos.y)*(it.y-pos.y)) < MERGE_THRESHOLD }

/** 查找距离pos附近MERGE_THRESHOLD范围内的所有原子（用于多重重合提醒） */
fun findMergeAtoms(atoms: List<MoleculeAtom>, pos: Offset): List<MoleculeAtom> =
atoms.filter { a ->
    val dx = a.x - pos.x; val dy = a.y - pos.y
    sqrt(dx * dx + dy * dy) < MERGE_THRESHOLD
}

/** 检测pos是否在某个键上（距离键线<15px），返回 (键, 距离最近端点) */
fun hitBond(bonds: List<MoleculeBond>, atoms: List<MoleculeAtom>, pos: Offset): Pair<MoleculeBond, MoleculeAtom?>? {
    for (b in bonds) {
        val a1 = atoms.find { it.id == b.atom1 } ?: continue
        val a2 = atoms.find { it.id == b.atom2 } ?: continue
        val dx = a2.x - a1.x; val dy = a2.y - a1.y
        val len = sqrt(dx*dx + dy*dy); if (len < 1f) continue
        val t = ((pos.x - a1.x) * dx + (pos.y - a1.y) * dy) / (len * len)
        val tClamped = t.coerceIn(0f, 1f)
        val nearX = a1.x + tClamped * dx
        val nearY = a1.y + tClamped * dy
        val dist = sqrt((pos.x - nearX)*(pos.x - nearX) + (pos.y - nearY)*(pos.y - nearY))
        if (dist < 15f) {
            val endNear = if (tClamped < 0.5f) a1 else a2
            return Pair(b, endNear)
        }
    }
    return null
}

fun hitAnnotation(annotations: List<MoleculeAnnotation>, pos: Offset): MoleculeAnnotation? {
    // 文字标注：检测点击在文字附近（扩大范围到 60px）
    // 箭头标注：检测点击在箭头线附近
    for (ann in annotations) {
        when (ann.type) {
            AnnotationType.TEXT, AnnotationType.FUNC_GROUP -> {
                val dx = pos.x - ann.x; val dy = pos.y - ann.y
                val dist = sqrt(dx*dx + dy*dy)
                android.util.Log.d("MOLDRAW_DEBUG", "hitAnnotation TEXT id=${ann.id} pos=(${pos.x},${pos.y}) ann=(${ann.x},${ann.y}) dist=$dist")
                if (dist < 60f) return ann
            }
            AnnotationType.ARROW -> {
                val dx = ann.endX - ann.x; val dy = ann.endY - ann.y
                val len = sqrt(dx*dx + dy*dy); if (len < 1f) continue
                val t = ((pos.x - ann.x) * dx + (pos.y - ann.y) * dy) / (len * len)
                val tClamped = t.coerceIn(0f, 1f)
                val nearX = ann.x + tClamped * dx
                val nearY = ann.y + tClamped * dy
                val dist = sqrt((pos.x - nearX)*(pos.x - nearX) + (pos.y - nearY)*(pos.y - nearY))
                android.util.Log.d("MOLDRAW_DEBUG", "hitAnnotation ARROW id=${ann.id} dist=$dist")
                if (dist < 30f) return ann
            }
        }
    }
    return null
}

/** 在两点间自由建键（检测已有原子或创建新原子，不再自动合并） */
fun createFreeBond(
    atoms: MutableList<MoleculeAtom>,
    bonds: MutableList<MoleculeBond>,
    startP: Offset, endP: Offset, bondType: BondType
) {
    val dx = endP.x - startP.x; val dy = endP.y - startP.y
    val len = sqrt(dx*dx + dy*dy)
    if (len < 10f) return
    val startAtom = hitAtom(atoms, startP)
    val endAtom = hitAtom(atoms, endP)
    val a1 = startAtom ?: MoleculeAtom(nextId(), startP.x, startP.y, Element.C)
    val a2 = endAtom ?: MoleculeAtom(nextId(), endP.x, endP.y, Element.C)
    if (startAtom == null) atoms.add(a1)
    if (endAtom == null) atoms.add(a2)
    if (a1.id != a2.id && !bonds.any { (it.atom1 == a1.id && it.atom2 == a2.id) || (it.atom1 == a2.id && it.atom2 == a1.id) }) {
        bonds.add(MoleculeBond(nextId(), a1.id, a2.id, bondType))
    }
}

/** 计算正n边形环的顶点，检测每个顶点是否吸附到已有原子，返回(顶点列表, 吸附映射) */
fun computeRingVertices(
    atoms: List<MoleculeAtom>,
    cx: Float, cy: Float, n: Int
): Pair<Array<Pair<Float, Float>>, Map<Int, MoleculeAtom>> {
    val r = BOND_LENGTH / (2f * sin(Math.toRadians((180.0 / n)).toFloat()))
    val ringPoints = Array(n) { i ->
        val deg = i * (360f / n) - 90f
        val rad = Math.toRadians(deg.toDouble())
        Pair(cx + r * cos(rad).toFloat(), cy + r * sin(rad).toFloat())
    }
    val merged = mutableMapOf<Int, MoleculeAtom>()
    for (i in 0 until n) {
        val (px, py) = ringPoints[i]
        val merge = findMergeAtom(atoms, Offset(px, py))
        if (merge != null) merged[i] = merge
    }
    return Pair(ringPoints, merged)
}

/** 放置正n边形环 */
fun placeRing(
    atoms: MutableList<MoleculeAtom>,
    bonds: MutableList<MoleculeBond>,
    cx: Float, cy: Float, n: Int
) {
    val (ringPoints, merged) = computeRingVertices(atoms, cx, cy, n)
    val idMap = mutableMapOf<Int, Int>()
    for ((i, ma) in merged) idMap[i] = ma.id
    for (i in 0 until n) {
        if (i in idMap) continue
        val (px, py) = ringPoints[i]
        atoms.add(MoleculeAtom(nextId(), px, py, Element.C))
        idMap[i] = atoms.last().id
    }
    for (i in 0 until n) {
        val a = idMap[i] ?: continue
        val b = idMap[(i + 1) % n] ?: continue
        if (a != b && !bonds.any { (it.atom1 == a && it.atom2 == b) || (it.atom1 == b && it.atom2 == a) }) {
            bonds.add(MoleculeBond(nextId(), a, b))
        }
    }
}

/** 计算原子的隐式氢数量 */
fun calcImplicitH(atom: MoleculeAtom, bonds: List<MoleculeBond>): Int {
    if (atom.element == Element.H || atom.element == Element.C) return 0 // C 用圆点表示，不显示 H
    val connected = bonds.filter { it.atom1 == atom.id || it.atom2 == atom.id }
    var bondOrderSum = 0
    var aromaticNeighborCount = 0
    for (b in connected) {
        when (b.type) {
            BondType.SINGLE, BondType.WEDGE_UP, BondType.WEDGE_DOWN -> bondOrderSum += 1
            BondType.DOUBLE -> bondOrderSum += 2
            BondType.TRIPLE -> bondOrderSum += 3
            BondType.AROMATIC -> { bondOrderSum += 1; aromaticNeighborCount++ }
        }
    }
    var h = (atom.element.valence - bondOrderSum).coerceAtLeast(0)
    // 芳香环修正：芳香体系中原子参与离域 π 键，消耗 1 个额外价电子
    if (aromaticNeighborCount >= 2 && h > 0) h -= 1
    return h
}

/** 放置正n边形环（苯环根据 BenzeneStyle 使用不同键类型） */
fun placeRing(
    atoms: MutableList<MoleculeAtom>,
    bonds: MutableList<MoleculeBond>,
    cx: Float, cy: Float, n: Int, benzene: Boolean = false,
    benzeneStyle: BenzeneStyle = BenzeneStyle.KEKULE
) {
    val (ringPoints, merged) = computeRingVertices(atoms, cx, cy, n)
    val idMap = mutableMapOf<Int, Int>()
    for ((i, ma) in merged) idMap[i] = ma.id
    for (i in 0 until n) {
        if (i in idMap) continue
        val (px, py) = ringPoints[i]
        atoms.add(MoleculeAtom(nextId(), px, py, Element.C, aromatic = benzene))
        idMap[i] = atoms.last().id
    }
    for (i in 0 until n) {
        val a = idMap[i] ?: continue
        val b = idMap[(i + 1) % n] ?: continue
        if (a != b && !bonds.any { (it.atom1 == a && it.atom2 == b) || (it.atom1 == b && it.atom2 == a) }) {
            if (benzene && benzeneStyle == BenzeneStyle.PAULING) {
                bonds.add(MoleculeBond(nextId(), a, b, BondType.AROMATIC))
            } else if (benzene) {
                // 凯库勒式：数据也用 AROMATIC，渲染时在画布层按奇偶显示单双交替
                bonds.add(MoleculeBond(nextId(), a, b, BondType.AROMATIC))
            } else {
                bonds.add(MoleculeBond(nextId(), a, b))
            }
        }
    }
}

/**
 * 分子布局引擎接口。
 * 实现该接口的类可以通过外部布局引擎（如 Indigo）为分子计算布局坐标。
 */
fun interface MoleculeLayoutEngine {
    /**
     * 对给定的 V3000 Molfile 字符串进行布局计算。
     * @param molfileV3000 包含分子拓扑信息的 V3000 格式 Molfile
     * @return 布局后的 V3000 Molfile 字符串（包含原子坐标），或 null 表示失败
     */
    fun layout(molfileV3000: String): String?
}

/**
 * 自动调整：优化分子结构布局
 *
 * 算法分五阶段：
 *   1. 构建邻接图
 *   2. BFS 检测所有环 → 标记环原子
 *   3. 每个环 → 正多边形排列（半径 = BOND_LENGTH / (2·sin(π/n))）
 *   4. 链式结构 → 从环原子 BFS 向外布局，±120° 锯齿主链，±60° 偏转分支
 *   5. 纯链结构（无环）→ 端点出发，交替 ±120° 锯齿排列
 *   6. 键长标准化 + 整体居中
 *
 * @param layoutEngine 可选的外部布局引擎（如 Indigo）。若提供则优先使用外部引擎进行布局。
 */
fun autoFit(
    atoms: MutableList<MoleculeAtom>,
    bonds: MutableList<MoleculeBond>,
    selectedIds: Set<Int>,
    layoutEngine: MoleculeLayoutEngine? = null,
    canvasWidth: Float = 800f,
    canvasHeight: Float = 600f,
    annotations: MutableList<MoleculeAnnotation>? = null
) {
    // ★ 日志 → logcat（Android 13+ 对 /sdcard/ 直接写入有限制）
    android.util.Log.d("MOLDRAW_DEBUG", "autoFit ENTERED atoms=${atoms.size} bonds=${bonds.size} sel=${selectedIds.size}")
    android.util.Log.d("MOLDRAW_DEBUG", "layoutEngine=${layoutEngine} selIds=${selectedIds}")
    android.util.Log.d("MOLDRAW_DEBUG", "atoms: ${atoms.joinToString(";") { "${it.id}(${it.x},${it.y})" }}")
    if (selectedIds.size < 2) return
    val selSet = selectedIds

    // ── 1. 仅选区内构建邻接表 ──
    val adj = mutableMapOf<Int, MutableList<Int>>()
    for (id in selSet) adj[id] = mutableListOf()
    for (b in bonds) {
        if (b.atom1 in selSet && b.atom2 in selSet) {
            adj[b.atom1]!!.add(b.atom2)
            adj[b.atom2]!!.add(b.atom1)
        }
    }

    // ── 2. BFS 最小环检测（SSSR-like） ──
    val ringAtomIds = mutableSetOf<Int>()
    val ringPaths   = mutableListOf<List<Int>>()
    run {
        // 对每条边，BFS 找包含该边的最小环
        val seenEdges = mutableSetOf<Pair<Int, Int>>()
        for (b in bonds) {
            if (b.atom1 !in selSet || b.atom2 !in selSet) continue
            val edgeKey = if (b.atom1 < b.atom2) b.atom1 to b.atom2 else b.atom2 to b.atom1
            if (edgeKey in seenEdges) continue
            seenEdges.add(edgeKey)

            val (u, v) = edgeKey
            // BFS 从 u 到 v，但禁止直接走边 (u,v)
            val parent = mutableMapOf(u to -1)
            val dist = mutableMapOf(u to 0)
            val queue = ArrayDeque<Int>()
            queue.addLast(u)
            var found = false
            while (queue.isNotEmpty() && !found) {
                val cur = queue.removeFirst()
                for (nbr in adj[cur].orEmpty()) {
                    // 禁止直接使用边 (u,v)
                    if (cur == u && nbr == v) continue
                    if (cur == v && nbr == u) continue
                    if (nbr in parent) continue
                    parent[nbr] = cur
                    dist[nbr] = (dist[cur] ?: 0) + 1
                    if (nbr == v) {
                        found = true
                        break
                    }
                    if ((dist[cur] ?: 0) < 6) { // 限制深度
                        queue.addLast(nbr)
                    }
                }
            }
            if (!found) continue

            // 构造环路径：从 parent 回溯 v → ... → u，加入直接边 u-v 构成完整环
            val cycle = mutableListOf<Int>()
            var cur = v
            while (cur != u) {
                cycle.add(cur)
                cur = parent[cur] ?: break
            }
            cycle.add(u)
            // cycle = [v, ..., u], fullCycle = [u, ..., v] 加上直接边 v
            val fullCycle = buildList {
                addAll(cycle.asReversed()) // u → ... → v
            }
            if (fullCycle.size < 3) continue
            // 规范化：从最小 id 开始，最小方向
            val n = fullCycle.size
            val forward = List(n) { shift -> List(n) { fullCycle[(shift + it) % n] } }
            val reversed = fullCycle.asReversed()
            val backward = List(n) { shift -> List(n) { reversed[(shift + it) % n] } }
            val best = (forward + backward).minWith(compareBy { it.joinToString(",") })

            ringPaths.add(best)
        }
        // 去重 + SSSR：按大小排序，只保留独立环
        val unique = ringPaths.distinctBy { it.toSet() }
        // SSSR 构建：按环大小排序，逐一检查独立性
        val sortedUnique = unique.sortedBy { it.size }
        val sssrRings = mutableListOf<List<Int>>()
        val coveredEdges = mutableSetOf<Pair<Int, Int>>()
        for (r in sortedUnique) {
            // 该环的边集
            val rEdges = mutableSetOf<Pair<Int, Int>>()
            for (i in r.indices) {
                val a = r[i]; val b = r[(i + 1) % r.size]
                val edge = if (a < b) a to b else b to a
                rEdges.add(edge)
            }
            // 检查是否所有边已被覆盖
            if (rEdges.all { it in coveredEdges }) continue
            // 独立环，加入
            sssrRings.add(r)
            coveredEdges.addAll(rEdges)
        }
        ringPaths.clear()
        ringPaths.addAll(sssrRings)
        ringAtomIds.addAll(ringPaths.flatten())

        // DEBUG: 输出环检测结果
        val sb = StringBuilder("=== autoFit ring detection ===\n")
        sb.appendLine("ringPaths count=${ringPaths.size}")
        ringPaths.forEachIndexed { i, p ->
            sb.appendLine("  ring[$i]: ${p.joinToString(",")} (size=${p.size})")
        }
        sb.appendLine("ringAtomIds=${ringAtomIds.joinToString(",")}")
        android.util.Log.d("MOLDRAW_DEBUG", sb.toString().trimEnd())
    }

    // ── 外部引擎布局分支 ──
    if (layoutEngine != null) {
        android.util.Log.d("MOLDRAW_DEBUG", "[autoFit] layoutEngine is NOT null, type=${layoutEngine::class.simpleName}")
        try {
            // 只将选中的原子和它们之间的键传给引擎，避免影响未选中的分子
            val selAtomsList = atoms.filter { it.id in selSet }
            val selBondsList = bonds.filter { it.atom1 in selSet && it.atom2 in selSet }
            android.util.Log.d("MOLDRAW_DEBUG", "[autoFit] selOnly: atoms=${selAtomsList.size} bonds=${selBondsList.size}")

            if (layoutEngine is IndigoNativeLayoutEngineAdapter) {
                // Indigo 引擎：和 SMILES 导入完全一样的流程
                // 1. 生成 SMILES（包含官能团标注展开）
                // 2. layoutAndClean（含 clean2d 美化）
                // 3. 解析 V3000 构建新分子
                // 4. 完全替换选中区域
                // 5. 保留/重新放置官能团标注
                
                // 保存选中区域相关的官能团标注及组内元素信息（供 V3000 解析后重新标记）
                val savedFuncGroups = if (annotations != null) {
                    annotations.filter { it.type == AnnotationType.FUNC_GROUP && it.text.isNotBlank() }
                        .mapNotNull { ann ->
                            val fg = FUNCTIONAL_GROUPS.firstOrNull { it.label == ann.text }
                            if (fg != null) {
                                val connElem = Element.fromSymbol(fg.expandAtoms[fg.connectIndex].first)
                                val memberElems = fg.expandAtoms.filterIndexed { i, _ -> i != fg.connectIndex }
                                    .map { Element.fromSymbol(it.first) }.filter { it != Element.H }.toSet()
                                android.util.Log.d("MOLDRAW_DEBUG", "[autoFit] saving FUNC_GROUP ${ann.text} connElem=$connElem memberElems=$memberElems")
                                Triple(ann, connElem, memberElems)
                            } else null
                        }
                } else emptyList()
                android.util.Log.d("MOLDRAW_DEBUG", "[autoFit] savedFuncGroups count=${savedFuncGroups.size}")

                // 官能团缩写：原子已常驻展开，直接传所有原子（不过滤 FUNC_GROUP 标注避免重复展开）
                val cleanAnn = annotations?.filter { it.type != AnnotationType.FUNC_GROUP } ?: emptyList()
                val smiles = layoutEngine.generateSmiles(selAtomsList, selBondsList, cleanAnn)
                if (smiles == null || smiles.isEmpty()) {
                    android.util.Log.w("MOLDRAW_DEBUG", "[autoFit] generateSmiles returned null/empty, giving up")
                    return
                }
                android.util.Log.d("MOLDRAW_DEBUG", "[autoFit] Generated SMILES: '$smiles'")

                val result = layoutEngine.layoutAndClean(smiles)
                android.util.Log.d("MOLDRAW_DEBUG", "[autoFit] layoutAndClean result=${if (result == null) "NULL" else "ok (${result.length} chars)"}")

                if (result != null) {
                    android.util.Log.d("MOLDRAW_DEBUG", "[autoFit] V3000:\n$result")

                    // 解析 V3000（同 SMILES 导入）
                    val lines = result.lines()
                    var parsingAtoms = false; var parsingBonds = false
                    val tempAtoms = mutableListOf<MoleculeAtom>()
                    val tempBonds = mutableListOf<MoleculeBond>()
                    val SCALE = BOND_LENGTH / 1.5f

                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.contains("BEGIN ATOM")) { parsingAtoms = true; parsingBonds = false; continue }
                        if (trimmed.contains("BEGIN BOND")) { parsingAtoms = false; parsingBonds = true; continue }
                        if (trimmed.startsWith("M  V30 END") || trimmed.startsWith("M V30 END")) { parsingAtoms = false; parsingBonds = false; continue }
                        if (parsingAtoms && (trimmed.startsWith("M  V30 ") || trimmed.startsWith("M V30 "))) {
                            val parts = trimmed.split("\\s+".toRegex())
                            if (parts.size >= 6) {
                                val seq = parts[2].toIntOrNull() ?: continue
                                val elem = Element.fromSymbol(parts[3]) ?: Element.C
                                val x = parts[4].toFloatOrNull() ?: 0f
                                val y = parts[5].toFloatOrNull() ?: 0f
                                tempAtoms.add(MoleculeAtom(seq, x * SCALE, y * SCALE, elem))
                            }
                        }
                        if (parsingBonds && (trimmed.startsWith("M  V30 ") || trimmed.startsWith("M V30 "))) {
                            val parts = trimmed.split("\\s+".toRegex())
                            if (parts.size >= 6) {
                                val typeVal = parts[3].toIntOrNull() ?: 1
                                val from = parts[4].toIntOrNull() ?: continue
                                val to = parts[5].toIntOrNull() ?: continue
                                var bt = when (typeVal) { 2 -> BondType.DOUBLE; 3 -> BondType.TRIPLE; 4 -> BondType.AROMATIC; else -> BondType.SINGLE }
                                for (i in 6 until parts.size) {
                                    when (parts[i]) {
                                        "CFG=1" -> { bt = BondType.WEDGE_UP; break }
                                        "CFG=3" -> { bt = BondType.WEDGE_DOWN; break }
                                    }
                                }
                                tempBonds.add(MoleculeBond(nextId(), from, to, bt))
                            }
                        }
                    }

                    android.util.Log.d("MOLDRAW_DEBUG", "[autoFit] parsed atoms=${tempAtoms.size} bonds=${tempBonds.size}")

                    if (tempAtoms.isNotEmpty()) {
                        // 计算原选中区域的中心（用于保持位置）
                        val oldSelAtoms = atoms.filter { it.id in selSet }
                        val oldCenterX = if (oldSelAtoms.isNotEmpty()) (oldSelAtoms.minOf { it.x } + oldSelAtoms.maxOf { it.x }) / 2f else 0f
                        val oldCenterY = if (oldSelAtoms.isNotEmpty()) (oldSelAtoms.minOf { it.y } + oldSelAtoms.maxOf { it.y }) / 2f else 0f

                        // 完全替换选中区域：删除选中的旧原子和键
                        atoms.removeAll { it.id in selSet }
                        bonds.removeAll { it.atom1 in selSet || it.atom2 in selSet }

                        // 计算新分子的中心
                        val newCenterX = (tempAtoms.minOf { it.x } + tempAtoms.maxOf { it.x }) / 2f
                        val newCenterY = (tempAtoms.minOf { it.y } + tempAtoms.maxOf { it.y }) / 2f
                        val offsetX = oldCenterX - newCenterX
                        val offsetY = oldCenterY - newCenterY

                        // 新原子重新分配 id，并平移到原位置
                        val idOffset = (atoms.maxOfOrNull { it.id } ?: 0) + 1
                        val newAtoms = tempAtoms.map { a ->
                            MoleculeAtom(a.id + idOffset, a.x + offsetX, a.y + offsetY, a.element)
                        }.toMutableList()
                        val newBonds = tempBonds.map { b ->
                            MoleculeBond(nextId(), b.atom1 + idOffset, b.atom2 + idOffset, b.type)
                        }

                        // 重新标记官能团原子（从 V3000 解析后重新检测各组内原子）
                        if (savedFuncGroups.isNotEmpty()) {
                            for ((ann, connElem, memberElems) in savedFuncGroups) {
                                // 找连接点原子：元素匹配，且尽量连接到非组内原子（如苯环 C）
                                val connector = newAtoms.filter { it.element == connElem }
                                    .maxByOrNull { a ->
                                        newBonds.count { b ->
                                            (b.atom1 == a.id || b.atom2 == a.id) &&
                                            newAtoms.none {
                                                it.id == (if (b.atom1 == a.id) b.atom2 else b.atom1) &&
                                                it.element in memberElems
                                            }
                                        }
                                    }
                                if (connector != null) {
                                    connector.funGroupLabel = ann.text
                                    connector.isFunGroupConnector = true
                                    // 找成员原子：元素匹配且连到连接点
                                    for (memberAtom in newAtoms.filter { it.element in memberElems }) {
                                        val isBonded = newBonds.any { b ->
                                            (b.atom1 == memberAtom.id || b.atom2 == memberAtom.id) &&
                                            (b.atom1 == connector.id || b.atom2 == connector.id)
                                        }
                                        if (isBonded) {
                                            memberAtom.funGroupLabel = ann.text
                                            memberAtom.isFunGroupConnector = false
                                        }
                                    }
                                    // 更新标注位置到连接点原子
                                    ann.x = connector.x
                                    ann.y = connector.y
                                    android.util.Log.d("MOLDRAW_DEBUG", "[autoFit] repositioned FUNC_GROUP ${ann.text} to connector ${connector.id} (${connector.x},${connector.y})")
                                }
                            }
                        }

                        atoms.addAll(newAtoms)
                        bonds.addAll(newBonds)

                        // 保持原位置，不居中
                        android.util.Log.d("MOLDRAW_DEBUG", "[autoFit] kept original positions (offset=($offsetX,$offsetY)), no centering")

                        // 触发重组
                        val snap = atoms.toList()
                        atoms.clear(); atoms.addAll(snap)
                        android.util.Log.d("MOLDRAW_DEBUG", "[autoFit] DONE - repositioned ${savedFuncGroups.size} groups, total atoms=${atoms.size}, bonds=${bonds.size}")
                        android.util.Log.d("MOLDRAW_DEBUG", "[autoFit] final atoms: ${atoms.map { "${it.id}(${it.element.symbol})${if (it.funGroupLabel != null) "["+it.funGroupLabel+"]" else ""}@(${it.x.toInt()},${it.y.toInt()})" }}")
                        return
                    }
                }
                android.util.Log.w("MOLDRAW_DEBUG", "[autoFit] layoutAndClean failed")
            } else {
                // 非 Indigo 引擎：用 V3000 路径
                val input = MoleculeData(selAtomsList, selBondsList).toV3000Mol()
                android.util.Log.d("MOLDRAW_DEBUG", "[autoFit] Input V3000:\n$input")
                val result = layoutEngine.layout(input)
                android.util.Log.d("MOLDRAW_DEBUG", "[autoFit] layoutEngine returned: ${if (result == null) "NULL" else "non-null (${result.length} chars)"}")
                if (result != null) {
                    val coords = parseV3000Coords(result)
                    if (coords != null && coords.isNotEmpty()) {
                        for ((v3000Seq, xy) in coords) {
                            val idx = v3000Seq - 1
                            if (idx >= 0 && idx < selAtomsList.size) {
                                val atom = atoms.find { it.id == selAtomsList[idx].id }
                                if (atom != null) { atom.x = xy.first * (BOND_LENGTH / 1.5f); atom.y = xy.second * (BOND_LENGTH / 1.5f) }
                            }
                        }
                        // 保持原位置，不居中
                        android.util.Log.d("MOLDRAW_DEBUG", "[autoFit] kept original positions (non-Indigo)")
                        val snap = atoms.toList()
                        atoms.clear(); atoms.addAll(snap)
                        android.util.Log.d("MOLDRAW_DEBUG", "[autoFit] DONE via non-Indigo engine")
                        return
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MOLDRAW_DEBUG", "[autoFit] EXCEPTION: ${e::class.simpleName}: ${e.message}")
            android.util.Log.e("MOLDRAW_DEBUG", "[autoFit] Stack: ${e.stackTraceToString().take(1000)}")
        }
        android.util.Log.d("MOLDRAW_DEBUG", "[autoFit] Engine branch ended without applying coords")
    } else {
        android.util.Log.d("MOLDRAW_DEBUG", "[autoFit] layoutEngine is NULL!")
    }
}

// ── SMILES 导入逻辑（从统一对话框调用） ──
fun processSmilesImport(
    smiles: String,
    layoutEngine: MoleculeLayoutEngine?,
    atoms: MutableList<MoleculeAtom>,
    bonds: MutableList<MoleculeBond>,
    annotations: MutableList<MoleculeAnnotation>,
    selectedIds: MutableSet<Int>,
    @Suppress("UNUSED_PARAMETER") gNextIdDummy: Any?,
    canvasSizePx: androidx.compose.ui.geometry.Size
) {
    android.util.Log.d("MOLDRAW_DEBUG", "[IMPORT] SMILES: $smiles")
    android.util.Log.d("MOLDRAW_DEBUG", "[IMPORT] layoutEngine=${layoutEngine}")
    if (layoutEngine != null) {
        val result = if (layoutEngine is IndigoNativeLayoutEngineAdapter) {
            layoutEngine.layoutAndClean(smiles)
        } else {
            layoutEngine.layout(smiles)
        }
        android.util.Log.d("MOLDRAW_DEBUG", "[IMPORT] layout result=${if (result == null) "NULL" else "ok (${result.length} chars)"}")
        if (result != null) {
            android.util.Log.d("MOLDRAW_DEBUG", "[IMPORT] V3000:\n$result")
            // 不清空画布，只在空白处生成新分子
            val lines = result.lines()
            var parsingAtoms = false; var parsingBonds = false
            val tempAtoms = mutableListOf<MoleculeAtom>()
            val tempBonds = mutableListOf<MoleculeBond>()
            val SCALE = com.moldraw.app.ui.moleculedraw.BOND_LENGTH / 1.5f
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.contains("BEGIN ATOM")) { parsingAtoms = true; parsingBonds = false; continue }
                if (trimmed.contains("BEGIN BOND")) { parsingAtoms = false; parsingBonds = true; continue }
                if (trimmed.startsWith("M  V30 END") || trimmed.startsWith("M V30 END")) { parsingAtoms = false; parsingBonds = false; continue }
                if (parsingAtoms && (trimmed.startsWith("M  V30 ") || trimmed.startsWith("M V30 "))) {
                    val parts = trimmed.split("\\s+".toRegex())
                    if (parts.size >= 6) {
                        val seq = parts[2].toIntOrNull() ?: continue
                        val elem = Element.fromSymbol(parts[3]) ?: Element.C
                        val x = parts[4].toFloatOrNull() ?: 0f
                        val y = parts[5].toFloatOrNull() ?: 0f
                        tempAtoms.add(MoleculeAtom(seq, x * SCALE, y * SCALE, elem))
                    }
                }
                if (parsingBonds && (trimmed.startsWith("M  V30 ") || trimmed.startsWith("M V30 "))) {
                    val parts = trimmed.split("\\s+".toRegex())
                    if (parts.size >= 6) {
                        val typeVal = parts[3].toIntOrNull() ?: 1
                        val from = parts[4].toIntOrNull() ?: continue
                        val to = parts[5].toIntOrNull() ?: continue
                        var bt = when (typeVal) { 2 -> BondType.DOUBLE; 3 -> BondType.TRIPLE; 4 -> BondType.AROMATIC; else -> BondType.SINGLE }
                        for (i in 6 until parts.size) {
                            when (parts[i]) {
                                "CFG=1" -> { bt = BondType.WEDGE_UP; break }
                                "CFG=3" -> { bt = BondType.WEDGE_DOWN; break }
                            }
                        }
                        tempBonds.add(MoleculeBond(nextId(), from, to, bt))
                    }
                }
            }
            android.util.Log.d("MOLDRAW_DEBUG", "[IMPORT] parsed atoms=${tempAtoms.size} bonds=${tempBonds.size}")
            if (tempAtoms.isNotEmpty()) {
                // 找空白区域放置：计算画布中心偏移（使新分子居中）
                val canvasW = if (canvasSizePx.width > 0f) canvasSizePx.width else 800f
                val canvasH = if (canvasSizePx.height > 0f) canvasSizePx.height else 600f
                val minX = tempAtoms.minOf { it.x }; val maxX = tempAtoms.maxOf { it.x }
                val minY = tempAtoms.minOf { it.y }; val maxY = tempAtoms.maxOf { it.y }
                val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
                val offX = canvasW / 2f - cx
                val offY = canvasH / 2f - cy
                // 重新分配 id，避免与已有原子冲突
                val idOffset = (atoms.maxOfOrNull { it.id } ?: 0) + 1
                val newAtoms = tempAtoms.mapIndexed { _, a ->
                    MoleculeAtom(a.id + idOffset, a.x + offX, a.y + offY, a.element)
                }
                val oldToNew = tempAtoms.mapIndexed { idx, a -> a.id to newAtoms[idx].id }.toMap()
                val newBonds = tempBonds.map { b ->
                    MoleculeBond(nextId(), oldToNew[b.atom1] ?: b.atom1, oldToNew[b.atom2] ?: b.atom2, b.type)
                }
                atoms.addAll(newAtoms)
                bonds.addAll(newBonds)
                // 清空其他选中，仅选中导入分子
                selectedIds.clear()
                selectedIds.addAll(newAtoms.map { it.id })
                gNextId = (maxOf(atoms.maxOfOrNull { it.id } ?: 0, bonds.maxOfOrNull { it.id } ?: 0)) + 1
                val snap = atoms.toList()
                atoms.clear(); atoms.addAll(snap)
                android.util.Log.d("MOLDRAW_DEBUG", "[IMPORT] DONE total atoms=${atoms.size} bonds=${bonds.size}")
            } else {
                android.util.Log.w("MOLDRAW_DEBUG", "[IMPORT] no atoms parsed from V3000")
            }
        } else {
            android.util.Log.w("MOLDRAW_DEBUG", "[IMPORT] layoutEngine returned null")
        }
    } else {
        android.util.Log.w("MOLDRAW_DEBUG", "[IMPORT] no layoutEngine")
    }
}

// ── Mol 文件导入逻辑（从统一对话框调用） ──
fun processMolImport(
    molContent: String,
    layoutEngine: MoleculeLayoutEngine?,
    atoms: MutableList<MoleculeAtom>,
    bonds: MutableList<MoleculeBond>,
    annotations: MutableList<MoleculeAnnotation>,
    selectedIds: MutableSet<Int>,
    @Suppress("UNUSED_PARAMETER") gNextIdDummy: Any?,
    canvasSizePx: androidx.compose.ui.geometry.Size
) {
    android.util.Log.d("MOLDRAW_DEBUG", "[MOL_IMPORT] Input (${molContent.length} chars)")
    if (layoutEngine != null) {
        val result = layoutEngine.layout(molContent)
        android.util.Log.d("MOLDRAW_DEBUG", "[MOL_IMPORT] layout result=${if (result == null) "NULL" else "ok (${result.length} chars)"}")
        if (result != null) {
            // 从 V3000 解析（同 SMILES 导入逻辑，不清空画布）
            val lines = result.lines()
            var parsingAtoms = false; var parsingBonds = false
            val tempAtoms = mutableListOf<MoleculeAtom>()
            val tempBonds = mutableListOf<MoleculeBond>()
            val SCALE = com.moldraw.app.ui.moleculedraw.BOND_LENGTH / 1.5f
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.contains("BEGIN ATOM")) { parsingAtoms = true; parsingBonds = false; continue }
                if (trimmed.contains("BEGIN BOND")) { parsingAtoms = false; parsingBonds = true; continue }
                if (trimmed.startsWith("M  V30 END") || trimmed.startsWith("M V30 END")) { parsingAtoms = false; parsingBonds = false; continue }
                if (parsingAtoms && (trimmed.startsWith("M  V30 ") || trimmed.startsWith("M V30 "))) {
                    val parts = trimmed.split("\\s+".toRegex())
                    if (parts.size >= 6) {
                        val seq = parts[2].toIntOrNull() ?: continue
                        val elem = Element.fromSymbol(parts[3]) ?: Element.C
                        val x = parts[4].toFloatOrNull() ?: 0f
                        val y = parts[5].toFloatOrNull() ?: 0f
                        tempAtoms.add(MoleculeAtom(seq, x * SCALE, y * SCALE, elem))
                    }
                }
                if (parsingBonds && (trimmed.startsWith("M  V30 ") || trimmed.startsWith("M V30 "))) {
                    val parts = trimmed.split("\\s+".toRegex())
                    if (parts.size >= 6) {
                        val typeVal = parts[3].toIntOrNull() ?: 1
                        val from = parts[4].toIntOrNull() ?: continue
                        val to = parts[5].toIntOrNull() ?: continue
                        var bt = when (typeVal) { 2 -> BondType.DOUBLE; 3 -> BondType.TRIPLE; 4 -> BondType.AROMATIC; else -> BondType.SINGLE }
                        for (i in 6 until parts.size) {
                            when (parts[i]) {
                                "CFG=1" -> { bt = BondType.WEDGE_UP; break }
                                "CFG=3" -> { bt = BondType.WEDGE_DOWN; break }
                            }
                        }
                        tempBonds.add(MoleculeBond(nextId(), from, to, bt))
                    }
                }
            }
            android.util.Log.d("MOLDRAW_DEBUG", "[MOL_IMPORT] parsed atoms=${tempAtoms.size} bonds=${tempBonds.size}")
            if (tempAtoms.isNotEmpty()) {
                // 重新分配 id，避免与已有原子冲突
                val idOffset = (atoms.maxOfOrNull { it.id } ?: 0) + 1
                val newAtoms = tempAtoms.mapIndexed { _, a ->
                    MoleculeAtom(a.id + idOffset, a.x, a.y, a.element)
                }
                val oldToNew = tempAtoms.mapIndexed { idx, a -> a.id to newAtoms[idx].id }.toMap()
                val newBonds = tempBonds.map { b ->
                    MoleculeBond(nextId(), oldToNew[b.atom1] ?: b.atom1, oldToNew[b.atom2] ?: b.atom2, b.type)
                }
                atoms.addAll(newAtoms)
                bonds.addAll(newBonds)
                // 清空其他选中，仅选中导入分子
                selectedIds.clear()
                selectedIds.addAll(newAtoms.map { it.id })
                gNextId = (maxOf(atoms.maxOfOrNull { it.id } ?: 0, bonds.maxOfOrNull { it.id } ?: 0)) + 1
                val snap = atoms.toList()
                atoms.clear(); atoms.addAll(snap)
                android.util.Log.d("MOLDRAW_DEBUG", "[MOL_IMPORT] DONE total atoms=${atoms.size} bonds=${bonds.size}")
            } else {
                android.util.Log.w("MOLDRAW_DEBUG", "[MOL_IMPORT] no atoms parsed from V3000")
            }
        } else {
            // 如果 layout 失败，尝试直接解析 V2000
            android.util.Log.w("MOLDRAW_DEBUG", "[MOL_IMPORT] layout failed, trying raw V2000 parse")
            val molLines = molContent.lines()
            if (molLines.size >= 4) {
                val countsLine = molLines.getOrNull(3)?.trim() ?: ""
                val atomCount = countsLine.substringBefore(" ").toIntOrNull()
                if (atomCount != null && atomCount > 0) {
                    atoms.clear(); bonds.clear(); annotations.clear(); selectedIds.clear()
                    for (i in 0 until atomCount.coerceAtMost(molLines.size - 4)) {
                        val atomLine = molLines.getOrNull(4 + i) ?: break
                        val x = atomLine.substring(0, 10).trim().toFloatOrNull() ?: continue
                        val y = atomLine.substring(10, 20).trim().toFloatOrNull() ?: continue
                        val elem = atomLine.substring(31, 33).trim()
                        atoms.add(MoleculeAtom(nextId(), x, y, Element.fromSymbol(elem) ?: Element.C))
                    }
                    val bondStartLine = 4 + atomCount
                    for (i in bondStartLine until molLines.size) {
                        val line = molLines.getOrNull(i)?.trim() ?: break
                        if (line.startsWith("M  END") || line.contains("V3000")) break
                        val parts2 = line.split("\\s+".toRegex())
                        if (parts2.size >= 4) {
                            val a1 = parts2[0].toIntOrNull() ?: continue
                            val a2 = parts2[1].toIntOrNull() ?: continue
                            val order = parts2[2].toIntOrNull() ?: 1
                            val stereo = parts2.getOrNull(3)?.toIntOrNull() ?: 0
                            val bt = when (order) { 2 -> BondType.DOUBLE; 3 -> BondType.TRIPLE; 4 -> BondType.AROMATIC; else -> BondType.SINGLE }
                            val finalBt = if (stereo == 1) BondType.WEDGE_UP else if (stereo == 6) BondType.WEDGE_DOWN else bt
                            bonds.add(MoleculeBond(nextId(), a1, a2, finalBt))
                        }
                    }
                    val maxId = maxOf(atoms.maxOfOrNull { it.id } ?: 0, bonds.maxOfOrNull { it.id } ?: 0)
                    gNextId = maxId + 1
                    val canvasW = if (canvasSizePx.width > 0f) canvasSizePx.width else 800f
                    val canvasH = if (canvasSizePx.height > 0f) canvasSizePx.height else 600f
                    val minX = atoms.minOf { it.x }; val maxX = atoms.maxOf { it.x }
                    val minY = atoms.minOf { it.y }; val maxY = atoms.maxOf { it.y }
                    val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
                    val offX = canvasW / 2f - cx
                    val offY = canvasH / 2f - cy
                    for (a in atoms) { a.x += offX; a.y += offY }
                    val snap = atoms.toList()
                    atoms.clear(); atoms.addAll(snap)
                    android.util.Log.d("MOLDRAW_DEBUG", "[MOL_IMPORT] V2000 DONE atoms=${atoms.size} bonds=${bonds.size}")
                }
            }
        }
    } else {
        android.util.Log.w("MOLDRAW_DEBUG", "[MOL_IMPORT] no layoutEngine, trying raw parse")
    }
}

// 简单引用包装器，用于在工具函数中修改 gNextId
class Ref<T>(var value: T)

/**
 * 将分子渲染为位图并通过系统分享发送。
 */
fun shareBitmap(
    context: android.content.Context,
    atoms: List<MoleculeAtom>,
    bonds: List<MoleculeBond>,
    annotations: List<MoleculeAnnotation> = emptyList(),
    fileName: String,
    format: android.graphics.Bitmap.CompressFormat,
    benzeneStyle: BenzeneStyle = BenzeneStyle.KEKULE
) {
    if (atoms.isEmpty() && annotations.isEmpty()) return
    val pad = 40f
    val allX = mutableListOf<Float>()
    val allY = mutableListOf<Float>()
    allX.addAll(atoms.map { it.x }); allY.addAll(atoms.map { it.y })
    for (ann in annotations) {
        allX.add(ann.x); allY.add(ann.y)
        if (ann.type == AnnotationType.ARROW) { allX.add(ann.endX); allY.add(ann.endY) }
    }
    if (allX.isEmpty()) return
    val minX = allX.min() - pad
    val minY = allY.min() - pad
    val maxX = allX.max() + pad
    val maxY = allY.max() + pad
    val w = ((maxX - minX).toInt()).coerceAtLeast(100)
    val h = ((maxY - minY).toInt()).coerceAtLeast(100)

    val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)

    val strokePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#333333")
        strokeWidth = STROKE_WIDTH
        style = android.graphics.Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    val fillPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#555555")
        style = android.graphics.Paint.Style.FILL
        isAntiAlias = true
    }
    val textPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#333333")
        textSize = FONT_SIZE
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }

    // 偏移使分子居中于位图
    val offsetX = -minX; val offsetY = -minY

    // 画键（跳过 AROMATIC，由后面统一处理）
    for (b in bonds) {
        if (b.type == BondType.AROMATIC) continue
        val a1 = atoms.find { it.id == b.atom1 } ?: continue
        val a2 = atoms.find { it.id == b.atom2 } ?: continue
        val dx = a2.x - a1.x; val dy = a2.y - a1.y
        val len = sqrt(dx*dx + dy*dy)
        if (len < 1f) continue
        val ux = dx/len; val uy = dy/len
        val r1 = if (a1.element == Element.C) CARBON_DOT_R else HETERO_CIRCLE_R
        val r2 = if (a2.element == Element.C) CARBON_DOT_R else HETERO_CIRCLE_R
        val sx = a1.x + ux * r1 + offsetX; val sy = a1.y + uy * r1 + offsetY
        val ex = a2.x - ux * r2 + offsetX; val ey = a2.y - uy * r2 + offsetY

        when (b.type) {
            BondType.SINGLE -> canvas.drawLine(sx, sy, ex, ey, strokePaint)
            BondType.DOUBLE -> {
                val px = -uy * 3f; val py = ux * 3f
                canvas.drawLine(sx+px, sy+py, ex+px, ey+py, strokePaint)
                canvas.drawLine(sx-px, sy-py, ex-px, ey-py, strokePaint)
            }
            BondType.TRIPLE -> {
                val px = -uy * 4f; val py = ux * 4f
                canvas.drawLine(sx, sy, ex, ey, strokePaint)
                canvas.drawLine(sx+px, sy+py, ex+px, ey+py, strokePaint)
                canvas.drawLine(sx-px, sy-py, ex-px, ey-py, strokePaint)
            }
            BondType.WEDGE_UP -> {
                val hw = 6f
                val perpX = -uy * hw; val perpY = ux * hw
                val path = android.graphics.Path().apply {
                    moveTo(sx, sy); lineTo(ex+perpX, ey+perpY); lineTo(ex-perpX, ey-perpY); close()
                }
                canvas.drawPath(path, strokePaint)
                canvas.drawPath(path, fillPaint)
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
                    canvas.drawLine(lsx, lsy, lex, ley, strokePaint)
                    drawn += segLen + gapLen
                }
            }
            BondType.AROMATIC -> {} // 由后面统一处理
        }
    }

    // 芳香环渲染（按 benzeneStyle）
    val doublePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#333333")
        strokeWidth = STROKE_WIDTH - 0.5f
        style = android.graphics.Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    val paulingRings = findPaulingRings(atoms, bonds)
    if (benzeneStyle == BenzeneStyle.PAULING) {
        // 鲍林式：实线 + 内切圆
        for (ring0 in paulingRings) {
            val ring = ring0 as List<Pair<Float, Float>>
            for (i in 0 until ring.size) {
                val j = (i + 1) % ring.size
                val a1 = ring[i]; val a2 = ring[j]
                val dx = a2.first - a1.first; val dy = a2.second - a1.second
                val len = sqrt(dx*dx + dy*dy); if (len < 1f) continue
                val ux = dx/len; val uy = dy/len
                val r = CARBON_DOT_R
                val sx = a1.first + ux * r + offsetX; val sy = a1.second + uy * r + offsetY
                val ex = a2.first - ux * r + offsetX; val ey = a2.second - uy * r + offsetY
                canvas.drawLine(sx, sy, ex, ey, strokePaint)
            }
            // 内切圆
            val xs = ring.map { it.first }; val ys = ring.map { it.second }
            val cx = (xs.sum() / xs.size) + offsetX
            val cy = (ys.sum() / ys.size) + offsetY
            val dx = (xs.max() - xs.min()); val dy = (ys.max() - ys.min())
            val radius = sqrt((dx * dx + dy * dy).toDouble()).toFloat() * 0.29f
            canvas.drawCircle(cx, cy, radius, strokePaint)
        }
    } else {
        // 凯库勒式：单双交替
        for (ring0 in paulingRings) {
            val ring = ring0 as List<Pair<Float, Float>>
            for (i in 0 until ring.size) {
                val j = (i + 1) % ring.size
                val a1 = ring[i]; val a2 = ring[j]
                val dx = a2.first - a1.first; val dy = a2.second - a1.second
                val len = sqrt(dx*dx + dy*dy); if (len < 1f) continue
                val ux = dx/len; val uy = dy/len
                val r = CARBON_DOT_R
                val sx = a1.first + ux * r + offsetX; val sy = a1.second + uy * r + offsetY
                val ex = a2.first - ux * r + offsetX; val ey = a2.second - uy * r + offsetY
                if (i % 2 == 0) {
                    canvas.drawLine(sx, sy, ex, ey, strokePaint)
                } else {
                    val px = -uy * 3f; val py = ux * 3f
                    canvas.drawLine(sx+px, sy+py, ex+px, ey+py, doublePaint)
                    canvas.drawLine(sx-px, sy-py, ex-px, ey-py, doublePaint)
                }
            }
        }
    }

    // 画原子（跳过 FUNC_GROUP 标注覆盖的原子）
    for (a in atoms) {
        val cx = a.x + offsetX; val cy = a.y + offsetY
        // 如果原子位置有 FUNC_GROUP 标注，跳过绘制（标注会覆盖）
        if (annotations.any { it.type == AnnotationType.FUNC_GROUP && abs(it.x - a.x) < 5f && abs(it.y - a.y) < 5f }) continue
        if (a.element == Element.C) {
            canvas.drawCircle(cx, cy, CARBON_DOT_R, fillPaint)
        } else {
            val symbol = a.element.symbol
            val connected = bonds.filter { it.atom1 == a.id || it.atom2 == a.id }
            var bondOrderSum = 0
            for (b in connected) {
bondOrderSum += when (b.type) {
                        BondType.SINGLE, BondType.WEDGE_UP, BondType.WEDGE_DOWN, BondType.AROMATIC -> 1
                        BondType.DOUBLE -> 2
                        BondType.TRIPLE -> 3
                    }
            }
            val hCount = (a.element.valence - bondOrderSum).coerceAtLeast(0)
            val text = if (hCount == 0) symbol else if (hCount == 1) "${symbol}H" else "${symbol}H${hCount}"
            canvas.drawText(text, cx, cy + FONT_SIZE/3f, textPaint)
        }
    }

    // 画标注（文字、箭头和官能团缩写）
    for (ann in annotations) {
        val cx = ann.x + offsetX; val cy = ann.y + offsetY
        if (ann.type == AnnotationType.TEXT || ann.type == AnnotationType.FUNC_GROUP) {
            val tp = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#333333")
                textSize = FONT_SIZE * (ann.scale.coerceAtLeast(0.5f))
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
            canvas.drawText(ann.text ?: "", cx, cy + FONT_SIZE * 0.35f * (ann.scale.coerceAtLeast(0.5f)), tp)
        } else if (ann.type == AnnotationType.ARROW) {
            val ex = ann.endX + offsetX; val ey = ann.endY + offsetY
            val aPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#333333")
                strokeWidth = 2.5f * (ann.scale.coerceAtLeast(0.5f))
                style = android.graphics.Paint.Style.STROKE
                isAntiAlias = true
                strokeCap = android.graphics.Paint.Cap.ROUND
            }
            canvas.drawLine(cx, cy, ex, ey, aPaint)
            // 箭头头部
            val dx = ex - cx; val dy = ey - cy; val len = sqrt(dx*dx + dy*dy)
            if (len > 5f) {
                val ux = dx/len; val uy = dy/len
                val headSize = 12f * (ann.scale.coerceAtLeast(0.5f))
                val angle = Math.toRadians(25.0)
                val cosA = cos(angle).toFloat(); val sinA = sin(angle).toFloat()
                val lx = ex - ux * headSize * cosA + uy * headSize * sinA
                val ly = ey - uy * headSize * cosA - ux * headSize * sinA
                val rx = ex - ux * headSize * cosA - uy * headSize * sinA
                val ry = ey - uy * headSize * cosA + ux * headSize * sinA
                val headPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#333333")
                    strokeWidth = 2.5f * (ann.scale.coerceAtLeast(0.5f))
                    style = android.graphics.Paint.Style.FILL
                    isAntiAlias = true
                }
                canvas.drawLine(ex, ey, lx, ly, aPaint)
                canvas.drawLine(ex, ey, rx, ry, aPaint)
            }
        }
    }

    // 写入缓存并分享（用时间戳保证唯一文件名，避免 FileProvider 返回旧缓存）
    val ts = java.lang.System.currentTimeMillis()
    val uniqueName = "${fileName.substringBeforeLast('.')}_$ts.${fileName.substringAfterLast('.')}"
    val dir = java.io.File(context.cacheDir, "exports")
    dir.mkdirs()
    // 清理30分钟前的旧缓存文件
    val cutoff = ts - 30 * 60 * 1000L
    dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    val file = java.io.File(dir, uniqueName)
    val outputStream = java.io.FileOutputStream(file)
    bitmap.compress(format, 95, outputStream)
    outputStream.close()
    bitmap.recycle()

    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file)
    val mimeType = if (format == android.graphics.Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "分享 $fileName"))
}

// ── 工作区文件序列化（JSON，包含全部信息） ──

/**
 * 构建工作区 JSON 字符串，包含原子、键、标注和键长配置。
 * 可被 [loadWorkspaceJson] 完整恢复。
 */
fun buildWorkspaceJson(
    atoms: List<MoleculeAtom>,
    bonds: List<MoleculeBond>,
    annotations: List<MoleculeAnnotation>,
    bondLength: Float,
    benzeneStyle: BenzeneStyle = BenzeneStyle.KEKULE,
    layoutEngine: MoleculeLayoutEngine? = null
): String {
    try {
    val sb = StringBuilder()
    sb.appendLine("{")
    sb.appendLine("  \"format\": \"moldraw\",")
    sb.appendLine("  \"version\": 1,")
    sb.appendLine("  \"bondLength\": $bondLength,")
    sb.appendLine("  \"strokeWidth\": $STROKE_WIDTH,")
    sb.appendLine("  \"fontSize\": $FONT_SIZE,")
    sb.appendLine("  \"annTextSize\": $ANN_TEXT_SIZE,")
    sb.appendLine("  \"carbonDotR\": $CARBON_DOT_R,")
    sb.appendLine("  \"heteroCircleR\": $HETERO_CIRCLE_R,")
    sb.appendLine("  \"arrowHeadSize\": $ARROW_HEAD_SIZE,")
    sb.appendLine("  \"benzeneStyle\": \"${benzeneStyle.name}\",")
    // 原子
    sb.appendLine("  \"atoms\": [")
    for ((i, a) in atoms.withIndex()) {
        sb.append("    {\"id\":${a.id},\"x\":${a.x},\"y\":${a.y},\"element\":\"${a.element.name}\"")
        if (a.aromatic) sb.append(",\"aromatic\":true")
        if (a.chiral.isNotEmpty()) sb.append(",\"chiral\":\"${a.chiral}\"")
        if (a.funGroupLabel != null) sb.append(",\"funGroupLabel\":\"${a.funGroupLabel}\"")
        if (a.isFunGroupConnector) sb.append(",\"isFunGroupConnector\":true")
        sb.append(if (i < atoms.lastIndex) "}," else "}")
        sb.appendLine()
    }
    sb.appendLine("  ],")
    // 键
    sb.appendLine("  \"bonds\": [")
    for ((i, b) in bonds.withIndex()) {
        sb.appendLine("    {\"id\":${b.id},\"a1\":${b.atom1},\"a2\":${b.atom2},\"type\":\"${b.type.name}\"}${if (i < bonds.lastIndex) "," else ""}")
    }
    sb.appendLine("  ],")
    // 标注
    sb.appendLine("  \"annotations\": [")
    for ((i, ann) in annotations.withIndex()) {
        sb.append("    {\"id\":${ann.id},\"type\":\"${ann.type.name}\",\"x\":${ann.x},\"y\":${ann.y}")
        if (ann.text.isNotEmpty()) sb.append(",\"text\":\"${ann.text.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        if (ann.type == AnnotationType.ARROW || ann.endX != 0f || ann.endY != 0f) {
            sb.append(",\"endX\":${ann.endX},\"endY\":${ann.endY}")
        }
        if (abs(ann.scale - 1f) > 0.01f) sb.append(",\"scale\":${ann.scale}")
        if (ann.subScript) sb.append(",\"subScript\":true")
        sb.append(if (i < annotations.lastIndex) "}," else "}")
        sb.appendLine()
    }
    sb.appendLine("  ],")
    // ── reaction（可选，当有 ARROW 标注且 layoutEngine 可用时生成）──
    try {
        val arrowAnns = annotations.filter { it.type == AnnotationType.ARROW }
        if (arrowAnns.isNotEmpty() && layoutEngine != null && layoutEngine is IndigoNativeLayoutEngineAdapter) {
            // 全局连通性分组：所有原子按化学键分成独立分子
            val globalGroups = mutableListOf<MutableList<MoleculeAtom>>()
            val remaining = atoms.toMutableList()
            while (remaining.isNotEmpty()) {
                val group = mutableListOf(remaining.removeAt(0))
                var changed = true
                while (changed) {
                    changed = false
                    val toAdd = remaining.filter { ra ->
                        group.any { ga ->
                            bonds.any { b ->
                                (b.atom1 == ra.id && b.atom2 == ga.id) ||
                                (b.atom2 == ra.id && b.atom1 == ga.id)
                            }
                        }
                    }
                    if (toAdd.isNotEmpty()) {
                        group.addAll(toAdd); remaining.removeAll(toAdd); changed = true
                    }
                }
                globalGroups.add(group)
            }
            val annsNoArrow = annotations.filter { it.type != AnnotationType.ARROW }
            fun sameReactionBand(a: MoleculeAnnotation, b: MoleculeAnnotation): Boolean {
                val aMinY = min(a.y, a.endY) - bondLength
                val aMaxY = max(a.y, a.endY) + bondLength
                val bMinY = min(b.y, b.endY) - bondLength
                val bMaxY = max(b.y, b.endY) + bondLength
                return aMaxY >= bMinY && bMaxY >= aMinY
            }
            data class ArrowCorridor(
                val minX: Float,
                val maxX: Float,
                val minY: Float,
                val maxY: Float
            )
            val arrowCorridors = arrowAnns.map { arrow ->
                val segmentMinX = min(arrow.x, arrow.endX)
                val segmentMaxX = max(arrow.x, arrow.endX)
                val segmentMinY = min(arrow.y, arrow.endY)
                val segmentMaxY = max(arrow.y, arrow.endY)

                val overlappingGroupYs = globalGroups.mapNotNull { grp ->
                    if (grp.isEmpty()) return@mapNotNull null
                    val groupMinY = grp.minOf { it.y }
                    val groupMaxY = grp.maxOf { it.y }
                    val overlapsArrowY = groupMaxY >= segmentMinY - bondLength && groupMinY <= segmentMaxY + bondLength
                    if (!overlapsArrowY) return@mapNotNull null
                    Pair(groupMinY, groupMaxY)
                }
                val corridorMinY = overlappingGroupYs.minOfOrNull { it.first } ?: segmentMinY
                val corridorMaxY = overlappingGroupYs.maxOfOrNull { it.second } ?: segmentMaxY
                ArrowCorridor(
                    minX = min(segmentMinX, arrow.endX),
                    maxX = max(segmentMaxX, arrow.endX),
                    minY = corridorMinY,
                    maxY = corridorMaxY
                )
            }
            // 对每个分子组，计算它到每个箭头的归属关系
            // 归属规则：分子包围盒与箭头反应带相交，且在同一反应带内归属给最近的对应侧箭头
            // 多条反应彼此独立计算，不把相邻箭头自动串成链式反应
            data class GroupMatch(
                val arrowIndex: Int,
                val minProj: Float,
                val maxProj: Float,
                val groupMinX: Float,
                val groupMaxX: Float,
                val groupMinY: Float,
                val groupMaxY: Float
            )
            fun isClosestArrowOnSide(groupCenterX: Float, arrowIndex: Int, productSide: Boolean): Boolean {
                val arrow = arrowAnns[arrowIndex]
                val sideBoundaryX = if (productSide) arrow.endX else arrow.x
                val sideDistance = kotlin.math.abs(groupCenterX - sideBoundaryX)
                return arrowAnns.withIndex().none { (otherIndex, otherArrow) ->
                    otherIndex != arrowIndex && sameReactionBand(otherArrow, arrow) && run {
                        val otherBoundaryX = if (productSide) otherArrow.endX else otherArrow.x
                        kotlin.math.abs(groupCenterX - otherBoundaryX) < sideDistance
                    }
                }
            }
            val groupAssignments = mutableMapOf<Int, MutableList<GroupMatch>>() // 分子组索引 → 匹配的箭头列表

            for ((gi, grp) in globalGroups.withIndex()) {
                if (grp.isEmpty()) continue
                val groupMinX = grp.minOf { it.x }
                val groupMaxX = grp.maxOf { it.x }
                val groupMinY = grp.minOf { it.y }
                val groupMaxY = grp.maxOf { it.y }
                for ((ai, arrow) in arrowAnns.withIndex()) {
                    val dx = arrow.endX - arrow.x; val dy = arrow.endY - arrow.y
                    val len = sqrt(dx * dx + dy * dy)
                    if (len < 1f) continue
                    val ux = dx / len; val uy = dy / len
                    val groupCenterX = (groupMinX + groupMaxX) / 2f
                    val corridor = arrowCorridors[ai]
                    val corridorMinY = corridor.minY
                    val corridorMaxY = corridor.maxY
                    val overlapsReactionBandY = groupMaxY >= corridorMinY && groupMinY <= corridorMaxY
                    if (!overlapsReactionBandY) {
                        android.util.Log.d("MOLDRAW_DEBUG", "arrow $ai skip group $gi: outside reaction band y=[$groupMinY,$groupMaxY] corridorY=[$corridorMinY,$corridorMaxY]")
                        continue
                    }
                    val inReactantSide = groupMaxX <= arrow.x && isClosestArrowOnSide(groupCenterX, ai, productSide = false)
                    val inProductSide = groupMinX >= arrow.endX && isClosestArrowOnSide(groupCenterX, ai, productSide = true)
                    val overlapsArrowBody = groupMaxX >= corridor.minX && groupMinX <= corridor.maxX
                    if (!inReactantSide && !inProductSide && !overlapsArrowBody) {
                        android.util.Log.d("MOLDRAW_DEBUG", "arrow $ai skip group $gi: outside independent x lanes bbox=[$groupMinX,$groupMaxX] reactant<=${arrow.x} product>=${arrow.endX}")
                        continue
                    }
                    val projections = grp.map { atom ->
                        (atom.x - arrow.x) * ux + (atom.y - arrow.y) * uy
                    }
                    val minProj = projections.minOrNull() ?: continue
                    val maxProj = projections.maxOrNull() ?: continue
                    groupAssignments.getOrPut(gi) { mutableListOf() }.add(
                        GroupMatch(ai, minProj, maxProj, groupMinX, groupMaxX, groupMinY, groupMaxY)
                    )
                }
            }

            sb.appendLine("  \"reaction\": {")
            sb.appendLine("    \"reactions\": [")
            for ((ri, arrow) in arrowAnns.withIndex()) {
                val dx = arrow.endX - arrow.x; val dy = arrow.endY - arrow.y
                val len = sqrt(dx * dx + dy * dy)
                val reactants = mutableListOf<Pair<String, Double>>()
                val products = mutableListOf<Pair<String, Double>>()
                val conditions = mutableListOf<String>()
                if (len > 1f) {
                    val ux = dx / len; val uy = dy / len
                    val arrowMinX = min(arrow.x, arrow.endX)
                    val arrowMaxX = max(arrow.x, arrow.endX)
                    val grpMatches = groupAssignments.filterValues { matches ->
                        matches.any { it.arrowIndex == ri }
                    }
                    for ((gi, matches) in grpMatches) {
                        val grp = globalGroups[gi]
                        if (grp.isEmpty()) continue
                        val match = matches.firstOrNull { it.arrowIndex == ri } ?: continue
                        val grpBonds = bonds.filter { b -> grp.any { it.id == b.atom1 } && grp.any { it.id == b.atom2 } }
                        val smiles: String? = layoutEngine.generateSmiles(grp, grpBonds, annsNoArrow)
                        val mw = if (smiles != null && smiles.isNotBlank()) {
                            try { com.moldraw.app.indigo_native.IndigoNative.molecularWeight(smiles) } catch (e2: Exception) { 0.0 }
                        } else 0.0
                        if (smiles.isNullOrBlank()) {
                            android.util.Log.d("MOLDRAW_DEBUG", "skip group $gi for arrow $ri: blank smiles bbox=[${match.groupMinX},${match.groupMinY}]..[${match.groupMaxX},${match.groupMaxY}] proj=[${match.minProj},${match.maxProj}]")
                            continue
                        }
                        when {
                            match.groupMaxX <= arrow.x || match.maxProj <= 0f -> {
                                reactants.add(Pair(smiles, mw))
                                android.util.Log.d("MOLDRAW_DEBUG", "arrow $ri reactant group $gi smiles=$smiles bbox=[${match.groupMinX},${match.groupMinY}]..[${match.groupMaxX},${match.groupMaxY}] proj=[${match.minProj},${match.maxProj}]")
                            }
                            match.groupMinX >= arrow.endX || match.minProj >= 0f -> {
                                products.add(Pair(smiles, mw))
                                android.util.Log.d("MOLDRAW_DEBUG", "arrow $ri product group $gi smiles=$smiles bbox=[${match.groupMinX},${match.groupMinY}]..[${match.groupMaxX},${match.groupMaxY}] proj=[${match.minProj},${match.maxProj}]")
                            }
                            else -> {
                                android.util.Log.d("MOLDRAW_DEBUG", "arrow $ri ignore center-overlap group $gi smiles=$smiles bbox=[${match.groupMinX},${match.groupMinY}]..[${match.groupMaxX},${match.groupMaxY}] proj=[${match.minProj},${match.maxProj}]")
                            }
                        }
                    }
                    // 包围框内的 TEXT 标注作为条件
                    val myGroups = grpMatches.keys.map { globalGroups[it] }
                    val allMyAtoms = myGroups.flatten()

                    if (allMyAtoms.isNotEmpty()) {
                        val minX = allMyAtoms.minOf { it.x }
                        val maxX = allMyAtoms.maxOf { it.x }
                        val minY = allMyAtoms.minOf { it.y }
                        val maxY = allMyAtoms.maxOf { it.y }
                        val pad = 40f
                        for (ann in annotations) {
                            if (ann.type == AnnotationType.TEXT && ann.text.isNotBlank()) {
                                if (ann.x >= minX - pad && ann.x <= maxX + pad &&
                                    ann.y >= minY - pad && ann.y <= maxY + pad) {
                                    if (!conditions.contains(ann.text)) conditions.add(ann.text)
                                }
                            }
                        }
                    }
                }
                sb.appendLine("      {")
                sb.append("        \"reactants\": [")
                sb.append(reactants.joinToString(", ") { val s = it.first.replace("\"", "\\\""); val m = "%.2f".format(it.second); "{\"smiles\": \"" + s + "\", \"mw\": " + m + "}" })
                sb.appendLine("],")
                sb.append("        \"products\": [")
                sb.append(products.joinToString(", ") { val s = it.first.replace("\"", "\\\""); val m = "%.2f".format(it.second); "{\"smiles\": \"" + s + "\", \"mw\": " + m + "}" })
                sb.appendLine("],")
                sb.appendLine("        \"conditions\": \"" + conditions.joinToString("; ").replace("\"", "\\\"") + "\"")
                sb.append(if (ri < arrowAnns.lastIndex) "      }," else "      }")
                sb.appendLine()
            }
            sb.appendLine("    ]")
            sb.appendLine("  },")
        }
    } catch (e: Exception) {
        android.util.Log.e("MOLDRAW_DEBUG", "buildWorkspaceJson reaction failed: ${e.message}")
    }
    sb.appendLine("}")
    return sb.toString()
    } catch (e: Exception) {
        android.util.Log.e("MOLDRAW_DEBUG", "buildWorkspaceJson CRASH: ${e::class.simpleName}: ${e.message}")
        android.util.Log.e("MOLDRAW_DEBUG", "stack: ${e.stackTraceToString().take(500)}")
        return """{
"format": "moldraw",
"version": 1,
"bondLength": $bondLength,
"atoms": [],
"bonds": [],
"annotations": []
}"""
    }
}


/**
 * 从 JSON 字符串加载工作区，清空并填充 [atoms]、[bonds]、[annotations]。
 */
fun loadWorkspaceJson(
    json: String,
    atoms: MutableList<MoleculeAtom>,
    bonds: MutableList<MoleculeBond>,
    annotations: MutableList<MoleculeAnnotation>
): BenzeneStyle? {
    atoms.clear(); bonds.clear(); annotations.clear()
    var benzeneStyle: BenzeneStyle? = null
    try {
        // 简单手动 JSON 解析（不依赖第三方库）
        val lines = json.lines()
        var section = ""
        val tempAtoms = mutableListOf<MoleculeAtom>()
        val tempBonds = mutableListOf<MoleculeBond>()
        val tempAnnotations = mutableListOf<MoleculeAnnotation>()
        val renderParams = mutableMapOf<String, Float>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("\"atoms\"")) { section = "atoms"; continue }
            if (trimmed.startsWith("\"bonds\"")) { section = "bonds"; continue }
            if (trimmed.startsWith("\"annotations\"")) { section = "annotations"; continue }
            if (trimmed.startsWith("\"reaction\"")) { section = "reaction"; continue }
            if (trimmed.startsWith("\"bondLength\"")) {
                val v = trimmed.substringAfter(":").substringBefore(",").substringBefore("}").trim()
                v.toFloatOrNull()?.let { BOND_LENGTH = it.coerceIn(30f, 100f) }
            }
            // 读取渲染参数
            for (key in listOf("strokeWidth", "fontSize", "annTextSize", "carbonDotR", "heteroCircleR", "arrowHeadSize")) {
                if (trimmed.startsWith("\"$key\"")) {
                    val v = trimmed.substringAfter(":").substringBefore(",").substringBefore("}").trim().toFloatOrNull()
                    if (v != null) renderParams[key] = v
                }
            }
            // 读取 benzeneStyle
            if (trimmed.startsWith("\"benzeneStyle\"")) {
                val v = extractJsonString(trimmed, "\"benzeneStyle\"")
                if (v != null) benzeneStyle = runCatching { BenzeneStyle.valueOf(v) }.getOrNull()
            }

            when (section) {
                "atoms" -> {
                    if (trimmed.startsWith("{")) {
                        val id = extractJsonInt(trimmed, "\"id\"").toInt()
                        val x = extractJsonFloat(trimmed, "\"x\"")
                        val y = extractJsonFloat(trimmed, "\"y\"")
                        val elem = extractJsonString(trimmed, "\"element\"")?.let { runCatching { Element.valueOf(it) }.getOrNull() } ?: Element.C
                        val aromatic = extractJsonString(trimmed, "\"aromatic\"")?.toBooleanStrictOrNull() ?: false
                        val chiral = extractJsonString(trimmed, "\"chiral\"") ?: ""
                        val funGroupLabel = extractJsonString(trimmed, "\"funGroupLabel\"")?.takeIf { it.isNotEmpty() }
                        val isFunGroupConnector = extractJsonString(trimmed, "\"isFunGroupConnector\"")?.toBooleanStrictOrNull() ?: false
                        tempAtoms.add(MoleculeAtom(id, x, y, elem, aromatic, chiral, funGroupLabel, isFunGroupConnector))
                    }
                }
                "bonds" -> {
                    if (trimmed.startsWith("{")) {
                        val id = extractJsonInt(trimmed, "\"id\"").toInt()
                        val a1 = extractJsonInt(trimmed, "\"a1\"").toInt()
                        val a2 = extractJsonInt(trimmed, "\"a2\"").toInt()
                        val type = extractJsonString(trimmed, "\"type\"")?.let { runCatching { BondType.valueOf(it) }.getOrNull() } ?: BondType.SINGLE
                        tempBonds.add(MoleculeBond(id, a1, a2, type))
                    }
                }
                "annotations" -> {
                    if (trimmed.startsWith("{")) {
                        val id = extractJsonInt(trimmed, "\"id\"").toInt()
                        val type = extractJsonString(trimmed, "\"type\"")?.let { runCatching { AnnotationType.valueOf(it) }.getOrNull() } ?: continue
                        val x = extractJsonFloat(trimmed, "\"x\"")
                        val y = extractJsonFloat(trimmed, "\"y\"")
                        val text = extractJsonString(trimmed, "\"text\"") ?: ""
                        val endX = extractJsonFloat(trimmed, "\"endX\"")
                        val endY = extractJsonFloat(trimmed, "\"endY\"")
                        val scale = extractJsonFloat(trimmed, "\"scale\"")
                        val subScript = trimmed.contains("\"subScript\":true")
                        tempAnnotations.add(MoleculeAnnotation(id, type, x, y, text, endX, endY, if (scale != 0f) scale else 1f, subScript))
                    }
                }
            }
        }

        atoms.addAll(tempAtoms)
        bonds.addAll(tempBonds)
        annotations.addAll(tempAnnotations)

        // 应用渲染参数（如果有）
        renderParams["strokeWidth"]?.let { v -> STROKE_WIDTH = v }
        renderParams["fontSize"]?.let { v -> FONT_SIZE = v }
        renderParams["annTextSize"]?.let { v -> ANN_TEXT_SIZE = v }
        renderParams["carbonDotR"]?.let { v -> CARBON_DOT_R = v }
        renderParams["heteroCircleR"]?.let { v -> HETERO_CIRCLE_R = v }
        renderParams["arrowHeadSize"]?.let { v -> ARROW_HEAD_SIZE = v }

        val maxId = maxOf(
            atoms.maxOfOrNull { it.id } ?: 0,
            bonds.maxOfOrNull { it.id } ?: 0,
            annotations.maxOfOrNull { it.id } ?: 0
        )
        gNextId = maxId + 1
    } catch (e: Exception) {
        android.util.Log.e("MOLDRAW_DEBUG", "loadWorkspaceJson failed: ${e.message}")
    }
    return benzeneStyle
}

/** 从 JSON 行中提取 int 值 */
private fun extractJsonInt(line: String, key: String): Int {
    val idx = line.indexOf(key)
    if (idx < 0) return 0
    val after = line.substring(idx + key.length)
    val numStart = after.indexOfAny("0123456789-".toCharArray())
    if (numStart < 0) return 0
    return after.substring(numStart).takeWhile { it.isDigit() || it == '-' }.toIntOrNull() ?: 0
}

/** 从 JSON 行中提取 float 值 */
private fun extractJsonFloat(line: String, key: String): Float {
    val idx = line.indexOf(key)
    if (idx < 0) return 0f
    val after = line.substring(idx + key.length)
    val numStart = after.indexOfAny("0123456789-.".toCharArray())
    if (numStart < 0) return 0f
    val numStr = after.substring(numStart).takeWhile { it.isDigit() || it == '-' || it == '.' }
    return numStr.toFloatOrNull() ?: 0f
}

/** 从 JSON 行中提取 string 值 */
private fun extractJsonString(line: String, key: String): String? {
    val idx = line.indexOf(key)
    if (idx < 0) return null
    val after = line.substring(idx + key.length)
    val quoteStart = after.indexOf('"')
    if (quoteStart < 0) return null
    val quoteEnd = after.indexOf('"', quoteStart + 1)
    if (quoteEnd < 0) return null
    return after.substring(quoteStart + 1, quoteEnd)
}
/**
 * 清理残留的展开原子并标记官能团。
 * 旧版本可能在分子中残留了展开的 O 原子等（无 funGroupLabel），
 * 此函数检测 FUNC_GROUP 标注并给其覆盖的原子加上 funGroupLabel 标记。
 */
fun cleanupExpandedAtoms(
    atoms: MutableList<MoleculeAtom>,
    bonds: MutableList<MoleculeBond>,
    annotations: List<MoleculeAnnotation>
) {
    val funcAnns = annotations.filter { it.type == AnnotationType.FUNC_GROUP && it.text.isNotBlank() }
    if (funcAnns.isEmpty()) return
    for (ann in funcAnns) {
        val fg = FUNCTIONAL_GROUPS.firstOrNull { it.label == ann.text } ?: continue
        val connElem = Element.fromSymbol(fg.expandAtoms[fg.connectIndex].first)
        val expandElemSet = fg.expandAtoms.filterIndexed { i, _ -> i != fg.connectIndex }
            .map { Element.fromSymbol(it.first) }.filter { it != Element.H }.toSet()
        // 找连接点原子
        val connAtom = atoms.filter { it.element == connElem && it.funGroupLabel == null }
            .minByOrNull { abs(it.x - ann.x) + abs(it.y - ann.y) } ?: continue
        connAtom.funGroupLabel = ann.text
        connAtom.isFunGroupConnector = true
        // 找并标记成员原子
        val toMark = atoms.filter { atom ->
            atom.id != connAtom.id &&
            atom.element in expandElemSet &&
            atom.funGroupLabel == null &&
            bonds.any { b ->
                (b.atom1 == atom.id || b.atom2 == atom.id) &&
                (b.atom1 == connAtom.id || b.atom2 == connAtom.id)
            }
        }
        for (m in toMark) {
            m.funGroupLabel = ann.text
            m.isFunGroupConnector = false
        }
        if (toMark.isNotEmpty()) {
            android.util.Log.d("MOLDRAW_DEBUG", "[cleanup] marked ${toMark.size} atoms for '${ann.text}': ${toMark.map { "${it.id}(${it.element.symbol})" }}")
        }
    }
}
