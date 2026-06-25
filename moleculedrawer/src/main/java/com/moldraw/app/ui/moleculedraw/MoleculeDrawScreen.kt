package com.moldraw.app.ui.moleculedraw

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kotlin.math.*
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MoleculeDrawActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SMILES = "extra_smiles"; const val EXTRA_MOL = "extra_mol"; const val EXTRA_JSON = "extra_json"
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_FILE_URI = "extra_file_uri"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

@OptIn(ExperimentalMaterial3Api::class)
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
                    cursor?.use { if (it.moveToFirst()) { val name = it.getString(0); s.saveFileName = name } }
                } catch (_: Exception) {}
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

Scaffold(topBar = {
        TopAppBar(title = { Text("分子结构绘制器", fontSize = 16.sp) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF5F5F5)), actions = {
            IconButton(onClick = { s.tool = DrawTool.PAN; s.bondStart = null; s.isBondDragging = false; s.selectedAtom = null; s.magnifierPos = null; s.bondFirstAtom = null; s.selRectStart = null; s.isSelDragging = false }) { Icon(Icons.Default.PanTool, "平移", tint = if (s.tool == DrawTool.PAN) Color(0xFF1976D2) else Color(0xFF555555)) }
            IconButton(onClick = { s.doUndo() }, enabled = s.undoCount > 0) { Icon(Icons.Default.Undo, "撤销", tint = if (s.undoCount > 0) Color(0xFF333333) else Color(0xFFCCCCCC)) }
            if (s.atoms.isNotEmpty()) IconButton(onClick = { s.pushUndo(); s.atoms.clear(); s.bonds.clear(); s.annotations.clear(); s.selectedIds = emptySet() }) { Icon(Icons.Default.DeleteSweep, "清除", tint = Color(0xFF555555)) }
            IconButton(onClick = { s.showImportDialog = true; s.importInput = "" }) { Icon(Icons.Default.FileOpen, "导入 SMILES / Mol", tint = Color(0xFF555555)) }
            IconButton(onClick = { s.showExportDialog = true }) { Icon(Icons.Default.Share, "导出", tint = Color(0xFF555555)) }
            // 保存项目（.moldraw 格式，可再次编辑）
            IconButton(onClick = {
                android.util.Log.d("MOLDRAW_DEBUG", "=== SAVE CLICKED ===")
                android.util.Log.d("MOLDRAW_DEBUG", "atoms=${s.atoms.size} bonds=${s.bonds.size} annotations=${s.annotations.size}")
                var json = ""
                try {
                json = buildWorkspaceJson(s.atoms.toList(), s.bonds.toList(), s.annotations.toList(), BOND_LENGTH, s.benzeneStyle, layoutEngine)
                s.pendingSaveJson = json
                android.util.Log.d("MOLDRAW_DEBUG", "buildWorkspaceJson OK, json len=${json.length}")
                } catch (e: Exception) {
                    android.util.Log.e("MOLDRAW_DEBUG", "buildWorkspaceJson threw at save site: ${e::class.simpleName}: ${e.message}")
                    android.util.Log.e("MOLDRAW_DEBUG", "stack: ${e.stackTraceToString().take(500)}")
                }
                if (currentFileUri.isNotEmpty()) {
                    // 有已有文件 URI → 直接通过 ContentResolver 覆盖保存
                    try {
                        val uri = Uri.parse(currentFileUri)
                        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { w -> if (json.isNotEmpty()) w.write(json) }
                        android.widget.Toast.makeText(context, "已保存", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, "保存失败: ${e.localizedMessage ?: "未知错误"}", android.widget.Toast.LENGTH_LONG).show()
                    }
                } else {
                    // 无已有文件 → 弹对话框输入文件名，再通过 SAF CreateDocument 选位置
                    s.showSaveDialog = true
                }
            }) { Icon(Icons.Default.Save, "保存项目", tint = Color(0xFF555555)) }
            // 打开项目（.moldraw 文件）
            IconButton(onClick = { openFileLauncher.launch(arrayOf("application/octet-stream", "*/*")) }) { Icon(Icons.Default.FolderOpen, "打开项目", tint = Color(0xFF555555)) }
        })
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).background(Color(0xFFFAFAFA))) {
            Column(Modifier.fillMaxSize()) {
                MoleculeDrawToolbar(state = s, layoutEngine = layoutEngine)

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Canvas(Modifier.fillMaxSize().onSizeChanged { s.canvasSizePx = it.toSize(); s.canvasSize.value = Offset(it.width.toFloat(), it.height.toFloat()) }
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
                            DrawTool.ATOM -> { s.pushUndo(); val wp = Offset(pos.x - s.canvasOffsetX, pos.y - s.canvasOffsetY); hitAtom(s.atoms, wp)?.let { hit ->
                                val existing = s.bonds.filter { b -> b.atom1 == hit.id || b.atom2 == hit.id }; val angle = if (existing.isNotEmpty()) { val d = existing.firstNotNullOfOrNull { b -> val o = if (b.atom1 == hit.id) s.atoms.find { it.id == b.atom2 } else s.atoms.find { it.id == b.atom1 }; o?.let { atan2(it.y - hit.y, it.x - hit.x) } }; snapAngle(Math.toDegrees((d ?: 0.0).toDouble()).toFloat() + 120f) } else 0f; val rad = Math.toRadians(angle.toDouble()); s.atoms.add(MoleculeAtom(nextId(), hit.x + BOND_LENGTH * cos(rad).toFloat(), hit.y + BOND_LENGTH * sin(rad).toFloat(), s.selElem)); val lid = s.atoms.last().id; if (!s.bonds.any { (it.atom1 == hit.id && it.atom2 == lid) || (it.atom1 == lid && it.atom2 == hit.id) }) s.bonds.add(MoleculeBond(nextId(), hit.id, lid))
                            } ?: run { val m = findMergeAtom(s.atoms, wp); if (m != null) m.element = s.selElem else s.atoms.add(MoleculeAtom(nextId(), wp.x, wp.y, s.selElem)) } }
                            DrawTool.RING -> { s.pushUndo(); s.magnifierPos = pos; placeRing(s.atoms, s.bonds, pos.x - s.canvasOffsetX, pos.y - s.canvasOffsetY, s.ringType.n, s.ringType.benzene, benzeneStyle = s.benzeneStyle); MainScope().launch { delay(600); s.magnifierPos = null } }
                            DrawTool.BOND -> {
                                if (s.extendMode) {
                                    // 延长模式点击：切换到普通BOND的点击行为，让拖拽来处理
                                    // 点击不做任何事（拖拽吸附才是主要交互）
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
                                        // 移动模式：直接拖拽移动选中元素
                                        s.activeHandle = -1
                                        s.isMovingSelection = true; s.moveStartOffset = wp; s.pushUndo()
                                        s.updateAlignGuides() // 显示辅助线
                                        return@detectDragGestures
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
        val targetAtom = s.atoms.find { it.id == s.dragSrc }
        if (targetAtom != null) {
            val fg = s.selectedFuncGroup!!
            val tx = targetAtom.x; val ty = targetAtom.y
            // 清除目标位置的旧 FUNC_GROUP 标注（避免重叠残留），保留其他位置的标注
            s.annotations.removeAll { it.type == AnnotationType.FUNC_GROUP && abs(it.x - tx) < 5f && abs(it.y - ty) < 5f }
            // 删除目标位置旧官能团的展开原子（若之前已有官能团标注，删除残留的组内原子）
            val oldConnElems = s.annotations.filter { it.type == AnnotationType.FUNC_GROUP && abs(it.x - tx) < 5f && abs(it.y - ty) < 5f }
                .mapNotNull { ann -> FUNCTIONAL_GROUPS.firstOrNull { it.label == ann.text } }
                .flatMap { fgDef -> fgDef.expandAtoms.map { Element.fromSymbol(it.first) } }.toSet()
            if (oldConnElems.isNotEmpty()) {
                val oldGroupAtoms = s.atoms.filter { it.funGroupLabel != null }.toList()
                s.atoms.removeAll { it.id in oldGroupAtoms.map { it.id } }
                s.bonds.removeAll { b -> oldGroupAtoms.any { it.id == b.atom1 || it.id == b.atom2 } }
            }
            // 记录邻居
            val neighborIds = s.bonds
                .filter { it.atom1 == targetAtom.id || it.atom2 == targetAtom.id }
                .map { if (it.atom1 == targetAtom.id) it.atom2 else it.atom1 }
                .toList()
            // 删除目标原子及其键
            s.atoms.remove(targetAtom)
            s.bonds.removeAll { it.atom1 == targetAtom.id || it.atom2 == targetAtom.id }

            // --- 创建官能团所有原子（连接点 + 展开的非H重原子）---
            val connElem = Element.fromSymbol(fg.expandAtoms[fg.connectIndex].first)
            val connAtom = MoleculeAtom(nextId(), tx, ty, connElem,
                funGroupLabel = fg.label, isFunGroupConnector = true)
            s.atoms.add(connAtom)
            // 创建展开的非H重原子（如 NO₂ 的 O 原子）
            val nonConnectList = fg.expandAtoms.filterIndexed { i, _ -> i != fg.connectIndex }
            var newIdx = 0
            val memberIds = mutableListOf<Int>()
            for ((j, pair) in nonConnectList.withIndex()) {
                val (elemSym, bondType) = pair
                val elem = Element.fromSymbol(elemSym)
                if (elem == Element.H) continue // 跳过 H（隐式氢）
                // 计算展开原子位置：围绕连接点均匀分布
                val nonHCount = nonConnectList.filter { Element.fromSymbol(it.first) != Element.H }.size.coerceAtLeast(1)
                val angle = newIdx * (360.0 / nonHCount)
                val rad = Math.toRadians(angle)
                val offset = BOND_LENGTH * 0.6f
                val ax = tx + offset * cos(rad).toFloat()
                val ay = ty + offset * sin(rad).toFloat()
                val memberAtom = MoleculeAtom(nextId(), ax, ay, elem,
                    funGroupLabel = fg.label, isFunGroupConnector = false)
                s.atoms.add(memberAtom)
                memberIds.add(memberAtom.id)
                // 连接点 ←→ 展开原子 键
                s.bonds.add(MoleculeBond(nextId(), connAtom.id, memberAtom.id, bondType))
                newIdx++
            }
            // 重连邻居到连接点原子
            for (nid in neighborIds) {
                if (s.atoms.any { it.id == nid }) {
                    s.bonds.add(MoleculeBond(nextId(), connAtom.id, nid, BondType.SINGLE))
                }
            }
            // 在原位置加缩写标注（用于显示文字）
            s.annotations.add(MoleculeAnnotation(nextId(), AnnotationType.FUNC_GROUP, tx, ty, text = fg.label))
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
                                            if (hitAnn != null) {
                                                val annId = -hitAnn.id
                                                s.selectedIds = if (annId in s.selectedIds) s.selectedIds - annId else s.selectedIds + annId
                                            } else {
                                                hitAtom(s.atoms, tapWp)?.let { a ->
                                                    s.selectedIds = if (a.id in s.selectedIds) s.selectedIds - a.id else s.selectedIds + a.id
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
                        MoleculeDrawMagnifier(magnifierPos = s.magnifierPos, state = s)
                }

                // 底部 SMILES 栏
                if (s.selectedIds.isNotEmpty()) {
                    val selectedAtoms = s.atoms.filter { it.id in s.selectedIds }
                    if (selectedAtoms.isNotEmpty()) {
                        val set = selectedAtoms.map { it.id }.toSet()
                        val selectedBonds = s.bonds.filter { it.atom1 in set && it.atom2 in set }
                        val smiles = if (layoutEngine is IndigoNativeLayoutEngineAdapter) (layoutEngine).generateSmiles(selectedAtoms, selectedBonds, s.annotations) ?: "" else ""
                        Surface(Modifier.fillMaxWidth(), color = Color(0xFFEEEEEE), shadowElevation = 2.dp) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(text = if (smiles.isEmpty()) "(选中 ${s.selectedIds.size} 个原子)" else smiles, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = Color(0xFF333333), modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                IconButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("smiles", smiles)) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.ContentCopy, "复制", tint = Color(0xFF555555), modifier = Modifier.size(16.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
