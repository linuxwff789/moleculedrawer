package com.moldraw.app.ui.moleculedraw

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.math.abs

// ── 配色（统一在这里改）──────────────────────────────────────────────
private val ToolBarBg = Color(0xFFFFFFFF)
private val ToolSelectedBg = Color(0xFFE8F0FE)
private val ToolSelectedFg = Color(0xFF1A73E8)
private val ToolFg = Color(0xFF444444)
private val DividerColor = Color(0xFFE6E6E6)
private val SubTextColor = Color(0xFF607D8B)

/** 主工具：两行五列，全部工具常驻可见（原来横向滚动会把缩放/自动调整/箭头/平移挤出屏幕） */
private val PRIMARY_TOOLS = listOf(
    DrawTool.ATOM, DrawTool.BOND, DrawTool.RING, DrawTool.SELECT, DrawTool.ERASE,
    DrawTool.TEXT, DrawTool.ARROW, DrawTool.SCALE, DrawTool.AUTO_FIT, DrawTool.PAN
)

private fun toolIconOf(t: DrawTool): ImageVector = when (t) {
    DrawTool.ATOM -> Icons.Default.Science
    DrawTool.BOND -> Icons.Default.Timeline
    DrawTool.SELECT -> Icons.Default.HighlightAlt
    DrawTool.ERASE -> Icons.Default.Delete
    DrawTool.TEXT -> Icons.Default.TextFields
    DrawTool.ARROW -> Icons.Default.NorthEast
    DrawTool.SCALE -> Icons.Default.AspectRatio
    DrawTool.AUTO_FIT -> Icons.Default.AutoFixHigh
    DrawTool.PAN -> Icons.Default.PanTool
    else -> Icons.Default.Build
}

private fun toolLabelOf(t: DrawTool): String = when (t) {
    DrawTool.ATOM -> "原子"
    DrawTool.BOND -> "自由键"
    DrawTool.RING -> "环"
    DrawTool.SELECT -> "选择"
    DrawTool.ERASE -> "擦除"
    DrawTool.TEXT -> "文字"
    DrawTool.ARROW -> "箭头"
    DrawTool.SCALE -> "缩放"
    DrawTool.AUTO_FIT -> "自动调整"
    DrawTool.PAN -> "平移"
}

/** 底部状态栏用的一行操作提示，替代原来每种工具各占一整行的说明文字 */
fun toolHint(t: DrawTool): String = when (t) {
    DrawTool.ATOM -> "点按空白处放置原子，从原子上拖拽出新键"
    DrawTool.BOND -> "在原子间拖拽连键，点击已有键切换键级"
    DrawTool.RING -> "点按放置环，从原子拖拽可直接连接"
    DrawTool.SELECT -> "拖拽框选，选中后可复制/翻转/合并"
    DrawTool.ERASE -> "点按原子或键删除"
    DrawTool.TEXT -> "点按画布添加文字标注"
    DrawTool.ARROW -> "从起点拖到终点画箭头"
    DrawTool.SCALE -> "输入倍率后点“应用”"
    DrawTool.AUTO_FIT -> "选中片段后点“自动调整”重排"
    DrawTool.PAN -> "拖拽平移画布，双指缩放"
}

/** 元素分组：常用元素放在快捷栏，其余进“更多”面板，避免 47 个元素挤在一条横向滚动条里 */
private val ELEMENT_GROUPS: List<Pair<String, List<Element>>> = listOf(
    "常用" to listOf(Element.C, Element.H, Element.O, Element.N, Element.S, Element.P, Element.F, Element.Cl, Element.Br, Element.I),
    "第 1 周期" to listOf(Element.H, Element.He),
    "第 2 周期" to listOf(Element.Li, Element.Be, Element.B, Element.C, Element.N, Element.O, Element.F, Element.Ne),
    "第 3 周期" to listOf(Element.Na, Element.Mg, Element.Al, Element.Si, Element.P, Element.S, Element.Cl, Element.Ar),
    "第 4 周期" to listOf(Element.K, Element.Ca, Element.Sc, Element.Ti, Element.V, Element.Cr, Element.Mn, Element.Fe, Element.Co, Element.Ni, Element.Cu, Element.Zn, Element.Ga, Element.Ge, Element.As, Element.Se, Element.Br, Element.Kr),
    "第 5 周期" to listOf(Element.Rb, Element.Sr, Element.Y, Element.Zr, Element.Nb, Element.Mo, Element.Tc, Element.Ru, Element.Rh, Element.Pd, Element.Ag, Element.Cd, Element.In, Element.Sn, Element.Sb, Element.Te, Element.I, Element.Xe),
    "第 6 周期" to listOf(Element.Cs, Element.Ba, Element.La, Element.Ce, Element.Pr, Element.Nd, Element.Pm, Element.Sm, Element.Eu, Element.Gd, Element.Tb, Element.Dy, Element.Ho, Element.Er, Element.Tm, Element.Yb, Element.Lu, Element.Hf, Element.Ta, Element.W, Element.Re, Element.Os, Element.Ir, Element.Pt, Element.Au, Element.Hg, Element.Tl, Element.Pb, Element.Bi, Element.Po, Element.At, Element.Rn),
    "第 7 周期" to listOf(Element.Fr, Element.Ra, Element.Ac, Element.Th, Element.Pa, Element.U, Element.Np, Element.Pu, Element.Am, Element.Cm, Element.Bk, Element.Cf, Element.Es, Element.Fm, Element.Md, Element.No, Element.Lr, Element.Rf, Element.Db, Element.Sg, Element.Bh, Element.Hs, Element.Mt, Element.Ds, Element.Rg, Element.Cn, Element.Nh, Element.Fl, Element.Mc, Element.Lv, Element.Ts, Element.Og)
)
private val QUICK_ELEMENTS = listOf(Element.C, Element.H, Element.O, Element.N, Element.S, Element.P, Element.F, Element.Cl, Element.Br, Element.I)

/** 绘图工具栏（主工具网格 + 当前工具的子工具栏） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoleculeToolRail(
    state: MoleculeDrawState,
    layoutEngine: MoleculeLayoutEngine? = null,
    vertical: Boolean = false
) {
    val s = state
    // ═══ 主工具栏：两行五列，等宽，无横向滚动 ═══
    Column(Modifier.fillMaxWidth().background(ToolBarBg).padding(horizontal = 4.dp, vertical = 4.dp)) {
        PRIMARY_TOOLS.chunked(if (vertical) 1 else 5).forEach { rowTools ->
            Row(Modifier.fillMaxWidth()) {
                rowTools.forEach { t ->
                    if (t == DrawTool.RING) {
                        Box(Modifier.weight(1f)) {
                            ToolButton(
                                selected = s.tool == DrawTool.RING,
                                label = toolLabelOf(t),
                                compact = vertical,
                                onClick = {
                                    s.tool = DrawTool.RING
                                    s.ringMenuExpanded = true
                                    s.bondStart = null; s.isBondDragging = false
                                    s.selectedAtom = null; s.magnifierPos = null; s.bondFirstAtom = null
                                }
                            ) { RingIcon(s.ringType, 20.dp) }
                            DropdownMenu(
                                expanded = s.ringMenuExpanded,
                                onDismissRequest = { s.ringMenuExpanded = false }
                            ) {
                                RingType.entries.forEach { rt ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RingIcon(rt, 16.dp)
                                                Spacer(Modifier.width(8.dp))
                                                Text(rt.label, fontSize = 13.sp)
                                            }
                                        },
                                        onClick = { s.ringType = rt; s.ringMenuExpanded = false }
                                    )
                                }
                                if (s.ringType.benzene) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("苯环样式: ", fontSize = 12.sp, color = SubTextColor)
                                                Text(if (s.benzeneStyle == BenzeneStyle.KEKULE) "凯库勒式" else "鲍林式", fontSize = 13.sp)
                                            }
                                        },
                                        onClick = {
                                            s.benzeneStyle = if (s.benzeneStyle == BenzeneStyle.KEKULE) BenzeneStyle.PAULING else BenzeneStyle.KEKULE
                                            s.ringMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        Box(Modifier.weight(1f)) {
                            ToolButton(
                                selected = s.tool == t,
                                label = toolLabelOf(t),
                                compact = vertical,
                                onClick = {
                                    when (t) {
                                        DrawTool.ERASE -> if (s.selectedIds.isNotEmpty()) {
                                            s.pushUndo()
                                            s.selectedIds.filter { it > 0 }.forEach { id -> removeFunctionalGroupAround(s, id) }
                                            s.bonds.removeAll { it.atom1 in s.selectedIds || it.atom2 in s.selectedIds }
                                            s.atoms.removeAll { it.id in s.selectedIds }
                                            s.annotations.removeAll { -it.id in s.selectedIds }
                                            s.selectedIds = emptySet(); s.activeGroup = null
                                        } else {
                                            s.tool = t
                                        }
                                        DrawTool.AUTO_FIT -> if (s.selectedIds.size >= 2) {
                                            s.pushUndo()
                                            val newSel = autoFit(s.atoms, s.bonds, s.selectedIds, layoutEngine, s.canvasSizePx.width, s.canvasSizePx.height, s.annotations)
                                            s.selectedIds = newSel
                                            s.activeGroup = null
                                            if (newSel.isNotEmpty()) s.refreshGroup()
                                            s.tool = DrawTool.SELECT
                                        } else {
                                            s.tool = t
                                        }
                                        else -> s.tool = t
                                    }
                                    s.bondStart = null; s.isBondDragging = false
                                    s.selectedAtom = null; s.magnifierPos = null; s.bondFirstAtom = null
                                    s.selRectStart = null; s.isSelDragging = false
                                }
                            ) { Icon(toolIconOf(t), null, modifier = Modifier.size(20.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MoleculeContextBar(
    state: MoleculeDrawState,
    layoutEngine: MoleculeLayoutEngine? = null
) {
    val s = state
    var showElemSheet by remember { mutableStateOf(false) }
    HorizontalDivider(color = DividerColor, thickness = 1.dp)

    // ═══ 子工具栏（随当前工具变化，单行高 40dp）═══
    when (s.tool) {
        DrawTool.ATOM -> {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
              Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                // 官能团缩写
                Box {
                    ToolChip(
                        selected = s.isFuncGroupDragMode,
                        label = if (s.isFuncGroupDragMode) "点原子添加" else "缩写",
                        onClick = { s.showFgMenu = true },
                        icon = { Icon(Icons.Default.ShortText, null, modifier = Modifier.size(14.dp)) }
                    )
                    DropdownMenu(expanded = s.showFgMenu, onDismissRequest = { s.showFgMenu = false }) {
                        FUNCTIONAL_GROUPS.forEachIndexed { index, fg ->
                            if (fg.category.isNotEmpty() &&
                                (index == 0 || FUNCTIONAL_GROUPS[index - 1].category != fg.category)
                            ) {
                                Text(
                                    fg.category,
                                    fontSize = 10.sp,
                                    color = SubTextColor,
                                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(fg.name, fontSize = 12.5.sp)
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                fg.label,
                                                fontSize = 12.5.sp,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                                color = ToolSelectedFg
                                            )
                                        }
                                        if (fg.formula.isNotEmpty()) {
                                            Text(fg.formula, fontSize = 10.sp, color = SubTextColor)
                                        }
                                    }
                                },
                                onClick = {
                                    s.showFgMenu = false
                                    s.selectedFuncGroup = fg
                                    s.isFuncGroupDragMode = true
                                }
                            )
                        }
                    }
                }
                Box(Modifier.width(1.dp).height(20.dp).background(DividerColor))
                // 常用元素
                QUICK_ELEMENTS.forEach { e ->
                    ToolChip(selected = s.selElem == e, label = e.symbol, onClick = { s.selElem = e })
                }
              }
              Spacer(Modifier.width(6.dp))
              // “更多”固定在最右，不随元素行滚走
              ToolChip(selected = false, label = "更多", onClick = { showElemSheet = true }) {
                  Icon(Icons.Default.ExpandMore, null, modifier = Modifier.size(16.dp))
              }
            }
        }

        DrawTool.BOND -> {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BondType.entries.forEach { bt ->
                    if (bt == BondType.AROMATIC) return@forEach
                    val label = when (bt) {
                        BondType.SINGLE -> "单键"
                        BondType.DOUBLE -> "双键"
                        BondType.TRIPLE -> "三键"
                        BondType.WEDGE_UP -> "楔形↑"
                        BondType.WEDGE_DOWN -> "楔形↓"
                        else -> ""
                    }
                    ToolChip(selected = s.selBond == bt, label = label, onClick = { s.selBond = bt; s.extendMode = false })
                }
                Box(Modifier.width(1.dp).height(20.dp).background(DividerColor))
                ToolChip(
                    selected = s.extendMode,
                    label = "延长碳链",
                    onClick = { s.extendMode = !s.extendMode; s.tool = DrawTool.BOND }
                ) { Icon(if (s.extendMode) Icons.Default.CheckCircle else Icons.Default.Timeline, null, modifier = Modifier.size(14.dp)) }
            }
        }

        DrawTool.SCALE -> {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = s.scaleValue,
                    onValueChange = { v ->
                        s.scaleValue = v
                        v.toFloatOrNull()?.let { f -> if (f > 0f) s.scaleFactor = f }
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Color(0xFF333333), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, textAlign = TextAlign.Center),
                    singleLine = true,
                    suffix = { Text("×", fontSize = 12.sp, color = SubTextColor) },
                    modifier = Modifier.width(104.dp)
                )
                listOf(0.5f, 0.8f, 1.0f, 1.2f, 1.5f, 2.0f).forEach { f ->
                    ToolChip(
                        selected = abs(s.scaleFactor - f) < 0.01f,
                        label = "${f}×",
                        onClick = { s.scaleFactor = f; s.scaleValue = f.toString() }
                    )
                }
                if (s.atoms.isNotEmpty() && abs(s.scaleFactor - 1f) > 0.01f) {
                    Button(
                        onClick = {
                            s.pushUndo()
                            val selected = if (s.selectedIds.isNotEmpty()) s.atoms.filter { it.id in s.selectedIds } else s.atoms.toList()
                            val cx = selected.map { it.x }.average().toFloat()
                            val cy = selected.map { it.y }.average().toFloat()
                            for (a in s.atoms) {
                                a.x = cx + (a.x - cx) * s.scaleFactor
                                a.y = cy + (a.y - cy) * s.scaleFactor
                            }
                            s.scaleFactor = 1f; s.scaleValue = "1.0"
                        },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                    ) { Text("应用", fontSize = 12.sp) }
                }
            }
        }

        DrawTool.SELECT -> {
            val context = LocalContext.current
            if (s.flipToast.isNotEmpty()) {
                LaunchedEffect(s.flipToast) {
                    android.widget.Toast.makeText(context, s.flipToast, android.widget.Toast.LENGTH_SHORT).show()
                    s.flipToast = ""
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolChip(
                    selected = s.isMoveMode,
                    label = "组合移动",
                    onClick = { s.isMoveMode = !s.isMoveMode }
                ) { Icon(if (s.isMoveMode) Icons.Default.CheckCircle else Icons.Default.OpenWith, null, modifier = Modifier.size(14.dp)) }

                if (s.selectedIds.isNotEmpty()) {
                    Text("已选 ${s.selectedIds.size}", fontSize = 11.sp, color = Color(0xFF2E7D32))
                    SmallIconButton(Icons.Default.ContentCopy, "复制") {
                        val atomList = s.selection.getAtoms()
                        val annList = s.selection.getAnnotations()
                        val bondList = s.selection.getInternalBonds()
                        val json = buildWorkspaceJson(atomList, bondList, annList, BOND_LENGTH, s.benzeneStyle)
                        (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("moldraw", json))
                        android.widget.Toast.makeText(context, "已复制 ${atomList.size} 个原子, ${annList.size} 个标注", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    SmallIconButton(Icons.Default.ContentCut, "剪切") {
                        s.pushUndo()
                        val atomList = s.selection.getAtoms()
                        val annList = s.selection.getAnnotations()
                        val bondList = s.selection.getInternalBonds()
                        val json = buildWorkspaceJson(atomList, bondList, annList, BOND_LENGTH, s.benzeneStyle)
                        (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("moldraw", json))
                        s.selectedIds.filter { it > 0 }.forEach { id -> removeFunctionalGroupAround(s, id) }
                        s.bonds.removeAll { it.atom1 in s.selectedIds || it.atom2 in s.selectedIds }
                        s.atoms.removeAll { it.id in s.selectedIds }
                        s.annotations.removeAll { -it.id in s.selectedIds }
                        s.selectedIds = emptySet(); s.activeGroup = null
                        android.widget.Toast.makeText(context, "已剪切 ${atomList.size} 个原子, ${annList.size} 个标注", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    SmallIconButton(Icons.Default.ContentPaste, "粘贴") {
                        val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clipData = clip.primaryClip ?: return@SmallIconButton
                        if (clipData.itemCount > 0) {
                            val text = clipData.getItemAt(0).text?.toString() ?: return@SmallIconButton
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
                                    android.widget.Toast.makeText(context, "已粘贴 ${newAtoms.size} 个原子, ${newAnn.size} 个标注", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    ToolChip(selected = false, label = "取消选择", onClick = { s.selectedIds = emptySet(); s.collapseGroup(); s.activeGroup = null })
                }
                if (s.selectedIds.size >= 2) {
                    ToolChip(selected = false, label = "水平翻转", onClick = { s.pushUndo(); s.collapseGroup(); s.flipToast = s.selection.flipHorizontal() }) {
                        Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(14.dp))
                    }
                    ToolChip(selected = false, label = "垂直翻转", onClick = { s.pushUndo(); s.collapseGroup(); s.flipToast = s.selection.flipVertical() }) {
                        Icon(Icons.Default.SwapVert, null, modifier = Modifier.size(14.dp))
                    }
                    ToolChip(selected = false, label = "旋转90°", onClick = { s.pushUndo(); s.collapseGroup(); s.flipToast = s.selection.rotate90() }) {
                        Icon(Icons.Default.RotateRight, null, modifier = Modifier.size(14.dp))
                    }
                }
                if (s.selectedIds.size == 2) {
                    ToolChip(selected = false, label = "合并", onClick = { s.mergeSelectedAtoms() }) {
                        Icon(Icons.Default.Compress, null, modifier = Modifier.size(14.dp), tint = Color(0xFFE91E63))
                    }
                }
            }
        }

        // TEXT / ARROW / PAN 不再占用一整行提示，提示统一放到底部状态栏
        else -> Unit
    }

    HorizontalDivider(color = DividerColor, thickness = 1.dp)

    // ═══ 更多元素面板 ═══
    if (showElemSheet) {
        ModalBottomSheet(onDismissRequest = { showElemSheet = false }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp).padding(bottom = 24.dp)
            ) {
                Text("选择元素", fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("当前：${s.selElem.symbol}", fontSize = 12.sp, color = SubTextColor)
                Spacer(Modifier.height(12.dp))
                ELEMENT_GROUPS.forEach { (title, elems) ->
                    Text(title, fontSize = 12.sp, color = SubTextColor, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        elems.forEach { e ->
                            ElementCell(selected = s.selElem == e, symbol = e.symbol) {
                                s.selElem = e
                                showElemSheet = false
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ColumnScope.MoleculeDrawToolbar(
    state: MoleculeDrawState,
    layoutEngine: MoleculeLayoutEngine? = null
) {
    MoleculeToolRail(state, layoutEngine = layoutEngine, vertical = false)
    MoleculeContextBar(state, layoutEngine = layoutEngine)
}

/** 主工具按钮：图标在上、文字在下，44dp 高，整块可点（原来 34dp 高、纯文字标签） */
@Composable
private fun ToolButton(
    selected: Boolean,
    label: String,
    compact: Boolean = false,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (compact) 38.dp else 46.dp)
            .padding(horizontal = 2.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) ToolSelectedBg else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides (if (selected) ToolSelectedFg else ToolFg)
            ) { icon() }
            Spacer(Modifier.height(1.dp))
            Text(
                label,
                fontSize = if (compact) 9.sp else 10.sp,
                color = if (selected) ToolSelectedFg else ToolFg,
                maxLines = 1
            )
        }
    }
}

/** 子工具栏胶囊：36dp 高，比原来 28dp 更接近可点区域下限 */
@Composable
private fun ToolChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    icon: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) ToolSelectedBg else Color(0xFFF3F4F6))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides (if (selected) ToolSelectedFg else ToolFg)
            ) { icon() }
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            fontSize = 12.sp,
            color = if (selected) ToolSelectedFg else ToolFg,
            maxLines = 1
        )
    }
}

@Composable
private fun SmallIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, description, tint = ToolFg, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ElementCell(selected: Boolean, symbol: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) ToolSelectedBg else Color(0xFFF3F4F6))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, fontSize = 13.sp, color = if (selected) ToolSelectedFg else ToolFg)
    }
}
