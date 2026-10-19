package com.moldraw.app.ui.moleculedraw

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kotlin.math.*
import com.moldraw.app.indigo_native.IndigoNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MW_ROLE_PREFIX = "MW:"

/** 打开中的文档槽 */
private data class DocEntry(val id: Long, val name: String, val json: String, val uri: String)

class MoleculeDrawActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SMILES = "extra_smiles"; const val EXTRA_MOL = "extra_mol"; const val EXTRA_JSON = "extra_json"
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_FILE_URI = "extra_file_uri"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 绘制风格（经典 / 专业）是全局偏好，启动时恢复
        DrawStyleState.load(this)
        // 沉浸模式：内容铺满全屏（含状态栏/导航栏区域），系统栏从边缘滑动临时唤出
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val bars = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        // 读取传入参数
        val inputSmiles = intent?.getStringExtra(EXTRA_SMILES) ?: ""
        val inputMol = intent?.getStringExtra(EXTRA_MOL) ?: ""
        var inputJson = intent?.getStringExtra(EXTRA_JSON) ?: ""
        var inputFilePath = intent?.getStringExtra(EXTRA_FILE_PATH) ?: ""
        var inputFileUri = intent?.getStringExtra(EXTRA_FILE_URI) ?: ""
        
        // 从 URI 参数解析（moldraw://edit?smiles=...&mol=...&file=...）
        val uri = intent?.data
        if (uri != null && uri.scheme == "moldraw") {
            val uriSmiles = uri.getQueryParameter("smiles") ?: ""
            val uriMol = uri.getQueryParameter("mol") ?: ""
            val uriFile = uri.getQueryParameter("file") ?: ""
            if (inputSmiles.isEmpty() && uriSmiles.isNotEmpty()) inputSmiles.let { /* 下面统一处理 */ }
        }
        
        // 处理其他 App 分享/打开的文件（ACTION_VIEW / ACTION_SEND）
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { dataUri ->
                    try {
                        val text = contentResolver.openInputStream(dataUri)?.bufferedReader()?.readText()
                        if (!text.isNullOrBlank()) {
                            inputJson = text
                            inputFileUri = dataUri.toString()
                        }
                    } catch (_: Exception) {}
                }
            }
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                    if (!text.isNullOrBlank()) inputJson = text
                }
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { streamUri ->
                    try {
                        val text = contentResolver.openInputStream(streamUri)?.bufferedReader()?.readText()
                        if (!text.isNullOrBlank()) {
                            inputJson = text
                            inputFileUri = streamUri.toString()
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        
        setContent { MoleculeDrawApp(
            initialSmiles = inputSmiles.ifEmpty { null },
            initialMol = inputMol.ifEmpty { null },
            initialJson = inputJson.ifEmpty { null },
            initialFilePath = inputFilePath.ifEmpty { null },
            initialFileUri = inputFileUri.ifEmpty { null },
            onResult = { smiles, mol, json ->
                val intent = Intent().apply {
                    putExtra(EXTRA_SMILES, smiles); putExtra(EXTRA_MOL, mol); putExtra(EXTRA_JSON, json)
                }
                setResult(RESULT_OK, intent); finish()
            },
            layoutEngine = run {
                val native = IndigoNativeLayoutEngineAdapter()
                if (native.isLoaded()) native else IndigoLayoutEngineAdapter()
            }
        ) }
    }
}

enum class RingType(val label: String, val n: Int, val benzene: Boolean = false) {
    RING3("三元环", 3), RING4("四元环", 4), RING5("五元环", 5),
    RING6("六元环", 6), RING7("七元环", 7), BENZENE("苯环", 6, benzene = true)
}

@Composable
fun RingIcon(ringType: RingType, size: androidx.compose.ui.unit.Dp) {
    val n = ringType.n; val color = Color(0xFF333333)
    Canvas(Modifier.size(size)) {
        val cx = size.toPx() / 2f; val cy = size.toPx() / 2f
        val r = (size.toPx() / 2f - 1.5f).coerceAtLeast(1f)
        val pts = Array(n) { i -> val deg = i * (360f / n) - 90f; val rad = Math.toRadians(deg.toDouble()); Offset(cx + r * cos(rad).toFloat(), cy + r * sin(rad).toFloat()) }
        for (i in 0 until n) { val p = pts[i]; val q = pts[(i + 1) % n]
            if (ringType.benzene && i % 2 == 1) { val ex = q.x - p.x; val ey = q.y - p.y; val elen = sqrt(ex * ex + ey * ey)
                if (elen > 0f) { val ux = -ey / elen; val uy = ex / elen; val off = 1.2f
                    drawLine(color, Offset(p.x + ux * off, p.y + uy * off), Offset(q.x + ux * off, q.y + uy * off), strokeWidth = 1.5f)
                    drawLine(color, Offset(p.x - ux * off, p.y - uy * off), Offset(q.x - ux * off, q.y - uy * off), strokeWidth = 1.5f) } }
            else drawLine(color, p, q, strokeWidth = 1.5f) }
        for (p in pts) drawCircle(color, 1.8f, p)
    }
}

@Composable fun toolIcon(t: DrawTool) = when (t) {
    DrawTool.ATOM -> Icons.Default.FontDownload; DrawTool.BOND -> Icons.Default.ShowChart
    DrawTool.ERASE -> Icons.Default.Delete; DrawTool.SELECT -> Icons.Default.Dashboard
    DrawTool.TEXT -> Icons.Default.TextFields; DrawTool.ARROW -> Icons.Default.NorthEast
    DrawTool.PAN -> Icons.Default.PanTool; else -> Icons.Default.Build
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MoleculeDrawApp(
    onResult: (smiles: String, mol: String, json: String) -> Unit,
    layoutEngine: MoleculeLayoutEngine? = null,
    initialSmiles: String? = null,
    initialMol: String? = null,
    initialJson: String? = null,
    initialFilePath: String? = null,
    initialFileUri: String? = null
) {
    val s = remember { MoleculeDrawState() }
    val context = LocalContext.current
    val toast = remember { android.widget.Toast::class }

    // 当前编辑的文件 URI / 路径（传参得到）
    var currentFilePath by remember { mutableStateOf(initialFilePath ?: "") }
    var currentFileUri by remember { mutableStateOf(initialFileUri ?: "") }

    // ── 多文档：内存里的文档槽（切换时把当前画布序列化进去）──
    val docs = remember { mutableStateListOf<DocEntry>() }
    var activeDocId by remember { mutableStateOf(0L) }
    var docSeq by remember { mutableStateOf(1L) }
    var showDocSheet by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun snapshotActiveDoc() {
        val idx = docs.indexOfFirst { it.id == activeDocId }
        if (idx < 0) return
        val json = runCatching {
            buildWorkspaceJson(s.atoms.toList(), s.bonds.toList(), s.annotations.toList(), BOND_LENGTH, s.benzeneStyle, layoutEngine)
        }.getOrDefault("")
        docs[idx] = docs[idx].copy(json = json, name = s.saveFileName, uri = currentFileUri)
    }

    fun loadDocInto(doc: DocEntry) {
        s.pushUndo()
        s.atoms.clear(); s.bonds.clear(); s.annotations.clear()
        s.selectedIds = emptySet(); s.activeGroup = null
        if (doc.json.isNotBlank()) {
            runCatching { loadWorkspaceJson(doc.json, s.atoms, s.bonds, s.annotations) }
                .onSuccess { it?.let { style -> s.benzeneStyle = style } }
        }
        currentFileUri = doc.uri
        s.saveFileName = doc.name
    }

    fun newDoc() {
        snapshotActiveDoc()
        docSeq += 1
        val d = DocEntry(docSeq, "未命名 $docSeq", "", "")
        docs.add(d)
        activeDocId = d.id
        loadDocInto(d)
    }

    fun switchDoc(doc: DocEntry) {
        if (doc.id == activeDocId) return
        snapshotActiveDoc()
        activeDocId = doc.id
        loadDocInto(doc)
    }

    fun closeDoc(id: Long) {
        val idx = docs.indexOfFirst { it.id == id }
        if (idx < 0) return
        if (id == activeDocId) snapshotActiveDoc()
        docs.removeAt(idx)
        if (docs.isEmpty()) {
            docSeq += 1
            val d = DocEntry(docSeq, "未命名 $docSeq", "", "")
            docs.add(d); activeDocId = d.id; loadDocInto(d)
        } else if (id == activeDocId) {
            val next = docs[idx.coerceAtMost(docs.size - 1)]
            activeDocId = next.id
            loadDocInto(next)
        }
    }

    // 启动时至少有一个文档
    LaunchedEffect(Unit) {
        if (docs.isEmpty()) {
            docs.add(DocEntry(1L, s.saveFileName, "", currentFileUri))
            activeDocId = 1L
        }
    }

    // ── 定时保存到应用内部临时文件（崩溃 / 被系统杀掉后可恢复）──
    val autosaveFile = remember { java.io.File(context.filesDir, "autosave.moldraw") }
    var restoreCandidate by remember { mutableStateOf<java.io.File?>(null) }

    LaunchedEffect(Unit) {
        if (autosaveFile.exists() && autosaveFile.length() > 20) restoreCandidate = autosaveFile
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(20_000)
            if (s.atoms.isNotEmpty() || s.annotations.isNotEmpty()) {
                try {
                    val json = buildWorkspaceJson(s.atoms.toList(), s.bonds.toList(), s.annotations.toList(), BOND_LENGTH, s.benzeneStyle, layoutEngine)
                    autosaveFile.writeText(json)
                } catch (_: Exception) {}
            }
        }
    }

    // 恢复提示
    val rc = restoreCandidate
    if (rc != null) {
        val ts = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(rc.lastModified()))
        AlertDialog(
            onDismissRequest = { restoreCandidate = null },
            title = { Text("恢复未保存的内容", fontWeight = FontWeight.Medium) },
            text = { Text("发现上次未保存的编辑（$ts），要恢复吗？") },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        s.pushUndo()
                        s.atoms.clear(); s.bonds.clear(); s.annotations.clear()
                        s.selectedIds = emptySet(); s.activeGroup = null
                        loadWorkspaceJson(rc.readText(), s.atoms, s.bonds, s.annotations)?.let { s.benzeneStyle = it }
                    } catch (_: Exception) {}
                    restoreCandidate = null
                }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { runCatching { rc.delete() }; restoreCandidate = null }) { Text("丢弃") }
            }
        )
    }

    val molFilePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { try { context.contentResolver.openInputStream(it)?.bufferedReader()?.readText()?.also { s.importInput = it } } catch (_: Exception) {} }
    }

    // 项目文件保存 launcher（CreateDocument — SAF）
    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it, "wt")?.bufferedWriter()?.use { w ->
                    w.write(s.pendingSaveJson)
                }
                s.pendingSaveJson = ""
                // 记录 URI，下次保存直接覆盖
                currentFileUri = it.toString()
                currentFilePath = ""
                runCatching { autosaveFile.delete() }
                android.widget.Toast.makeText(context, "已保存", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e("MOLDRAW", "save project failed", e)
                android.widget.Toast.makeText(context, "保存失败: ${e.localizedMessage ?: "未知错误"}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    // 项目文件打开 launcher（OpenDocument）
    val openFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try {
                val text = context.contentResolver.openInputStream(it)?.bufferedReader()?.readText() ?: return@let
                s.pushUndo()
                s.atoms.clear(); s.bonds.clear(); s.annotations.clear(); s.selectedIds = emptySet(); s.activeGroup = null
loadWorkspaceJson(text, s.atoms, s.bonds, s.annotations)?.let { s.benzeneStyle = it }
                // 记录文件路径和 URI，下次保存直接覆盖
                currentFileUri = it.toString()
                currentFilePath = ""
                // 从 URI 尝试获取文件名
                try {
                    val cursor = context.contentResolver.query(it, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    cursor?.use { c -> if (c.moveToFirst()) { val name = c.getString(0); s.saveFileName = name } }
                } catch (_: Exception) {}
                runCatching { snapshotActiveDoc() }
        } catch (e: Exception) {
            android.util.Log.e("MOLDRAW", "open project failed", e)
        }
        }
    }

    // 从 Intent 参数初始加载
    LaunchedEffect(initialSmiles, initialMol, initialJson, initialFilePath, initialFileUri) {
        if (initialJson != null && initialJson.isNotEmpty()) {
            s.pushUndo()
            s.atoms.clear(); s.bonds.clear(); s.annotations.clear(); s.selectedIds = emptySet(); s.activeGroup = null
            loadWorkspaceJson(initialJson, s.atoms, s.bonds, s.annotations)?.let { s.benzeneStyle = it }
        // 清理残留的展开原子（O等），避免旧保存文件的 O 原子显示
        cleanupExpandedAtoms(s.atoms, s.bonds, s.annotations)
    } else if (initialSmiles != null && initialSmiles.isNotEmpty()) {
        s.pushUndo()
        s.atoms.clear(); s.bonds.clear(); s.annotations.clear(); s.selectedIds = emptySet(); s.activeGroup = null
            processSmilesImport(initialSmiles, layoutEngine, s.atoms, s.bonds, s.annotations, s.selectedIds.toMutableSet(), gNextId, s.canvasSizePx)
        } else if (initialMol != null && initialMol.isNotEmpty()) {
            s.pushUndo()
            s.atoms.clear(); s.bonds.clear(); s.annotations.clear(); s.selectedIds = emptySet(); s.activeGroup = null
            val isV3000 = initialMol.contains("V3000")
            if (isV3000) processMolImport(initialMol, layoutEngine, s.atoms, s.bonds, s.annotations, s.selectedIds.toMutableSet(), gNextId, s.canvasSizePx)
            else processSmilesImport(initialMol, layoutEngine, s.atoms, s.bonds, s.annotations, s.selectedIds.toMutableSet(), gNextId, s.canvasSizePx)
        }
        
        // 从文件路径读取（本地文件）- 仅当文件确实存在时
        if (initialFilePath != null && initialFilePath.isNotEmpty()) {
            try {
                val file = java.io.File(initialFilePath)
                if (file.exists()) {
                    val text = file.readText()
                    s.pushUndo()
                    s.atoms.clear(); s.bonds.clear(); s.annotations.clear(); s.selectedIds = emptySet(); s.activeGroup = null
                    if (text.contains("\"atoms\"") || text.contains("\"bonds\"")) {
                        loadWorkspaceJson(text, s.atoms, s.bonds, s.annotations)?.let { s.benzeneStyle = it }
            } else {
                processSmilesImport(text.trim(), layoutEngine, s.atoms, s.bonds, s.annotations, s.selectedIds.toMutableSet(), gNextId, s.canvasSizePx)
            }
            }
            // 文件不存在时静默跳过（通过 URI 加载）
            } catch (e: Exception) {
                android.util.Log.e("MOLDRAW", "load from file failed", e)
            }
        }
        if (initialFileUri != null && initialFileUri.isNotEmpty()) {
            try {
                val uri = Uri.parse(initialFileUri)
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                if (text != null) {
                    s.pushUndo()
                    s.atoms.clear(); s.bonds.clear(); s.annotations.clear(); s.selectedIds = emptySet(); s.activeGroup = null
                    if (text.contains("\"atoms\"") || text.contains("\"bonds\"")) {
loadWorkspaceJson(text, s.atoms, s.bonds, s.annotations)?.let { s.benzeneStyle = it }
                        } else {
                            processSmilesImport(text.trim(), layoutEngine, s.atoms, s.bonds, s.annotations, s.selectedIds.toMutableSet(), gNextId, s.canvasSizePx)
                        }
                } else {
                    android.widget.Toast.makeText(context, "无法读取 URI: $initialFileUri", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "打开 URI 失败: ${e.localizedMessage ?: "未知错误"}", android.widget.Toast.LENGTH_LONG).show()
                android.util.Log.e("MOLDRAW", "load from URI failed", e)
            }
        }
    }

    // 文字对话框
    if (s.showTextDialog && s.textDialogPos != null) AlertDialog(
        onDismissRequest = { s.showTextDialog = false }, title = { Text("输入文字标注", fontWeight = FontWeight.Medium) },
        text = {
            Column {
                OutlinedTextField(value = s.textInput, onValueChange = { s.textInput = it }, label = { Text("标注文字") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = s.textSubScript, onCheckedChange = { s.textSubScript = it })
                    Spacer(Modifier.width(4.dp))
                    Text("自动下标（数字自动缩小下沉）", fontSize = 14.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (s.textInput.isNotBlank()) { s.pushUndo(); val wx = s.textDialogPos!!.x - s.canvasOffsetX; val wy = s.textDialogPos!!.y - s.canvasOffsetY; s.annotations.add(MoleculeAnnotation(nextId(), AnnotationType.TEXT, wx, wy, text = s.textInput, subScript = s.textSubScript)) }; s.showTextDialog = false }) { Text("确定") } },
        dismissButton = { TextButton(onClick = { s.showTextDialog = false }) { Text("取消") } }
    )
    // 延长碳链对话框
    if (s.showExtendDialog && s.extendStart != null) AlertDialog(
        onDismissRequest = { s.showExtendDialog = false }, title = { Text("延长碳链", fontWeight = FontWeight.Medium) },
        text = { Column { Text("碳链个数：", fontSize = 14.sp); Spacer(Modifier.height(8.dp)); Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { s.extendInput.toIntOrNull()?.let { if (it > 1) s.extendInput = (it - 1).toString() } }) { Icon(Icons.Default.Remove, "减") }
            OutlinedTextField(value = s.extendInput, onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 3) s.extendInput = it }, label = { Text("个数") }, singleLine = true, modifier = Modifier.width(80.dp), textStyle = TextStyle(textAlign = TextAlign.Center, fontSize = 16.sp))
            IconButton(onClick = { val v = s.extendInput.toIntOrNull() ?: 0; if (v < 99) s.extendInput = (v + 1).toString() }) { Icon(Icons.Default.Add, "加") }
        } } },
        confirmButton = { TextButton(onClick = {
            val n = s.extendInput.toIntOrNull(); if (n != null && n > 0 && s.extendStart != null) { s.pushUndo()
                var px = s.extendStart!!.x; var py = s.extendStart!!.y
                val startAtom = hitAtom(s.atoms, Offset(px, py)) ?: findMergeAtom(s.atoms, Offset(px, py))
                if (startAtom == null) {
                    // 空白处点击 → 以点击点为起点直接画一段锯齿碳链（原子间正常成键）
                    var angle = -30f
                    var prevId = -1
                    for (i in 0 until n) {
                        if (i > 0) {
                            angle += if (i % 2 == 1) 60f else -60f
                            val rad = Math.toRadians(angle.toDouble())
                            px += BOND_LENGTH * cos(rad).toFloat()
                            py += BOND_LENGTH * sin(rad).toFloat()
                        }
                        s.atoms.add(MoleculeAtom(nextId(), px, py, Element.C))
                        val cid = s.atoms.last().id
                        if (prevId > 0) s.bonds.add(MoleculeBond(nextId(), prevId, cid))
                        prevId = cid
                    }
                    s.showExtendDialog = false; s.extendMode = false
                    return@TextButton
                }
                // 计算起始方向角：若有已连接原子，取背离方向；否则取 0°
                val a0 = if (startAtom != null) { val connected = s.bonds.filter { it.atom1 == startAtom.id || it.atom2 == startAtom.id }
                    if (connected.isNotEmpty()) {
                        // 取所有相邻原子的平均方向 + 180°（背离方向）
                        val avgAngle = connected.mapNotNull { b ->
                            val o = if (b.atom1 == startAtom.id) b.atom2 else b.atom1
                            s.atoms.find { it.id == o }?.let { Math.toDegrees(atan2((it.y - startAtom.y).toDouble(), (it.x - startAtom.x).toDouble())).toFloat() }
                        }.let { angles ->
                            if (angles.isEmpty()) 0f
                            else {
                                val rads = angles.map { Math.toRadians(it.toDouble()) }
                                val avgX = rads.sumOf { cos(it) } / angles.size
                                val avgY = rads.sumOf { sin(it) } / angles.size
                                Math.toDegrees(atan2(avgY, avgX)).toFloat()
                            }
                        }
                        // 背离方向 = 相邻原子的平均方向 + 180°
                        (avgAngle + 180f) % 360f
                    } else 0f } else 0f
                var angle = a0; var pid = startAtom?.id ?: -1
                for (i in 0 until n) {
                    // 锯齿碳链：每次交替偏转 +60° / -60°（相对于当前方向）
                    val turn = if (i % 2 == 0) 60f else -60f
                    angle = (angle + turn) % 360f
                    val rad = Math.toRadians(angle.toDouble())
                    s.atoms.add(MoleculeAtom(nextId(), px + BOND_LENGTH * cos(rad).toFloat(), py + BOND_LENGTH * sin(rad).toFloat(), Element.C))
                    val nid = s.atoms.last().id; if (pid > 0 && pid != nid) s.bonds.add(MoleculeBond(nextId(), pid, nid)); pid = nid; px = s.atoms.last().x; py = s.atoms.last().y
                }
            }
            s.showExtendDialog = false; s.extendMode = false
        }) { Text("确定") } },
        dismissButton = { TextButton(onClick = { s.showExtendDialog = false }) { Text("取消") } }
    )
    // 导入对话框
    if (s.showImportDialog) AlertDialog(
        onDismissRequest = { s.showImportDialog = false }, title = { Text("导入分子", fontWeight = FontWeight.Medium) },
        text = { Column { Text("输入 SMILES 或粘贴 Molfile（V2000/V3000）：", fontSize = 14.sp); Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = s.importInput, onValueChange = { s.importInput = it }, label = { Text("SMILES / Molfile") }, singleLine = false, minLines = 4, maxLines = 12, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
            Spacer(Modifier.height(8.dp)); TextButton(onClick = { molFilePickerLauncher.launch("chemical/x-mdl-molfile") }) { Icon(Icons.Default.FolderOpen, "打开文件", modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("从文件读取 .mol", fontSize = 13.sp) } } },
        confirmButton = { TextButton(onClick = { if (s.importInput.isNotBlank()) { val c = s.importInput.trim(); val isMol = c.contains("V3000") || c.contains("V2000") || (c.lines().size >= 4 && c.lines()[3].trim().contains("V2000")); s.pushUndo(); if (isMol) processMolImport(c, layoutEngine, s.atoms, s.bonds, s.annotations, s.selectedIds.toMutableSet(), gNextId, s.canvasSizePx) else processSmilesImport(c, layoutEngine, s.atoms, s.bonds, s.annotations, s.selectedIds.toMutableSet(), gNextId, s.canvasSizePx) }; s.showImportDialog = false }) { Text("确定") } },
        dismissButton = { TextButton(onClick = { s.showImportDialog = false }) { Text("取消") } }
    )
    // 导出对话框
    // 导出对话框 — 只传选中的原子/键（若有选中），否则传全部
                val exportAtoms = if (s.selectedIds.isNotEmpty()) s.atoms.filter { it.id in s.selectedIds } else s.atoms.toList()
                val exportSet = exportAtoms.map { it.id }.toSet()
                val exportBonds = s.bonds.filter { it.atom1 in exportSet && it.atom2 in exportSet }
                MoleculeExportDialog(atoms = exportAtoms, bonds = exportBonds, annotations = s.annotations, layoutEngine = layoutEngine, showExportDialog = s.showExportDialog, onDismiss = { s.showExportDialog = false }, benzeneStyle = s.benzeneStyle)

// 保存文件对话框（让用户通过 SAF 选择保存位置）
if (s.showSaveDialog) {
    var fileName by remember(s.showSaveDialog) { mutableStateOf(s.saveFileName.ifEmpty { "molecule.moldraw" }) }
    AlertDialog(
        onDismissRequest = { s.showSaveDialog = false },
        title = { Text("保存分子文件", fontWeight = FontWeight.Medium) },
        text = {
            Column {
                Text("输入文件名，将打开系统文件选择器选择保存位置：", fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("文件名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 14.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (fileName.isNotBlank()) {
                    s.saveFileName = if (fileName.endsWith(".moldraw")) fileName else "$fileName.moldraw"
                    // 弹出系统文件选择器让用户选位置
                    saveFileLauncher.launch(s.saveFileName)
                    s.showSaveDialog = false
                }
            }) { Text("选择位置") }
        },
        dismissButton = { TextButton(onClick = { s.showSaveDialog = false }) { Text("取消") } }
    )
}

    // ── 分子量：写成真正的 TEXT 标注（进文档，可保存/移动/导出），结构变化时同步 ──
    val connKey = s.atoms.size * 1000003 +
        s.atoms.map { it.id to it.element }.hashCode() * 31 +
        s.bonds.map { Triple(it.atom1, it.atom2, it.type) }.hashCode()
    LaunchedEffect(connKey, s.showMolecularWeight) {
        if (!s.showMolecularWeight) {
            s.annotations.removeAll { it.role?.startsWith(MW_ROLE_PREFIX) == true }
        } else {
            val labels = withContext(Dispatchers.Default) { computeMwLabels(s.atoms.toList(), s.bonds.toList(), layoutEngine) }
            // 已经不存在的分子：删掉它的标注
            s.annotations.removeAll { ann ->
                val r = ann.role ?: return@removeAll false
                r.startsWith(MW_ROLE_PREFIX) && !labels.containsKey(r.removePrefix(MW_ROLE_PREFIX).replace(";", ","))
            }
            for ((key, label) in labels) {
                val ids = key.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
                var minX = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE
                for (a in s.atoms) if (a.id in ids) {
                    if (a.x < minX) minX = a.x
                    if (a.x > maxX) maxX = a.x
                    if (a.y > maxY) maxY = a.y
                }
                if (minX == Float.MAX_VALUE) continue
                val role = MW_ROLE_PREFIX + key.replace(",", ";")
                val cx = (minX + maxX) / 2f          // 水平居中：文字本身就是 Align.CENTER
                val by = maxY + 26f
                val existing = s.annotations.find { it.role == role }
                if (existing == null) {
                    s.annotations.add(MoleculeAnnotation(nextId(), AnnotationType.TEXT, cx, by, text = label, role = role))
                } else {
                    existing.text = label
                    existing.x = cx
                    existing.y = by
                }
            }
        }
    }

    if (showAbout) {
        AboutSheet(onDismiss = { showAbout = false })
    }

    // 打开的文档列表
    if (showDocSheet) {
        ModalBottomSheet(onDismissRequest = { showDocSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text("打开的文档（${docs.size}）", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                docs.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { switchDoc(d); showDocSheet = false }
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (d.id == activeDocId) Icons.Default.CheckCircle else Icons.Default.InsertDriveFile,
                            null,
                            tint = if (d.id == activeDocId) Color(0xFF1A73E8) else Color(0xFF78909C),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                (d.name.ifBlank { "未命名" }) + if (d.id == activeDocId) "（当前）" else "",
                                fontSize = 13.sp, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                (if (d.json.isBlank()) "空文档" else "已缓存") + if (d.uri.isNotBlank()) " · 已关联文件" else " · 未保存到文件",
                                fontSize = 10.sp, color = Color(0xFF90A4AE)
                            )
                        }
                        IconButton(onClick = { closeDoc(d.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, "关闭该文档", tint = Color(0xFF9E9E9E), modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    Button(onClick = { newDoc(); showDocSheet = false }, modifier = Modifier.height(38.dp)) {
                        Text("新建文档", fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = { showDocSheet = false; openFileLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                        modifier = Modifier.height(38.dp)
                    ) { Text("打开文件", fontSize = 12.sp) }
                }
            }
        }
    }

    // 顶栏只保留高频动作（撤销/导入/导出），保存/打开/清空收进 ⋮，避免图标挤压标题
    var menuExpanded by remember { mutableStateOf(false) }

    val onSaveProject: () -> Unit = {
        var json = ""
        try {
            json = buildWorkspaceJson(s.atoms.toList(), s.bonds.toList(), s.annotations.toList(), BOND_LENGTH, s.benzeneStyle, layoutEngine)
            s.pendingSaveJson = json
        } catch (e: Exception) {
            android.util.Log.e("MOLDRAW_DEBUG", "buildWorkspaceJson threw at save site: ${e::class.simpleName}: ${e.message}")
        }
        if (currentFileUri.isNotEmpty()) {
            try {
                val uri = Uri.parse(currentFileUri)
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { w -> if (json.isNotEmpty()) w.write(json) }
                runCatching { autosaveFile.delete() }
                android.widget.Toast.makeText(context, "已保存", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "保存失败: ${e.localizedMessage ?: "未知错误"}", android.widget.Toast.LENGTH_LONG).show()
            }
        } else {
            s.showSaveDialog = true
        }
    }

    // 顶栏动作（竖屏放在 TopAppBar，横屏放进左侧栏）
    val topActions: @Composable () -> Unit = {
                TopAction("撤销", enabled = s.undoCount > 0, compact = landscape, onClick = { s.doUndo() }) {
                    Icon(Icons.Default.Undo, null, modifier = Modifier.size(18.dp))
                }
                TopAction("重做", enabled = s.redoCount > 0, compact = landscape, onClick = { s.doRedo() }) {
                    Icon(Icons.Default.Redo, null, modifier = Modifier.size(18.dp))
                }
                TopAction(DrawStyleState.style.label, compact = landscape, onClick = {
                    DrawStyleState.toggle(context)
                    android.widget.Toast.makeText(
                        context,
                        if (isProStyle()) "专业样式：碳不画点、双键内侧线缩短、氢数下标" else "经典样式：碳画灰点、双键等长",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Icon(Icons.Default.Palette, null, modifier = Modifier.size(18.dp))
                }
                TopAction("导出", compact = landscape, onClick = { s.showExportDialog = true }) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                }
                Box {
                    TopAction("更多", compact = landscape, onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("导入 SMILES / Molfile", fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.FileOpen, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menuExpanded = false; s.showImportDialog = true; s.importInput = "" }
                        )
                        DropdownMenuItem(
                            text = { Text("导出图片 / 文件", fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menuExpanded = false; s.showExportDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text(if (s.showMolecularWeight) "隐藏分子量" else "显示分子量", fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(
                                    if (s.showMolecularWeight) Icons.Default.CheckCircle else Icons.Default.Functions,
                                    null, modifier = Modifier.size(18.dp),
                                    tint = if (s.showMolecularWeight) Color(0xFF1A73E8) else Color(0xFF444444)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                s.pushUndo()
                                s.showMolecularWeight = !s.showMolecularWeight
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("新建文档", fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.NoteAdd, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menuExpanded = false; newDoc() }
                        )
                        DropdownMenuItem(
                            text = { Text("文档列表（${docs.size}）", fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.FolderCopy, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menuExpanded = false; showDocSheet = true }
                        )
                        DropdownMenuItem(
                            text = { Text("关闭当前文档", fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menuExpanded = false; closeDoc(activeDocId) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("保存项目", fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menuExpanded = false; onSaveProject() }
                        )
                        DropdownMenuItem(
                            text = { Text("打开项目", fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menuExpanded = false; openFileLauncher.launch(arrayOf("application/octet-stream", "*/*")) }
                        )
                        if (s.atoms.isNotEmpty() || s.annotations.isNotEmpty()) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("清空画布", fontSize = 13.sp, color = Color(0xFFD32F2F)) },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(18.dp), tint = Color(0xFFD32F2F)) },
                                onClick = {
                                    menuExpanded = false
                                    s.pushUndo()
                                    s.atoms.clear(); s.bonds.clear(); s.annotations.clear(); s.selectedIds = emptySet()
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("关于", fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Info, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menuExpanded = false; showAbout = true }
                        )
                    }
                }
    }

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        if (!landscape) TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text("分子结构绘制器", fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF7F7F8)),
            actions = { topActions() }        )
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).background(Color(0xFFFAFAFA))) {
            // 横屏：左侧栏放顶栏动作 + 工具，右侧是子工具栏/画布/状态栏
            Column(Modifier.fillMaxSize().padding(start = if (landscape) 136.dp else 0.dp)) {
                if (landscape) {
                    MoleculeContextBar(state = s, layoutEngine = layoutEngine)
                } else {
                    MoleculeDrawToolbar(state = s, layoutEngine = layoutEngine)
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Canvas(Modifier.fillMaxSize().clipToBounds().onSizeChanged { s.canvasSizePx = it.toSize(); s.canvasSize.value = Offset(it.width.toFloat(), it.height.toFloat()) }
                        .pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ ->
                            if (zoom != 1f && s.atoms.isNotEmpty()) { if (!s.isPinchZooming) { s.pushUndo(); s.isPinchZooming = true }
                                val hasSelection = s.selectedIds.isNotEmpty()
                                val sel = if (hasSelection) s.atoms.filter { it.id in s.selectedIds } else s.atoms.toList()
                                val selAnn = if (hasSelection) s.annotations.filter { -it.id in s.selectedIds } else s.annotations.toList()
                                if (sel.isEmpty() && selAnn.isEmpty()) return@detectTransformGestures
                                val sf = zoom.coerceIn(0.5f, 2f)
                                // 缩放中心（所有原子平均）
                                val zcx = if (sel.isNotEmpty()) sel.map { it.x }.average().toFloat() else s.canvasSizePx.width / 2f
                                val zcy = if (sel.isNotEmpty()) sel.map { it.y }.average().toFloat() else s.canvasSizePx.height / 2f
                                // 缩放原子
                                if (sel.isNotEmpty()) {
                                    val targetIds = if (hasSelection) s.selectedIds.filter { it > 0 } else s.atoms.indices.map { s.atoms[it].id }.toSet()
                                    for (aid in targetIds) {
                                        val idx = s.atoms.indexOfFirst { it.id == aid }; if (idx < 0) continue
                                        val a = s.atoms[idx]
                                        s.atoms[idx] = MoleculeAtom(a.id, zcx + (a.x - zcx) * sf, zcy + (a.y - zcy) * sf, a.element, a.aromatic, a.chiral, a.funGroupLabel, a.isFunGroupConnector)
                                    }
                                }
                                // 缩放标注（与原子使用相同的缩放中心）
                                for (ann in selAnn) {
                                    ann.scale *= sf
                                    ann.x = zcx + (ann.x - zcx) * sf
                                    ann.y = zcy + (ann.y - zcy) * sf
                                    if (ann.type == AnnotationType.ARROW) {
                                        ann.endX = zcx + (ann.endX - zcx) * sf
                                        ann.endY = zcy + (ann.endY - zcy) * sf
                                    }
                                }
                            } else if (zoom == 1f) s.isPinchZooming = false; s.canvasOffsetX += pan.x; s.canvasOffsetY += pan.y
                        } }
                        .pointerInput(s.tool, s.selElem, s.selBond, s.ringType, s.selectedIds, s.extendMode) { 
        detectTapGestures { pos -> when (s.tool) {
                            DrawTool.ATOM -> { if (s.isFuncGroupDragMode && s.selectedFuncGroup != null) {
                                s.pushUndo()
                                val wp = Offset(pos.x - s.canvasOffsetX, pos.y - s.canvasOffsetY)
                                val hit = hitAtom(s.atoms, wp)
                                applyFunctionalGroupAt(s, s.selectedFuncGroup!!, if (hit != null) hit.x else wp.x, if (hit != null) hit.y else wp.y, hit)
                                s.isFuncGroupDragMode = false; s.selectedFuncGroup = null
                                return@detectTapGestures
                            }
                            s.pushUndo(); val wp = Offset(pos.x - s.canvasOffsetX, pos.y - s.canvasOffsetY); hitAtom(s.atoms, wp)?.let { hit ->
                                val existing = s.bonds.filter { b -> b.atom1 == hit.id || b.atom2 == hit.id }; val angle = if (existing.isNotEmpty()) { val d = existing.firstNotNullOfOrNull { b -> val o = if (b.atom1 == hit.id) s.atoms.find { it.id == b.atom2 } else s.atoms.find { it.id == b.atom1 }; o?.let { atan2(it.y - hit.y, it.x - hit.x) } }; snapAngle(Math.toDegrees((d ?: 0.0).toDouble()).toFloat() + 120f) } else 0f; val rad = Math.toRadians(angle.toDouble()); s.atoms.add(MoleculeAtom(nextId(), hit.x + BOND_LENGTH * cos(rad).toFloat(), hit.y + BOND_LENGTH * sin(rad).toFloat(), s.selElem)); val lid = s.atoms.last().id; if (!s.bonds.any { (it.atom1 == hit.id && it.atom2 == lid) || (it.atom1 == lid && it.atom2 == hit.id) }) s.bonds.add(MoleculeBond(nextId(), hit.id, lid))
                            } ?: run { val m = findMergeAtom(s.atoms, wp); if (m != null) m.element = s.selElem else s.atoms.add(MoleculeAtom(nextId(), wp.x, wp.y, s.selElem)) } }
                            DrawTool.RING -> { s.pushUndo(); s.magnifierPos = pos; placeRing(s.atoms, s.bonds, pos.x - s.canvasOffsetX, pos.y - s.canvasOffsetY, s.ringType.n, s.ringType.benzene, benzeneStyle = s.benzeneStyle); MainScope().launch { delay(600); s.magnifierPos = null } }
                            DrawTool.BOND -> {
                                if (s.extendMode) {
                                    // 延长模式：点原子 → 从该原子延长；点空白 → 就在点击位置生成一段碳链
                                    val wp = Offset(pos.x - s.canvasOffsetX, pos.y - s.canvasOffsetY)
                                    val hit = hitAtom(s.atoms, wp)
                                    s.extendStart = if (hit != null) Offset(hit.x, hit.y) else wp
                                    s.extendInput = "1"
                                    s.showExtendDialog = true
                                } else {
                                    s.pushUndo(); val wp = Offset(pos.x - s.canvasOffsetX, pos.y - s.canvasOffsetY)
                                    hitBond(s.bonds, s.atoms, wp)?.let { (bond, _) ->
                                        val nt = when (bond.type) { BondType.SINGLE -> BondType.DOUBLE; BondType.DOUBLE -> BondType.TRIPLE; BondType.TRIPLE -> BondType.SINGLE; BondType.WEDGE_UP -> BondType.WEDGE_DOWN; BondType.WEDGE_DOWN -> BondType.SINGLE; else -> BondType.DOUBLE }
                                        val idx = s.bonds.indexOfFirst { it.id == bond.id }; if (idx >= 0) s.bonds[idx] = bond.copy(type = nt)
                                    } ?: run { hitAtom(s.atoms, wp)?.let { hit -> s.atoms.add(MoleculeAtom(nextId(), hit.x + BOND_LENGTH, hit.y, Element.C)); val lid = s.atoms.last().id; if (!s.bonds.any { (it.atom1 == hit.id && it.atom2 == lid) || (it.atom1 == lid && it.atom2 == hit.id) }) s.bonds.add(MoleculeBond(nextId(), hit.id, lid, s.selBond)) } ?: run { val a1 = MoleculeAtom(nextId(), wp.x - BOND_LENGTH/2f, wp.y, Element.C); val a2 = MoleculeAtom(nextId(), wp.x + BOND_LENGTH/2f, wp.y, Element.C); s.atoms.add(a1); s.atoms.add(a2); s.bonds.add(MoleculeBond(nextId(), a1.id, a2.id, s.selBond)) } }
                                }
                            }
                                    DrawTool.ERASE -> { s.pushUndo(); val wp = Offset(pos.x - s.canvasOffsetX, pos.y - s.canvasOffsetY); if (s.selectedIds.isNotEmpty()) { s.bonds.removeAll { it.atom1 in s.selectedIds || it.atom2 in s.selectedIds }; s.atoms.removeAll { it.id in s.selectedIds }; s.annotations.removeAll { -it.id in s.selectedIds }; s.selectedIds = emptySet(); s.activeGroup = null } else { hitAtom(s.atoms, wp)?.let { a -> s.bonds.removeAll { b -> b.atom1 == a.id || b.atom2 == a.id }; s.atoms.remove(a) } }; Unit }
                                    DrawTool.TEXT -> { s.textDialogPos = pos; s.textInput = ""; s.showTextDialog = true }
                                    DrawTool.ARROW -> {} // 箭头不响应点击
                                    DrawTool.SELECT -> {
                                        // 点按选择：点原子/键 → 选中整个连通分子；点标注 → 切换标注；点空白 → 取消选择
                                        val wp = Offset(pos.x - s.canvasOffsetX, pos.y - s.canvasOffsetY)
                                        val hitAnn = hitAnnotation(s.annotations, wp)
                                        if (hitAnn != null) {
                                            val annId = -hitAnn.id
                                            s.selectedIds = if (annId in s.selectedIds) s.selectedIds - annId else s.selectedIds + annId
                                        } else {
                                            val hit = hitAtom(s.atoms, wp)
                                            val picked: Set<Int>? = if (hit != null) {
                                                connectedAtomIds(s.atoms, s.bonds, hit.id)
                                            } else {
                                                hitBond(s.bonds, s.atoms, wp)?.let { (b, _) ->
                                                    connectedAtomIds(s.atoms, s.bonds, b.atom1) + connectedAtomIds(s.atoms, s.bonds, b.atom2)
                                                }
                                            }
                                            val pickedIds: Set<Int>? = if (picked == null) null else {
                                                // 把该分子上的官能团缩写一起选中，拖动时缩写跟着走
                                                val groupAnns = s.annotations.filter { ann ->
                                                    ann.type == AnnotationType.FUNC_GROUP && s.atoms.any { a ->
                                                        a.id in picked && a.funGroupLabel == ann.text && a.isFunGroupConnector
                                                    }
                                                }.map { -it.id }
                                                picked + groupAnns
                                            }
                                            s.selectedIds = if (pickedIds == null || pickedIds == s.selectedIds) emptySet() else pickedIds
                                            if (s.selectedIds.isNotEmpty()) s.isMoveMode = true
                                        }
                                        s.activeGroup = null
                                        if (s.selectedIds.isNotEmpty()) s.refreshGroup() else s.collapseGroup()
                                    }
                                    else -> {}
                                } }
                            }
                        .pointerInput(s.tool, s.selBond, s.ringType, s.isFuncGroupDragMode, s.selectedFuncGroup) { detectDragGestures(
                            onDragStart = { pos -> when (s.tool) {
                                DrawTool.ATOM -> { s.pushUndo(); val wp = Offset(pos.x - s.canvasOffsetX, pos.y - s.canvasOffsetY); val hit = hitAtom(s.atoms, wp) ?: findMergeAtom(s.atoms, wp); s.dragSrc = hit?.id; s.isDragging = true; s.dragEnd = wp }
                                DrawTool.BOND -> { 
                                    // 延长模式也走普通BOND拖拽（吸附/合并预览）
                                    s.pushUndo(); val wp = Offset(pos.x - s.canvasOffsetX, pos.y - s.canvasOffsetY); s.bondStart = wp; s.bondCur = wp; s.isBondDragging = true
                                }
                                DrawTool.RING -> { s.pushUndo(); val wp = Offset(pos.x - s.canvasOffsetX, pos.y - s.canvasOffsetY); s.bondStart = wp; s.isBondDragging = true; s.bondCur = wp }
                                DrawTool.SELECT -> {
                                    val wp = Offset(pos.x - s.canvasOffsetX, pos.y - s.canvasOffsetY)
                                    // 先保存起点用于判断点击还是拖拽
                                    s.selTapStart = wp
                                    
                                    if (s.isMoveMode && s.selectedIds.isNotEmpty()) {
                                        // 只有按在选区（包围盒）里才移动；按在空白处继续走框选
                                        val selPts = s.atoms.filter { it.id in s.selectedIds }
                                        val inSel = if (selPts.isNotEmpty()) {
                                            val pad = BOND_LENGTH * 0.4f
                                            val minX = selPts.minOf { it.x } - pad; val maxX = selPts.maxOf { it.x } + pad
                                            val minY = selPts.minOf { it.y } - pad; val maxY = selPts.maxOf { it.y } + pad
                                            wp.x in minX..maxX && wp.y in minY..maxY
                                        } else false
                                        if (inSel) {
                                            s.activeHandle = -1
                                            s.isMovingSelection = true; s.moveStartOffset = wp; s.pushUndo()
                                            s.updateAlignGuides() // 显示辅助线
                                            return@detectDragGestures
                                        }
                                    }
                                    
                                    // 先检测是否在手柄上
                                    val g = s.activeGroup
                                    if (g != null) {
                                        val handles = computeHandles(g, s.atoms, s.annotations).second
                                        val hi = hitHandle(handles, wp)
                                        if (hi >= 0) {
                                            s.activeHandle = hi
                                            s.handleDragBase = wp
                                            s.handleDragPrev = wp
                                            s.pushUndo()
                                            return@detectDragGestures
                                        }
                                        // 检测是否在选框内（在组内拖拽=平移组）
                                        // 使用变换后的坐标判断
                                        val selAtoms = s.atoms.filter { it.id in g.atomIds }
                                        val selAnnBoxes = g.annotationIds.mapNotNull { aid ->
                                            val ann = s.annotations.find { it.id == aid } ?: return@mapNotNull null
                                            if (ann.type == AnnotationType.ARROW) {
                                                listOf(Offset(ann.x, ann.y), Offset(ann.endX, ann.endY))
                                            } else {
                                                listOf(Offset(ann.x, ann.y))
                                            }
                                        }.flatten()
                                        val allTestPoints = selAtoms.map { Offset(it.x, it.y) } + selAnnBoxes
                                        var inside = false
                                        if (allTestPoints.isNotEmpty()) {
                                            val rad = Math.toRadians(g.rotation.toDouble())
                                            val cosR = cos(rad).toFloat(); val sinR = sin(rad).toFloat()
                                            val tx = { p: Offset ->
                                                val lx = (p.x - g.pivotX) * g.scaleX
                                                val ly = (p.y - g.pivotY) * g.scaleY
                                                g.pivotX + (lx * cosR - ly * sinR) + g.translationX
                                            }
                                            val ty = { p: Offset ->
                                                val lx = (p.x - g.pivotX) * g.scaleX
                                                val ly = (p.y - g.pivotY) * g.scaleY
                                                g.pivotY + (lx * sinR + ly * cosR) + g.translationY
                                            }
                                            inside = wp.x >= allTestPoints.minOf { tx(it) } && wp.x <= allTestPoints.maxOf { tx(it) } &&
                                                wp.y >= allTestPoints.minOf { ty(it) } && wp.y <= allTestPoints.maxOf { ty(it) }
                                        }
                                        if (inside) {
                                            s.handleDragPrev = wp
                                            s.activeHandle = -2 // -2=选区内部平移
                                            s.pushUndo()
                                            return@detectDragGestures
                                        }
                                    }
                                    // 检测是否点击到标注
                                    val hitAnn = hitAnnotation(s.annotations, wp)
                                    if (hitAnn != null) {
                                        val annId = -hitAnn.id
                                        s.selectedIds = if (annId in s.selectedIds) s.selectedIds - annId else s.selectedIds + annId
                                        s.isMovingSelection = true; s.moveStartOffset = wp; s.pushUndo()
                                    } else {
                                        // 检测选中原子
                                        val hs = hitAtom(s.atoms.filter { it.id in s.selectedIds }, wp)
                                        if (hs != null) { s.isMovingSelection = true; s.moveStartOffset = wp; s.pushUndo() }
                                        else { s.selRectStart = pos; s.selRectEnd = pos; s.isSelDragging = true }
                                    }
                                }
                                DrawTool.ARROW -> { s.pushUndo(); val wp = Offset(pos.x - s.canvasOffsetX, pos.y - s.canvasOffsetY); s.arrowStart = wp; s.arrowEnd = wp; s.isArrowDragging = true }
                                DrawTool.PAN -> { s.magnifierPos = null; s.selRectStart = null; s.isSelDragging = false; s.isMovingSelection = false }
                                else -> {}
                            }; if (s.tool != DrawTool.SELECT && s.tool != DrawTool.ARROW && s.tool != DrawTool.SCALE) s.magnifierPos = pos },
                            onDrag = { change, _ -> when (s.tool) {
                                DrawTool.ATOM -> { if (s.isDragging) { change.consume(); s.dragEnd = Offset(change.position.x - s.canvasOffsetX, change.position.y - s.canvasOffsetY); s.magnifierPos = change.position; hitAtom(s.atoms, s.dragEnd)?.let { s.dragSrc = it.id } } }
                                DrawTool.BOND -> { if (s.isBondDragging && s.bondStart != null) { change.consume(); s.magnifierPos = change.position; val wp = Offset(change.position.x - s.canvasOffsetX, change.position.y - s.canvasOffsetY); val hit = hitAtom(s.atoms, wp); if (hit != null && s.bondFirstAtom == null) { s.bondFirstAtom = hit.id; s.bondStart = Offset(hit.x, hit.y); s.bondCur = s.bondStart!!; s.bondSnapping = true } else if (s.bondFirstAtom != null) { val src = s.atoms.find { it.id == s.bondFirstAtom }; if (src != null) { val merges = findMergeAtoms(s.atoms, wp).filter { it.id != s.bondFirstAtom }; if (merges.isNotEmpty()) { s.bondCur = Offset(merges.first().x, merges.first().y); s.bondEndMerge = merges; s.bondSnapping = false } else { val rad = Math.toRadians(snapAngle(Math.toDegrees(atan2((wp.y - src.y).toDouble(), (wp.x - src.x).toDouble())).toFloat()).toDouble()); s.bondCur = Offset(src.x + BOND_LENGTH * cos(rad).toFloat(), src.y + BOND_LENGTH * sin(rad).toFloat()); s.bondSnapping = true; s.bondEndMerge = emptyList(); findMergeAtoms(s.atoms, s.bondCur).filter { it.id != s.bondFirstAtom }.takeIf { it.isNotEmpty() }?.let { s.bondCur = Offset(it.first().x, it.first().y); s.bondEndMerge = it } } } else { s.bondCur = wp; s.bondSnapping = false; s.bondEndMerge = emptyList() } } else { val merges = findMergeAtoms(s.atoms, wp); if (merges.isNotEmpty()) { s.bondCur = Offset(merges.first().x, merges.first().y); s.bondEndMerge = merges } else { s.bondCur = wp; s.bondEndMerge = emptyList() }; s.bondSnapping = false } } }
                                DrawTool.RING -> { if (s.isBondDragging && s.bondStart != null) { change.consume(); s.bondCur = Offset(change.position.x - s.canvasOffsetX, change.position.y - s.canvasOffsetY); s.magnifierPos = change.position } }
                                DrawTool.SELECT -> {
                                    val wp = Offset(change.position.x - s.canvasOffsetX, change.position.y - s.canvasOffsetY)
                                    if (s.activeHandle >= 0) {
                                        val g = s.activeGroup
                                        if (g != null) {
                                            // 手柄拖拽
                                            change.consume()
                                            val dx = wp.x - s.handleDragPrev.x
                                            val dy = wp.y - s.handleDragPrev.y
                                            val handles = computeHandles(g, s.atoms, s.annotations).second
                                            val hi = handles.find { it.index == s.activeHandle }
                                            when (hi?.kind) {
                                                HandleKind.CORNER -> {
                                                    // 对角缩放
                                                    val pivot = handles.firstOrNull { it.index == (s.activeHandle + 2) % 4 }?.center ?: Offset(g.pivotX, g.pivotY)
                                                    val oldDist = sqrt((s.handleDragBase.x - pivot.x)*(s.handleDragBase.x - pivot.x) + (s.handleDragBase.y - pivot.y)*(s.handleDragBase.y - pivot.y))
                                                    val newDist = sqrt((wp.x - pivot.x)*(wp.x - pivot.x) + (wp.y - pivot.y)*(wp.y - pivot.y))
                                                    if (oldDist > 1f) {
                                                        val factor = newDist / oldDist
                                                        // 直接缩放选中原子的坐标
                                                        for (aid in g.atomIds) {
                                                            val a = s.atoms.find { it.id == aid } ?: continue
                                                            a.x = pivot.x + (a.x - pivot.x) * factor
                                                            a.y = pivot.y + (a.y - pivot.y) * factor
                                                        }
                                                        // 同步缩放标注的 ann.scale 和位置
                                                        for (aid in g.annotationIds) {
                                                            val ann = s.annotations.find { it.id == aid } ?: continue
                                                            ann.scale *= factor
                                                            ann.x = pivot.x + (ann.x - pivot.x) * factor
                                                            ann.y = pivot.y + (ann.y - pivot.y) * factor
                                                            if (ann.type == AnnotationType.ARROW) {
                                                                ann.endX = pivot.x + (ann.endX - pivot.x) * factor
                                                                ann.endY = pivot.y + (ann.endY - pivot.y) * factor
                                                            }
                                                        }
                                                        // 重置g的变换，因为坐标已经直接改了
                                                        g.scaleX = 1f; g.scaleY = 1f
                                                        s.handleDragBase = wp
                                                        s.groupVersion++ // 强制Canvas重绘
                                                    }
                                                }
                                                HandleKind.EDGE -> {
                                                    // 平移：直接修改选中元素的坐标，不改 g.translationX/Y
                                                    // 这样 computeHandles 会根据新坐标自动计算包围盒
                                                    for (aid in g.atomIds) {
                                                        s.atoms.find { it.id == aid }?.let { it.x += dx; it.y += dy }
                                                    }
                                                    // 同步移动标注
                                                    for (aid in g.annotationIds) {
                                                        val ann = s.annotations.find { it.id == aid } ?: continue
                                                        ann.x += dx; ann.y += dy
                                                        if (ann.type == AnnotationType.ARROW) { ann.endX += dx; ann.endY += dy }
                                                    }
                                                    // 重置 translation，因为坐标已经直接改了
                                                    g.translationX = 0f; g.translationY = 0f
                                                    s.handleDragPrev = wp
                                                    s.groupVersion++ // 强制Canvas重绘
                                                }
                                                HandleKind.ROTATE -> {
                                                    // 旋转：计算所有选中元素（原子+标注）的包围盒中心
                                                    val hasAtoms = g.atomIds.isNotEmpty()
                                                    val hasAnns = g.annotationIds.isNotEmpty()
                                                    val cx: Float; val cy: Float
                                                    if (hasAtoms) {
                                                        val selAtoms = s.atoms.filter { it.id in g.atomIds }
                                                        cx = (selAtoms.minOf { a -> a.x } + selAtoms.maxOf { a -> a.x }) / 2f
                                                        cy = (selAtoms.minOf { a -> a.y } + selAtoms.maxOf { a -> a.y }) / 2f
                                                    } else if (hasAnns) {
                                                        val anns = g.annotationIds.mapNotNull { aid -> s.annotations.find { it.id == aid } }
                                                        val allX = anns.flatMap { if (it.type == AnnotationType.ARROW) listOf(it.x, it.endX) else listOf(it.x) }
                                                        val allY = anns.flatMap { if (it.type == AnnotationType.ARROW) listOf(it.y, it.endY) else listOf(it.y) }
                                                        cx = (allX.min() + allX.max()) / 2f
                                                        cy = (allY.min() + allY.max()) / 2f
                                                    } else { cx = g.pivotX; cy = g.pivotY }
                                                    val prevAngle = Math.toDegrees(atan2((s.handleDragPrev.y - cy).toDouble(), (s.handleDragPrev.x - cx).toDouble())).toFloat()
                                                    val curAngle = Math.toDegrees(atan2((wp.y - cy).toDouble(), (wp.x - cx).toDouble())).toFloat()
                                                    g.rotation += curAngle - prevAngle
                                                    // 同步旋转标注坐标
                                                    val rad = Math.toRadians((curAngle - prevAngle).toDouble())
                                                    val cosR = cos(rad).toFloat(); val sinR = sin(rad).toFloat()
                                                    for (aid in g.atomIds) {
                                                        val a = s.atoms.find { it.id == aid } ?: continue
                                                        val lx = a.x - cx; val ly = a.y - cy
                                                        a.x = cx + (lx * cosR - ly * sinR)
                                                        a.y = cy + (lx * sinR + ly * cosR)
                                                    }
                                                    for (aid in g.annotationIds) {
                                                        val ann = s.annotations.find { it.id == aid } ?: continue
                                                        val lx = ann.x - cx; val ly = ann.y - cy
                                                        ann.x = cx + (lx * cosR - ly * sinR)
                                                        ann.y = cy + (lx * sinR + ly * cosR)
                                                        if (ann.type == AnnotationType.ARROW) {
                                                            val elx = ann.endX - cx; val ely = ann.endY - cy
                                                            ann.endX = cx + (elx * cosR - ely * sinR)
                                                            ann.endY = cy + (elx * sinR + ely * cosR)
                                                        }
                                                    }
                                                    g.rotation = 0f // 重置旋转，因为坐标已经直接改了
                                                    s.groupVersion++ // 强制Canvas重绘
                                                }
                                                else -> {}
                                            }
                                            s.handleDragPrev = wp
                                        }
                                    } else if (s.activeHandle == -2) {
                                        val g = s.activeGroup
                                        if (g != null) {
                                            // 选区内部平移：直接修改坐标，不改 g.translationX/Y
                                            change.consume()
                                            val dx = wp.x - s.handleDragPrev.x
                                            val dy = wp.y - s.handleDragPrev.y
                                            for (aid in g.atomIds) {
                                                s.atoms.find { it.id == aid }?.let { it.x += dx; it.y += dy }
                                            }
                                            for (aid in g.annotationIds) {
                                                val ann = s.annotations.find { it.id == aid } ?: continue
                                                ann.x += dx; ann.y += dy
                                                if (ann.type == AnnotationType.ARROW) { ann.endX += dx; ann.endY += dy }
                                            }
                                            g.translationX = 0f; g.translationY = 0f
                                            s.handleDragPrev = wp
                                            s.groupVersion++ // 强制Canvas重绘
                                        }
                                    } else if (s.isMovingSelection) {
change.consume()
// 仅在拖拽位置附近有非选中原子时显示放大镜
val nearAtom = s.atoms.any { a -> a.id !in s.selectedIds && sqrt((a.x - wp.x)*(a.x - wp.x) + (a.y - wp.y)*(a.y - wp.y)) < 60f }
s.magnifierPos = if (nearAtom) change.position else null
val dx = wp.x - s.moveStartOffset.x; val dy = wp.y - s.moveStartOffset.y
                                        for (aid in s.selectedIds) {
                                            if (aid < 0) {
                                                // 负ID：标注
                                                val ann = s.annotations.find { it.id == -aid }
                                                ann?.let { it.x += dx; it.y += dy; if (it.type == AnnotationType.ARROW) { it.endX += dx; it.endY += dy } }
                                            } else {
                                                s.atoms.find { it.id == aid }?.let { it.x += dx; it.y += dy }
                                            }
                                        }
                                        s.moveStartOffset = wp
                        s.updateAlignGuides() // 辅助线实时跟随
                        s.groupVersion++ // 强制Canvas重绘
                    } else if (s.isSelDragging && s.selRectStart != null) {
                                        change.consume(); s.selRectEnd = change.position; s.updateSelection()
                                    }
                                }
                                DrawTool.ARROW -> { if (s.isArrowDragging && s.arrowStart != null) { change.consume(); s.arrowEnd = Offset(change.position.x - s.canvasOffsetX, change.position.y - s.canvasOffsetY) } }
                                DrawTool.PAN -> { change.consume(); s.canvasOffsetX += change.position.x - change.previousPosition.x; s.canvasOffsetY += change.position.y - change.previousPosition.y }
                                else -> {}
                            } },
                            onDragEnd = { when (s.tool) {
                                DrawTool.ATOM -> {
        if (s.isFuncGroupDragMode && s.selectedFuncGroup != null && s.dragSrc != null) {
            s.atoms.find { it.id == s.dragSrc }?.let { target ->
                applyFunctionalGroupAt(s, s.selectedFuncGroup!!, target.x, target.y, target)
            }
        }
        s.isFuncGroupDragMode = false; s.selectedFuncGroup = null; s.isDragging = false; s.dragSrc = null; s.magnifierPos = null
    } else if (s.isDragging) {
        if (s.dragSrc != null) s.atoms.find { it.id == s.dragSrc }?.let { it.element = s.selElem }; s.isDragging = false; s.dragSrc = null; s.magnifierPos = null
    } else { s.magnifierPos = null }
}
                                DrawTool.BOND -> { 
                                    if (s.extendMode && s.bondFirstAtom != null) {
                                        // 延长模式松手：从吸附的第一个原子延长
                                        val srcAtom = s.atoms.find { it.id == s.bondFirstAtom }
                                        if (srcAtom != null) {
                                            s.extendStart = Offset(srcAtom.x, srcAtom.y)
                                            s.extendInput = "1"
                                            s.showExtendDialog = true
                                        }
                                        s.isBondDragging = false; s.bondStart = null; s.bondFirstAtom = null; s.magnifierPos = null; s.bondSnapping = false; s.bondEndMerge = emptyList()
                                    } else if (s.isBondDragging && s.bondStart != null) { if (s.bondFirstAtom != null) { s.atoms.find { it.id == s.bondFirstAtom }?.let { sa -> hitAtom(s.atoms, s.bondCur)?.let { eh -> val eb = s.bonds.find { (it.atom1 == sa.id && it.atom2 == eh.id) || (it.atom1 == eh.id && it.atom2 == sa.id) }; if (eb != null) s.bonds[s.bonds.indexOfFirst { it.id == eb.id }] = eb.copy(type = when (eb.type) { BondType.SINGLE -> BondType.DOUBLE; BondType.DOUBLE -> BondType.TRIPLE; BondType.TRIPLE -> BondType.SINGLE; else -> BondType.DOUBLE }) else if (sa.id != eh.id) s.bonds.add(MoleculeBond(nextId(), sa.id, eh.id, s.selBond)) } ?: run { findMergeAtom(s.atoms, s.bondCur)?.let { em -> if (sa.id != em.id) s.bonds.add(MoleculeBond(nextId(), sa.id, em.id, s.selBond)) } ?: run { val na = MoleculeAtom(nextId(), s.bondCur.x, s.bondCur.y, Element.C); s.atoms.add(na); s.bonds.add(MoleculeBond(nextId(), sa.id, na.id, s.selBond)) } } } } else createFreeBond(s.atoms, s.bonds, s.bondStart!!, s.bondCur, s.selBond); s.isBondDragging = false; s.bondStart = null; s.bondFirstAtom = null }; s.magnifierPos = null; s.bondSnapping = false; s.bondEndMerge = emptyList() }
                                DrawTool.RING -> { if (s.isBondDragging && s.bondStart != null) { placeRing(s.atoms, s.bonds, s.bondCur.x, s.bondCur.y, s.ringType.n, s.ringType.benzene, benzeneStyle = s.benzeneStyle); s.isBondDragging = false; s.bondStart = null; s.magnifierPos = Offset(s.bondCur.x + s.canvasOffsetX, s.bondCur.y + s.canvasOffsetY); MainScope().launch { delay(600); s.magnifierPos = null } } else s.magnifierPos = null }
                                DrawTool.SELECT -> {
                                    // 框选结束时确保选中状态保留
                                    if (s.isSelDragging && s.selRectStart != null) {
                                        s.updateSelection()
                                    }
                                    s.isMovingSelection = false; s.isSelDragging = false
s.showAlignGuides = false
s.activeHandle = -1
// 组合模式拖拽结束后延迟关闭放大镜
s.magnifierPos?.let { MainScope().launch { delay(300); s.magnifierPos = null } }
                                    // 检测是否为点击：selRectStart和selRectEnd都很接近（屏幕坐标比较）
                                    val rectStart = s.selRectStart
                                    if (rectStart != null) {
                                        val rectEnd = s.selRectEnd
                                        val tapDx = abs(rectStart.x - rectEnd.x)
                                        val tapDy = abs(rectStart.y - rectEnd.y)
                                        if (tapDx < 6f && tapDy < 6f) {
                                            // 点击行为：检测标注或原子（不取消选中）
                                            val tapWp = Offset(rectStart.x - s.canvasOffsetX, rectStart.y - s.canvasOffsetY)
                                            val hitAnn = hitAnnotation(s.annotations, tapWp)
                                            val tappedAtom = if (hitAnn == null) hitAtom(s.atoms, tapWp) else null
                                            when {
                                                hitAnn != null -> {
                                                    val annId = -hitAnn.id
                                                    s.selectedIds = if (annId in s.selectedIds) s.selectedIds - annId else s.selectedIds + annId
                                                }
                                                tappedAtom != null -> {
                                                    // 点中任意一个原子 → 选中它所在的整个（连通）分子；再次点击同一分子则取消
                                                    val comp = connectedAtomIds(s.atoms, s.bonds, tappedAtom.id)
                                                    s.selectedIds = if (comp == s.selectedIds) emptySet() else comp
                                                }
                                                else -> {
                                                    // 点空白 → 取消选择
                                                    s.selectedIds = emptySet(); s.collapseGroup(); s.activeGroup = null
                                                }
                                            }
                                        }
                                    }
                                    // 框选结束后刷新变换组
                                    if (s.selectedIds.size >= 1) s.refreshGroup()
                                    else {
                                        s.collapseGroup()
                                        s.activeGroup = null
                                    }
                                    s.selRectStart = null; s.selTapStart = null
                                }
                                DrawTool.ARROW -> { if (s.isArrowDragging && s.arrowStart != null) { if (sqrt((s.arrowEnd.x - s.arrowStart!!.x).let { it*it } + (s.arrowEnd.y - s.arrowStart!!.y).let { it*it }) > 10f) s.annotations.add(MoleculeAnnotation(nextId(), AnnotationType.ARROW, s.arrowStart!!.x, s.arrowStart!!.y, endX = s.arrowEnd.x, endY = s.arrowEnd.y)); s.isArrowDragging = false; s.arrowStart = null }; s.magnifierPos = null }
                                else -> s.magnifierPos = null
                            } },
                            onDragCancel = { s.magnifierPos = null; s.isDragging = false; s.dragSrc = null; s.isBondDragging = false; s.bondStart = null; s.bondSnapping = false; s.bondEndMerge = emptyList(); s.isSelDragging = false; s.selRectStart = null; s.isMovingSelection = false; s.arrowStart = null; s.isArrowDragging = false; s.activeHandle = -1 }
                        ) }
                    ) {
                        val tx = { x: Float -> x + s.canvasOffsetX }; val ty = { y: Float -> y + s.canvasOffsetY }
                        val _gv = s.groupVersion // 读取版本号，groupVersion变化时强制Canvas重绘
                        drawCanvasContent(atoms = s.atoms, bonds = s.bonds, annotations = s.annotations, tool = s.tool, ringType = s.ringType, selectedAtom = s.selectedAtom, selectedIds = s.selectedIds,
                            isDragging = s.isDragging, dragSrc = s.dragSrc, dragEnd = s.dragEnd, isBondDragging = s.isBondDragging, bondStart = s.bondStart, bondCur = s.bondCur,
                            bondFirstAtom = s.bondFirstAtom, bondSnapping = s.bondSnapping, bondEndMerge = s.bondEndMerge, isSelDragging = s.isSelDragging,
                            selRectStart = s.selRectStart, selRectEnd = s.selRectEnd, isArrowDragging = s.isArrowDragging, arrowEnd = s.arrowEnd, arrowStart = s.arrowStart,
isScaleDragging = s.isScaleDragging, selBond = s.selBond, benzeneStyle = s.benzeneStyle, activeGroup = s.activeGroup,
showAlignGuides = s.showAlignGuides, alignGuideX = s.alignGuideX, alignGuideY = s.alignGuideY,
tx = tx, ty = ty)
                        }
                    // 空画布引导：放在 Canvas 之上，按钮才不会被画布的 pointerInput 吃掉点击
                    if (s.atoms.isEmpty() && s.annotations.isEmpty()) {
                        Column(
                            Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Science, null, tint = Color(0xFFCFD8DC), modifier = Modifier.size(44.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("点按画布放置原子", fontSize = 13.sp, color = Color(0xFF78909C))
                            Spacer(Modifier.height(4.dp))
                            Text("从原子上拖拽可连键 · 选环工具可直接画环", fontSize = 11.sp, color = Color(0xFF90A4AE))
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(
                                onClick = { s.showImportDialog = true; s.importInput = "" },
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                                modifier = Modifier.height(40.dp)
                            ) {
                                Icon(Icons.Default.FileOpen, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("导入 SMILES / Molfile", fontSize = 12.sp)
                            }
                        }
                    }
                        MoleculeDrawMagnifier(magnifierPos = s.magnifierPos, state = s)
                }

                // 底部状态栏：常驻当前工具提示（原来每种工具各占一整行说明），有选区时同时给出 SMILES
                val selAtoms = s.atoms.filter { it.id in s.selectedIds }
                val selSet = selAtoms.map { it.id }.toSet()
                val selBonds = s.bonds.filter { it.atom1 in selSet && it.atom2 in selSet }
                val selSmiles = if (selAtoms.isNotEmpty() && layoutEngine is IndigoNativeLayoutEngineAdapter)
                    layoutEngine.generateSmiles(selAtoms, selBonds, s.annotations) ?: "" else ""
                Surface(Modifier.fillMaxWidth(), color = Color(0xFFF7F7F8), shadowElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
                        if (selAtoms.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (selSmiles.isEmpty()) "(选中 ${s.selectedIds.size} 个原子)" else selSmiles,
                                    fontSize = 11.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = Color(0xFF37474F),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                            .setPrimaryClip(ClipData.newPlainText("smiles", selSmiles))
                                        android.widget.Toast.makeText(context, "SMILES 已复制", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(30.dp)
                                ) { Icon(Icons.Default.ContentCopy, "复制 SMILES", tint = Color(0xFF546E7A), modifier = Modifier.size(15.dp)) }
                            }
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                toolHint(s.tool),
                                fontSize = 10.sp,
                                color = Color(0xFF607D8B),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (selAtoms.isNotEmpty()) Text("${s.selectedIds.size} 个原子", fontSize = 10.sp, color = Color(0xFF2E7D32))
                        }
                    }
                }
            }
            // 横屏：左侧竖排工具轨（覆盖在内容左侧，内容已留出 168dp）
            if (landscape) {
                Surface(Modifier.width(136.dp).fillMaxHeight(), color = Color.White, shadowElevation = 4.dp) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Text(
                            "分子编辑器", fontSize = 11.sp, color = Color(0xFF546E7A),
                            modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 4.dp)
                        )
                        FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 4) { topActions() }
                        HorizontalDivider(color = Color(0xFFE6E6E6))
                        MoleculeToolRail(state = s, layoutEngine = layoutEngine, vertical = true)
                    }
                }
            }
        }
    }
}

/** 顶栏动作：图标 + 小字标签，避免 6 个纯图标挤在一起时认不出“导入/导出” */
@Composable
private fun TopAction(
    label: String,
    enabled: Boolean = true,
    compact: Boolean = false,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Column(
        Modifier
            .width(if (compact) 32.dp else 46.dp)
            .height(if (compact) 34.dp else 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalContentColor provides (if (enabled) Color(0xFF444444) else Color(0xFFCCCCCC))
        ) { icon() }
        if (!compact) Text(label, fontSize = 9.sp, color = if (enabled) Color(0xFF444444) else Color(0xFFCCCCCC), maxLines = 1)
    }
}

/**
 * 把官能团挂到 anchor 上（anchor 为 null 时就在 (x,y) 新建）。
 *
 * 语义统一成“挂上去”而不是“替换”：
 *  - 新建一个连接点原子（如 NO₂ 的 N、CH₃ 的 C），用单键连到 anchor；
 *  - 展开的非氢成员原子围绕连接点排开，角度避开 anchor 已有的键；
 *  - 连接点与成员都打上 funGroupLabel（渲染时隐藏），另加一条 FUNC_GROUP 标注显示缩写。
 * 这样环碳上挂 COOH / CH₃ / NO₂ 都不会破坏环，自动调整后拓扑也能匹配回来。
 */
internal fun applyFunctionalGroupAt(
    s: MoleculeDrawState,
    fg: FunctionalGroup,
    x: Float, y: Float,
    anchor: MoleculeAtom?
) {
    // ── 1. 先清掉 anchor 上原有的官能团（连接点 + 组内成员 + 标注），避免隐形残留 ──
    if (anchor != null) {
        val oldConn = s.bonds
            .filter { it.atom1 == anchor.id || it.atom2 == anchor.id }
            .mapNotNull { b -> s.atoms.find { it.id == if (b.atom1 == anchor.id) b.atom2 else b.atom1 } }
            .firstOrNull { it.isFunGroupConnector && it.funGroupLabel != null }
        if (oldConn != null) {
            val label = oldConn.funGroupLabel!!
            val groupIds = mutableSetOf(oldConn.id)
            for (b in s.bonds) {
                if (b.atom1 != oldConn.id && b.atom2 != oldConn.id) continue
                val oid = if (b.atom1 == oldConn.id) b.atom2 else b.atom1
                val other = s.atoms.find { it.id == oid }
                if (other != null && other.funGroupLabel == label) groupIds.add(oid)
            }
            s.atoms.removeAll { it.id in groupIds }
            s.bonds.removeAll { it.atom1 in groupIds || it.atom2 in groupIds }
            s.annotations.removeAll {
                it.type == AnnotationType.FUNC_GROUP && it.text == label &&
                    abs(it.x - oldConn.x) < 5f && abs(it.y - oldConn.y) < 5f
            }
        }
    }

    // ── 2. 摆放连接点：从 anchor 已有的键的反方向伸出去，避免和现有键重叠 ──
    fun dirsFromAtom(a: MoleculeAtom): List<Float> =
        s.bonds.filter { it.atom1 == a.id || it.atom2 == a.id }.mapNotNull { b ->
            val oid = if (b.atom1 == a.id) b.atom2 else b.atom1
            val o = s.atoms.find { it.id == oid } ?: return@mapNotNull null
            Math.toDegrees(atan2((o.y - a.y).toDouble(), (o.x - a.x).toDouble())).toFloat()
        }
    val baseAngle: Float = if (anchor == null) 0f else {
        val dirs = dirsFromAtom(anchor)
        if (dirs.isEmpty()) 0f else {
            val sx = dirs.sumOf { cos(Math.toRadians(it.toDouble())) }
            val sy = dirs.sumOf { sin(Math.toRadians(it.toDouble())) }
            if (abs(sx) < 1e-4 && abs(sy) < 1e-4) 0f
            else Math.toDegrees(atan2(-sy, -sx)).toFloat()
        }
    }
    val connElem = Element.fromSymbol(fg.expandAtoms[fg.connectIndex].first)
    val cx: Float; val cy: Float
    if (anchor == null) {
        cx = x; cy = y
    } else {
        val rad = Math.toRadians(baseAngle.toDouble())
        cx = anchor.x + BOND_LENGTH * cos(rad).toFloat()
        cy = anchor.y + BOND_LENGTH * sin(rad).toFloat()
    }
    val connAtom = MoleculeAtom(nextId(), cx, cy, connElem,
        funGroupLabel = fg.label, isFunGroupConnector = true)
    s.atoms.add(connAtom)
    if (anchor != null) s.bonds.add(MoleculeBond(nextId(), anchor.id, connAtom.id, BondType.SINGLE))

    // ── 3. 展开的非氢成员原子，围绕连接点均匀铺开 ──
    val members = fg.expandAtoms
        .filterIndexed { i, _ -> i != fg.connectIndex }
        .filter { Element.fromSymbol(it.first) != Element.H }
    val n = members.size
    if (n > 0) {
        val spacing = if (n <= 2) 120f else 360f / n
        val startAngle = baseAngle - spacing * (n - 1) / 2f
        members.forEachIndexed { idx, pair ->
            val angle = startAngle + spacing * idx
            val rad = Math.toRadians(angle.toDouble())
            val mAtom = MoleculeAtom(
                nextId(),
                cx + BOND_LENGTH * cos(rad).toFloat(),
                cy + BOND_LENGTH * sin(rad).toFloat(),
                Element.fromSymbol(pair.first),
                funGroupLabel = fg.label
            )
            s.atoms.add(mAtom)
            s.bonds.add(MoleculeBond(nextId(), connAtom.id, mAtom.id, pair.second))
        }
    }

    // ── 4. 缩写标注落在连接点上 ──
    s.annotations.add(MoleculeAnnotation(nextId(), AnnotationType.FUNC_GROUP, cx, cy, text = fg.label))
}

/** 与 atomId 连通的全部原子 id（用于“点一下选中整个分子”） */
private fun connectedAtomIds(atoms: List<MoleculeAtom>, bonds: List<MoleculeBond>, atomId: Int): Set<Int> {
    val adj = HashMap<Int, MutableList<Int>>()
    for (b in bonds) {
        adj.getOrPut(b.atom1) { mutableListOf() }.add(b.atom2)
        adj.getOrPut(b.atom2) { mutableListOf() }.add(b.atom1)
    }
    val seen = mutableSetOf(atomId)
    val stack = ArrayDeque<Int>()
    stack.add(atomId)
    while (stack.isNotEmpty()) {
        val cur = stack.removeLast()
        adj[cur]?.forEach { n -> if (seen.add(n)) stack.add(n) }
    }
    return seen
}

/** 逐分子（连通片段）计算分子式与分子量，key 为排序后的原子 id 串 */
private fun computeMwLabels(
    atoms: List<MoleculeAtom>,
    bonds: List<MoleculeBond>,
    layoutEngine: MoleculeLayoutEngine?
): Map<String, String> {
    val engine = layoutEngine as? IndigoNativeLayoutEngineAdapter ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    val visited = mutableSetOf<Int>()
    for (a in atoms) {
        if (a.funGroupLabel != null || !visited.add(a.id)) continue
        val comp = connectedAtomIds(atoms, bonds, a.id)
        visited.addAll(comp)
        val compAtoms = atoms.filter { it.id in comp }
        val compBonds = bonds.filter { it.atom1 in comp && it.atom2 in comp }
        // 孤立原子（没有任何键）不算一个分子：既不该标分子量，也不该被当成 CH4
        if (compBonds.isEmpty()) continue
        val smiles = runCatching { engine.generateSmiles(compAtoms, compBonds) }.getOrNull().orEmpty()
        if (smiles.isBlank()) continue
        val formula = runCatching { IndigoNative.grossFormula(smiles) }.getOrDefault("")
        val mw = runCatching { IndigoNative.molecularWeight(smiles) }.getOrDefault(0.0)
        if (mw <= 0.0) continue
        val text = if (formula.isBlank()) "MW %.2f".format(mw) else "%s   MW %.2f".format(formula, mw)
        out[comp.sorted().joinToString(",")] = text
    }
    return out
}
