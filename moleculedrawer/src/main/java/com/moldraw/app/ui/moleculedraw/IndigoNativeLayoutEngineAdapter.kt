package com.moldraw.app.ui.moleculedraw

import com.moldraw.app.indigo_native.IndigoNative

/**
 * 基于真实 Indigo C 库（通过 JNI）的布局引擎适配器。
 * 支持 layout() + clean2d()，效果与 Ketcher 一致。
 */
class IndigoNativeLayoutEngineAdapter : MoleculeLayoutEngine {

    private val indigoLoaded: Boolean

    init {
        indigoLoaded = IndigoNative.load()
        if (indigoLoaded) {
            android.util.Log.d("MOLDRAW_DEBUG", "[IndigoNativeLayoutEngine] Indigo loaded, version=${IndigoNative.getVersion()}")
        } else {
            android.util.Log.w("MOLDRAW_DEBUG", "[IndigoNativeLayoutEngine] Failed to load Indigo native library, will fall back")
        }
    }
    override fun layout(molfileV3000: String): String? {
        if (!indigoLoaded) return null

        // 检测输入是否为纯 SMILES（不带 V3000 或 V2000 标记）
        val isSmiles = !molfileV3000.contains("V3000") && !molfileV3000.contains("V2000")
        
        if (isSmiles) {
            // 直接传 SMILES 给 Indigo，用 layoutOnly（只布局不 clean2d）
            android.util.Log.d("MOLDRAW_DEBUG", "[IndigoNativeLayoutEngine] input is SMILES, layoutOnly")
            return try {
                val result = IndigoNative.layoutOnly(molfileV3000)
                if (result.isEmpty()) null else result
            } catch (e: Exception) {
                android.util.Log.e("MOLDRAW_DEBUG", "[IndigoNativeLayoutEngine] layout error on SMILES", e)
                null
            }
        }
        
        // 检测是否为 V2000 格式（含 "V2000" 标记）
        val isV2000 = molfileV3000.contains("V2000")
        
        if (isV2000) {
            // V2000 格式：直接传 Indigo 的 layoutOnly
            android.util.Log.d("MOLDRAW_DEBUG", "[IndigoNativeLayoutEngine] input is V2000, layoutOnly")
            return try {
                val result = IndigoNative.layoutOnly(molfileV3000)
                if (result.isEmpty()) null else result
            } catch (e: Exception) {
                android.util.Log.e("MOLDRAW_DEBUG", "[IndigoNativeLayoutEngine] layout error on V2000", e)
                null
            }
        }

        // V3000 格式处理...
        // 先尝试直接用 V3000 布局（如果 V3000 包含有效坐标信息）
        val directResult = try {
            val result = IndigoNative.layoutAndClean(molfileV3000)
            if (result.isEmpty()) null else result
        } catch (e: Exception) {
            android.util.Log.e("MOLDRAW_DEBUG", "[IndigoNativeLayoutEngine] direct layoutAndClean error", e)
            null
        }
        if (directResult != null) {
            android.util.Log.d("MOLDRAW_DEBUG", "[IndigoNativeLayoutEngine] layout via V3000 succeeded")
            return directResult
        }

        // V3000 布局失败（可能是带坐标的 V3000 加载问题），转 SMILES 路径
        android.util.Log.d("MOLDRAW_DEBUG", "[IndigoNativeLayoutEngine] V3000 layout failed, trying SMILES path")
        return try {
            // 先用 toSmiles 从 V3000 提取拓扑 SMILES
            val smiles = IndigoNative.toSmiles(molfileV3000)
            if (smiles.isEmpty()) {
                android.util.Log.w("MOLDRAW_DEBUG", "[IndigoNativeLayoutEngine] toSmiles failed, giving up")
                return null
            }
            android.util.Log.d("MOLDRAW_DEBUG", "[IndigoNativeLayoutEngine] SMILES from V3000: '$smiles'")
            
            // 用 SMILES 做布局（不含 clean2d，避免扭曲）
            val cleanMolfile = IndigoNative.layoutOnly(smiles)
            if (cleanMolfile.isEmpty()) {
                android.util.Log.w("MOLDRAW_DEBUG", "[IndigoNativeLayoutEngine] layoutAndClean on SMILES failed")
                null
            } else {
                android.util.Log.d("MOLDRAW_DEBUG", "[IndigoNativeLayoutEngine] layout via SMILES succeeded")
                cleanMolfile
            }
        } catch (e: Exception) {
            android.util.Log.e("MOLDRAW_DEBUG", "[IndigoNativeLayoutEngine] SMILES layout error", e)
            null
        }
    }

    fun layoutAndClean(molfileV3000: String): String? {
        if (!indigoLoaded) return null
        return try {
            val result = IndigoNative.layoutAndClean(molfileV3000)
            if (result.isEmpty()) null else result
        } catch (e: Exception) {
            android.util.Log.e("MOLDRAW_DEBUG", "[IndigoNativeLayoutEngine] layoutAndClean error", e)
            null
        }
    }

    fun isLoaded(): Boolean = indigoLoaded

    /**
     * 使用 Indigo C 库生成 canonical SMILES。
     * 如果有楔形键（手性），先生成 V3000 传给 Indigo，否则直接传原子和键列表。
     */
    fun generateSmiles(atoms: List<MoleculeAtom>, bonds: List<MoleculeBond>, annotations: List<MoleculeAnnotation> = emptyList()): String? {
        android.util.Log.d("MOLDRAW_DEBUG", "[IndigoNative] generateSmiles called: atoms=${atoms.size}, bonds=${bonds.size}, indigoLoaded=$indigoLoaded")
        if (!indigoLoaded) {
            android.util.Log.w("MOLDRAW_DEBUG", "[IndigoNative] generateSmiles: indigo not loaded")
            return null
        }
        if (atoms.isEmpty()) {
            android.util.Log.w("MOLDRAW_DEBUG", "[IndigoNative] generateSmiles: atoms is empty")
            return null
        }
        return try {
            // 日志：打印所有键的类型，诊断立体中心问题
            val wedgeBonds = bonds.filter { it.type == BondType.WEDGE_UP || it.type == BondType.WEDGE_DOWN }
            if (wedgeBonds.isNotEmpty()) {
                android.util.Log.d("MOLDRAW_DEBUG", "[IndigoNative] wedge bonds count=${wedgeBonds.size}")
                for (wb in wedgeBonds) {
                    val a1 = atoms.find { it.id == wb.atom1 }
                    val a2 = atoms.find { it.id == wb.atom2 }
                    android.util.Log.d("MOLDRAW_DEBUG", "[IndigoNative]   bond #${wb.id}: ${a1?.element?.symbol}${wb.atom1} -> ${a2?.element?.symbol}${wb.atom2}, type=${wb.type}")
                }
                // 检查是否有原子同时有多个楔形键指向它
                val wedgeTargets = mutableMapOf<Int, MutableList<Pair<Int, BondType>>>()
                for (wb in wedgeBonds) {
                    wedgeTargets.getOrPut(wb.atom2) { mutableListOf() }.add(Pair(wb.atom1, wb.type))
                }
                for ((target, sources) in wedgeTargets) {
                    if (sources.size > 1) {
                        val targetAtom = atoms.find { it.id == target }
                        android.util.Log.w("MOLDRAW_DEBUG", "[IndigoNative]   ** CONFLICT: ${targetAtom?.element}$target has ${sources.size} wedge bonds pointing to it!")
                    }
                }
            }
            // 官能团缩写标注：如果原子已标记 funGroupLabel（常驻展开），不再重复展开
            val hasFuncGroup = annotations.any { it.type == AnnotationType.FUNC_GROUP }
            val atomsAlreadyExpanded = atoms.any { it.funGroupLabel != null }
            val (expandedAtoms, expandedBonds) = if (hasFuncGroup && !atomsAlreadyExpanded) {
                val result = expandFunctionalGroups(atoms, bonds, annotations)
                android.util.Log.d("MOLDRAW_DEBUG", "[IndigoNative] expandFunctionalGroups: atoms ${atoms.size}->${result.first.size}, bonds ${bonds.size}->${result.second.size}")
                result
            } else {
                atoms to bonds
            }
            // 展开后传给 toV3000Mol 时去除 FUNC_GROUP 标注，避免生成 SGROUP 导致 Indigo toSmiles 抛异常
            val cleanAnnotations = annotations.filter { it.type != AnnotationType.FUNC_GROUP }
            val molfile = MoleculeData(expandedAtoms, expandedBonds, cleanAnnotations).toV3000Mol(scaleToAngstrom = true, includeCoords = true)
            // 日志：打印展开后的原子、键和 V3000
            android.util.Log.d("MOLDRAW_DEBUG", "[IndigoNative] expandedAtoms: ${expandedAtoms.joinToString(";") { "${it.id}(${it.element.symbol})" }}")
            android.util.Log.d("MOLDRAW_DEBUG", "[IndigoNative] expandedBonds: ${expandedBonds.joinToString(";") { "${it.id}:${it.atom1}-${it.atom2}" }}")
            android.util.Log.d("MOLDRAW_DEBUG", "[IndigoNative] V3000:\n$molfile")
            val rawResult = IndigoNative.toSmiles(molfile)
            // 清理 Indigo 可能附加的 ChemAxon 扩展标记（如 |&1:1,r|）
            val result = rawResult?.substringBefore(" |")?.trim() ?: ""
            if (result.isNotEmpty()) {
                android.util.Log.d("MOLDRAW_DEBUG", "[IndigoNative] generateSmiles result='$result'")
                result
            } else {
                // 如果 V3000 失败（可能是立体中心冲突，如 stereocenters: degenerate case -- bonds overlap）
                // 去掉楔形键信息，用纯单/双/三键重试
                android.util.Log.w("MOLDRAW_DEBUG", "[IndigoNative] V3000 toSmiles returned empty, retrying with flat bonds (no stereo)")
                val flatBonds = bonds.map { b ->
                    when (b.type) {
                        BondType.WEDGE_UP, BondType.WEDGE_DOWN -> b.copy(type = BondType.SINGLE)
                        else -> b
                    }
                }
                val flatMolfile = MoleculeData(atoms, flatBonds, annotations).toV3000Mol(scaleToAngstrom = true, includeCoords = true)
                val flatRaw = IndigoNative.toSmiles(flatMolfile)
                val flatResult = flatRaw?.substringBefore(" |")?.trim() ?: ""
                android.util.Log.d("MOLDRAW_DEBUG", "[IndigoNative] generateSmiles (flat) result='${flatResult}' (len=${flatResult.length})")
                if (flatResult.isEmpty()) null else flatResult
            }
        } catch (e: Exception) {
            android.util.Log.e("MOLDRAW_DEBUG", "[IndigoNative] generateSmiles error: ${e.message}")
            null
        }
    }
}