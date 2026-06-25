package com.moldraw.app.ui.moleculedraw

import androidx.compose.ui.geometry.Offset
import kotlin.math.*

enum class DrawTool { ATOM, BOND, RING, ERASE, SELECT, TEXT, ARROW, SCALE, AUTO_FIT, PAN }

class UndoManager(private val maxSize: Int = 30) {
    private val stack = mutableListOf<Triple<String, String, String>>()
    var count: Int = 0
        private set

    fun clear() { stack.clear(); count = 0 }

    fun push(atoms: List<MoleculeAtom>, bonds: List<MoleculeBond>, annotations: List<MoleculeAnnotation>) {
        val a = atoms.joinToString("|") { "${it.id},${it.x},${it.y},${it.element.name},${it.aromatic},${it.chiral},${it.funGroupLabel ?: ""},${it.isFunGroupConnector}" }
        val b = bonds.joinToString("|") { "${it.id},${it.atom1},${it.atom2},${it.type.name}" }
        val ann = annotations.joinToString("|") { "${it.id},${it.x},${it.y},${it.type.name},${it.endX},${it.endY},${it.scale},${it.text?.let { t -> t.replace("|", "/") } ?: ""}" }
        stack.add(Triple(a, b, ann))
        if (stack.size > maxSize) stack.removeAt(0)
        count = stack.size
    }

    fun pop(): Triple<List<MoleculeAtom>, List<MoleculeBond>, List<MoleculeAnnotation>>? {
        if (stack.isEmpty()) return null
        val (aj, bj, annj) = stack.removeAt(stack.lastIndex)
        count = stack.size
        val atoms = if (aj.isEmpty()) emptyList()
            else aj.split("|").map { s ->
                val p = s.split(",", limit = 8)
                MoleculeAtom(
                    p[0].toInt(), p[1].toFloat(), p[2].toFloat(),
                    Element.valueOf(p[3]),
                    aromatic = p.getOrNull(4)?.toBooleanStrictOrNull() ?: false,
                    chiral = p.getOrNull(5) ?: "",
                    funGroupLabel = p.getOrNull(6)?.takeIf { it.isNotEmpty() },
                    isFunGroupConnector = p.getOrNull(7)?.toBooleanStrictOrNull() ?: false
                )
            }
        val bonds = if (bj.isEmpty()) emptyList()
            else bj.split("|").map { s -> val p = s.split(","); MoleculeBond(p[0].toInt(), p[1].toInt(), p[2].toInt(), BondType.valueOf(p[3])) }
        val annotations = if (annj.isEmpty()) emptyList()
            else annj.split("|").map { s -> val p = s.split(",", limit = 8); MoleculeAnnotation(p[0].toInt(), AnnotationType.valueOf(p[3]), p[1].toFloat(), p[2].toFloat(), p.getOrNull(7)?.replace("/", "|") ?: "", p[4].toFloat(), p[5].toFloat(), p[6].toFloat()) }
        return Triple(atoms, bonds, annotations)
    }
}