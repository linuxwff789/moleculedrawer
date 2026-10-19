package com.moldraw.app.ui.moleculedraw

import androidx.compose.ui.geometry.Offset
import kotlin.math.*

enum class DrawTool { ATOM, BOND, RING, ERASE, SELECT, TEXT, ARROW, SCALE, AUTO_FIT, PAN }

/**
 * 撤销 / 重做管理器。
 *
 * 存储形态是三个字符串快照（原子 / 键 / 标注），避免 UI 状态对象被历史栈持有。
 * 规则与桌面编辑器一致：push 清空重做栈；undo 把“当前状态”压进重做栈再取出上一份；
 * redo 反向操作。两侧各自限制 maxSize。
 */
class UndoManager(private val maxSize: Int = 30) {
    private data class Snapshot(val atoms: String, val bonds: String, val anns: String)

    private val stack = mutableListOf<Snapshot>()
    private val redoStack = mutableListOf<Snapshot>()

    var count: Int = 0
        private set
    var redoCount: Int = 0
        private set

    private fun sync() {
        count = stack.size
        redoCount = redoStack.size
    }

    private fun encode(
        atoms: List<MoleculeAtom>,
        bonds: List<MoleculeBond>,
        annotations: List<MoleculeAnnotation>
    ): Snapshot {
        val a = atoms.joinToString("|") {
            "${it.id},${it.x},${it.y},${it.element.name},${it.aromatic},${it.chiral},${it.funGroupLabel ?: ""},${it.isFunGroupConnector}"
        }
        val b = bonds.joinToString("|") { "${it.id},${it.atom1},${it.atom2},${it.type.name}" }
        val ann = annotations.joinToString("|") {
            "${it.id},${it.x},${it.y},${it.type.name},${it.endX},${it.endY},${it.scale},${it.role ?: ""},${
                it.text.replace("|", "/")
            },${it.subScript}"
        }
        return Snapshot(a, b, ann)
    }

    private fun decode(snap: Snapshot): Triple<List<MoleculeAtom>, List<MoleculeBond>, List<MoleculeAnnotation>> {
        val atoms = if (snap.atoms.isEmpty()) emptyList()
            else snap.atoms.split("|").map { s ->
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
        val bonds = if (snap.bonds.isEmpty()) emptyList()
            else snap.bonds.split("|").map { s ->
                val p = s.split(",")
                MoleculeBond(p[0].toInt(), p[1].toInt(), p[2].toInt(), BondType.valueOf(p[3]))
            }
        val annotations = if (snap.anns.isEmpty()) emptyList()
            else snap.anns.split("|").map { s ->
                val p = s.split(",", limit = 10)
                MoleculeAnnotation(
                    p[0].toInt(), AnnotationType.valueOf(p[3]), p[1].toFloat(), p[2].toFloat(),
                    p.getOrNull(8)?.replace("/", "|") ?: "", p[4].toFloat(), p[5].toFloat(), p[6].toFloat(),
                    subScript = p.getOrNull(9)?.toBooleanStrictOrNull() ?: false,
                    role = p.getOrNull(7)?.takeIf { it.isNotEmpty() }
                )
            }
        return Triple(atoms, bonds, annotations)
    }

    fun clear() {
        stack.clear()
        redoStack.clear()
        sync()
    }

    /** 记录一次编辑前的状态；任何新编辑都会让重做链失效。 */
    fun push(atoms: List<MoleculeAtom>, bonds: List<MoleculeBond>, annotations: List<MoleculeAnnotation>) {
        stack.add(encode(atoms, bonds, annotations))
        if (stack.size > maxSize) stack.removeAt(0)
        redoStack.clear()
        sync()
    }

    /** @param current 撤销前的当前画布，会进入重做栈 */
    fun undo(
        current: List<MoleculeAtom>,
        currentBonds: List<MoleculeBond>,
        currentAnns: List<MoleculeAnnotation>
    ): Triple<List<MoleculeAtom>, List<MoleculeBond>, List<MoleculeAnnotation>>? {
        if (stack.isEmpty()) return null
        val target = stack.removeAt(stack.lastIndex)
        redoStack.add(encode(current, currentBonds, currentAnns))
        if (redoStack.size > maxSize) redoStack.removeAt(0)
        sync()
        return decode(target)
    }

    /** @param current 重做前的当前画布，会回到撤销栈 */
    fun redo(
        current: List<MoleculeAtom>,
        currentBonds: List<MoleculeBond>,
        currentAnns: List<MoleculeAnnotation>
    ): Triple<List<MoleculeAtom>, List<MoleculeBond>, List<MoleculeAnnotation>>? {
        if (redoStack.isEmpty()) return null
        val target = redoStack.removeAt(redoStack.lastIndex)
        stack.add(encode(current, currentBonds, currentAnns))
        if (stack.size > maxSize) stack.removeAt(0)
        sync()
        return decode(target)
    }
}
