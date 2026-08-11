package com.moldraw.app.ui.moleculedraw

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import kotlin.math.abs

/** 绘制工具栏（顶栏按钮 + 子工具栏） */
@Composable
fun ColumnScope.MoleculeDrawToolbar(
    state: MoleculeDrawState,
    layoutEngine: MoleculeLayoutEngine? = null
) {
    val s = state

    // ═══ 主工具栏 ═══
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        DrawTool.entries.forEach { t ->
            when (t) {
                DrawTool.RING -> {
                    Box {
                        FilterChip(
                            selected = s.tool == DrawTool.RING,
                            onClick = { s.tool = DrawTool.RING; s.ringMenuExpanded = true; s.bondStart = null; s.isBondDragging = false; s.selectedAtom = null; s.magnifierPos = null; s.bondFirstAtom = null },
                            label = { RingIcon(s.ringType, 14.dp) },
                            leadingIcon = { RingIcon(s.ringType, 16.dp) },
                            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.height(34.dp)
                        )
                        DropdownMenu(
                            expanded = s.ringMenuExpanded,
                            onDismissRequest = { s.ringMenuExpanded = false }
                        ) {
                            RingType.entries.forEach { rt ->
                                DropdownMenuItem(
                                    text = { Row(verticalAlignment = Alignment.CenterVertically) { RingIcon(rt, 16.dp); Spacer(Modifier.width(6.dp)); Text(rt.label, fontSize = 13.sp) } },
                                    onClick = { s.ringType = rt; s.ringMenuExpanded = false }
                                )
                            }
                            if (s.ringType.benzene) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("苯环样式: ", fontSize = 12.sp, color = Color.Gray)
                                        Text(if (s.benzeneStyle == BenzeneStyle.KEKULE) "凯库勒式" else "鲍林式", fontSize = 13.sp)
                                    } },
                                    onClick = { s.benzeneStyle = if (s.benzeneStyle == BenzeneStyle.KEKULE) BenzeneStyle.PAULING else BenzeneStyle.KEKULE; s.ringMenuExpanded = false }
                                )
                            }
                        }
                    }
                }
                DrawTool.SCALE -> {
                    FilterChip(
                        selected = s.tool == DrawTool.SCALE,
                        onClick = { s.tool = DrawTool.SCALE; s.bondStart = null; s.isBondDragging = false; s.selectedAtom = null; s.magnifierPos = null },
                        label = { Text("缩放", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.height(34.dp)
                    )
                }
                DrawTool.AUTO_FIT -> {
                    FilterChip(
                        selected = s.tool == DrawTool.AUTO_FIT,
                        onClick = {
                            s.tool = DrawTool.AUTO_FIT
                            if (s.selectedIds.size >= 2) {
                                autoFit(s.atoms, s.bonds, s.selectedIds, layoutEngine, s.canvasSizePx.width, s.canvasSizePx.height, s.annotations)
                                s.tool = DrawTool.SELECT
                            }
                        },
                        label = { Text("自动调整", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.AutoFixHigh, null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.height(34.dp)
                    )
                }
                DrawTool.ERASE -> {
                    FilterChip(
                        selected = s.tool == DrawTool.ERASE,
                        onClick = {
                            if (s.selectedIds.isNotEmpty()) {
                                // 有选中元素时，点击擦除直接删除选中的元素
                                s.pushUndo()
                                s.bonds.removeAll { it.atom1 in s.selectedIds || it.atom2 in s.selectedIds }
                                s.atoms.removeAll { it.id in s.selectedIds }
                                s.annotations.removeAll { -it.id in s.selectedIds }
                                s.selectedIds = emptySet(); s.activeGroup = null
                            } else {
                                s.tool = t; s.bondStart = null; s.isBondDragging = false; s.selectedAtom = null; s.magnifierPos = null; s.bondFirstAtom = null; s.selRectStart = null; s.isSelDragging = false
                            }
                        },
                        label = { Text("擦除", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.height(34.dp)
                    )
                }
                DrawTool.SELECT -> {
                    FilterChip(
                        selected = s.tool == DrawTool.SELECT,
                        onClick = { s.tool = t; s.bondStart = null; s.isBondDragging = false; s.selectedAtom = null; s.magnifierPos = null; s.bondFirstAtom = null; s.selRectStart = null; s.isSelDragging = false },
                        label = { Text("选择", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Dashboard, null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.height(34.dp)
                    )
                }
                else -> {
                    val label = when (t) {
                        DrawTool.ATOM -> "原子"; DrawTool.BOND -> "自由键"
                        DrawTool.TEXT -> "文字"; DrawTool.ARROW -> "箭头"
                        else -> ""
                    }
                    if (label.isNotEmpty()) {
                        FilterChip(
                            selected = s.tool == t,
                            onClick = { s.tool = t; s.bondStart = null; s.isBondDragging = false; s.selectedAtom = null; s.magnifierPos = null; s.bondFirstAtom = null; s.selRectStart = null; s.isSelDragging = false },
                            label = { Text(label, fontSize = 11.sp) },
                            leadingIcon = { Icon(toolIcon(t), null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.height(34.dp)
                        )
                    }
                }
            }
        }
    }

    // ═══ 子工具栏 ═══
    if (s.tool == DrawTool.ATOM) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // 官能团缩写（放在第一个）
            Box {
                FilterChip(
                    selected = s.isFuncGroupDragMode,
                    onClick = { s.showFgMenu = true },
                    label = { Text(if (s.isFuncGroupDragMode) "拖拽到原子上替换" else "缩写", fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Default.ShortText, null, modifier = Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp)
                )
                DropdownMenu(
                    expanded = s.showFgMenu,
                    onDismissRequest = { s.showFgMenu = false }
                ) {
                    FUNCTIONAL_GROUPS.forEach { fg ->
                        DropdownMenuItem(
                            text = { Text("${fg.name} (${fg.label})", fontSize = 12.sp) },
                            onClick = {
                                s.showFgMenu = false
                                s.selectedFuncGroup = fg
                                s.isFuncGroupDragMode = true
                            }
                        )
                    }
                }
            }
            // 元素面板：按周期表分组，横向滚动查看全部
            listOf(
                // 有机常用（非金属）
                Element.C, Element.H, Element.O, Element.N, Element.S, Element.P, Element.F, Element.Cl, Element.Br, Element.I,
                Element.B, Element.Si, Element.Se, Element.As, Element.Te,
                // 碱金属 / 碱土金属
                Element.Li, Element.Na, Element.K, Element.Rb, Element.Cs,
                Element.Be, Element.Mg, Element.Ca, Element.Sr, Element.Ba,
                // 过渡金属
                Element.Ti, Element.V, Element.Cr, Element.Mn, Element.Fe, Element.Co, Element.Ni, Element.Cu, Element.Zn,
                Element.Mo, Element.W, Element.Pd, Element.Pt, Element.Ag, Element.Au, Element.Cd, Element.Hg,
                // 主族金属
                Element.Al, Element.Ga, Element.In, Element.Tl, Element.Sn, Element.Pb, Element.Sb, Element.Bi,
                // 惰性气体
                Element.He, Element.Ne, Element.Ar
            ).forEach { e ->
                FilterChip(selected = s.selElem == e, onClick = { s.selElem = e }, label = { Text(e.symbol, fontSize = 11.sp) }, modifier = Modifier.height(28.dp))
            }
        }
    }

    if (s.tool == DrawTool.BOND) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            BondType.entries.forEach { bt ->
    if (bt == BondType.AROMATIC) return@forEach // 跳过芳香键
    val label = when (bt) { BondType.SINGLE -> "单键 —"; BondType.DOUBLE -> "双键 ⵐ"; BondType.TRIPLE -> "三键 ≡"; BondType.WEDGE_UP -> "楔形 ▲"; BondType.WEDGE_DOWN -> "楔形 △"; else -> "" }
    FilterChip(selected = s.selBond == bt, onClick = { s.selBond = bt; s.extendMode = false }, label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.height(28.dp))
}
            Spacer(Modifier.width(4.dp))
            FilterChip(
                selected = s.extendMode,
                onClick = { s.extendMode = !s.extendMode; s.tool = DrawTool.BOND },
                label = { Text("延长", fontSize = 11.sp) },
                leadingIcon = { Icon(if (s.extendMode) Icons.Default.CheckCircle else Icons.Default.Timeline, null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.height(28.dp)
            )
        }
    }

    if (s.tool == DrawTool.SCALE) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("缩放比:", fontSize = 12.sp, color = Color(0xFF666666))
            OutlinedTextField(
                value = s.scaleValue,
                onValueChange = { v ->
                    s.scaleValue = v
                    v.toFloatOrNull()?.let { f -> if (f > 0f) s.scaleFactor = f }
                },
                textStyle = TextStyle(fontSize = 14.sp, color = Color(0xFF333333), fontWeight = FontWeight.Medium),
                modifier = Modifier
                    .width(72.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Text("×", fontSize = 12.sp, color = Color(0xFF666666))
            listOf(0.5f, 0.8f, 1.0f, 1.2f, 1.5f, 2.0f).forEach { f ->
                FilterChip(
                    selected = abs(s.scaleFactor - f) < 0.01f,
                    onClick = { s.scaleFactor = f; s.scaleValue = f.toString() },
                    label = { Text("${f}×", fontSize = 10.sp) },
                    modifier = Modifier.height(26.dp)
                )
            }
            Box(Modifier.width(4.dp))
            if (s.atoms.isNotEmpty() && abs(s.scaleFactor - 1f) > 0.01f) {
                Button(
                    onClick = {
                        s.pushUndo()
                        val selected = if (s.selectedIds.isNotEmpty()) s.atoms.filter { it.id in s.selectedIds }
                                       else s.atoms.toList()
                        val cx = selected.map { it.x }.average().toFloat()
                        val cy = selected.map { it.y }.average().toFloat()
                        for (a in s.atoms) {
                            a.x = cx + (a.x - cx) * s.scaleFactor
                            a.y = cy + (a.y - cy) * s.scaleFactor
                        }
                        s.scaleFactor = 1f; s.scaleValue = "1.0"
                    },
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) { Text("应用", fontSize = 11.sp) }
            }
        }
    }

    if (s.tool == DrawTool.SELECT) {
        val context = LocalContext.current
        // Toast 提示
        if (s.flipToast.isNotEmpty()) {
            LaunchedEffect(s.flipToast) {
                Toast.makeText(context, s.flipToast, Toast.LENGTH_SHORT).show()
                s.flipToast = ""
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!s.isMoveMode) {
                Text("拖拽框选原子", fontSize = 11.sp, color = Color.Gray)
            } else {
                Text("拖拽组合移动选中元素 | 双指缩放", fontSize = 11.sp, color = Color(0xFF1976D2))
            }
            if (s.selectedIds.isNotEmpty()) {
                Text("已选 ${s.selectedIds.size} 个", fontSize = 11.sp, color = Color(0xFF4CAF50))
                // 复制
                IconButton(onClick = {
                    val atomList = s.selection.getAtoms()
                    val annList = s.selection.getAnnotations()
                    val bondList = s.selection.getInternalBonds()
                    val json = buildWorkspaceJson(atomList, bondList, annList, BOND_LENGTH, s.benzeneStyle)
            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("moldraw", json))
            Toast.makeText(context, "已复制 ${atomList.size} 个原子, ${annList.size} 个标注", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.ContentCopy, "复制", tint = Color(0xFF555555), modifier = Modifier.size(16.dp)) }
        // 剪切
        IconButton(onClick = {
            s.pushUndo()
            val atomList = s.selection.getAtoms()
            val annList = s.selection.getAnnotations()
            val bondList = s.selection.getInternalBonds()
            val json = buildWorkspaceJson(atomList, bondList, annList, BOND_LENGTH, s.benzeneStyle)
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("moldraw", json))
                    // 删除选中元素
                    s.bonds.removeAll { it.atom1 in s.selectedIds || it.atom2 in s.selectedIds }
                    s.atoms.removeAll { it.id in s.selectedIds }
                    s.annotations.removeAll { -it.id in s.selectedIds }
                    s.selectedIds = emptySet(); s.activeGroup = null
                    Toast.makeText(context, "已剪切 ${atomList.size} 个原子, ${annList.size} 个标注", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.ContentCut, "剪切", tint = Color(0xFF555555), modifier = Modifier.size(16.dp)) }
                // 粘贴
                IconButton(onClick = {
                    val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clipData = clip.primaryClip ?: return@IconButton
                    if (clipData.itemCount > 0) {
                        val text = clipData.getItemAt(0).text?.toString() ?: return@IconButton
                        if (text.trimStart().startsWith("{")) {
                            s.pushUndo()
                            val tempAtoms = mutableListOf<MoleculeAtom>()
                            val tempBonds = mutableListOf<MoleculeBond>()
                            val tempAnnotations = mutableListOf<MoleculeAnnotation>()
                            loadWorkspaceJson(text, tempAtoms, tempBonds, tempAnnotations)
                            if (tempAtoms.isNotEmpty() || tempAnnotations.isNotEmpty()) {
                                val maxId = s.atoms.maxOfOrNull { it.id }?.coerceAtLeast(s.annotations.maxOfOrNull { it.id } ?: 0) ?: 0
                                val idOffset = maxId + 1
                                val idMap = mutableMapOf<Int, Int>()
                                val newAtoms = tempAtoms.map { a ->
                                    val newId = a.id + idOffset
                                    idMap[a.id] = newId
                                    MoleculeAtom(newId, a.x + 30f, a.y + 30f, a.element, a.aromatic)
                                }
                                val newAnn = tempAnnotations.map { a ->
                                    MoleculeAnnotation(a.id + idOffset, a.type, a.x + 30f, a.y + 30f, a.text, a.endX + 30f, a.endY + 30f, a.scale)
                                }
                                val newBonds = tempBonds.map { b ->
                                    MoleculeBond(b.id + idOffset, idMap[b.atom1] ?: b.atom1, idMap[b.atom2] ?: b.atom2, b.type)
                                }
                                s.atoms.addAll(newAtoms)
                                s.bonds.addAll(newBonds)
                                s.annotations.addAll(newAnn)
                                s.selectedIds = newAtoms.map { it.id }.toSet() + newAnn.map { -it.id }.toSet()
                                Toast.makeText(context, "已粘贴 ${newAtoms.size} 个原子, ${newAnn.size} 个标注", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.ContentPaste, "粘贴", tint = Color(0xFF555555), modifier = Modifier.size(16.dp)) }
                // 移动模式开关
                FilterChip(
                    selected = s.isMoveMode,
                    onClick = { s.isMoveMode = !s.isMoveMode },
                    label = { Text("组合", fontSize = 11.sp) },
                    leadingIcon = { Icon(if (s.isMoveMode) Icons.Default.CheckCircle else Icons.Default.OpenWith, null, modifier = Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp)
                )
                TextButton(onClick = { s.selectedIds = emptySet(); s.collapseGroup(); s.activeGroup = null }) { Text("清除", fontSize = 11.sp) }
            }
            if (s.selectedIds.size >= 2) {
                FilterChip(
                    selected = false,
                    onClick = { s.pushUndo(); s.flipToast = s.selection.flipHorizontal() },
                    label = { Text("水平翻转", fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp)
                )
                FilterChip(
                    selected = false,
                    onClick = { s.pushUndo(); s.flipToast = s.selection.flipVertical() },
                    label = { Text("垂直翻转", fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Default.SwapVert, null, modifier = Modifier.size(14.dp)) },
                    modifier = Modifier.height(28.dp)
                )
            }
            if (s.selectedIds.size == 2) {
                FilterChip(
                    selected = false,
                    onClick = { s.mergeSelectedAtoms() },
                    label = { Text("合并", fontSize = 11.sp, color = Color(0xFFE91E63)) },
                    leadingIcon = { Icon(Icons.Default.Compress, null, modifier = Modifier.size(14.dp), tint = Color(0xFFE91E63)) },
                    modifier = Modifier.height(28.dp)
                )
            }
        }
    }

    if (s.tool == DrawTool.TEXT) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("点击画布添加文字标注", fontSize = 11.sp, color = Color.Gray)
        }
    }

    if (s.tool == DrawTool.ARROW) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("点击起点→拖动到终点添加箭头", fontSize = 11.sp, color = Color.Gray)
        }
    }

    if (s.tool == DrawTool.PAN) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("拖拽平移画布  |  双指缩放", fontSize = 11.sp, color = Color.Gray)
        }
    }
}
