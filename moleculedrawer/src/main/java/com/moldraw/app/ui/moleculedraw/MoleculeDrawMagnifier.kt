package com.moldraw.app.ui.moleculedraw

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/** 放大镜组件，2x 放大手指附近区域 */
@Composable
fun MoleculeDrawMagnifier(
    magnifierPos: Offset?,
    state: MoleculeDrawState
) {
    val mp = magnifierPos ?: return
    val s = state

    val density = LocalDensity.current
    val mgSizeDp = 240.dp
    val mgSizePx: Float = with(density) { mgSizeDp.toPx() }
    val halfSize: Float = mgSizePx / 2f

    // 计算放大镜位置：默认手指上方，上方不够时翻到下方
    val gapAbove = 10f // 手指上方留空
    val gapBelow = 30f // 手指下方留空
    var mx: Float = mp.x + 20f
    var my: Float = mp.y - halfSize - gapAbove       // 默认在上方（中心在手指上方 halfSize+gap）
    val canvasW = (s.canvasSize.value.x).coerceAtLeast(1f)
    val canvasH = (s.canvasSize.value.y).coerceAtLeast(1f)
    if (mx + halfSize > canvasW) mx = mp.x - mgSizePx - 10f
    if (mx < halfSize) mx = halfSize
    // 上方不够时翻转到下方
    if (my - halfSize < 0f) {
        my = mp.y + halfSize + gapBelow              // 下方：中心在手指下方 halfSize+gap
    }
    if (my + halfSize > canvasH) my = canvasH - halfSize // 贴底防溢出

    Box(
        modifier = Modifier
            .offset { IntOffset((mx - halfSize).toInt(), (my - halfSize).toInt()) }
            .size(mgSizeDp)
            .clip(CircleShape)
            .background(Color(0xEEFFFFFF))
            .border(2.dp, Color(0xFF333333), CircleShape)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val magnify = 2f
            val offX = mgSizePx / 2f - (mp.x - s.canvasOffsetX) * magnify
            val offY = mgSizePx / 2f - (mp.y - s.canvasOffsetY) * magnify
            val tx = { x: Float -> x * magnify + offX }
            val ty = { y: Float -> y * magnify + offY }
            drawCanvasContent(
                atoms = s.atoms, bonds = s.bonds, annotations = s.annotations,
                tool = s.tool, ringType = s.ringType, selectedAtom = s.selectedAtom,
                isDragging = s.isDragging, dragSrc = s.dragSrc, dragEnd = s.dragEnd,
                isBondDragging = s.isBondDragging, bondStart = s.bondStart, bondCur = s.bondCur,
                bondFirstAtom = s.bondFirstAtom, bondSnapping = s.bondSnapping,
                bondEndMerge = s.bondEndMerge,
                isSelDragging = s.isSelDragging, selRectStart = s.selRectStart, selRectEnd = s.selRectEnd,
                isArrowDragging = s.isArrowDragging, arrowEnd = s.arrowEnd, arrowStart = s.arrowStart,
                isScaleDragging = s.isScaleDragging, selBond = s.selBond, benzeneStyle = s.benzeneStyle,
                activeGroup = s.activeGroup,
                scale = magnify, tx = tx, ty = ty
            )
        }
    }
}