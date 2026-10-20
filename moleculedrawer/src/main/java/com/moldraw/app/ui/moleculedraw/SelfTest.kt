package com.moldraw.app.ui.moleculedraw

import android.content.Context
import com.moldraw.app.indigo_native.IndigoNative

/**
 * 无界面自测：`am start ... --es moldraw_selftest 1` 触发。
 *
 * 目的：官能团 / 自动调整这类逻辑，靠点屏幕验证太慢（要打包、等 CI、解锁、盲点），
 * 这里把每个基团都构造一遍，走和真实使用完全相同的代码路径：
 *   苯环 + 缩写原子 → expandFunctionalGroups → generateSmiles → grossFormula
 *   → autoFit（V3000 布局 + 序号搬标记）
 * 结果写 logcat（SELFTEST 标签）和 filesDir/selftest.txt，用 adb 直接读。
 */
fun runSelfTest(context: Context) {
    val sb = StringBuilder()
    fun log(line: String) {
        sb.appendLine(line)
        android.util.Log.d("SELFTEST", line)
    }
    try {
        val engine = IndigoNativeLayoutEngineAdapter()
        log("engine=${engine.javaClass.simpleName} indigo=${IndigoNative.getVersion()}")

        var pass = 0
        var fail = 0
        for (fg in FUNCTIONAL_GROUPS) {
            val connElem = Element.fromSymbol(fg.expandAtoms[fg.connectIndex].first)
            // ── 构造：苯环 + 缩写原子 ──
            val atoms = mutableListOf<MoleculeAtom>()
            val bonds = mutableListOf<MoleculeBond>()
            var id = 1
            val cx = 300f; val cy = 300f; val r = BOND_LENGTH
            for (i in 0 until 6) {
                val ang = Math.toRadians(60.0 * i)
                atoms.add(MoleculeAtom(id++, cx + r * kotlin.math.cos(ang).toFloat(), cy + r * kotlin.math.sin(ang).toFloat(), Element.C, aromatic = true))
            }
            for (i in 0 until 6) bonds.add(MoleculeBond(id++, i + 1, (i + 1) % 6 + 1, BondType.AROMATIC))
            val aliasId = id
            atoms.add(MoleculeAtom(aliasId, cx + r * 2f, cy, connElem, funGroupLabel = fg.label, isFunGroupConnector = true))
            bonds.add(MoleculeBond(id++, 1, aliasId, BondType.SINGLE))
            val anns = mutableListOf(MoleculeAnnotation(id++, AnnotationType.FUNC_GROUP, cx + r * 2f, cy, text = fg.label))

            val expectMembers = fg.expandAtoms
                .filterIndexed { i, _ -> i != fg.connectIndex }
                .count { Element.fromSymbol(it.first) != Element.H }

            // ── 1. 展开 ──
            val (expAtoms, _) = expandFunctionalGroups(atoms, bonds, anns)
            val added = expAtoms.size - atoms.size
            val expandOk = added == expectMembers

            // ── 2. SMILES + 分子式 ──
            val smiles = runCatching { engine.generateSmiles(atoms, bonds, anns) }.getOrNull().orEmpty()
            val formula = if (smiles.isNotEmpty()) runCatching { IndigoNative.grossFormula(smiles) }.getOrDefault("") else ""
            val smilesOk = smiles.isNotEmpty()

            // ── 3. 自动调整：缩写原子必须原样保留 ──
            val a2 = atoms.toMutableList()
            val b2 = bonds.toMutableList()
            val ann2 = anns.toMutableList()
            val sel = atoms.map { it.id }.toSet()
            val newSel = runCatching {
                autoFit(a2, b2, sel, engine, 800f, 600f, ann2)
            }.getOrNull()
            val aliasAfter = a2.count { it.funGroupLabel == fg.label }
            val annAfter = ann2.count { it.type == AnnotationType.FUNC_GROUP && it.text == fg.label }
            val fitOk = newSel != null && aliasAfter == 1 && annAfter == 1 && a2.size == atoms.size

            val ok = expandOk && smilesOk && fitOk
            if (ok) pass++ else fail++
            log(
                "SELFTEST ${if (ok) "PASS" else "FAIL"} ${fg.label.padEnd(8)} members=$added/$expectMembers " +
                    "smiles=${smiles.ifEmpty { "<em>" }} formula=$formula aliasAfterFit=$aliasAfter annAfterFit=$annAfter atoms=${a2.size}"
            )
        }
        // ── 替换路径：苯环上挂一个末端碳（模拟甲苯的甲基），把该碳换成各个基团 ──
        for (fg in FUNCTIONAL_GROUPS) {
            val connElem = Element.fromSymbol(fg.expandAtoms[fg.connectIndex].first)
            val atoms = mutableListOf<MoleculeAtom>()
            val bonds = mutableListOf<MoleculeBond>()
            var id = 1
            val cx = 300f; val cy = 300f; val r = BOND_LENGTH
            for (i in 0 until 6) {
                val ang = Math.toRadians(60.0 * i)
                atoms.add(MoleculeAtom(id++, cx + r * kotlin.math.cos(ang).toFloat(), cy + r * kotlin.math.sin(ang).toFloat(), Element.C, aromatic = true))
            }
            for (i in 0 until 6) bonds.add(MoleculeBond(id++, i + 1, (i + 1) % 6 + 1, BondType.AROMATIC))
            val methylId = id
            atoms.add(MoleculeAtom(methylId, cx + r * 2f, cy, Element.C))
            bonds.add(MoleculeBond(id++, 1, methylId, BondType.SINGLE))
            // 替换：末端碳就地变成基团连接点
            atoms.first { it.id == methylId }.apply {
                element = connElem; funGroupLabel = fg.label; isFunGroupConnector = true
            }
            val anns = mutableListOf(MoleculeAnnotation(id++, AnnotationType.FUNC_GROUP, cx + r * 2f, cy, text = fg.label))

            val expectMembers = fg.expandAtoms
                .filterIndexed { i, _ -> i != fg.connectIndex }
                .count { Element.fromSymbol(it.first) != Element.H }
            val (expAtoms, _) = expandFunctionalGroups(atoms, bonds, anns)
            val added = expAtoms.size - atoms.size
            val smiles = runCatching { engine.generateSmiles(atoms, bonds, anns) }.getOrNull().orEmpty()
            val formula = if (smiles.isNotEmpty()) runCatching { IndigoNative.grossFormula(smiles) }.getOrDefault("") else ""
            val ok = added == expectMembers && smiles.isNotEmpty()
            if (ok) pass++ else fail++
            log("SELFTEST REPLACE ${if (ok) "PASS" else "FAIL"} ${fg.label.padEnd(8)} members=$added/$expectMembers smiles=${smiles.ifEmpty { "<em>" }} formula=$formula")
        }

        // ── 放置语义：环碳（度数≥2）应挂新连接点；末端碳（度数1）应就地替换 ──
        fun buildDoc(withTerminal: Boolean): MoleculeDrawState {
            val st = MoleculeDrawState()
            var i = 1
            val cx = 300f; val cy = 300f; val r = BOND_LENGTH
            for (k in 0 until 6) {
                val ang = Math.toRadians(60.0 * k)
                st.atoms.add(MoleculeAtom(i++, cx + r * kotlin.math.cos(ang).toFloat(), cy + r * kotlin.math.sin(ang).toFloat(), Element.C, aromatic = true))
            }
            for (k in 0 until 6) st.bonds.add(MoleculeBond(i++, k + 1, (k + 1) % 6 + 1, BondType.AROMATIC))
            if (withTerminal) {
                st.atoms.add(MoleculeAtom(i, cx + r * 2f, cy, Element.C))
                st.bonds.add(MoleculeBond(i + 1, 1, i, BondType.SINGLE))
            }
            return st
        }
        for (fg in FUNCTIONAL_GROUPS) {
            // 环碳
            val s1 = buildDoc(withTerminal = false)
            val ringAtom = s1.atoms.first { it.id == 1 }
            val beforeBonds = degreeOf(s1, ringAtom)
            applyFunctionalGroupAt(s1, fg, ringAtom.x, ringAtom.y, ringAtom)
            val ringOk = s1.atoms.size == 7 && ringAtom.funGroupLabel == null &&
                degreeOf(s1, ringAtom) == beforeBonds + 1 &&
                s1.atoms.count { it.funGroupLabel == fg.label } == 1 &&
                s1.annotations.any { it.type == AnnotationType.FUNC_GROUP && it.text == fg.label }

            // 末端碳
            val s2 = buildDoc(withTerminal = true)
            val term = s2.atoms.last()
            applyFunctionalGroupAt(s2, fg, term.x, term.y, term)
            val termOk = s2.atoms.size == 7 && term.funGroupLabel == fg.label &&
                degreeOf(s2, term) == 1 &&
                s2.atoms.count { it.funGroupLabel == fg.label } == 1 &&
                s2.annotations.any { it.type == AnnotationType.FUNC_GROUP && it.text == fg.label }

            val ok = ringOk && termOk
            if (ok) pass++ else fail++
            log("SELFTEST PLACE ${if (ok) "PASS" else "FAIL"} ${fg.label.padEnd(8)} ring(attach)=$ringOk term(replace)=$termOk atoms=${s1.atoms.size}/${s2.atoms.size}")
        }

        // ── 结构反查（PubChem 精确匹配）──
        for (smi in listOf("C1=CC=C(C=C1)C(=O)O", "CCO", "N1CC(C(O)=O)C1")) {
            val h = runCatching { pubchemLookup(smi) }.getOrNull()
            val ok = h != null && h.cid > 0 && h.name.isNotBlank()
            if (ok) pass++ else fail++
            log("SELFTEST PUBCHEM ${if (ok) "PASS" else "FAIL"} $smi cid=${h?.cid} name=${h?.name} formula=${h?.formula} cas=${h?.cas}")
        }

        // ── 双键几何：环内双键的短线必须落在环内；末端双键(C=O)两根等长 ──
        fun ring(n: Int, cx: Float, cy: Float, r: Float, doubleIdx: Set<Int>): Pair<MutableList<MoleculeAtom>, MutableList<MoleculeBond>> {
            val as_ = mutableListOf<MoleculeAtom>()
            val bs = mutableListOf<MoleculeBond>()
            for (i in 0 until n) {
                val ang = 2 * Math.PI * i / n
                as_.add(MoleculeAtom(i + 1, cx + r * kotlin.math.cos(ang).toFloat(), cy + r * kotlin.math.sin(ang).toFloat(), Element.C))
            }
            for (i in 0 until n) {
                bs.add(MoleculeBond(100 + i, i + 1, (i + 1) % n + 1, if (i in doubleIdx) BondType.DOUBLE else BondType.SINGLE))
            }
            return Pair(as_, bs)
        }
        for ((n, dbl) in listOf(6 to setOf(0, 2, 4), 5 to setOf(1, 3), 7 to setOf(1, 4))) {
            val (aa, bb) = ring(n, 300f, 300f, BOND_LENGTH, dbl)
            var allIn = true
            val detail = StringBuilder()
            for (b in bb.filter { it.type == BondType.DOUBLE }) {
                val a1 = aa.first { it.id == b.atom1 }; val a2 = aa.first { it.id == b.atom2 }
                val poly = ringPathForBond(aa, bb, a1.id, a2.id)
                val dx = a2.x - a1.x; val dy = a2.y - a1.y
                val len = kotlin.math.hypot(dx, dy); val ux = dx / len; val uy = dy / len
                val (_, l2) = doubleBondSegments(a1.x, a1.y, a2.x, a2.y, ux, uy, len, poly, false)
                val mx = (l2[0] + l2[2]) / 2f; val my = (l2[1] + l2[3]) / 2f
                val inside = poly != null && pointInPolygon(mx, my, poly)
                if (!inside) allIn = false
                detail.append(" ${b.atom1}-${b.atom2}:${if (inside) "in" else "OUT"}")
            }
            if (allIn) pass++ else fail++
            log("SELFTEST DBOND_RING ${if (allIn) "PASS" else "FAIL"} n=$n$detail")
        }
        // 平移不变性：整张图平移到负坐标区（画布常有的状态），短线仍要在环内
        run {
            val (aa0, bb) = ring(6, 300f, 300f, BOND_LENGTH, setOf(1, 3, 5))
            val aa = aa0.map { MoleculeAtom(it.id, it.x - 520f, it.y - 760f, it.element) }.toMutableList()
            var ok = true
            for (b in bb.filter { it.type == BondType.DOUBLE }) {
                val a1 = aa.first { it.id == b.atom1 }; val a2 = aa.first { it.id == b.atom2 }
                val poly = ringPathForBond(aa, bb, a1.id, a2.id)
                val dx = a2.x - a1.x; val dy = a2.y - a1.y
                val len = kotlin.math.hypot(dx, dy); val ux = dx / len; val uy = dy / len
                val (_, l2) = doubleBondSegments(a1.x, a1.y, a2.x, a2.y, ux, uy, len, poly, false)
                val px = (l2[0] + l2[2]) / 2f; val py = (l2[1] + l2[3]) / 2f
                if (poly == null || !pointInPolygon(px, py, poly)) ok = false
            }
            if (ok) pass++ else fail++
            log("SELFTEST DBOND_SHIFT ${if (ok) "PASS" else "FAIL"}")
        }
        // 稠环（两个四元环共用一条边 2-3）：共用键两侧都是环，短线也必须落在环内
        run {
            val as_ = mutableListOf(
                MoleculeAtom(1, 300f, 300f, Element.C), MoleculeAtom(2, 400f, 300f, Element.C),
                MoleculeAtom(3, 400f, 400f, Element.C), MoleculeAtom(4, 300f, 400f, Element.C),
                MoleculeAtom(5, 500f, 300f, Element.C), MoleculeAtom(6, 500f, 400f, Element.C)
            )
            val bs = mutableListOf(
                MoleculeBond(1, 1, 2, BondType.DOUBLE), MoleculeBond(2, 2, 3, BondType.SINGLE),
                MoleculeBond(3, 3, 4, BondType.DOUBLE), MoleculeBond(4, 4, 1, BondType.SINGLE),
                MoleculeBond(5, 2, 5, BondType.SINGLE), MoleculeBond(6, 5, 6, BondType.DOUBLE),
                MoleculeBond(7, 6, 3, BondType.SINGLE)
            )
            var ok = true
            val detail = StringBuilder()
            for (b in bs.filter { it.type == BondType.DOUBLE }) {
                val a1 = as_.first { it.id == b.atom1 }; val a2 = as_.first { it.id == b.atom2 }
                val poly = ringPathForBond(as_, bs, a1.id, a2.id)
                val dx = a2.x - a1.x; val dy = a2.y - a1.y
                val len = kotlin.math.hypot(dx, dy); val ux = dx / len; val uy = dy / len
                val (_, l2) = doubleBondSegments(a1.x, a1.y, a2.x, a2.y, ux, uy, len, poly, false)
                val px = (l2[0] + l2[2]) / 2f; val py = (l2[1] + l2[3]) / 2f
                val inside = poly != null && pointInPolygon(px, py, poly)
                if (!inside) ok = false
                detail.append(" ${b.atom1}-${b.atom2}:${if (inside) "in" else "OUT"}")
            }
            if (ok) pass++ else fail++
            log("SELFTEST DBOND_FUSED ${if (ok) "PASS" else "FAIL"}$detail")
        }
        // 末端双键 C=O：两根线等长
        run {
            val as_ = listOf(MoleculeAtom(1, 300f, 300f, Element.C), MoleculeAtom(2, 350f, 300f, Element.O))
            val bs = listOf(MoleculeBond(1, 1, 2, BondType.DOUBLE))
            val len = 50f
            val (l1, l2) = doubleBondSegments(300f, 300f, 350f, 300f, 1f, 0f, len, null, isTerminalDouble(bs, 1, 2))
            val len1 = kotlin.math.hypot(l1[2] - l1[0], l1[3] - l1[1])
            val len2 = kotlin.math.hypot(l2[2] - l2[0], l2[3] - l2[1])
            val ok = kotlin.math.abs(len1 - len2) < 0.01f && len1 > len * 0.99f
            if (ok) pass++ else fail++
            log("SELFTEST DBOND_TERMINAL ${if (ok) "PASS" else "FAIL"} len1=$len1 len2=$len2")
        }

        // ── V2000 定宽格式：两位数/三位数的原子把计数行推宽，会让严格解析器读成 "99 V20" ──
        for ((n, m) in listOf(7 to 7, 37 to 41, 120 to 130)) {
            val as_ = (1..n).map { i -> MoleculeAtom(i, i * 1f, 0f, Element.C) }
            val bs = (1 until m + 1).map { i -> MoleculeBond(i, (i % n) + 1, ((i + 1) % n) + 1, BondType.SINGLE) }
            val mol = MoleculeData(as_, bs).toMol()
            val lines = mol.lines()
            val counts = lines[3]
            val countersOk = counts.length == 39 && counts.substring(33, 39) == " V2000"
            val atomOk = lines[4].length == 69 && lines[4].substring(31, 34).trim() == "C"
            val bondLine = lines[4 + n]
            val bondOk = bondLine.length == 21 && bondLine.substring(0, 3).trim().toIntOrNull() != null
            val endOk = lines[4 + n + m].trim() == "M  END"
            val ok = countersOk && atomOk && bondOk && endOk
            if (ok) pass++ else fail++
            log("SELFTEST MOL_V2000 ${if (ok) "PASS" else "FAIL"} n=$n m=$m counts='${counts}' len=${counts.length} atomLen=${lines[4].length} bondLen=${bondLine.length}")
        }

        // ── molfile 坐标尺度：必须是 1.5 左右的标准尺度，否则 Ketcher/ChemDraw 里会放大几十倍看不见 ──
        run {
            val as_ = (0 until 6).map { i ->
                val ang = 2 * Math.PI * i / 6
                MoleculeAtom(i + 1, 55f * kotlin.math.cos(ang).toFloat(), 55f * kotlin.math.sin(ang).toFloat(), Element.C)
            }
            val bs = (0 until 6).map { i -> MoleculeBond(i + 1, i + 1, (i + 1) % 6 + 1, BondType.SINGLE) }
            val mol = MoleculeData(as_, bs).toMol()
            val lines = mol.lines()
            val coords = (0 until 6).map {
                val l = lines[4 + it]
                l.substring(0, 10).trim().toFloat() to l.substring(10, 20).trim().toFloat()
            }
            val lens = (0 until 6).map { i ->
                val a = coords[i]; val b = coords[(i + 1) % 6]
                kotlin.math.hypot(b.first - a.first, b.second - a.second)
            }
            val avg = lens.average().toFloat()
            val maxAbs = coords.maxOf { kotlin.math.max(kotlin.math.abs(it.first), kotlin.math.abs(it.second)) }
            val ok = avg in 1.4f..1.6f && maxAbs < 10f
            // 大分子：坐标范围必须被压到画布内（14 x 8.5 单位）
            val big = (0 until 30).map { i -> MoleculeAtom(i + 1, i * 55f, (i % 2) * 40f, Element.C) }
            val bigBonds = (0 until 29).map { i -> MoleculeBond(i + 1, i + 1, i + 2, BondType.SINGLE) }
            val bigMol = MoleculeData(big, bigBonds).toMol().lines()
            val bcoords = (0 until 30).map {
                val l = bigMol[4 + it]
                l.substring(0, 10).trim().toFloat() to l.substring(10, 20).trim().toFloat()
            }
            val bw = bcoords.maxOf { it.first } - bcoords.minOf { it.first }
            val bh = bcoords.maxOf { it.second } - bcoords.minOf { it.second }
            val fitOk = bw <= 14.2f && bh <= 8.7f
            if (!fitOk) { /* 计入下面的失败 */ }
            val allOk = ok && fitOk
            if (allOk) pass++ else fail++
            log("SELFTEST MOL_SCALE ${if (allOk) "PASS" else "FAIL"} avgBond=$avg maxCoord=$maxAbs big30=%.1fx%.1f".format(bw, bh))
        }

        log("SELFTEST SUMMARY pass=$pass fail=$fail")
    } catch (e: Exception) {
        log("SELFTEST EXCEPTION ${e::class.simpleName}: ${e.message}")
        android.util.Log.e("SELFTEST", "stack", e)
    }
    runCatching { java.io.File(context.filesDir, "selftest.txt").writeText(sb.toString()) }
}
