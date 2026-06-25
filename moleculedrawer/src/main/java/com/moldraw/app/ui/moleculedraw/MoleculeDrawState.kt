package com.moldraw.app.ui.moleculedraw

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.*

/**
 * 所有编辑器的可变状态，集中管理。
 * 替代 MoleculeDrawApp 中平铺的 42 个 remember 变量。
 */
class MoleculeDrawState {
    // ── 原子/键/标注 ──
    val atoms = mutableStateListOf<MoleculeAtom>()
    val bonds = mutableStateListOf<MoleculeBond>()
    val annotations = mutableStateListOf<MoleculeAnnotation>()

    var selElem by mutableStateOf(Element.C)
    var selBond by mutableStateOf(BondType.SINGLE)
    var tool by mutableStateOf(DrawTool.ATOM)
    var ringType by mutableStateOf(RingType.RING6)
    var ringMenuExpanded by mutableStateOf(false)
    var benzeneStyle by mutableStateOf(BenzeneStyle.KEKULE)

    // ── 撤销 ──
    val undo = UndoManager()
    var undoCount by mutableIntStateOf(0)

    // ── 自由键拖拽 ──
    var bondStart by mutableStateOf<Offset?>(null)
    var bondCur by mutableStateOf(Offset.Zero)
    var isBondDragging by mutableStateOf(false)

    // ── ATOM拖拽 ──
    var dragSrc by mutableStateOf<Int?>(null)
    var dragEnd by mutableStateOf(Offset.Zero)
    var isDragging by mutableStateOf(false)

    // ── 原子高亮 ──
    var selectedAtom by mutableStateOf<Int?>(null)
    var bondFirstAtom by mutableStateOf<Int?>(null)
    var bondSnapping by mutableStateOf(false)
    var bondEndMerge by mutableStateOf<List<MoleculeAtom>>(emptyList())

    // ── 选择工具 ──
    var selRectStart by mutableStateOf<Offset?>(null)
    var selRectEnd by mutableStateOf(Offset.Zero)
    var isSelDragging by mutableStateOf(false)
    var selectedIds by mutableStateOf<Set<Int>>(emptySet())
    var isMovingSelection by mutableStateOf(false)
    var moveStartOffset by mutableStateOf(Offset.Zero)
    var selTapStart by mutableStateOf<Offset?>(null)
    var isMoveMode by mutableStateOf(false) // 移动模式：开启后拖拽直接移动选中元素
    
    /** Selection 便捷访问（单例，通过 selectedIds 读写） */
    val selection = Selection(object : MutableSet<Int> {
        override val size: Int get() = selectedIds.size
        override fun contains(element: Int) = element in selectedIds
        override fun containsAll(elements: Collection<Int>) = selectedIds.containsAll(elements)
        override fun isEmpty() = selectedIds.isEmpty()
        override fun iterator() = selectedIds.iterator() as MutableIterator<Int>
        override fun add(element: Int): Boolean { selectedIds = selectedIds + element; return true }
        override fun addAll(elements: Collection<Int>): Boolean { selectedIds = selectedIds + elements; return true }
        override fun clear() { selectedIds = emptySet() }
        override fun remove(element: Int): Boolean { selectedIds = selectedIds - element; return true }
        override fun removeAll(elements: Collection<Int>): Boolean { selectedIds = selectedIds - elements.toSet(); return true }
        override fun retainAll(elements: Collection<Int>): Boolean { selectedIds = selectedIds.intersect(elements.toSet()); return true }
    }, atoms, bonds, annotations)
    
    // ── 移动辅助线 ──
    var showAlignGuides by mutableStateOf(false)
    var alignGuideX by mutableStateOf(Float.NaN)
    var alignGuideY by mutableStateOf(Float.NaN)

    // ── 变换组（选中即编组）──
    // 选中 >1 个原子时自动形成临时组
    var activeGroup: TransformGroup? by mutableStateOf(null)
    // 手柄操作版本号：每次手柄拖拽后递增，用于强制Canvas重绘
    var groupVersion by mutableIntStateOf(0)
    // 手柄拖拽：-1=无，0..7=8个手柄，8=旋转手柄
    var activeHandle by mutableIntStateOf(-1)
    var handleDragBase by mutableStateOf(Offset.Zero)
    var handleDragPrev by mutableStateOf(Offset.Zero)

/** 选中元素后创建/更新变换组（≥1个元素即创建，支持原子+标注混合） */
    fun refreshGroup() {
        if (selectedIds.isNotEmpty()) {
            val selAtomIds = selectedIds.filter { it > 0 }
            val selAnnIds = selectedIds.filter { it < 0 }
            val selAtoms = atoms.filter { it.id in selAtomIds }
            // 收集所有选中元素的坐标（原子用 x/y，标注用中心点）
            val allPoints = mutableListOf<Pair<Float, Float>>()
            allPoints.addAll(selAtoms.map { Pair(it.x, it.y) })
            for (aid in selAnnIds) {
                val ann = annotations.find { it.id == -aid } ?: continue
                if (ann.type == AnnotationType.ARROW) {
                    allPoints.add(Pair(ann.x, ann.y))
                    allPoints.add(Pair(ann.endX, ann.endY))
                } else {
                    allPoints.add(Pair(ann.x, ann.y))
                }
            }
            if (allPoints.isEmpty()) { activeGroup = null; return }
            val cx = allPoints.map { it.first }.average().toFloat()
            val cy = allPoints.map { it.second }.average().toFloat()
            val old = activeGroup
            activeGroup = TransformGroup(
                pivotX = cx, pivotY = cy,
                atomIds = selAtomIds.toMutableSet(),
                annotationIds = selAnnIds.map { -it }.toMutableSet(),
                bondIds = bonds.filter { it.atom1 in selAtomIds && it.atom2 in selAtomIds }.map { it.id }.toMutableSet(),
                translationX = old?.translationX ?: 0f,
                translationY = old?.translationY ?: 0f,
                rotation = old?.rotation ?: 0f,
                scaleX = old?.scaleX ?: 1f,
                scaleY = old?.scaleY ?: 1f
            )
        } else {
            activeGroup = null
        }
    }

    /** 取消选中时展开变换组（固化变换到原子坐标）*/
    fun collapseGroup() {
        val g = activeGroup ?: return
        if (g.translationX == 0f && g.translationY == 0f && g.rotation == 0f && g.scaleX == 1f && g.scaleY == 1f) {
            activeGroup = null
            return
        }
        // 将变换应用到每个选中原子
        val rad = Math.toRadians(g.rotation.toDouble())
        val cosR = cos(rad).toFloat(); val sinR = sin(rad).toFloat()
        for (aid in g.atomIds) {
            val a = atoms.find { it.id == aid } ?: continue
            // 先相对 pivot 缩放
            var lx = (a.x - g.pivotX) * g.scaleX
            var ly = (a.y - g.pivotY) * g.scaleY
            // 旋转
            val rx = lx * cosR - ly * sinR
            val ry = lx * sinR + ly * cosR
            // 平移回世界坐标
            a.x = g.pivotX + rx + g.translationX
            a.y = g.pivotY + ry + g.translationY
        }
        activeGroup = null
    }

    // ── 翻转（水平/垂直）──
    /**
     * 水平翻转选中原子组。
     * 以选中原子水平中轴为对称轴镜像坐标，WEDGE_UP ↔ WEDGE_DOWN 自动交换。
     */
    fun flipHorizontal() {
        if (selectedIds.size < 2) return
        pushUndo()
        collapseGroup() // 先固化当前变换
        activeGroup = null
        val selAtoms = atoms.filter { it.id in selectedIds }
        val midX = selAtoms.map { it.x }.average().toFloat()
        for (a in selAtoms) a.x = midX + (midX - a.x)
        val flippedBondIds = mutableListOf<Int>()
        for (i in bonds.indices) {
            val b = bonds[i]
            if (b.atom1 in selectedIds && b.atom2 in selectedIds) {
                when (b.type) {
                    BondType.WEDGE_UP -> { bonds[i] = b.copy(type = BondType.WEDGE_DOWN); flippedBondIds.add(b.id) }
                    BondType.WEDGE_DOWN -> { bonds[i] = b.copy(type = BondType.WEDGE_UP); flippedBondIds.add(b.id) }
                    else -> {}
                }
            }
        }
        refreshGroup()
        flipToast = if (flippedBondIds.isNotEmpty()) "水平翻转：已自动交换 ${flippedBondIds.size} 个楔形键方向" else "水平翻转完成"
    }

    /**
     * 垂直翻转选中原子组。
     * 以选中原子垂直中轴为对称轴镜像坐标，WEDGE_UP ↔ WEDGE_DOWN 自动交换。
     */
    fun flipVertical() {
        if (selectedIds.size < 2) return
        pushUndo()
        collapseGroup() // 先固化当前变换
        activeGroup = null
        val selAtoms = atoms.filter { it.id in selectedIds }
        val midY = selAtoms.map { it.y }.average().toFloat()
        for (a in selAtoms) a.y = midY + (midY - a.y)
        val flippedBondIds = mutableListOf<Int>()
        for (i in bonds.indices) {
            val b = bonds[i]
            if (b.atom1 in selectedIds && b.atom2 in selectedIds) {
                when (b.type) {
                    BondType.WEDGE_UP -> { bonds[i] = b.copy(type = BondType.WEDGE_DOWN); flippedBondIds.add(b.id) }
                    BondType.WEDGE_DOWN -> { bonds[i] = b.copy(type = BondType.WEDGE_UP); flippedBondIds.add(b.id) }
                    else -> {}
                }
            }
        }
        refreshGroup()
        flipToast = if (flippedBondIds.isNotEmpty()) "垂直翻转：已自动交换 ${flippedBondIds.size} 个楔形键方向" else "垂直翻转完成"
    }

    // ── 手动合并 ──
    /**
     * 合并选中的两个原子：将第二个原子删除，其键重定向到第一个原子。
     * 只有恰好选中 2 个原子时才可调用。
     */
    fun mergeSelectedAtoms() {
        if (selectedIds.size != 2) return
        pushUndo()
        val ids = selectedIds.toList()
        val a1 = atoms.find { it.id == ids[0] } ?: return
        val a2 = atoms.find { it.id == ids[1] } ?: return
        // 将 a2 的所有键重定向到 a1
        for (i in bonds.indices) {
            val b = bonds[i]
            if (b.atom1 == a2.id || b.atom2 == a2.id) {
                val newAtom1 = if (b.atom1 == a2.id) a1.id else b.atom1
                val newAtom2 = if (b.atom2 == a2.id) a1.id else b.atom2
                // 避免自环
                if (newAtom1 != newAtom2) {
                    bonds[i] = MoleculeBond(b.id, newAtom1, newAtom2, b.type)
                }
            }
        }
        // 删除重复键
        val seen = mutableSetOf<Pair<Int, Int>>()
        bonds.removeAll {
            val pair = if (it.atom1 < it.atom2) it.atom1 to it.atom2 else it.atom2 to it.atom1
            if (pair in seen) true else { seen.add(pair); false }
        }
        // a1 放到两原子中点
        a1.x = (a1.x + a2.x) / 2f; a1.y = (a1.y + a2.y) / 2f
        // 移除 a2
        atoms.remove(a2)
        selectedIds = setOf(a1.id)
        activeGroup = null
        flipToast = "已合并两个原子"
    }

    // ── 画布平移 ──
    var canvasOffsetX by mutableStateOf(0f)
    var canvasOffsetY by mutableStateOf(0f)

    // ── 文字工具 ──
    var textDialogPos by mutableStateOf<Offset?>(null)
    var textInput by mutableStateOf("")
    var textSubScript by mutableStateOf(false)
    var showTextDialog by mutableStateOf(false)

    // ── 箭头工具 ──
    var arrowStart by mutableStateOf<Offset?>(null)
    var arrowEnd by mutableStateOf(Offset.Zero)
    var isArrowDragging by mutableStateOf(false)

    // ── 缩放工具 ──
    var scaleValue by mutableStateOf("1.0")
    var scaleFactor by mutableStateOf(1f)
    var isScaleDragging by mutableStateOf(false)
    var scaleLastY by mutableStateOf(0f)

    // ── 放大镜 ──
    var magnifierPos by mutableStateOf<Offset?>(null)
    val canvasSize = mutableStateOf(Offset.Zero)

    // ── 延长碳链 ──
    var extendMode by mutableStateOf(false)
    var showExtendDialog by mutableStateOf(false)
    var extendStart by mutableStateOf<Offset?>(null)
    var extendInput by mutableStateOf("")

    // ── 双指缩放 ──
    var isPinchZooming by mutableStateOf(false)

    // ── 导入导出 ──
    var showImportDialog by mutableStateOf(false)
    var importInput by mutableStateOf("")
    var showExportDialog by mutableStateOf(false)
    // 项目文件保存/打开相关
    var pendingSaveJson by mutableStateOf("")
var saveFileName by mutableStateOf("")
var showSaveDialog by mutableStateOf(false)
var pendingOpenCallback by mutableStateOf<((String) -> Unit)?>(null)
    // 官能团缩写
    var showFgMenu by mutableStateOf(false)
    var isFuncGroupDragMode by mutableStateOf(false)
    var selectedFuncGroup by mutableStateOf<FunctionalGroup?>(null)

    // ── 翻转提示──
    var flipToast by mutableStateOf("")

    // ── 画布尺寸 ──
    var canvasSizePx by mutableStateOf(Size.Zero)

    // ── 撤销快捷方法 ──
    fun doUndo() {
        val result = undo.pop() ?: return
        atoms.clear(); bonds.clear(); annotations.clear()
        val (a, b, anns) = result
        atoms.addAll(a); bonds.addAll(b); annotations.addAll(anns)
        val maxId = (atoms.maxOfOrNull { it.id } ?: 0).coerceAtLeast(bonds.maxOfOrNull { it.id } ?: 0)
            .coerceAtLeast(annotations.maxOfOrNull { it.id } ?: 0)
        gNextId = maxId + 1
        undoCount = undo.count
    }

    fun pushUndo() {
        undo.push(atoms.toList(), bonds.toList(), annotations.toList())
        undoCount = undo.count
    }

    fun updateSelection() {
        val start = selRectStart ?: return
        val end = selRectEnd
        val minX = minOf(start.x, end.x) - canvasOffsetX; val maxX = maxOf(start.x, end.x) - canvasOffsetX
        val minY = minOf(start.y, end.y) - canvasOffsetY; val maxY = maxOf(start.y, end.y) - canvasOffsetY
        // 框选原子
        val atomIds = atoms.filter { a ->
            a.x >= minX && a.x <= maxX && a.y >= minY && a.y <= maxY
        }.map { it.id }.toSet()
        // 框选标注（文字用中心点，箭头用两端中点）
        val annIds = annotations.filter { ann ->
            val cx = if (ann.type == AnnotationType.ARROW) (ann.x + ann.endX) / 2f else ann.x
            val cy = if (ann.type == AnnotationType.ARROW) (ann.y + ann.endY) / 2f else ann.y
            cx >= minX && cx <= maxX && cy >= minY && cy <= maxY
        }.map { -it.id }.toSet()
        selectedIds = atomIds + annIds
    }

    // ── 选中元素移动（增量位移）──
    /** 将选中的原子和标注同时移动 (dx, dy)，并更新辅助线位置 */
    fun moveSelection(dx: Float, dy: Float) {
        if ((dx == 0f && dy == 0f) || abs(dx) + abs(dy) < 0.5f) return
        selection.moveBy(dx, dy)
        if (showAlignGuides) updateAlignGuides()
    }

    /** 根据当前选中元素计算辅助线位置（包围盒中心） */
    fun updateAlignGuides() {
        val pos = selection.alignGuidePos()
        if (pos != null) {
            alignGuideX = pos.first; alignGuideY = pos.second; showAlignGuides = true
        } else {
            showAlignGuides = false
        }
    }

    // ── 双指缩放选中元素 ──
    /** 以选中元素中心为基点缩放 factor 倍 */
    fun pinchZoomSelection(sf: Float) {
        selection.pinchZoom(sf)
    }
}
