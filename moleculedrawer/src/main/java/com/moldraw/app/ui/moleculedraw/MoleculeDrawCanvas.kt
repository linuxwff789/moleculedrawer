package com.moldraw.app.ui.moleculedraw

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.*

// ═══════════════════════════════════════════════
// 画布预览绘制（DrawScope 扩展函数）
// 被主画布和放大镜共用：传 scale/tx/ty 即放大变换，不传即 1:1
// ═══════════════════════════════════════════════

fun DrawScope.drawCanvasContent(
    atoms: List<MoleculeAtom>,
    bonds: List<MoleculeBond>,
    annotations: List<MoleculeAnnotation>,
    tool: DrawTool,
    ringType: RingType,
    selectedAtom: Int?,
    // 多选高亮（selectedIds）
    selectedIds: Set<Int> = emptySet(),
    // ATOM拖拽
    isDragging: Boolean,
    dragSrc: Int?,
    dragEnd: Offset,
    // BOND拖拽
    isBondDragging: Boolean,
    bondStart: Offset?,
    bondCur: Offset,
    bondFirstAtom: Int?,
    bondSnapping: Boolean,
    bondEndMerge: List<MoleculeAtom>,
    // 选框
    isSelDragging: Boolean,
    selRectStart: Offset?,
    selRectEnd: Offset,
    // 箭头
    isArrowDragging: Boolean,
    arrowEnd: Offset,
    arrowStart: Offset?,
    // 缩放
    isScaleDragging: Boolean,
    // 键类型
    selBond: BondType,
    // 变换组
    activeGroup: TransformGroup? = null,
    // 苯环样式（凯库勒/鲍林）
    benzeneStyle: BenzeneStyle = BenzeneStyle.KEKULE,
    // 移动辅助线
    showAlignGuides: Boolean = false,
    alignGuideX: Float = Float.NaN,
    alignGuideY: Float = Float.NaN,
    // 放大变换（可选，不传 = 1:1 主画布）
    scale: Float = 1f,
    tx: ((Float) -> Float)? = null,
    ty: ((Float) -> Float)? = null
) {
    // 坐标变换辅助
    val xf = { x: Float -> tx?.invoke(x) ?: x }
    val yf = { y: Float -> ty?.invoke(y) ?: y }
    val sf = scale
    // 带缩放的点
    fun pt(x: Float, y: Float) = Offset(xf(x), yf(y))

    // ── 绘制已有内容（应用 activeGroup 变换到选中原子）──
    // 计算 group 变换辅助函数
    fun transformX(a: MoleculeAtom): Float {
        val g = activeGroup ?: return xf(a.x)
        if (a.id !in g.atomIds) return xf(a.x)
        val rad = Math.toRadians(g.rotation.toDouble())
        val cosR = cos(rad).toFloat(); val sinR = sin(rad).toFloat()
        val lx = (a.x - g.pivotX) * g.scaleX
        val ly = (a.y - g.pivotY) * g.scaleY
        val rx = lx * cosR - ly * sinR
        return xf(g.pivotX + rx + g.translationX)
    }
    fun transformY(a: MoleculeAtom): Float {
        val g = activeGroup ?: return yf(a.y)
        if (a.id !in g.atomIds) return yf(a.y)
        val rad = Math.toRadians(g.rotation.toDouble())
        val cosR = cos(rad).toFloat(); val sinR = sin(rad).toFloat()
        val lx = (a.x - g.pivotX) * g.scaleX
        val ly = (a.y - g.pivotY) * g.scaleY
        val ry = lx * sinR + ly * cosR
        return yf(g.pivotY + ry + g.translationY)
    }
    for (b in bonds) {
        val a1 = atoms.find { it.id == b.atom1 } ?: continue
        val a2 = atoms.find { it.id == b.atom2 } ?: continue
        // 跳过官能团组内键（如 N=O 双键），缩写模式下不显示
        // 两个原子属于同一官能团时才跳过（组内键）
        if (a1.funGroupLabel != null && a1.funGroupLabel == a2.funGroupLabel) continue
        // AROMATIC 键不在主循环画，由后面的统一芳香环渲染处理
        if (b.type == BondType.AROMATIC) continue
        drawBond(
            MoleculeAtom(a1.id, transformX(a1), transformY(a1), a1.element),
            MoleculeAtom(a2.id, transformX(a2), transformY(a2), a2.element),
            b.type
        )
    }
    // 跳过被 FUNC_GROUP 标注覆盖的原子（有 funGroupLabel 的都跳过，标注文字替代渲染）
    val coveredAtomIds = atoms.filter { it.funGroupLabel != null }.map { it.id }.toSet()
    if (coveredAtomIds.isNotEmpty()) android.util.Log.d("MOLDRAW_DEBUG", "[drawCanvas] coveredAtomIds=$coveredAtomIds (funGroupLabel)")
    for (a in atoms) {
        if (a.id in coveredAtomIds) continue // 被 FUNC_GROUP 覆盖，跳过
        drawAtom(MoleculeAtom(a.id, transformX(a), transformY(a), a.element), bonds, annotations)
    }

    // ── 选中高亮（多选时蓝色外圈）──
    if (selectedIds.isNotEmpty()) {
        for (aid in selectedIds) {
            if (aid < 0) {
                // 负ID：标注 — 选中时改变颜色来标识
            } else {
                val a = atoms.find { it.id == aid } ?: continue
                // 跳过官能团组内原子（不显示）
                if (a.funGroupLabel != null) continue
                val px = transformX(a); val py = transformY(a)
                drawCircle(Color(0xFF2196F3).copy(alpha = 0.3f), (HETERO_CIRCLE_R + 8f) * sf, Offset(px, py))
                drawCircle(Color(0xFF2196F3).copy(alpha = 0.55f), (HETERO_CIRCLE_R + 3f) * sf, Offset(px, py))
            }
        }
    }

    // 绘制标注（选中时用蓝色）
    for (ann in annotations) {
        val annSelected = -ann.id in selectedIds
        when (ann.type) {
            AnnotationType.TEXT, AnnotationType.FUNC_GROUP -> {
                val drawX = if (ann.type == AnnotationType.FUNC_GROUP) {
                    // FUNC_GROUP 文字放在连接点原子位置（有 funGroupLabel + isFunGroupConnector 的原子）
                    atoms.find { it.funGroupLabel == ann.text && it.isFunGroupConnector }
                        ?.let { xf(it.x) } ?: xf(ann.x)
                } else xf(ann.x)
                val drawY = if (ann.type == AnnotationType.FUNC_GROUP) {
                    atoms.find { it.funGroupLabel == ann.text && it.isFunGroupConnector }
                        ?.let { yf(it.y) } ?: yf(ann.y)
                } else yf(ann.y)
                drawAnnotationText(ann.copy(x = drawX, y = drawY), selected = annSelected)
            }
            AnnotationType.ARROW -> drawAnnotationArrow(
                ann.copy(x = xf(ann.x), y = yf(ann.y), endX = xf(ann.endX), endY = yf(ann.endY)),
                selected = annSelected
            )
        }
    }

    // ── ATOM工具拖拽高亮 ──
    if (isDragging && dragSrc != null) {
        val src = atoms.find { it.id == dragSrc }
        if (src != null) {
            val sp = pt(src.x, src.y)
            drawCircle(Color(0xFFFF8800).copy(alpha = 0.45f), (HETERO_CIRCLE_R + 8f) * sf, sp)
            drawCircle(Color(0xFFFF8800).copy(alpha = 0.7f), (HETERO_CIRCLE_R + 3f) * sf, sp)
        }
        val dp = pt(dragEnd.x, dragEnd.y)
        drawCircle(Color(0xFF2196F3).copy(alpha = 0.35f), (CARBON_DOT_R + 6f) * sf, dp)
        val srcAtom = dragSrc?.let { id -> atoms.find { it.id == id } }
        if (srcAtom != null) {
            val spt = pt(srcAtom.x, srcAtom.y)
            val ddx = dragEnd.x - srcAtom.x; val ddy = dragEnd.y - srcAtom.y
            val dist = sqrt(ddx * ddx + ddy * ddy)
            if (dist > 1f) {
                drawLine(Color(0xFF2196F3).copy(alpha = 0.5f), spt, dp, strokeWidth = 2f * sf)
            }
            if (dist > BOND_LENGTH) {
                val ratio = BOND_LENGTH / dist
                val limitedX = srcAtom.x + ddx * ratio
                val limitedY = srcAtom.y + ddy * ratio
                val deg = Math.toDegrees(atan2((limitedY - srcAtom.y).toDouble(), (limitedX - srcAtom.x).toDouble())).toFloat()
                val snapped = snapAngle(deg)
                val rad = Math.toRadians(snapped.toDouble())
                val snapX = srcAtom.x + BOND_LENGTH * cos(rad).toFloat()
                val snapY = srcAtom.y + BOND_LENGTH * sin(rad).toFloat()
                val snapPt = pt(snapX, snapY)
                drawLine(Color(0xFF2196F3).copy(alpha = 0.5f), spt, snapPt, strokeWidth = 2f * sf)
                for (i in 0 until 8) {
                    val ar = Math.toRadians((i * 45f).toDouble())
                    drawCircle(Color(0xFFBBBBCC), 2.5f * sf, pt(
                        srcAtom.x + BOND_LENGTH * 0.35f * cos(ar).toFloat(),
                        srcAtom.y + BOND_LENGTH * 0.35f * sin(ar).toFloat()
                    ))
                }
                drawCircle(Color(0xFF2196F3).copy(alpha = 0.65f), (CARBON_DOT_R + 4f) * sf, snapPt)
            } else {
                // 自由拖拽模式下也显示吸附预览
                val deg = Math.toDegrees(atan2(ddy.toDouble(), ddx.toDouble())).toFloat()
                val snapped = snapAngle(deg)
                val rad = Math.toRadians(snapped.toDouble())
                val snapX = srcAtom.x + BOND_LENGTH * cos(rad).toFloat()
                val snapY = srcAtom.y + BOND_LENGTH * sin(rad).toFloat()
                val snapPt = pt(snapX, snapY)
                drawLine(Color(0xFF2196F3).copy(alpha = 0.3f), spt, snapPt, strokeWidth = 1.5f * sf)
                for (i in 0 until 8) {
                    val ar = Math.toRadians((i * 45f).toDouble())
                    drawCircle(Color(0xFFBBBBCC), 2.5f * sf, pt(
                        srcAtom.x + BOND_LENGTH * 0.35f * cos(ar).toFloat(),
                        srcAtom.y + BOND_LENGTH * 0.35f * sin(ar).toFloat()
                    ))
                }
            }
        }
    }

    // ── 环/键工具拖拽预览 ──
    if (isBondDragging && bondStart != null) {
        if (tool == DrawTool.RING) {
            val n = ringType.n
            val (ringPoints, merged) = computeRingVertices(atoms, bondCur.x, bondCur.y, n)
            val mergedAtoms = merged

            // 计算变换后的顶点位置（用于绘制）
            val previewVerts = Array(n) { i ->
                val raw = ringPoints[i]
                if (i in merged) {
                    val ma = mergedAtoms[i]!!
                    Offset(xf(ma.x), yf(ma.y))
                } else {
                    Offset(xf(raw.first), yf(raw.second))
                }
            }

            // 绘制环（顶点 + 边）
            val previewColor = Color(0xFF2196F3).copy(alpha = 0.5f)
            for (i in 0 until n) {
                val p = previewVerts[i]
                drawCircle(previewColor, 4f * sf, p)
                val q = previewVerts[(i + 1) % n]
                if (ringType.benzene && i % 2 == 1) {
                    val ex = q.x - p.x; val ey = q.y - p.y
                    val elen = sqrt(ex * ex + ey * ey); if (elen == 0f) continue
                    val eux = -ey / elen; val euy = ex / elen
                    val offset = 3f * sf
                    drawLine(previewColor, Offset(p.x + eux * offset, p.y + euy * offset), Offset(q.x + eux * offset, q.y + euy * offset), strokeWidth = 2f)
                    drawLine(previewColor, Offset(p.x - eux * offset, p.y - euy * offset), Offset(q.x - eux * offset, q.y - euy * offset), strokeWidth = 2f)
                } else {
                    drawLine(previewColor, p, q, strokeWidth = 2f)
                }
            }

            // 吸附高亮标记
            for ((_, ma) in mergedAtoms) {
                drawCircle(Color(0xFF4CAF50).copy(alpha = 0.45f), (HETERO_CIRCLE_R + 6f) * sf, pt(ma.x, ma.y))
                drawCircle(Color(0xFF4CAF50).copy(alpha = 0.7f), (HETERO_CIRCLE_R + 2f) * sf, pt(ma.x, ma.y))
            }
        } else if (tool == DrawTool.BOND) {
            if (bondSnapping && bondEndMerge.isEmpty()) {
                // 角度吸附预览（仅当没有合并到原子时显示）
                bondFirstAtom?.let { fid ->
                    atoms.find { it.id == fid }?.let { a ->
                        val ap = pt(a.x, a.y)
                        drawCircle(Color(0xFF2196F3).copy(alpha = 0.3f), (HETERO_CIRCLE_R + 12f) * sf, ap)
                        drawCircle(Color(0xFF2196F3).copy(alpha = 0.5f), (HETERO_CIRCLE_R + 6f) * sf, ap)
                        for (i in 0 until 8) {
                            val ar = Math.toRadians((i * 45f).toDouble())
                            drawCircle(Color(0xFFBBBBCC), 2.5f * sf, pt(
                                a.x + BOND_LENGTH * 0.35f * cos(ar).toFloat(),
                                a.y + BOND_LENGTH * 0.35f * sin(ar).toFloat()
                            ))
                        }
                        val cp = pt(bondCur.x, bondCur.y)
                        val ddx = cp.x - ap.x; val ddy = cp.y - ap.y
                        if (sqrt(ddx * ddx + ddy * ddy) > 1f) {
                            drawLine(Color(0xFF2196F3).copy(alpha = 0.5f), ap, cp, strokeWidth = 2.5f)
                        }
                        drawCircle(Color(0xFF2196F3).copy(alpha = 0.65f), (CARBON_DOT_R + 4f) * sf, cp)
                        val p = Paint().apply { color = android.graphics.Color.parseColor("#2196F3"); textSize = FONT_SIZE; isAntiAlias = true }
                        drawContext.canvas.nativeCanvas.drawText("吸附", ap.x + (HETERO_CIRCLE_R + 20f) * sf, ap.y - 10f * sf, p)
                    }
                }
            } else {
                // 合并预览或自由拖拽
                val startP = bondStart!!
                val sp = pt(startP.x, startP.y)
                val cp = pt(bondCur.x, bondCur.y)
                drawLine(Color(0xFF2196F3).copy(alpha = 0.4f), sp, cp, strokeWidth = 2.5f)
                if (bondEndMerge.isNotEmpty()) {
                    for (em in bondEndMerge) {
                        val ep = pt(em.x, em.y)
                        drawCircle(Color(0xFFFF6600).copy(alpha = 0.4f), (HETERO_CIRCLE_R + 8f) * sf, ep)
                        drawCircle(Color(0xFFFF6600).copy(alpha = 0.5f), (HETERO_CIRCLE_R + 4f) * sf, ep)
                    }
                    val firstEm = bondEndMerge.first()
                    val fp = pt(firstEm.x, firstEm.y)
                    val p = Paint().apply { color = android.graphics.Color.parseColor("#FF6600"); textSize = FONT_SIZE; isAntiAlias = true }
                    drawContext.canvas.nativeCanvas.drawText("合并", fp.x + (HETERO_CIRCLE_R + 4f) * sf, fp.y - 6f * sf, p)
                }
            }
        }
    }

    // ── 键工具起点高亮 ──
    if (tool == DrawTool.BOND && bondFirstAtom != null) {
        atoms.find { it.id == bondFirstAtom }?.let {
            val ap = pt(it.x, it.y)
            drawCircle(Color(0xFF2196F3).copy(alpha = 0.35f), (HETERO_CIRCLE_R + 8f) * sf, ap)
            drawCircle(Color(0xFF2196F3).copy(alpha = 0.6f), (HETERO_CIRCLE_R + 3f) * sf, ap)
        }
    }

    // ── 重叠提示（多原子） ──
    if (isBondDragging && bondStart != null && (tool == DrawTool.ATOM || tool == DrawTool.BOND)) {
        for (overlap in findMergeAtoms(atoms, bondCur)) {
            val op = pt(overlap.x, overlap.y)
            drawCircle(Color(0xFFFF6600).copy(alpha = 0.4f), (HETERO_CIRCLE_R + 5f) * sf, op)
            drawCircle(Color(0xFFFF6600).copy(alpha = 0.2f), (HETERO_CIRCLE_R + 10f) * sf, op)
        }
    }
    if (isDragging && dragSrc != null) {
        for (overlap in findMergeAtoms(atoms, dragEnd)) {
            if (atoms.find { it.id == dragSrc }?.id != overlap.id) {
                val op = pt(overlap.x, overlap.y)
                drawCircle(Color(0xFFFF6600).copy(alpha = 0.4f), (HETERO_CIRCLE_R + 5f) * sf, op)
                drawCircle(Color(0xFFFF6600).copy(alpha = 0.2f), (HETERO_CIRCLE_R + 10f) * sf, op)
            }
        }
    }

    // ── 选择矩形框 ──
    if (isSelDragging && selRectStart != null) {
        val start = selRectStart!!
        val end = selRectEnd
        val minX = minOf(start.x, end.x); val maxX = maxOf(start.x, end.x)
        val minY = minOf(start.y, end.y); val maxY = maxOf(start.y, end.y)
        drawRect(
            color = Color(0xFF2196F3).copy(alpha = 0.12f),
            topLeft = Offset(minX, minY),
            size = Size(maxX - minX, maxY - minY)
        )
        drawRect(
            color = Color(0xFF2196F3).copy(alpha = 0.6f),
            topLeft = Offset(minX, minY),
            size = Size(maxX - minX, maxY - minY),
            style = Stroke(width = 1.5f)
        )
    }

    // ── 箭头拖拽预览 ──
    if (isArrowDragging && arrowStart != null) {
        val ex = arrowEnd.x; val ey = arrowEnd.y
        val sx = arrowStart!!.x; val sy = arrowStart!!.y
        val dx = ex - sx; val dy = ey - sy; val len = sqrt(dx * dx + dy * dy)
        if (len > 5f) {
            drawLine(Color(0xFF2196F3).copy(alpha = 0.5f), Offset(sx, sy), Offset(ex, ey), strokeWidth = 2.5f)
            val ux = dx / len; val uy = dy / len
            val headSize = 12f
            val angle = Math.toRadians(25.0)
            val cosA = cos(angle).toFloat(); val sinA = sin(angle).toFloat()
            val lx = ex - ux * headSize * cosA + uy * headSize * sinA
            val ly = ey - uy * headSize * cosA - ux * headSize * sinA
            val rx = ex - ux * headSize * cosA - uy * headSize * sinA
            val ry = ey - uy * headSize * cosA + ux * headSize * sinA
            drawLine(Color(0xFF2196F3).copy(alpha = 0.5f), Offset(ex, ey), Offset(lx, ly), strokeWidth = 2.5f)
            drawLine(Color(0xFF2196F3).copy(alpha = 0.5f), Offset(ex, ey), Offset(rx, ry), strokeWidth = 2.5f)
        }
    }

    // ── 缩放拖拽预览 ──
    if (isScaleDragging) {
        val selected = selectedAtom?.let { id -> atoms.find { it.id == id } }
        if (selected != null) {
            drawCircle(Color(0xFF4CAF50).copy(alpha = 0.25f), (HETERO_CIRCLE_R + 12f) * sf, pt(selected.x, selected.y))
        }
    }

    // ── 变换组操作手柄（已禁用）──
    // if (activeGroup != null && (activeGroup.atomIds.isNotEmpty() || activeGroup.annotationIds.isNotEmpty())) {
    //     drawHandles(activeGroup, atoms, annotations, xf, yf)
    // }

    // ── 芳香环渲染（按 benzeneStyle） ──
    try {
        val rings = findPaulingRings(atoms, bonds)
        if (benzeneStyle == BenzeneStyle.PAULING) {
            // 鲍林式：实线键 + 内切圆
            // 实线键由 findPaulingRings 识别的环绘制
            for (ring0 in rings) {
                val ring = ring0 as List<Pair<Float, Float>>
                for (i in 0 until ring.size) {
                    val j = (i + 1) % ring.size
                    val p1 = ring[i]; val p2 = ring[j]
                    val a1 = MoleculeAtom(0, p1.first, p1.second, Element.C)
                    val a2 = MoleculeAtom(0, p2.first, p2.second, Element.C)
                    drawBond(MoleculeAtom(0, xf(a1.x), yf(a1.y), Element.C),
                             MoleculeAtom(0, xf(a2.x), yf(a2.y), Element.C),
                             BondType.SINGLE)
                }
                // 内切圆
                val verts = ring.mapNotNull { (x, y) -> Offset(xf(x), yf(y)) }
                if (verts.size == 6) drawPaulingCircle(verts)
            }
        } else {
            // 凯库勒式：单双交替
            for (ring0 in rings) {
                val ring = ring0 as List<Pair<Float, Float>>
                for (i in 0 until ring.size) {
                    val j = (i + 1) % ring.size
                    val p1 = ring[i]; val p2 = ring[j]
                    val a1 = MoleculeAtom(0, xf(p1.first), yf(p1.second), Element.C)
                    val a2 = MoleculeAtom(0, xf(p2.first), yf(p2.second), Element.C)
                    if (i % 2 == 0) {
                        drawBond(a1, a2, BondType.SINGLE)
                    } else {
                        drawBond(a1, a2, BondType.DOUBLE)
                    }
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("MOLDRAW_DEBUG", "Aromatic ring render: ${e.message}")
    }

    // ── 移动辅助线（选中元素拖拽时显示十字参考线）──
    if (showAlignGuides && !alignGuideX.isNaN() && !alignGuideY.isNaN()) {
        val ax = xf(alignGuideX); val ay = yf(alignGuideY)
        val guideColor = Color(0xFFFF9800).copy(alpha = 0.6f)
        // 水平线：从画布左到右
        drawLine(guideColor, Offset(0f, ay), Offset(size.width, ay), strokeWidth = 1.5f)
        // 垂直线：从上到下
        drawLine(guideColor, Offset(ax, 0f), Offset(ax, size.height), strokeWidth = 1.5f)
        // 中心圆圈
        drawCircle(guideColor, 4f * sf, Offset(ax, ay))
    }
}