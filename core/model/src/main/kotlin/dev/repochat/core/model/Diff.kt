package dev.repochat.core.model

/**
 * Line-based diff between old and new file contents, used by the diff view.
 * Uses a classic LCS dynamic-programming algorithm; falls back to a
 * whole-file replace diff when the input is too large to diff in memory.
 */
enum class DiffLineType { CONTEXT, ADD, REMOVE }

data class DiffLine(
    val type: DiffLineType,
    val oldLine: Int?,
    val newLine: Int?,
    val text: String,
)

data class DiffResult(
    val lines: List<DiffLine>,
    val additions: Int,
    val removals: Int,
)

object LineDiffer {

    private const val MAX_DP_CELLS = 4_000_000L

    fun splitLines(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        return normalized.split('\n').let { lines ->
            if (lines.size == 1 && lines[0].isEmpty()) emptyList() else lines
        }
    }

    fun diff(oldText: String, newText: String): DiffResult {
        val old = splitLines(oldText)
        val new = splitLines(newText)

        if (old.isEmpty() && new.isEmpty()) return DiffResult(emptyList(), 0, 0)
        if (old.size.toLong() * new.size.toLong() > MAX_DP_CELLS) {
            return fallbackDiff(old, new)
        }

        val ops = lcsOps(old, new)
        val lines = mutableListOf<DiffLine>()
        var o = 0
        var n = 0
        var additions = 0
        var removals = 0
        for (op in ops) {
            when (op) {
                Op.EQUAL -> {
                    lines += DiffLine(DiffLineType.CONTEXT, o + 1, n + 1, old[o])
                    o++
                    n++
                }
                Op.DELETE -> {
                    lines += DiffLine(DiffLineType.REMOVE, o + 1, null, old[o])
                    removals++
                    o++
                }
                Op.INSERT -> {
                    lines += DiffLine(DiffLineType.ADD, null, n + 1, new[n])
                    additions++
                    n++
                }
            }
        }
        return DiffResult(lines, additions, removals)
    }

    private fun fallbackDiff(old: List<String>, new: List<String>): DiffResult {
        val lines = mutableListOf<DiffLine>()
        var removals = 0
        var additions = 0
        old.forEachIndexed { i, text ->
            lines += DiffLine(DiffLineType.REMOVE, i + 1, null, text)
            removals++
        }
        new.forEachIndexed { i, text ->
            lines += DiffLine(DiffLineType.ADD, null, i + 1, text)
            additions++
        }
        return DiffResult(lines, additions, removals)
    }

    private enum class Op { EQUAL, DELETE, INSERT }

    private fun lcsOps(old: List<String>, new: List<String>): List<Op> {
        val n = old.size
        val m = new.size
        // dp[i][j] = LCS length of old[0..i) and new[0..j)
        val dp = IntArray((n + 1) * (m + 1))
        for (i in 1..n) {
            val row = i * (m + 1)
            val prevRow = (i - 1) * (m + 1)
            for (j in 1..m) {
                dp[row + j] = if (old[i - 1] == new[j - 1]) {
                    dp[prevRow + j - 1] + 1
                } else {
                    maxOf(dp[prevRow + j], dp[row + j - 1])
                }
            }
        }

        // Backtrack from the bottom-right corner to build the edit script.
        val ops = ArrayList<Op>(n + m)
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            when {
                old[i - 1] == new[j - 1] -> {
                    ops += Op.EQUAL
                    i--
                    j--
                }
                dp[i * (m + 1) + j - 1] >= dp[(i - 1) * (m + 1) + j] -> {
                    ops += Op.INSERT
                    j--
                }
                else -> {
                    ops += Op.DELETE
                    i--
                }
            }
        }
        while (i > 0) {
            ops += Op.DELETE
            i--
        }
        while (j > 0) {
            ops += Op.INSERT
            j--
        }
        ops.reverse()
        return ops
    }
}
