package com.vanta.app

import java.util.Locale

enum class VantaRoute {
    CHAT,
    RESEARCH,
    DEEP_RESEARCH,
    ATTACHMENT_ANALYSIS,
    ANDROID_ACTION
}

data class DeepResearchResult(
    val text: String,
    val sources: List<VantaSource>
)

object VantaRouter {
    fun decide(
        prompt: String,
        selectedMode: VantaMode,
        hasAttachments: Boolean,
        deepResearchRequested: Boolean,
        hasLocalAction: Boolean
    ): VantaRoute {
        if (hasLocalAction) return VantaRoute.ANDROID_ACTION
        if (deepResearchRequested || shouldDeepResearch(prompt, selectedMode)) return VantaRoute.DEEP_RESEARCH
        if (VantaSmart.shouldResearch(prompt, selectedMode)) return VantaRoute.RESEARCH
        if (hasAttachments) return VantaRoute.ATTACHMENT_ANALYSIS
        return VantaRoute.CHAT
    }

    private fun shouldDeepResearch(prompt: String, selectedMode: VantaMode): Boolean {
        if (selectedMode == VantaMode.FACT_CHECK) return true
        val p = prompt.lowercase(Locale.getDefault())
        return listOf(
            "deep research",
            "deeply research",
            "research this thoroughly",
            "investigate this",
            "do a full investigation",
            "compare sources",
            "give me a detailed research report"
        ).any(p::contains)
    }

    fun status(route: VantaRoute): String = when (route) {
        VantaRoute.CHAT -> "Thinking"
        VantaRoute.RESEARCH -> "Researching the web"
        VantaRoute.DEEP_RESEARCH -> "Running deep research"
        VantaRoute.ATTACHMENT_ANALYSIS -> "Analyzing attachments"
        VantaRoute.ANDROID_ACTION -> "Running Android action"
    }
}

object VantaDeepResearch {
    fun run(
        api: VantaApi,
        prompt: String,
        instructions: String,
        history: List<SavedChatMessage>
    ): DeepResearchResult {
        val queries = listOf(
            prompt,
            prompt + " official sources primary documentation",
            prompt + " recent independent analysis evidence comparison"
        )
        val evidence = mutableListOf<String>()
        val sources = linkedMapOf<String, VantaSource>()

        queries.forEachIndexed { index, query ->
            val reply = api.ask(
                prompt = query,
                instructions = instructions + "\nYou are a research worker. Return concise findings, disagreements, dates, and useful source facts. Research pass " + (index + 1) + " of " + queries.size + ".",
                research = true,
                history = history.takeLast(6)
            )
            evidence += "PASS " + (index + 1) + "\n" + reply.text.take(7000)
            reply.sources.forEach { sources[it.url] = it }
        }

        val synthesisPrompt = "Synthesize a careful answer to the user's question.\n\n" +
            "USER QUESTION:\n" + prompt + "\n\n" +
            "RESEARCH EVIDENCE:\n" + evidence.joinToString("\n\n") + "\n\n" +
            "Requirements:\n" +
            "- Answer the original question directly.\n" +
            "- Separate confirmed facts from uncertain or conflicting claims.\n" +
            "- Prefer recent information when relevant.\n" +
            "- Do not invent citations or sources.\n" +
            "- Mention important dates and caveats.\n" +
            "- Do not describe internal research passes."

        val synthesis = api.ask(
            prompt = synthesisPrompt,
            instructions = instructions + "\nYou are VANTA's final research synthesizer. Use only the supplied research evidence and be transparent about uncertainty.",
            research = false,
            history = history.takeLast(6)
        )
        synthesis.sources.forEach { sources[it.url] = it }
        return DeepResearchResult(synthesis.text, sources.values.take(12))
    }
}

object VantaAttachmentInspector {
    fun isTextLike(mime: String, name: String): Boolean {
        val n = name.lowercase(Locale.getDefault())
        return mime.startsWith("text/") || n.endsWith(".txt") || n.endsWith(".md") ||
            n.endsWith(".csv") || n.endsWith(".json") || n.endsWith(".xml") || n.endsWith(".log")
    }

    fun isDocx(mime: String, name: String): Boolean =
        mime.contains("wordprocessingml.document") || name.lowercase(Locale.getDefault()).endsWith(".docx")
}
