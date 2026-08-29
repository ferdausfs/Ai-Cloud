package dev.repochat.core.model

/**
 * Builds every prompt sent to the LLM. The system prompt enforces the strict
 * JSON tool contract; context messages carry the task, the file tree and any
 * file contents the model requested.
 */
object PromptBuilder {

    fun system(): String = """
        You are RepoChat, an AI software engineer embedded in a mobile app that edits files in a GitHub repository on the user's behalf.

        You communicate through STRICT JSON ONLY. Every response must be exactly one JSON object matching this schema:

        {
          "action": "read_file" | "write_file" | "reply",
          "path": "string - required for read_file and write_file. Path relative to the repository root, e.g. src/main/Main.kt",
          "content": "string - required for write_file: the COMPLETE new file content, never a snippet or a patch",
          "commit_message": "string - for write_file: a short Conventional Commit message, e.g. 'fix: handle empty input'",
          "message": "string - for reply: plain text shown to the user"
        }

        Rules:
        - Use read_file to pull any file you need before editing it. Never guess a file's current contents.
        - write_file must contain the full new content of the file. If you only have a patch in mind, read the file first.
        - You may write_file a path that does not exist to create a new file; the app shows the user a diff before committing.
        - Keep replies concise and friendly. Prefer plain text over markdown on mobile.
        - Never invent repository contents; base every action on the file tree and file contents provided to you.
        - Do not mention branches, pull requests or git commands unless the user asks; the app handles all git operations safely for you.
        - Respond with JSON only: no markdown fences, no prose outside the JSON object.
    """.trimIndent()

    fun userTurn(
        task: String,
        owner: String,
        repo: String,
        branch: String,
        treeText: String,
        entryCount: Int,
    ): String = buildString {
        append("TASK:\n").append(task.trim()).append("\n\n")
        append("CONTEXT:\n")
        append("- Repository: ").append(owner).append('/').append(repo).append('\n')
        append("- All writes are applied to a working branch; you never deal with branches directly.\n")
        append("- File tree below (").append(entryCount).append(" entries, paths relative to the repository root):\n\n")
        append("FILE TREE:\n").append(treeText)
    }

    fun fileContentMessage(path: String, file: GitFile): String {
        val maxChars = 80_000
        val content = if (file.content.length > maxChars) {
            file.content.take(maxChars) + "\n\n... (file content truncated at $maxChars characters; read around this file with targeted searches if you need more)"
        } else {
            file.content
        }
        return "FILE CONTENT - $path (size ${file.sizeBytes} bytes):\n$content"
    }

    fun fileNotFoundMessage(path: String): String =
        "FILE NOT FOUND - $path does not exist on the working branch. " +
            "If the task requires it, use write_file to create it with full content."

    fun binaryFileMessage(path: String): String =
        "BINARY FILE - $path cannot be read or edited by this app. Work around it and explain any limitation to the user."

    /**
     * Keeps the message list inside a hard character budget: the system prompt
     * is always kept, oldest messages are dropped first.
     */
    fun cap(messages: List<OllamaMessage>, maxChars: Int = 200_000): List<OllamaMessage> {
        if (messages.isEmpty()) return messages
        var total = messages.sumOf { it.content.length }
        if (total <= maxChars) return messages
        val result = ArrayList<OllamaMessage>(messages.size)
        result += messages.first()
        var kept = messages.first().content.length
        for (message in messages.asReversed().dropLast(1)) {
            if (kept + message.content.length > maxChars) break
            result.add(1, message)
            kept += message.content.length
        }
        return result
    }
}
