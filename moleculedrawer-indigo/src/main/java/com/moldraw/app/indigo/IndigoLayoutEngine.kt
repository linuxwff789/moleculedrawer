package com.moldraw.app.indigo

import kotlin.math.*

/**
 * 纯 Kotlin 实现的分子布局引擎，对标 Indigo/Ketcher 布局质量。
 *
 * 功能覆盖：
 * - V3000 Molfile 解析/输出
 * - SSSR 最小环集检测（BFS+过滤）
 * - 单环正多边形布局
 * - 稠合环体系布局（双环共享边法、三环等边三角形法、通用 N 环布局）
 * - 链式结构布局（锯齿主链 + 分支偏转）
 * - 无环结构布局
 * - 键长标准化 + 整体居中
 * - 原子坐标 V3000 输出
 */
class IndigoLayoutEngine {

    /** 内部原子 */
    data class Atom(
        val id: Int,
        var element: String,
        var x: Double,
        var y: Double,
        var isotope: Int = 0,
        var charge: Int = 0,
        var radical: Int = 0,
        var stereopar: Int = 0
    )

    /** 内部键 */
    data class Bond(
        val id: Int,
        val atom1: Int,
        val atom2: Int,
        val type: Int,
        val stereo: Int = 0
    )

    /** 内部分子 */
    data class Molecule(
        val atoms: MutableList<Atom>,
        val bonds: MutableList<Bond>
    )

    companion object {
        private const val BOND_LEN = 1.5
        private val PI = kotlin.math.PI

        /** 主入口 */
        fun layout(molfileV3000: String): String? {
            android.util.Log.d("INDIGO_ENGINE", "layout() called, input len=${molfileV3000.length}")
            val mol = parseV3000(molfileV3000)
            android.util.Log.d("INDIGO_ENGINE", "parseV3000: ${mol?.atoms?.size} atoms, ${mol?.bonds?.size} bonds")
            if (mol == null) return null
            android.util.Log.d("INDIGO_ENGINE", "atoms: ${mol.atoms.joinToString(";") { "id=${it.id} ${it.element}" }}")
            android.util.Log.d("INDIGO_ENGINE", "bonds: ${mol.bonds.joinToString(";") { "${it.atom1}-${it.atom2}" }}")
            if (mol.atoms.isEmpty()) return toV3000(mol)
            if (mol.atoms.size == 1) { mol.atoms[0].x = 0.0; mol.atoms[0].y = 0.0; return toV3000(mol) }

            val adj = buildAdj(mol)
            val rings = detectSSSR(mol, adj)
            android.util.Log.d("INDIGO_ENGINE", "detectSSSR: ${rings.size} rings")
            rings.forEachIndexed { i, r -> android.util.Log.d("INDIGO_ENGINE", "  ring[$i]: ${r.joinToString(",")}") }
            val positioned = mutableSetOf<Int>()
            val atomById = mol.atoms.associateBy { it.id }

            if (rings.isNotEmpty()) {
                val ringAtomIds = rings.flatten().toSet()
                android.util.Log.d("INDIGO_ENGINE", "ringAtomIds=${ringAtomIds}")
                val groups = groupRings(rings)
                android.util.Log.d("INDIGO_ENGINE", "groups: ${groups.size}")
                for ((gi, g) in groups.withIndex()) {
                    android.util.Log.d("INDIGO_ENGINE", "  group[$gi]: ${g.size} rings")
                    if (g.size == 1) layoutSingleRing(mol, g[0], atomById, positioned)
                    else layoutFused(mol, g, atomById, positioned, rings)
                }
                layoutChainsBFS(mol, adj, positioned, ringAtomIds, atomById)
            } else {
                android.util.Log.d("INDIGO_ENGINE", "NO rings, calling layoutAcyclic")
                layoutAcyclic(mol, adj, atomById)
            }

            android.util.Log.d("INDIGO_ENGINE", "before normalize: ${mol.atoms.joinToString(";") { "id=${it.id} (${"%.2f".format(it.x)},${"%.2f".format(it.y)})" }}")
            normalizeBondLengths(mol, atomById)
            centerMolecule(mol)
            android.util.Log.d("INDIGO_ENGINE", "after center: ${mol.atoms.joinToString(";") { "id=${it.id} (${"%.2f".format(it.x)},${"%.2f".format(it.y)})" }}")
            return toV3000(mol)
        }

        // ========== 邻接表 ==========

        private fun buildAdj(mol: Molecule): Map<Int, List<Int>> {
            val adj = mutableMapOf<Int, MutableList<Int>>()
            for (a in mol.atoms) adj[a.id] = mutableListOf()
            for (b in mol.bonds) {
                adj[b.atom1]!!.add(b.atom2)
                adj[b.atom2]!!.add(b.atom1)
            }
            return adj
        }

        // ========== V3000 解析 ==========

        private fun parseV3000(mol: String): Molecule? {
            val lines = mol.lines().map { it.trimEnd('\r') }
            val atoms = mutableListOf<Atom>()
            val bonds = mutableListOf<Bond>()
            var inAtom = false; var inBond = false

            for (line in lines) {
                val t = line.trim()
                when {
                    t.startsWith("M  V30 BEGIN ATOM") || t.startsWith("BEGIN ATOM") -> { inAtom = true; inBond = false }
                    t.startsWith("M  V30 END ATOM") || t.startsWith("END ATOM") -> inAtom = false
                    t.startsWith("M  V30 BEGIN BOND") || t.startsWith("BEGIN BOND") -> { inBond = true; inAtom = false }
                    t.startsWith("M  V30 END BOND") || t.startsWith("END BOND") -> inBond = false
                    inAtom -> {
                        val p = t.split(Regex("\\s+"))
                        // 格式1（标准V3000，无前缀）: seqNum element X Y ...
                        // 格式2（ChemELN格式，有M V30前缀）: M V30 seqNum element X Y ...
                        val idxOffset = when {
                            p.size >= 2 && p[0] == "M" && p[1] == "V30" -> 2
                            else -> 0
                        }
                        val dataStart = idxOffset
                        if (p.size >= dataStart + 4) {
                            val id = p[dataStart].toIntOrNull() ?: continue
                            atoms.add(Atom(id, p[dataStart + 1], p[dataStart + 2].toDoubleOrNull() ?: 0.0, p[dataStart + 3].toDoubleOrNull() ?: 0.0))
                        }
                    }
                    inBond -> {
                        val p = t.split(Regex("\\s+"))
                        // 格式1（无前缀）: seqNum type atom1 atom2 ...
                        // 格式2（有M V30前缀）: M V30 seqNum type atom1 atom2 ...
                        val idxOffset = when {
                            p.size >= 2 && p[0] == "M" && p[1] == "V30" -> 2
                            else -> 0
                        }
                        val dataStart = idxOffset
                        if (p.size >= dataStart + 4) {
                            val id = p[dataStart].toIntOrNull() ?: continue
                            // V3000 键格式: seqNum type atom1 atom2
                            val type = p[dataStart + 1].toIntOrNull() ?: 1
                            val a1 = p[dataStart + 2].toIntOrNull() ?: continue
                            val a2 = p[dataStart + 3].toIntOrNull() ?: continue
                            bonds.add(Bond(id, a1, a2, type))
                        }
                    }
                }
            }
            return if (atoms.isNotEmpty()) Molecule(atoms, bonds) else null
        }

        // ========== V3000 输出 ==========

        private fun toV3000(mol: Molecule): String {
            val sb = StringBuilder()
            sb.appendLine()
            sb.appendLine("  IndigoLayoutEngine")
            sb.appendLine()
            sb.appendLine("  0  0  0  0  0  0  0  0  0  0  0 V3000")
            sb.appendLine("M  V30 BEGIN CTAB")
            sb.appendLine("M  V30 COUNTS ${mol.atoms.size} ${mol.bonds.size} 0 0 0")
            sb.appendLine("M  V30 BEGIN ATOM")
            for (a in mol.atoms) {
                sb.appendLine("M  V30 ${a.id} ${a.element} ${"%.4f".format(a.x)} ${"%.4f".format(a.y)} 0 DATOM=0")
            }
            sb.appendLine("M  V30 END ATOM")
            sb.appendLine("M  V30 BEGIN BOND")
            for (b in mol.bonds) {
                val s = if (b.stereo != 0) " CFG=${b.stereo}" else ""
                sb.appendLine("M  V30 ${b.id} ${b.type} ${b.atom1} ${b.atom2}$s")
            }
            sb.appendLine("M  V30 END BOND")
            sb.appendLine("M  V30 END CTAB")
            sb.appendLine("M  END")
            return sb.toString()
        }

        // ========== SSSR 环检测 ==========

        private fun detectSSSR(mol: Molecule, adj: Map<Int, List<Int>>): List<List<Int>> {
            val rings = mutableListOf<List<Int>>()
            val seen = mutableSetOf<Pair<Int, Int>>()

            for (b in mol.bonds) {
                val u = b.atom1; val v = b.atom2
                val key = if (u < v) u to v else v to u
                if (key in seen) continue
                seen.add(key)

                val parent = mutableMapOf(u to -1)
                val dist = mutableMapOf(u to 0)
                val q = ArrayDeque<Int>(); q.addLast(u)
                var found = false

                while (q.isNotEmpty() && !found) {
                    val cur = q.removeFirst()
                    for (nbr in adj[cur].orEmpty()) {
                        if (cur == u && nbr == v) continue
                        if (nbr in parent) continue
                        parent[nbr] = cur; dist[nbr] = (dist[cur] ?: 0) + 1
                        if (nbr == v) { found = true; break }
                        q.addLast(nbr)
                    }
                }
                if (!found) continue

                val cycle = mutableListOf<Int>()
                var c = v
                while (c != u) { cycle.add(c); c = parent[c] ?: break }
                cycle.add(u)
                if (cycle.size < 3) continue
                rings.add(normalizeRing(cycle))
            }

            return filterSSSR(rings.distinctBy { it.toSet() })
        }

        private fun normalizeRing(r: List<Int>): List<Int> {
            val n = r.size
            val all = mutableListOf<List<Int>>()
            for (s in 0 until n) all.add(List(n) { r[(s + it) % n] })
            for (s in 0 until n) all.add(List(n) { r.asReversed()[(s + it) % n] })
            return all.minWith(compareBy { it.joinToString(",") })
        }

        private fun filterSSSR(rings: List<List<Int>>): List<List<Int>> {
            val sorted = rings.sortedBy { it.size }
            val result = mutableListOf<List<Int>>()
            val covered = mutableSetOf<Pair<Int, Int>>()
            for (r in sorted) {
                val edges = mutableSetOf<Pair<Int, Int>>()
                for (i in r.indices) {
                    val a = r[i]; val b = r[(i + 1) % r.size]
                    edges.add(if (a < b) a to b else b to a)
                }
                if (edges.all { it in covered }) continue
                result.add(r); covered.addAll(edges)
            }
            return result
        }

        // ========== 环分组 ==========

        private fun groupRings(rings: List<List<Int>>): List<List<List<Int>>> {
            val adj = mutableMapOf<Int, MutableSet<Int>>()
            for (i in rings.indices) adj[i] = mutableSetOf()
            for (i in rings.indices)
                for (j in i + 1 until rings.size)
                    if (rings[i].toSet().intersect(rings[j].toSet()).size >= 2) { adj[i]!!.add(j); adj[j]!!.add(i) }

            val vis = mutableSetOf<Int>()
            val groups = mutableListOf<List<List<Int>>>()
            for (i in rings.indices) {
                if (i in vis) continue
                val g = mutableListOf<Int>()
                val q = ArrayDeque<Int>(); q.addLast(i); vis.add(i)
                while (q.isNotEmpty()) {
                    val cur = q.removeFirst(); g.add(cur)
                    for (n in adj[cur].orEmpty()) if (n !in vis) { vis.add(n); q.addLast(n) }
                }
                groups.add(g.map { rings[it] })
            }
            return groups
        }

        // ========== 单环布局 ==========

        private fun layoutSingleRing(
            mol: Molecule, ring: List<Int>,
            atomById: Map<Int, Atom>, positioned: MutableSet<Int>
        ) {
            val n = ring.size
            if (n < 3) return
            val r = BOND_LEN / (2.0 * sin(PI / n))
            for (i in 0 until n) {
                val a = atomById[ring[i]] ?: continue
                if (a.id in positioned) continue
                val angle = -PI / 2 + 2.0 * PI * i / n
                a.x = r * cos(angle); a.y = r * sin(angle)
                positioned.add(a.id)
            }
        }

        // ========== 稠合环布局 ==========

        private fun layoutFused(
            mol: Molecule, group: List<List<Int>>,
            atomById: Map<Int, Atom>, positioned: MutableSet<Int>,
            allRings: List<List<Int>>
        ) {
            // 三环稠合
            if (group.size == 3) { layoutThreeRing(mol, group, atomById, positioned); return }

            // 双环/多环稠合
            val first = group[0]
            val firstN = first.size
            val firstR = BOND_LEN / (2.0 * sin(PI / firstN))
            for (i in 0 until firstN) {
                val a = atomById[first[i]] ?: continue
                val angle = -PI / 2 + 2.0 * PI * i / firstN
                a.x = firstR * cos(angle); a.y = firstR * sin(angle)
                positioned.add(a.id)
            }

            val laid = mutableSetOf(0)
            val laidAtoms = first.toMutableSet()
            val sharedAll = findSharedAtomIds(group)

            var changed: Boolean
            do {
                changed = false
                for (ri in group.indices) {
                    if (ri in laid) continue
                    val ring = group[ri]; val n = ring.size
                    val r = BOND_LEN / (2.0 * sin(PI / n))

                    val edge = findEdgeInRing(ring, sharedAll)
                    if (edge == null) continue
                    val sa = atomById[edge.first] ?: continue
                    val sb = atomById[edge.second] ?: continue
                    if (sa.id !in positioned && sb.id !in positioned) continue

                    // 如果至少一个共享原子已定位，我们可以布局
                    val idxA = ring.indexOf(edge.first)
                    val idxB = ring.indexOf(edge.second)
                    if (idxA < 0 || idxB < 0) continue

                    val dx = sb.x - sa.x; val dy = sb.y - sa.y
                    val len = sqrt(dx * dx + dy * dy)
                    if (len <= 0.001) continue

                    val h = r * cos(PI / n)
                    val eAngle = atan2(dy, dx)
                    val midX = (sa.x + sb.x) / 2.0; val midY = (sa.y + sb.y) / 2.0
                    val perp = eAngle + PI / 2

                    val cx1 = midX + h * cos(perp); val cy1 = midY + h * sin(perp)
                    val cx2 = midX - h * cos(perp); val cy2 = midY - h * sin(perp)

                    // 选远离已布原子的方向
                    val lcx = laidAtoms.mapNotNull { atomById[it]?.x }.average()
                    val lcy = laidAtoms.mapNotNull { atomById[it]?.y }.average()
                    val d1 = (cx1 - lcx).let { it * it } + (cy1 - lcy).let { it * it }
                    val d2 = (cx2 - lcx).let { it * it } + (cy2 - lcy).let { it * it }
                    val (cx, cy) = if (d1 > d2) cx1 to cy1 else cx2 to cy2

                    val baseAngle = atan2(sa.y - cy, sa.x - cx) - 2.0 * PI * idxA / n

                    for (i in 0 until n) {
                        val a = atomById[ring[i]] ?: continue
                        if (a.id in positioned && a.id != sa.id && a.id != sb.id) continue
                        val angle = baseAngle + 2.0 * PI * i / n
                        a.x = cx + r * cos(angle); a.y = cy + r * sin(angle)
                        positioned.add(a.id)
                    }

                    laid.add(ri); laidAtoms.addAll(ring.toSet()); changed = true
                }
            } while (changed)
        }

        /** 三环稠合（等边三角形） */
        private fun layoutThreeRing(
            mol: Molecule, group: List<List<Int>>,
            atomById: Map<Int, Atom>, positioned: MutableSet<Int>
        ) {
            val n6 = 6
            val r6 = BOND_LEN / (2.0 * sin(PI / n6))
            val h6 = r6 * cos(PI / n6)

            val centers = arrayOf(
                doubleArrayOf(0.0, 0.0),                                     // 顶
                doubleArrayOf(2.0 * h6 * cos(-PI/6), 2.0 * h6 * sin(-PI/6)), // 右下
                doubleArrayOf(2.0 * h6 * cos(-5*PI/6), 2.0 * h6 * sin(-5*PI/6)) // 左下
            )

            // 找环间邻接
            data class RP(val ri: Int, val rj: Int)
            val pairs = mutableListOf<RP>()
            for (i in group.indices)
                for (j in i + 1 until group.size)
                    if (group[i].toSet().intersect(group[j].toSet()).size >= 2)
                        pairs.add(RP(i, j))

            val deg = IntArray(3)
            for (p in pairs) { deg[p.ri]++; deg[p.rj]++ }
            val top = deg.indices.maxByOrNull { deg[it] } ?: 0

            val map = IntArray(3) { -1 }
            map[top] = 0
            val rest = (0..2).filter { it != top }
            map[rest[0]] = 1; map[rest[1]] = 2

            val sharedAll = findSharedAtomIds(group)

            for (li in 0..2) {
                val ring = group[li]; val ti = map[li]
                val (cx, cy) = centers[ti]
                val n = ring.size
                val r = BOND_LEN / (2.0 * sin(PI / n))

                val edge = findEdgeInRing(ring, sharedAll) ?: continue
                val idxA = ring.indexOf(edge.first)
                val idxB = ring.indexOf(edge.second)
                if (idxA < 0 || idxB < 0) continue

                val rawDir = atan2(-cy, -cx)
                val dir = if (ti == 0) rawDir + PI else rawDir
                val midAngle = (idxA + idxB) / 2.0 * 2.0 * PI / n
                val rot = dir - midAngle

                for (i in 0 until n) {
                    val a = atomById[ring[i]] ?: continue
                    if (a.id in positioned) continue
                    val angle = rot + 2.0 * PI * i / n
                    a.x = cx + r * cos(angle); a.y = cy + r * sin(angle)
                    positioned.add(a.id)
                }
            }
        }

        private fun findSharedAtomIds(group: List<List<Int>>): Set<Int> {
            val cnt = mutableMapOf<Int, Int>()
            for (ring in group) for (id in ring.toSet()) cnt[id] = (cnt[id] ?: 0) + 1
            return cnt.filter { it.value >= 2 }.keys
        }

        private fun findEdgeInRing(ring: List<Int>, shared: Set<Int>): Pair<Int, Int>? {
            val n = ring.size
            val inRing = ring.filter { it in shared }
            var edge: Pair<Int, Int>? = (0 until n).firstNotNullOfOrNull { i ->
                val a = ring[i]; val b = ring[(i + 1) % n]
                if (a in inRing && b in inRing) a to b else null
            }
            if (edge == null && inRing.size >= 2) {
                var best = Int.MAX_VALUE
                for (i in inRing.indices)
                    for (j in i + 1 until inRing.size) {
                        val ai = ring.indexOf(inRing[i]); val aj = ring.indexOf(inRing[j])
                        if (ai < 0 || aj < 0) continue
                        val d = min(abs(ai - aj), n - abs(ai - aj))
                        if (d < best) { best = d; edge = inRing[i] to inRing[j] }
                    }
            }
            return edge
        }

        // ========== BFS 链式布局（对标 Ketcher/Indigo 质量） ==========

        /**
         * 从已定位原子 BFS 向外布局链/分支。
         *
         * 算法要点：
         * - 主链方向：从父节点指向当前节点的方向，作为"进入方向"
         * - 主链延续：交替偏转 ±60°（相对 incoming），相邻键夹角 120° 形成锯齿
         * - 多分支：各键在 360° 上均匀分布，角度平均
         * - 环原子：从环中心向外发射作为初始方向
         * - 无环结构：从端点 BFS，锯齿交替 ±60°
         */
        private fun layoutChainsBFS(
            mol: Molecule,
            adj: Map<Int, List<Int>>,
            positioned: Set<Int>,
            ringAtomIds: Set<Int>,
            atomById: Map<Int, Atom>
        ) {
            if (positioned.isEmpty()) return

            val ringCx = if (ringAtomIds.isNotEmpty())
                ringAtomIds.mapNotNull { atomById[it]?.x }.average() else 0.0
            val ringCy = if (ringAtomIds.isNotEmpty())
                ringAtomIds.mapNotNull { atomById[it]?.y }.average() else 0.0

            val processed = positioned.toMutableSet()
            val q = ArrayDeque<Int>()
            val depth = mutableMapOf<Int, Int>()
            val childCnt = mutableMapOf<Int, Int>()

            for (p in positioned) { q.addLast(p); depth[p] = 0; childCnt[p] = 0 }

            while (q.isNotEmpty()) {
                val cur = q.removeFirst()
                val curAtom = atomById[cur] ?: continue
                val nbrs = adj[cur]?.filter { it !in processed } ?: continue
                if (nbrs.isEmpty()) continue

                val d = depth[cur] ?: 0

                // 计算"进入方向"（度，从父→cur）
                // 对于环原子（depth==0且无父节点），使用从环心指向原子的方向
                val incoming = (adj.entries
                    .firstOrNull { (_, v) -> cur in v && v.any { it in processed } }
                    ?.let { (k, _) -> atomById[k] })?.let { parentAtom ->
                    Math.toDegrees(atan2(curAtom.y - parentAtom.y, curAtom.x - parentAtom.x))
                } ?: run {
                    if (ringAtomIds.contains(cur))
                        Math.toDegrees(atan2(curAtom.y - ringCy, curAtom.x - ringCx))
                    else null
                } ?: 0.0

                // 所有邻居键均匀分布在 360° 圆周上
                // 第一个邻居（主链延续）采用锯齿偏转 ±60°（相对 incoming 方向）
                // 其余邻居在所有方向上平均分布
                val nTotal = nbrs.size
                for ((nbrIdx, nbrId) in nbrs.withIndex()) {
                    val nbr = atomById[nbrId] ?: continue

                    val angle = if (nTotal == 1) {
                        // 单链延续：交替 ±60° 形成锯齿，相邻键夹角 120°
                        incoming + 60.0 * (if (d % 2 == 0) 1.0 else -1.0)
                    } else {
                        // 多分支：以 incoming 为基准，均匀分布在 360° 圆周上
                        // 使各个键之间的角度相等
                        // 第一个分支从 incoming - 60° 开始（锯齿方向），后续均匀排布
                        val step = 360.0 / nTotal
                        incoming - 60.0 + step * nbrIdx
                    }

                    val rad = Math.toRadians(angle)
                    nbr.x = curAtom.x + BOND_LEN * cos(rad)
                    nbr.y = curAtom.y + BOND_LEN * sin(rad)
                    processed.add(nbrId)
                    depth[nbrId] = d + 1
                    childCnt[cur] = (childCnt[cur] ?: 0) + 1
                    q.addLast(nbrId)
                }
            }
        }

        // ========== 无环结构布局 ==========

        private fun layoutAcyclic(
            mol: Molecule, adj: Map<Int, List<Int>>,
            atomById: Map<Int, Atom>
        ) {
            val ends = adj.filter { it.value.size == 1 }.keys
            if (ends.isEmpty()) {
                // 环状但没检测到环（如双原子分子），平均分布
                for ((i, a) in mol.atoms.withIndex()) {
                    val angle = 2.0 * PI * i / mol.atoms.size
                    a.x = BOND_LEN * 2 * cos(angle)
                    a.y = BOND_LEN * 2 * sin(angle)
                }
                return
            }

            val start = ends.first()
            val result = mutableMapOf<Int, Pair<Double, Double>>()
            result[start] = 0.0 to 0.0
            val visited = mutableSetOf(start)
            val q = ArrayDeque<Int>(); q.addLast(start)
            val parent = mutableMapOf(start to -1)

            while (q.isNotEmpty()) {
                val cur = q.removeFirst()
                val (cx, cy) = result[cur] ?: continue
                val children = adj[cur]?.filter { it !in visited } ?: continue

                val parentAngle = parent[cur]?.let { p ->
                    result[p]?.let { (px, py) -> Math.toDegrees(atan2(cy - py, cx - px)) }
                }

                for ((idx, nbr) in children.withIndex()) {
                    val angle = if (parentAngle != null) {
                        // 锯齿：交替 ±60°，相邻键夹角 120°
                        parentAngle + 60.0 * (if (idx % 2 == 0) 1 else -1)
                    } else {
                        // 根：均匀分散
                        120.0 * idx - 60.0
                    }
                    val rad = Math.toRadians(angle)
                    result[nbr] = (cx + BOND_LEN * cos(rad)) to (cy + BOND_LEN * sin(rad))
                    visited.add(nbr); parent[nbr] = cur; q.addLast(nbr)
                }
            }

            for (a in mol.atoms) {
                result[a.id]?.let { (x, y) -> a.x = x; a.y = y }
            }
        }

        // ========== 键长标准化 ==========

        private fun normalizeBondLengths(mol: Molecule, atomById: Map<Int, Atom>) {
            // 计算平均键长比例
            var total = 0.0; var count = 0
            for (b in mol.bonds) {
                val a1 = atomById[b.atom1] ?: continue
                val a2 = atomById[b.atom2] ?: continue
                val dx = a2.x - a1.x; val dy = a2.y - a1.y
                val len = sqrt(dx * dx + dy * dy)
                if (len > 0.01) { total += len / BOND_LEN; count++ }
            }
            if (count == 0) return
            val scale = total / count

            // 如果平均键长偏差 > 10%，则缩放
            if (abs(scale - 1.0) > 0.1) {
                // 分子整体缩放（以几何中心为基准）
                val cx = mol.atoms.map { it.x }.average()
                val cy = mol.atoms.map { it.y }.average()
                for (a in mol.atoms) {
                    a.x = cx + (a.x - cx) / scale
                    a.y = cy + (a.y - cy) / scale
                }
            }
        }

        // ========== 整体居中 ==========

        private fun centerMolecule(mol: Molecule) {
            val xs = mol.atoms.map { it.x }; val ys = mol.atoms.map { it.y }
            if (xs.isEmpty()) return
            val cx = (xs.min() + xs.max()) / 2.0; val cy = (ys.min() + ys.max()) / 2.0
            for (a in mol.atoms) { a.x -= cx; a.y -= cy }
        }
    }
}
