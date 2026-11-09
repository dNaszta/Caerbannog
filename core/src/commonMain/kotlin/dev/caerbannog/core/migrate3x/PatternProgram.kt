package dev.caerbannog.core.migrate3x

/** One NFA state. [Consume] moves to the next state, the others are decided without reading a character. */
internal sealed interface Instruction {
    data class Consume(val set: CharSet) : Instruction
    data class Split(val first: Int, val second: Int) : Instruction
    data class Jump(val target: Int) : Instruction
    data object AssertStart : Instruction
    data object AssertEnd : Instruction
    data object Match : Instruction
}

/** Turns a [PatternNode] tree into a list of [Instruction]s (Thompson construction), counting states so a pattern cannot blow up. */
internal class PatternProgramBuilder {
    private val code = mutableListOf<Instruction>()

    fun build(root: PatternNode): List<Instruction> {
        emit(root)
        add(Instruction.Match)
        return code.toList()
    }

    private fun add(instruction: Instruction): Int {
        if (code.size >= MAX_STATES) throw PatternException(PatternProblem.TooLarge)
        code += instruction
        return code.size - 1
    }

    private fun emit(node: PatternNode) {
        when (node) {
            PatternNode.Empty -> Unit
            is PatternNode.Character -> add(Instruction.Consume(node.set))
            PatternNode.LineStart -> add(Instruction.AssertStart)
            PatternNode.LineEnd -> add(Instruction.AssertEnd)
            is PatternNode.Sequence -> node.parts.forEach(::emit)
            is PatternNode.Choice -> emitChoice(node.branches)
            is PatternNode.Repeat -> emitRepeat(node)
        }
    }

    /** `a|b|c` becomes `split(a, split(b, c))`; every branch but the last ends with a jump to the end. */
    private fun emitChoice(branches: List<PatternNode>) {
        val jumpsToEnd = mutableListOf<Int>()
        branches.dropLast(1).forEach { branch ->
            val split = add(Instruction.Split(0, 0))
            emit(branch)
            jumpsToEnd += add(Instruction.Jump(0))
            code[split] = Instruction.Split(split + 1, code.size)
        }
        emit(branches.last())
        jumpsToEnd.forEach { code[it] = Instruction.Jump(code.size) }
    }

    /** `{n,m}` is expanded: `n` copies, then either a star (no maximum) or `m - n` nested optional copies. */
    private fun emitRepeat(node: PatternNode.Repeat) {
        repeat(node.min) { emit(node.body) }
        val max = node.max
        if (max == null) emitStar(node.body) else emitOptionalCopies(node.body, max - node.min)
    }

    private fun emitStar(body: PatternNode) {
        val split = add(Instruction.Split(0, 0))
        emit(body)
        add(Instruction.Jump(split))
        code[split] = Instruction.Split(split + 1, code.size)
    }

    private fun emitOptionalCopies(body: PatternNode, count: Int) {
        val splits = (0 until count).map {
            add(Instruction.Split(0, 0)).also { emit(body) }
        }
        splits.forEach { code[it] = Instruction.Split(it + 1, code.size) }
    }

    private companion object {
        /** The most states a pattern may have after expanding counted quantifiers. */
        const val MAX_STATES = 256
    }
}
