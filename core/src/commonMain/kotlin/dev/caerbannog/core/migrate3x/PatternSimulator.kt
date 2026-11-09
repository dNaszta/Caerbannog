package dev.caerbannog.core.migrate3x

/**
 * Runs a program over a name without backtracking: the set of live states is stepped one character at a time, so the cost is
 * at most (states x name length) whatever the pattern. The search is unanchored: the start state is added at every position.
 */
internal class PatternSimulator(private val program: List<Instruction>) {
    fun matchesSomewhere(name: String): Boolean {
        var current = StateSet(program.size)
        var next = StateSet(program.size)
        for (position in 0..name.length) {
            if (current.add(0, position, name.length)) return true
            if (position == name.length) return false
            val c = name[position]
            for (i in 0 until current.size) {
                val pc = current.states[i]
                val consume = program[pc] as Instruction.Consume
                if (consume.set.matches(c) && next.add(pc + 1, position + 1, name.length)) return true
            }
            current.clear()
            val done = current
            current = next
            next = done
        }
        return false
    }

    /** The states reachable at one position, each once. */
    private inner class StateSet(capacity: Int) {
        private val stamps = IntArray(capacity)
        private var generation = 1
        val states = IntArray(capacity)
        var size = 0
            private set

        fun clear() {
            generation++
            size = 0
        }

        /** Adds [pc] and everything reachable without reading a character; returns true when the match state is reached. */
        fun add(pc: Int, position: Int, length: Int): Boolean {
            if (stamps[pc] == generation) return false
            stamps[pc] = generation
            return when (val instruction = program[pc]) {
                is Instruction.Consume -> { states[size++] = pc; false }
                is Instruction.Jump -> add(instruction.target, position, length)
                is Instruction.Split -> add(instruction.first, position, length) || add(instruction.second, position, length)
                Instruction.AssertStart -> position == 0 && add(pc + 1, position, length)
                Instruction.AssertEnd -> position == length && add(pc + 1, position, length)
                Instruction.Match -> true
            }
        }
    }
}
