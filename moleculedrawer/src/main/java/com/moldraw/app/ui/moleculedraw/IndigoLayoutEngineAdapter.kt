package com.moldraw.app.ui.moleculedraw

import com.moldraw.app.indigo.IndigoLayoutEngine
import java.io.File

/**
 * Indigo 布局引擎的适配器。
 * 将纯 Kotlin 实现的 IndigoLayoutEngine 封装为 MoleculeLayoutEngine 接口，
 * 可直接注入到 MoleculeDrawApp 中使用。
 */
class IndigoLayoutEngineAdapter : MoleculeLayoutEngine {

    /**
     * 对给定的 V3000 Molfile 字符串进行布局计算。
     * @param molfileV3000 包含分子拓扑信息的 V3000 格式 Molfile
     * @return 布局后的 V3000 Molfile 字符串（包含原子坐标），或 null 表示失败
     */
    override fun layout(molfileV3000: String): String? {
        val dbg = StringBuilder("[IndigoLayoutEngineAdapter] layout() called\n")
        dbg.appendLine("[IndigoLayoutEngineAdapter] input length=${molfileV3000.length}")
        dbg.appendLine("[IndigoLayoutEngineAdapter] calling IndigoLayoutEngine.layout()...")
        val result = IndigoLayoutEngine.layout(molfileV3000)
        dbg.appendLine("[IndigoLayoutEngineAdapter] IndigoLayoutEngine.layout() returned: ${if (result == null) "NULL" else "non-null (${result.length} chars)"}")
        if (result != null) {
            dbg.appendLine("[IndigoLayoutEngineAdapter] result V3000:\n$result")
        }
        try { File("/sdcard/Download/indigo_adapter_debug.txt").writeText(dbg.toString()) } catch (_: Exception) {}
        return result
    }
}