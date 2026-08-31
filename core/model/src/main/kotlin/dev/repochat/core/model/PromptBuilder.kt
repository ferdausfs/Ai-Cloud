package dev.repochat.core.model

/**
 * Builds every prompt sent to the LLM. The system prompt enforces the strict
 * JSON tool contract; context messages carry the task, the file tree and any
 * file contents the model requested.
 */
object PromptBuilder {

    /** Plain chat (no repo tools / JSON contract). */
    fun generalSystem(): String =
        "You are RepoChat, a helpful AI coding assistant in a mobile app. " +
            "Answer clearly and concisely. Use markdown when it helps. " +
            "Do not invent repository file contents or claim you edited GitHub — " +
            "this conversation is not attached to a repository. " +
            "If the user needs repo edits, suggest starting a Chat with a repo session."

    fun system(): String = """
        You are RepoChat, an AI software engineer embedded in a mobile app that edits files in a GitHub repository on the user's behalf.

        You communicate through STRICT JSON ONLY. Every response must be exactly one JSON object matching this schema:

        {
          "action": "read_file" | "write_file" | "create_pull_request" | "check_ci_status" | "reply",
          "path": "string - required for read_file and write_file. Path relative to the repository root, e.g. src/main/Main.kt",
          "content": "string - required for write_file: the COMPLETE new file content, never a snippet or a patch",
          "commit_message": "string - for write_file: a short Conventional Commit message, e.g. 'fix: handle empty input'",
          "title": "string - for create_pull_request: short PR title",
          "body": "string - for create_pull_request: PR description (markdown ok)",
          "message": "string - for reply: plain text shown to the user"
        }

        Rules:
        - Use read_file to pull any file you need before editing it. Never guess a file's current contents.
        - write_file must contain the full new content of the file. If you only have a patch in mind, read the file first.
        - You may write_file a path that does not exist to create a new file; the app shows the user a diff before committing.
        - create_pull_request: ONLY when the user has asked to open/submit a PR (or explicitly confirmed after you asked). Do not open a PR proactively every turn. Prefer after at least one successful write this session. The app opens the PR from the working branch into the default branch — you never push to main.
        - check_ci_status: NEVER pass a branch — the app always checks the session's working branch itself. Use it only after you have committed a real change (or after a PR was created).
        - After create_pull_request or check_ci_status the app feeds you the result; then reply to the user with the PR URL or a plain-language CI summary.
        - Keep replies concise and friendly. Prefer plain text over markdown on mobile.
        - Never invent repository contents; base every action on the file tree and file contents provided to you.
        - Do not mention raw git commands unless the user asks; the app handles git operations safely for you.
        - Respond with JSON only: no markdown fences, no prose outside the JSON object.
    """.trimIndent()

    /**
     * Stricter system prompt used by [dev.repochat.core.domain.AutoFixLoop]
     * attempts. Makes the edit-before-CI ordering explicit so the model does
     * not burn an attempt by checking CI (or replying) instead of editing.
     */
    fun autofixSystem(): String = buildString {
        append(system())
        append(
            """

            AUTO-FIX LOOP MODE:
            - You are an autonomous fix loop. This attempt, you MUST make a concrete write_file change that addresses the task before anything else.
            - Do NOT call check_ci_status as your first action. The app only runs CI after a change is committed to the working branch.
            - Do NOT reply with prose until after you have committed the fix (or there is nothing left to change).
            - You may call read_file first to understand the code, but write_file is the goal of every attempt.
            """.trimIndent(),
        )
    }

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
        val content = capFileContent(file.content)
        return "FILE CONTENT - $path (size ${file.sizeBytes} bytes):\n$content"
    }

    /**
     * Formats a user-attached local file the same way repo file contents are
     * presented, so the model treats both sources consistently. Large files
     * are truncated at [FILE_CONTENT_MAX_CHARS].
     */
    fun attachedFileMessage(filename: String, content: String): String {
        val body = capFileContent(content)
        return "ATTACHED FILE - $filename:\n$body"
    }

    /**
     * Note placed in the prompt when the user attached an image. When the
     * configured model cannot accept vision input the bytes are omitted and
     * this text explains why, so the model (and user) aren't left guessing.
     */
    fun attachedImageMessage(filename: String, visionSupported: Boolean): String =
        if (visionSupported) {
            "ATTACHED IMAGE - $filename: image data is included with this message for the vision model."
        } else {
            "ATTACHED IMAGE - $filename: the user attached an image, but the configured model " +
                "does not support vision input so the image bytes were not sent. " +
                "Ask the user to describe the image if you need its contents."
        }

    fun fileNotFoundMessage(path: String): String =
        "FILE NOT FOUND - $path does not exist on the working branch. " +
            "If the task requires it, use write_file to create it with full content."

    fun binaryFileMessage(path: String): String =
        "BINARY FILE - $path cannot be read or edited by this app. Work around it and explain any limitation to the user."

    /** True when the model name is a known vision-capable Ollama family. */
    fun modelSupportsVision(modelName: String): Boolean {
        val n = modelName.lowercase()
        return VISION_MODEL_MARKERS.any { it in n }
    }

    private fun capFileContent(content: String): String =
        if (content.length > FILE_CONTENT_MAX_CHARS) {
            content.take(FILE_CONTENT_MAX_CHARS) +
                "\n\n... (file content truncated at $FILE_CONTENT_MAX_CHARS characters; " +
                "read around this file with targeted searches if you need more)"
        } else {
            content
        }

    private const val FILE_CONTENT_MAX_CHARS = 80_000

    private val VISION_MODEL_MARKERS = listOf(
        "llava",
        "vision",
        "bakllava",
        "moondream",
        "minicpm-v",
        "qwen2-vl",
        "qwen-vl",
        "gemma3", // gemma3 family accepts images on Ollama
    )

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
