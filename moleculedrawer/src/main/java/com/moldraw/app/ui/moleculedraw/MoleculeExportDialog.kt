package com.moldraw.app.ui.moleculedraw

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moldraw.app.indigo_native.IndigoNative
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 分子信息导出/计算/查询对话框。
 *
 * @param atoms 当前分子原子列表
 * @param bonds 当前分子键列表
 * @param layoutEngine 布局引擎（用于生成 SMILES）
 * @param showExportDialog 控制对话框显示
 * @param onDismiss 关闭回调
 */
@Composable
fun MoleculeExportDialog(
    atoms: List<MoleculeAtom>,
    bonds: List<MoleculeBond>,
    annotations: List<MoleculeAnnotation> = emptyList(),
    layoutEngine: Any?,
    showExportDialog: Boolean,
    onDismiss: () -> Unit,
    benzeneStyle: BenzeneStyle = BenzeneStyle.KEKULE
) {
    if (!showExportDialog) return

    val context = LocalContext.current
    val atomsSize = atoms.size
    val bondsSize = bonds.size
    val smiles = remember(atomsSize, bondsSize, annotations.hashCode()) {
        if (atoms.isNotEmpty() && layoutEngine is IndigoNativeLayoutEngineAdapter) {
            // Indigo 原生支持 V3000 SGROUP，直接传标注即可生成正确 SMILES
            layoutEngine.generateSmiles(atoms, bonds, annotations) ?: ""
        } else ""
    }
    val d = remember(atomsSize, bondsSize) { MoleculeData(atoms.toList(), bonds.toList(), annotations.toList(), benzeneStyle = benzeneStyle) }
    val molStr = remember(d) { d.toMol() }
    val svgStr = remember(d) { d.toSvg() }

    // ── 分子计算 ──
    val molFormula = remember(atoms, bonds, smiles) {
        if (atoms.isNotEmpty()) {
            // 优先用 Indigo 计算（含隐式氢），回退到手算
            if (smiles.isNotBlank()) {
                try {
                    val f = IndigoNative.grossFormula(smiles)
                    if (f.isNotBlank()) f else calcMolecularFormula(atoms, bonds)
                } catch (e: Exception) {
                    calcMolecularFormula(atoms, bonds)
                }
            } else {
                calcMolecularFormula(atoms, bonds)
            }
        } else ""
    }
    val molWeight = remember(atoms, bonds, smiles) {
        if (atoms.isNotEmpty()) {
            try {
                // 优先用 Indigo 计算分子量（含隐式氢）
                if (smiles.isNotBlank()) {
                    val w = IndigoNative.molecularWeight(smiles)
                    if (w > 0) "%.2f g/mol".format(w) else "%.2f g/mol".format(calcMolecularWeight(atoms, bonds))
                } else {
                    "%.2f g/mol".format(calcMolecularWeight(atoms, bonds))
                }
            } catch (e: Exception) {
                "%.2f g/mol".format(calcMolecularWeight(atoms, bonds))
            }
        } else ""
    }
    val rotatableBonds = remember(atoms, bonds) { countRotatableBonds(atoms, bonds) }
    val hAcceptors = remember(atoms, bonds) { countHBA(atoms, bonds) }
    val hDonors = remember(atoms, bonds) { countHBD(atoms, bonds) }

    // ── PubChem 查询 ──
    var pubChemResult by remember { mutableStateOf<PubChemResult?>(null) }
    var querying by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分子信息", fontWeight = FontWeight.Medium) },
        text = {
            Column(
                Modifier
                    .widthIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // ═══ 分子计算 ═══
                Text("性质", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                PropertyRow("分子式", molFormula)
                PropertyRow("分子量", molWeight)
                PropertyRow("可旋转键", rotatableBonds.toString())
                PropertyRow("氢键受体", hAcceptors.toString())
                PropertyRow("氢键供体", hDonors.toString())

                Spacer(Modifier.height(12.dp))
                Divider()

                // ═══ SMILES ═══
                Spacer(Modifier.height(8.dp))
                Text("SMILES", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        smiles.ifEmpty { "(无)" },
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = {
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clip.setPrimaryClip(ClipData.newPlainText("smiles", smiles))
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ContentCopy, "复制", tint = Color(0xFF555555), modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))
                Divider()

                // ═══ Molfile ═══
                Spacer(Modifier.height(8.dp))
                Text("Molfile", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        molStr.take(80).replace("\n", "↵") + if (molStr.length > 80) "..." else "",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = Color(0xFF666666)
                    )
                    IconButton(onClick = {
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clip.setPrimaryClip(ClipData.newPlainText("mol", molStr))
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ContentCopy, "复制", tint = Color(0xFF555555), modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ═══ 分享按钮 ═══
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        shareText(context, smiles.ifEmpty { molStr }, "分享 SMILES")
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("分享 SMILES", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = {
                        shareText(context, molStr, "分享 Molfile")
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("分享 Molfile", fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))
                Divider()

                // ═══ 图片导出 ═══
                Spacer(Modifier.height(8.dp))
                Text("导出图片", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        shareSvgFile(context, svgStr)
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("SVG", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = {
                        shareBitmap(context, atoms, bonds, annotations, "molecule.png", android.graphics.Bitmap.CompressFormat.PNG, benzeneStyle)
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("PNG", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = {
                        shareBitmap(context, atoms, bonds, annotations, "molecule.jpg", android.graphics.Bitmap.CompressFormat.JPEG, benzeneStyle)
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("JPG", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = {
                        sharePdf(context, atoms, bonds, annotations, benzeneStyle)
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("PDF", fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Divider()

                // ═══ PubChem 查询 ═══
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PubChem 查询", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (querying) {
                        LinearProgressIndicator(modifier = Modifier.width(80.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (smiles.isNotBlank()) {
                                querying = true
                                pubChemResult = null
                                // 异步查询
                                queryPubChem(smiles) { result ->
                                    pubChemResult = result
                                    querying = false
                                }
                            }
                        },
                        enabled = smiles.isNotBlank() && !querying,
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("查询", fontSize = 12.sp)
                    }
                }
                if (querying) {
                    Spacer(Modifier.height(4.dp))
                    Text("查询中…", fontSize = 12.sp, color = Color.Gray)
                }
                pubChemResult?.let { r ->
                    Spacer(Modifier.height(6.dp))
                    PubChemResultCard(r)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

// ── 辅助组件 ──

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("$label：", fontSize = 12.sp, color = Color(0xFF666666), modifier = Modifier.width(100.dp))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PubChemResultCard(result: PubChemResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F8FF))
    ) {
        Column(Modifier.padding(8.dp)) {
            Text("PubChem CID: ${result.cid}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            // 按 PUBCHEM_HEADINGS 顺序显示查到的数据
            for ((heading, label) in PUBCHEM_HEADINGS) {
                val value = result.properties[heading]
                if (value != null) {
                    PropertyRow(label, value.take(80))
                }
            }
            // 显示额外的属性（不在 heading 列表中的）
            for ((k, v) in result.properties) {
                if (PUBCHEM_HEADINGS.none { it.first == k }) {
                    PropertyRow(k, v.take(80))
                }
            }
        }
    }
}

// ── 工具函数 ──

private fun shareText(context: Context, text: String, title: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, title)
    }
    context.startActivity(Intent.createChooser(intent, title))
}

private fun shareSvgFile(context: Context, svgStr: String) {
    val ts = java.lang.System.currentTimeMillis()
    val dir = java.io.File(context.cacheDir, "exports")
    dir.mkdirs()
    // 清理30分钟前的旧文件
    dir.listFiles()?.forEach { f ->
        if (f.name.startsWith("molecule_") && (f.name.endsWith(".svg") || f.name.endsWith(".png") || f.name.endsWith(".jpg") || f.name.endsWith(".pdf")) &&
            ts - f.lastModified() > 30 * 60 * 1000L) f.delete()
    }
    val file = java.io.File(dir, "molecule_$ts.svg")
    file.writeText(svgStr)
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/svg+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享 SVG"))
}

fun sharePdf(
    context: Context,
    atoms: List<MoleculeAtom>,
    bonds: List<MoleculeBond>,
    annotations: List<MoleculeAnnotation>,
    benzeneStyle: BenzeneStyle = BenzeneStyle.KEKULE
) {
    if (atoms.isEmpty() && annotations.isEmpty()) return
    
    // 计算包围盒（作为 PDF 页面尺寸），充分包含文字和箭头头部
    val pad = 60f
    val allX = mutableListOf<Float>()
    val allY = mutableListOf<Float>()
    allX.addAll(atoms.map { it.x }); allY.addAll(atoms.map { it.y })
    for (ann in annotations) {
        val halfSize = 40f * ann.scale.coerceAtLeast(0.5f)
        allX.add(ann.x - halfSize); allX.add(ann.x + halfSize)
        allY.add(ann.y - halfSize); allY.add(ann.y + halfSize)
        if (ann.type == AnnotationType.ARROW) {
            allX.add(ann.endX - halfSize); allX.add(ann.endX + halfSize)
            allY.add(ann.endY - halfSize); allY.add(ann.endY + halfSize)
        }
    }
    if (allX.isEmpty()) return
    val minX = allX.min() - pad; val minY = allY.min() - pad
    val maxX = allX.max() + pad; val maxY = allY.max() + pad
    
    val pageW = ((maxX - minX).toInt()).coerceAtLeast(100)
    val pageH = ((maxY - minY).toInt()).coerceAtLeast(100)
    
    val offsetX = -minX; val offsetY = -minY
    
    // 创建 PDF 文档（直接使用矢量 Canvas API）
    val pdfDocument = android.graphics.pdf.PdfDocument()
    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageW, pageH, 1).create()
    val page = pdfDocument.startPage(pageInfo)
    val canvas = page.canvas  // 矢量 Canvas
    
    canvas.drawColor(android.graphics.Color.WHITE)
    
    // 绘制键
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
                val hw = 6f; val perpX = -uy * hw; val perpY = ux * hw
                android.graphics.Path().apply {
                    moveTo(sx, sy); lineTo(ex+perpX, ey+perpY); lineTo(ex-perpX, ey-perpY); close()
                }.let { canvas.drawPath(it, strokePaint); canvas.drawPath(it, fillPaint) }
            }
            BondType.WEDGE_DOWN -> {
                val segLen = 8f; val gapLen = 5f; var drawn = 0f
                while (drawn < len - r1 - r2) {
                    val t1 = drawn / (len - r1 - r2); val t2 = (drawn + segLen) / (len - r1 - r2)
                    if (t2 > 1f) break
                    canvas.drawLine(sx + (ex-sx)*t1, sy + (ey-sy)*t1, sx + (ex-sx)*t2, sy + (ey-sy)*t2, strokePaint)
                    drawn += segLen + gapLen
                }
            }
            BondType.AROMATIC -> {} // 由后面统一处理
        }
    }
    // 芳香环渲染（按 benzeneStyle）— PDF 矢量
    val doublePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#333333")
        strokeWidth = STROKE_WIDTH - 0.5f
        style = android.graphics.Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    val paulingRings = findPaulingRings(atoms, bonds)
    if (benzeneStyle == BenzeneStyle.PAULING) {
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
            val xs = ring.map { it.first }; val ys = ring.map { it.second }
            val cx = (xs.sum() / xs.size) + offsetX
            val cy = (ys.sum() / ys.size) + offsetY
            val dx = (xs.max() - xs.min()); val dy = (ys.max() - ys.min())
            val radius = sqrt((dx * dx + dy * dy).toDouble()).toFloat() * 0.29f
            canvas.drawCircle(cx, cy, radius, strokePaint)
        }
    } else {
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
    // 绘制原子（矢量文字）
    val textPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#333333")
        textSize = FONT_SIZE
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }
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
            for (b in connected) { bondOrderSum += when (b.type) { BondType.SINGLE, BondType.WEDGE_UP, BondType.WEDGE_DOWN -> 1; BondType.DOUBLE -> 2; BondType.TRIPLE -> 3; BondType.AROMATIC -> 1 } }
            var hCount = (a.element.valence - bondOrderSum).coerceAtLeast(0)
            // 芳香环修正：sp² C 在芳香环中有 π 键消耗 1 个价电子
            if (connected.count { it.type == BondType.AROMATIC } >= 2 && hCount > 0) hCount -= 1
            canvas.drawText(if (hCount == 0) symbol else if (hCount == 1) "${symbol}H" else "${symbol}H$hCount", cx, cy + FONT_SIZE/3f, textPaint)
        }
    }
    // 绘制标注（矢量文字、箭头和官能团缩写）
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
                style = android.graphics.Paint.Style.STROKE; isAntiAlias = true; strokeCap = android.graphics.Paint.Cap.ROUND
            }
            canvas.drawLine(cx, cy, ex, ey, aPaint)
            val dx = ex - cx; val dy = ey - cy; val len = sqrt(dx*dx + dy*dy)
            if (len > 5f) {
                val ux = dx/len; val uy = dy/len; val hs = 12f * (ann.scale.coerceAtLeast(0.5f))
                val ang = Math.toRadians(25.0); val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
                val lx = ex - ux*hs*ca + uy*hs*sa; val ly = ey - uy*hs*ca - ux*hs*sa
                val rx = ex - ux*hs*ca - uy*hs*sa; val ry = ey - uy*hs*ca + ux*hs*sa
                canvas.drawLine(ex, ey, lx, ly, aPaint); canvas.drawLine(ex, ey, rx, ry, aPaint)
            }
        }
    }
    
    pdfDocument.finishPage(page)
    
    // 写入文件
    val ts = java.lang.System.currentTimeMillis()
    val file = java.io.File(context.cacheDir, "exports/molecule_$ts.pdf")
    file.parentFile?.mkdirs()
    // 导出前清理30分钟前的旧文件
    file.parentFile?.listFiles()?.forEach { f ->
        if (f.name.startsWith("molecule_") && f.name.endsWith(".pdf") &&
            ts - f.lastModified() > 30 * 60 * 1000L) f.delete()
    }
    java.io.FileOutputStream(file).use { pdfDocument.writeTo(it) }
    pdfDocument.close()
    
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享 PDF"))
}

// ── 分子计算函数 ──

/** 计算分子式（含隐式氢） */
fun calcMolecularFormula(atoms: List<MoleculeAtom>, bonds: List<MoleculeBond>): String {
    if (atoms.isEmpty()) return ""
    val countMap = mutableMapOf<String, Int>()
    for (a in atoms) {
        countMap[a.element.symbol] = (countMap[a.element.symbol] ?: 0) + 1
    }
    // 补隐式氢
    val implicitH = countImplicitHydrogens(atoms, bonds)
    countMap["H"] = (countMap["H"] ?: 0) + implicitH
    // 标准顺序：C, H, 其他按字母
    val sb = StringBuilder()
    val c = countMap.remove("C") ?: 0
    val h = countMap.remove("H") ?: 0
    if (c > 0) sb.append("C"); if (c > 1) sb.append(c)
    if (h > 0) sb.append("H"); if (h > 1) sb.append(h)
    for (sym in countMap.keys.sorted()) {
        sb.append(sym)
        val cnt = countMap[sym] ?: 1
        if (cnt > 1) sb.append(cnt)
    }
    return sb.toString()
}

/** 计算分子量（含隐式氢） */
fun calcMolecularWeight(atoms: List<MoleculeAtom>, bonds: List<MoleculeBond>): Double {
    val atomicWeights = mapOf(
        "C" to 12.011, "H" to 1.008, "O" to 15.999, "N" to 14.007,
        "S" to 32.06, "P" to 30.974, "F" to 18.998, "Cl" to 35.45,
        "Br" to 79.904, "I" to 126.904, "Na" to 22.990, "K" to 39.098,
        "Fe" to 55.845, "Cu" to 63.546, "Zn" to 65.38, "Mg" to 24.305,
        "Ca" to 40.078, "B" to 10.81, "Si" to 28.085, "Se" to 78.971,
        "As" to 74.922, "Te" to 127.60, "Li" to 6.941, "Be" to 9.012,
        "Al" to 26.982, "Ti" to 47.867, "Mn" to 54.938, "Co" to 58.933,
        "Ni" to 58.693, "Pd" to 106.42, "Pt" to 195.084, "Au" to 196.967,
        "Ag" to 107.868
    )
    var weight = atoms.sumOf { atomicWeights[it.element.symbol] ?: 0.0 }
    // 补隐式氢
    val implicitH = countImplicitHydrogens(atoms, bonds)
    weight += implicitH * 1.008
    return weight
}

/** 从 Indigo 返回的分子式（如 "C6H6"）计算分子量 */
fun calcWeightFromFormula(formula: String): Double {
    val atomicWeights = mapOf(
        "C" to 12.011, "H" to 1.008, "O" to 15.999, "N" to 14.007,
        "S" to 32.06, "P" to 30.974, "F" to 18.998, "Cl" to 35.45,
        "Br" to 79.904, "I" to 126.904, "Na" to 22.990, "K" to 39.098,
        "Fe" to 55.845, "Cu" to 63.546, "Zn" to 65.38, "Mg" to 24.305,
        "Ca" to 40.078, "B" to 10.81, "Si" to 28.085, "Se" to 78.971,
        "As" to 74.922, "Te" to 127.60, "Li" to 6.941, "Be" to 9.012,
        "Al" to 26.982, "Ti" to 47.867, "Mn" to 54.938, "Co" to 58.933,
        "Ni" to 58.693, "Pd" to 106.42, "Pt" to 195.084, "Au" to 196.967,
        "Ag" to 107.868
    )
    var weight = 0.0
    var i = 0
    while (i < formula.length) {
        val c = formula[i]
        if (c.isUpperCase()) {
            val symStart = i
            i++
            while (i < formula.length && formula[i].isLowerCase()) i++
            val symbol = formula.substring(symStart, i)
            val numStart = i
            while (i < formula.length && formula[i].isDigit()) i++
            val count = if (i > numStart) formula.substring(numStart, i).toInt() else 1
            weight += (atomicWeights[symbol] ?: 0.0) * count
        } else {
            i++
        }
    }
    return weight
}

/** 计算所有原子需要补的隐式氢总数 */
private fun countImplicitHydrogens(atoms: List<MoleculeAtom>, bonds: List<MoleculeBond>): Int {
    val adj = mutableMapOf<Int, MutableList<Pair<Int, BondType>>>()
    for (a in atoms) adj[a.id] = mutableListOf()
    for (b in bonds) {
        adj[b.atom1]?.add(b.atom2 to b.type)
        adj[b.atom2]?.add(b.atom1 to b.type)
    }
    var totalH = 0
    for (a in atoms) {
        val connected = adj[a.id].orEmpty()
        var bondOrderSum = 0
        var aromaticNeighborCount = 0  // 芳香环邻居数
        for ((_, type) in connected) {
            when (type) {
                BondType.SINGLE, BondType.WEDGE_UP, BondType.WEDGE_DOWN -> bondOrderSum += 1
                BondType.DOUBLE -> bondOrderSum += 2
                BondType.TRIPLE -> bondOrderSum += 3
                BondType.AROMATIC -> {
                    aromaticNeighborCount++
                    // AROMATIC 键是介于 SINGLE 和 DOUBLE 之间的离域键
                    // 在六元芳香环中，每个原子通过 2 个 σ 键 + 1 个 π 键连接
                    bondOrderSum += 1
                }
            }
        }
        // 芳香环特殊处理：对于在芳香体系中的 C 和 N，
        // 它们实际参与了一个离域 π 体系，消耗了额外的价电子
        // 规则：如果原子有 >= 2 个 AROMATIC 邻接（即在芳香环内），
        // 需要额外扣减 π 键的贡献
        var h = (a.element.valence - bondOrderSum).coerceAtLeast(0)
        if (aromaticNeighborCount >= 2) {
            // 芳香环中的原子：除了 σ 键外还有 π 体系贡献
            // 对于 C（价4）：2个σ环键 + 1个σ外键(H) + 1个π键 = 4，h=0 → 实际h=1(因为到H是隐式键)
            // 但我们已有 bondOrderSum = aromaticNeighborCount * 1 = 2，
            // 所以 h = 4-2 = 2，需要扣掉 π 的 1 个电子
            // 对于 N（价3）：2个σ环键 + 0个σ外键(孤对) + 1个π键 = 3，h=0 → 实际h=0
            // 已有 bondOrderSum = 2，h = 3-2 = 1，需要扣掉 π 的 1 个电子 → h=0 ✓
            // 修正：减去 π 键贡献（1个键序单位）
            if (h > 0) h -= 1
        }
        totalH += h
    }
    return totalH
}

/** 计算可旋转键数（非环、非末端、非三键的单键） */
fun countRotatableBonds(atoms: List<MoleculeAtom>, bonds: List<MoleculeBond>): Int {
    // 简单检测：单键且两端不是末端（至少各有2个连接）
    val adj = mutableMapOf<Int, MutableList<Int>>()
    for (a in atoms) adj[a.id] = mutableListOf()
    for (b in bonds) {
        adj[b.atom1]?.add(b.atom2)
        adj[b.atom2]?.add(b.atom1)
    }
    return bonds.count { b ->
        b.type == BondType.SINGLE &&
        (adj[b.atom1]?.size ?: 0) >= 2 &&
        (adj[b.atom2]?.size ?: 0) >= 2
    }
}

/** 氢键受体数（N, O, F 原子） */
fun countHBA(atoms: List<MoleculeAtom>, bonds: List<MoleculeBond>): Int {
    return atoms.count {
        val sym = it.element.symbol
        (sym == "N" || sym == "O" || sym == "F") && !isTerminal(it.id, atoms, bonds)
    }
}

/** 氢键供体数（连有 H 的 N, O, F） */
fun countHBD(atoms: List<MoleculeAtom>, bonds: List<MoleculeBond>): Int {
    val adj = mutableMapOf<Int, MutableList<Int>>()
    for (a in atoms) adj[a.id] = mutableListOf()
    for (b in bonds) {
        adj[b.atom1]?.add(b.atom2)
        adj[b.atom2]?.add(b.atom1)
    }
    return atoms.count { a ->
        val sym = a.element.symbol
        (sym == "N" || sym == "O" || sym == "F") &&
        (adj[a.id]?.any { nbrId -> atoms.find { it.id == nbrId }?.element == Element.H } == true)
    }
}

private fun isTerminal(id: Int, atoms: List<MoleculeAtom>, bonds: List<MoleculeBond>): Boolean {
    val connected = bonds.count { it.atom1 == id || it.atom2 == id }
    return connected <= 1
}

// ── PubChem 查询 ──

/** PugView 可查询的 heading 列表 */
private val PUBCHEM_HEADINGS = listOf(
    "CAS" to "CAS",
    "IUPAC Name" to "IUPAC Name",
    "Molecular Formula" to "分子式",
    "Molecular Weight" to "分子量",
    "Density" to "密度",
    "Melting Point" to "熔点",
    "Boiling Point" to "沸点",
    "Flash Point" to "闪点",
    "Autoignition Temperature" to "自燃温度",
    "Vapor Pressure" to "蒸气压",
    "Vapor Density" to "蒸气密度",
    "Solubility" to "溶解度",
    "Surface Tension" to "表面张力",
    "Viscosity" to "粘度",
    "Refractive Index" to "折射率",
    "Dissociation Constants" to "解离常数(pKa)",
    "Heat of Vaporization" to "汽化热",
    "Ionization Potential" to "电离势",
    "Color" to "颜色",
    "Odor" to "气味",
    "Stability" to "稳定性",
    "Storage Conditions" to "储存条件",
    "Henry's Law Constant" to "亨利常数",
    "Critical Temperature" to "临界温度",
    "Critical Pressure" to "临界压力",
    "InChI" to "InChI",
    "InChIKey" to "InChIKey",
    "SMILES" to "SMILES"
)

data class PubChemResult(
    val cid: Int,
    /** heading -> value 映射 */
    val properties: Map<String, String> = emptyMap()
)

fun queryPubChem(smiles: String, onResult: (PubChemResult?) -> Unit) {
    Thread {
        try {
            val encodedSmiles = java.net.URLEncoder.encode(smiles, "UTF-8")
            val url = java.net.URL("https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/smiles/$encodedSmiles/JSON")
            android.util.Log.d("PUBCHEM", "step1 querying: $url")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            // 提取 CID
            var cid = extractJsonInt(response, "\"cid\"")
            if (cid == null) cid = extractJsonInt(response, "\"CID\"")
            android.util.Log.d("PUBCHEM", "step1 cid=$cid")

            if (cid == null || cid <= 0) {
                onResult(null)
                return@Thread
            }

            // 第二步：并行查所有 PugView heading
            val props = mutableMapOf<String, String>()
            val lock = Any()

            // 先从 step1 响应中提取基础属性
            val iupacName = extractPubChemProp(response, "IUPAC Name")
            val molFormula = extractPubChemProp(response, "Molecular Formula")
            val molWeight = extractPubChemProp(response, "Molecular Weight")
            if (iupacName != null) props["IUPAC Name"] = iupacName
            if (molFormula != null) props["Molecular Formula"] = molFormula
            if (molWeight != null) props["Molecular Weight"] = molWeight

            // 并行查 PugView
            val threads = PUBCHEM_HEADINGS.map { (heading, _) ->
                Thread {
                    try {
                        val encoded = java.net.URLEncoder.encode(heading, "UTF-8")
                        val hUrl = java.net.URL("https://pubchem.ncbi.nlm.nih.gov/rest/pug_view/data/compound/$cid/JSON/?heading=$encoded")
                        val hConn = hUrl.openConnection() as java.net.HttpURLConnection
                        hConn.connectTimeout = 8000
                        hConn.readTimeout = 8000
                        hConn.requestMethod = "GET"
                        val hResponse = if (hConn.responseCode == 200) {
                            hConn.inputStream.bufferedReader().readText()
                        } else null
                        hConn.disconnect()
                        if (hResponse != null) {
                            val value = extractPugViewStringValue(hResponse)
                            if (value != null) {
                                synchronized(lock) { props[heading] = value }
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            android.util.Log.d("PUBCHEM", "step2 done, ${props.size} properties")
            onResult(PubChemResult(cid, props.toMap()))
        } catch (e: java.net.UnknownHostException) {
            android.util.Log.w("PUBCHEM", "No network: ${e.message}")
            onResult(null)
        } catch (e: java.io.FileNotFoundException) {
            android.util.Log.w("PUBCHEM", "SMILES not found in PubChem: $smiles")
            onResult(null)
        } catch (e: Exception) {
            android.util.Log.w("PUBCHEM", "query failed: ${e.message}")
            onResult(null)
        }
    }.start()
}

private fun extractJsonInt(json: String, key: String): Int? {
    val idx = json.indexOf(key)
    if (idx < 0) return null
    val after = json.substring(idx + key.length)
    val numStart = after.indexOfAny("0123456789-".toCharArray())
    if (numStart < 0) return null
    val numStr = after.substring(numStart).takeWhile { it.isDigit() || it == '-' }
    return numStr.toIntOrNull()
}

private fun extractJsonString(json: String, key: String): String? {
    val idx = json.indexOf(key)
    if (idx < 0) return null
    val after = json.substring(idx + key.length)
    val quoteStart = after.indexOf('"')
    if (quoteStart < 0) return null
    val quoteEnd = after.indexOf('"', quoteStart + 1)
    if (quoteEnd < 0) return null
    return after.substring(quoteStart + 1, quoteEnd)
}

/** 在 PubChem JSON 的 props 数组中搜索指定 label 对应的 sval 值 */
private fun extractPubChemProp(json: String, label: String): String? {
    // PubChem JSON 中 label 格式为 "label": "xxx"（冒号后有空格）
    val searchKey = "\"$label\""
    var idx = json.indexOf(searchKey)
    if (idx < 0) return null
    // 往前确认前面有 "label": 或 "name": 
    val before = json.substring((idx - 20).coerceAtLeast(0), idx)
    if (!before.contains("label") && !before.contains("name")) return null
    // 从 label 位置往后找 sval
    val after = json.substring(idx + searchKey.length)
    val svalKey = "\"sval\":\""
    // 先搜无空格再搜有空格
    var svalIdx = after.indexOf(svalKey)
    if (svalIdx < 0) {
        svalIdx = after.indexOf("\"sval\": \"")
    }
    if (svalIdx < 0) return null
    val valueStart = after.indexOf('"', svalIdx) + 1
    val valueEnd = after.indexOf('"', valueStart)
    if (valueEnd < 0) return null
    return after.substring(valueStart, valueEnd)
}

/** 从 PugView JSON 中提取第一个 "String": "..." 的值 */
private fun extractPugViewStringValue(json: String): String? {
    // PugView JSON 中的格式是 "String": "xxx"（冒号后有空格）
    var idx = json.indexOf("\"String\": \"")
    if (idx < 0) {
        idx = json.indexOf("\"String\":\"") // 无空格回退
    }
    if (idx < 0) return null
    // 找到第一个冒号后的引号
    val colonIdx = json.indexOf(':', idx)
    if (colonIdx < 0) return null
    val quoteStart = json.indexOf('"', colonIdx + 1)
    if (quoteStart < 0) return null
    val start = quoteStart + 1
    val end = json.indexOf('"', start)
    if (end < 0) return null
    return json.substring(start, end)
}
