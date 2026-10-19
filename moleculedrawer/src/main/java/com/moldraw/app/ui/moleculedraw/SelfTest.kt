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
            val term = s2.atoms.first { it.id == 7 }
            applyFunctionalGroupAt(s2, fg, term.x, term.y, term)
            val termOk = s2.atoms.size == 7 && term.funGroupLabel == fg.label &&
                degreeOf(s2, term) == 1 &&
                s2.atoms.count { it.funGroupLabel == fg.label } == 1 &&
                s2.annotations.any { it.type == AnnotationType.FUNC_GROUP && it.text == fg.label }

            val ok = ringOk && termOk
            if (ok) pass++ else fail++
            log("SELFTEST PLACE ${if (ok) "PASS" else "FAIL"} ${fg.label.padEnd(8)} ring(attach)=$ringOk term(replace)=$termOk atoms=${s1.atoms.size}/${s2.atoms.size}")
        }

        log("SELFTEST SUMMARY pass=$pass fail=$fail")
    } catch (e: Exception) {
        log("SELFTEST EXCEPTION ${e::class.simpleName}: ${e.message}")
        android.util.Log.e("SELFTEST", "stack", e)
    }
    runCatching { java.io.File(context.filesDir, "selftest.txt").writeText(sb.toString()) }
}
