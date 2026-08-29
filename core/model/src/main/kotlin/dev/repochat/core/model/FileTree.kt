package dev.repochat.core.model

/**
 * Turns the repository file tree into a compact, bounded string that is safe
 * to embed in an LLM prompt. Huge trees are truncated with an explicit note so
 * we never dump an unbounded payload into the model context.
 */
object FileTreeFormatter {

    private const val MAX_ENTRIES = 2_500
    private const val MAX_CHARS = 45_000
    private const val MAX_LINE_LENGTH = 120

    fun format(entries: List<TreeEntry>): String {
        if (entries.isEmpty()) return "(empty repository — no files yet)"

        val sorted = entries.sortedWith(
            compareBy({ if (it.type == "tree") 0 else 1 }, { it.path.lowercase() })
        )
        val builder = StringBuilder()
        var shown = 0
        for (entry in sorted) {
            if (shown >= MAX_ENTRIES) break
            val suffix = if (entry.type == "tree") "/" else ""
            val line = (entry.path + suffix).take(MAX_LINE_LENGTH)
            if (builder.length + line.length + 1 > MAX_CHARS) break
            builder.append(line).append('\n')
            shown++
        }
        if (shown < sorted.size) {
            builder.append("\n... (tree truncated: showing ").append(shown)
                .append(" of ").append(sorted.size).append(" entries)\n")
        }
        return builder.toString().trimEnd()
    }
}
