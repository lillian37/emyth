/*
 * Emyth
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package dev.emyth.app.data.ai.prompts

object ChatBuiltinPrompts {
    val codeBlock = BuiltinPromptDefinition(
        id = "chat.code_block",
        title = "代码块与 ZIP 规则",
        group = "普通聊天",
        description = "告诉 AI 如何命名代码块，以及通过工具提供 ZIP 文件。",
        defaultText = listOf(
            "## Code Block Rules (MUST FOLLOW)",
            "",
            "1. **ALWAYS name code blocks with filenames**: You MUST use the " +
                "actual filename as the code block language tag instead of just the " +
                "language name. This is critical for proper file saving and syntax " +
                "highlighting. Examples:",
            "   - ✅ Correct: ```MainActivity.kt instead of ```kotlin",
            "   - ✅ Correct: ```index.html instead of ```html",
            "   - ✅ Correct: ```styles.css instead of ```css",
            "   - ✅ Correct: ```package.json instead of ```json",
            "   - ✅ Correct: ```manifest.xml instead of ```xml",
            "   - ✅ Correct: ```main.py instead of ```python",
            "   - ✅ Correct: ```App.vue instead of ```vue",
            "   - ❌ Wrong: ```kotlin, ```python, ```javascript (these don't " +
                "provide filenames)",
            "   - For code without a specific filename, use a descriptive name " +
                "like ```example.ts, ```helper.py",
            "",
            "2. **ZIP Download via `write_files` tool**: Users can download code " +
                "files as a ZIP ONLY when you call this tool.",
            "   - **Full write** (first time / new files): " +
                "`{\"zip_name\":\"project.zip\",\"files\":[{\"name\":\"MainActivity.kt\"," +
                "\"content\":\"...\"}]}`",
            "   - **Incremental edit** (saves tokens! For modifying existing " +
                "files): `{\"zip_name\":\"project-v2.zip\",\"base_files\":\"previous\"," +
                "\"edits\":[{\"name\":\"MainActivity.kt\",\"search\":\"old code\",\"replace\":\"new " +
                "code\"}]}`",
            "   - The `edits` mode applies search/replace to the files from your " +
                "previous `write_files` call. Files not mentioned in `edits` keep " +
                "their content unchanged.",
            "   - Always use actual filenames (e.g. `MainActivity.kt`) as code " +
                "block language tags, not just language names (e.g. `kotlin`).",
            "",
        ).joinToString("\n"),
    )

    val screenJump = BuiltinPromptDefinition(
        id = "chat.screen_jump",
        title = "屏幕跳转规则",
        group = "普通聊天",
        description = "告诉 AI 如何使用 [JUMP] 标记把聊天界面带到前台。",
        defaultText = listOf(
            "## 屏幕跳转能力",
            "你可以在回复末尾追加 [JUMP] 标记（单独一行）来把聊天界面拉到用户屏幕最前面。",
            "适用场景：",
            "- 用户说要去别的应用，你觉得需要把用户拉回来时",
            "- 你觉得接下来的内容需要用户立即看到时",
            "不适用场景：",
            "- 一般闲聊不需要跳转",
            "- 用户正在跟你正常对话时不需要跳转",
            "[JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。",
            "",
        ).joinToString("\n"),
    )

    val skipReply = BuiltinPromptDefinition(
        id = "chat.skip_reply",
        title = "跳过回复规则",
        group = "普通聊天",
        description = "仅在助手开启“允许跳过回复”时使用。",
        defaultText = listOf(
            "## Skip Reply",
            "If you determine that no reply is needed (e.g., the user's message " +
                "doesn't require a response, or you have nothing meaningful to add), " +
                "you may reply with exactly `[SKIP]` (without any other text). This " +
                "message will be hidden from the user. Use this sparingly and only " +
                "when truly appropriate.",
            "",
        ).joinToString("\n"),
    )

    val messageBubbles = BuiltinPromptDefinition(
        id = "chat.message_bubbles",
        title = "分气泡规则",
        group = "普通聊天",
        description = "仅在助手开启“按行分气泡”时使用。",
        defaultText = listOf(
            "## Message Bubbles",
            "Your reply will be automatically split into separate chat bubbles at " +
                "every line break (\\n) you write, similar to how a person sends " +
                "several short texts in a row instead of one long message. You are " +
                "fully in control of this: write a line break whenever you want the " +
                "previous thought/sentence to appear as its own bubble, and keep " +
                "things on the same line when they belong together. Do not insert " +
                "blank lines purely for spacing — every line break becomes a new " +
                "bubble, so use them intentionally. Exception: line breaks inside " +
                "fenced code blocks (```) and Markdown tables are preserved as-is and " +
                "will NOT create new bubbles, since those must stay intact as a single " +
                "block.",
            "",
        ).joinToString("\n"),
    )

    val pluginOverview = BuiltinPromptDefinition(
        id = "chat.plugin_overview",
        title = "插件主动使用总览",
        group = "插件",
        description = "存在提供工具的插件时使用；清空后不追加这段总览。",
        defaultText = listOf(
            "你当前装载了以下插件提供的工具（完整工具列表和参数见 tools 定义）：{{plugin_names}}。" +
                "不要只在用户明确点名某个工具时才使用——只要对话场景与某个工具的用途相关，就应该主动考虑调用它，而不是被动等待用户指示。" +
                "部分工具绑定的是持续性的人设/系统状态（例如经营、社交、记录类），更需要你自己记得在合适的时机调用，而不是等用户提醒。",
        ).joinToString("\n"),
        variables = setOf("plugin_names"),
    )

    val entries: List<BuiltinPromptDefinition> = listOf(
        codeBlock,
        screenJump,
        skipReply,
        messageBubbles,
        pluginOverview,
    )
}

