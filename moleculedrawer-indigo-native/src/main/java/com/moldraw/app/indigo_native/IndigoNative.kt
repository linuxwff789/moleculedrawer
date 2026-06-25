package com.moldraw.app.indigo_native

/**
 * Indigo C 库的 JNI 封装。
 * 提供加载分子、布局（layout）、美化（clean2d）功能。
 */
object IndigoNative {
    private var loaded = false

    /** 加载原生库，返回是否成功 */
    fun load(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("indigo-jni")
            loaded = true
            android.util.Log.d("MOLDRAW_DEBUG", "[IndigoNative] loadLibrary(indigo-jni) SUCCESS")
            true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("MOLDRAW_DEBUG", "[IndigoNative] loadLibrary(indigo-jni) FAILED: ${e.message}")
            // 尝试直接加载 indigo 库
            try {
                System.loadLibrary("indigo")
                loaded = true
                android.util.Log.d("MOLDRAW_DEBUG", "[IndigoNative] loadLibrary(indigo) SUCCESS")
                true
            } catch (e2: UnsatisfiedLinkError) {
                android.util.Log.e("MOLDRAW_DEBUG", "[IndigoNative] loadLibrary(indigo) also FAILED: ${e2.message}")
                false
            }
        }
    }

    /** 获取 Indigo 版本号 */
    external fun getVersion(): String

    /** 从字符串加载分子，返回句柄 */
    external fun loadMolecule(molfile: String): Int

    /** 对分子进行 2D 布局计算 */
    external fun layout(handle: Int): Boolean

    /** 对分子进行 2D 美化（Clean） */
    external fun clean2d(handle: Int): Boolean

    /** 一站式：加载 → 布局（不含 clean2d）→ 输出 molfile */
    external fun layoutOnly(molfile: String): String

    /** 一站式：加载 → 布局 → 美化 → 输出 molfile */
    external fun layoutAndClean(molfile: String): String

    /** 加载分子 → 输出 canonical SMILES */
    external fun toSmiles(molStr: String): String

    /** 直接从原子和键生成 SMILES（绕过 V3000 格式问题） */
    external fun toSmilesFromAtoms(xCoords: FloatArray, yCoords: FloatArray, elements: Array<String>, bondPairs: IntArray, bondTypes: IntArray?): String

    /** 释放 Indigo 对象 */
    external fun free(handle: Int)

    /** 设置 Indigo 选项 */
    external fun setOption(name: String, value: String): Boolean

    /** 使用 Indigo 计算分子式（含隐式氢），输入 SMILES 或 molfile */
    external fun grossFormula(molInput: String): String

    /** 使用 Indigo 计算分子量（含隐式氢），输入 SMILES 或 molfile */
    external fun molecularWeight(molInput: String): Double
}