package app.mycelium.engine.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One planned sentence: a template key, its values (all taken from FACTS) and the event ids it rests on. */
data class Line(val key: String, val vars: Map<String, String> = emptyMap(), val cites: List<Long> = emptyList())

data class Sentence(val text: String, val cites: List<Long>)

/** Fills assets/chat_templates.json. Variant choice is deterministic per question. */
object TemplateWriter {
    fun parse(json: String): Map<String, List<String>> =
        Json.parseToJsonElement(json).jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }

    fun render(lines: List<Line>, templates: Map<String, List<String>>, salt: Int): List<Sentence> =
        lines.mapIndexedNotNull { i, line ->
            val variants = templates[line.key] ?: return@mapIndexedNotNull null
            val tpl = variants[Math.floorMod(salt + i, variants.size)]
            var missing = false
            val text = Regex("\\{(\\w+)\\}").replace(tpl) { m -> line.vars[m.groupValues[1]] ?: run { missing = true; "" } }
            if (missing) null else Sentence(text.replaceFirstChar { it.uppercaseChar() }, line.cites.distinct())
        }
}
