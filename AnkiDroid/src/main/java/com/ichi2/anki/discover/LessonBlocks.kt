// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber

/*
 * Lesson content v2: a screen is a list of typed blocks. Text marked "md" in the contract is
 * md-lite (see MdLite.kt). A block type the app does not know, or a block it cannot read, is
 * skipped so the rest of the lesson still shows.
 */

/** One block of a lesson screen. */
sealed interface LessonBlock

@Serializable
data class TextBlock(
    val body: String,
) : LessonBlock

@Serializable
enum class CalloutKind {
    @SerialName("kern")
    KEY,

    @SerialName("valkuil")
    PITFALL,

    @SerialName("nuance")
    NUANCE,

    @SerialName("praktijk")
    PRACTICE,
}

@Serializable
data class CalloutBlock(
    val kind: CalloutKind = CalloutKind.KEY,
    val title: String? = null,
    val body: String,
) : LessonBlock

@Serializable
data class ExampleBlock(
    val title: String,
    val steps: List<String> = emptyList(),
    val result: String? = null,
) : LessonBlock

@Serializable
data class CompareSide(
    val title: String,
    val points: List<String> = emptyList(),
)

@Serializable
data class CompareBlock(
    val left: CompareSide,
    val right: CompareSide,
    val verdict: String? = null,
) : LessonBlock

@Serializable
data class TableBlock(
    val headers: List<String>,
    val rows: List<List<String>> = emptyList(),
) : LessonBlock {
    /** Rows padded or cut to the number of headers, so a malformed row cannot break the layout. */
    val normalizedRows: List<List<String>>
        get() = rows.map { row -> List(headers.size) { row.getOrNull(it).orEmpty() } }
}

@Serializable
data class FlowNode(
    val label: String,
    val note: String? = null,
)

@Serializable
data class FlowBlock(
    val title: String? = null,
    val nodes: List<FlowNode> = emptyList(),
) : LessonBlock

@Serializable
data class CodeBlock(
    val language: String? = null,
    /** Literal text, not md-lite. */
    val body: String,
) : LessonBlock

private fun blockSerializer(type: String?): KSerializer<out LessonBlock>? =
    when (type) {
        "text" -> TextBlock.serializer()
        "callout" -> CalloutBlock.serializer()
        "example" -> ExampleBlock.serializer()
        "compare" -> CompareBlock.serializer()
        "table" -> TableBlock.serializer()
        "flow" -> FlowBlock.serializer()
        "code" -> CodeBlock.serializer()
        else -> null
    }

private fun typeOf(block: LessonBlock): String =
    when (block) {
        is TextBlock -> "text"
        is CalloutBlock -> "callout"
        is ExampleBlock -> "example"
        is CompareBlock -> "compare"
        is TableBlock -> "table"
        is FlowBlock -> "flow"
        is CodeBlock -> "code"
    }

/** Reads `blocks`, dropping unknown types and blocks that do not match their type's fields. */
object LessonBlockListSerializer : KSerializer<List<LessonBlock>> {
    private val raw = ListSerializer(JsonElement.serializer())
    override val descriptor: SerialDescriptor = raw.descriptor

    override fun deserialize(decoder: Decoder): List<LessonBlock> {
        val json = (decoder as? JsonDecoder) ?: throw SerializationException("lesson blocks can only be read from JSON")
        val array = json.decodeJsonElement() as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
            val serializer = blockSerializer(type)
            if (serializer == null) {
                Timber.i("Discover: skipping unknown block type %s", type)
                return@mapNotNull null
            }
            try {
                json.json.decodeFromJsonElement(serializer, JsonObject(obj - "type"))
            } catch (e: SerializationException) {
                Timber.w(e, "Discover: skipping unreadable %s block", type)
                null
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "Discover: skipping unreadable %s block", type)
                null
            }
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: List<LessonBlock>,
    ) {
        val json = (encoder as? JsonEncoder) ?: throw SerializationException("lesson blocks can only be written as JSON")
        val elements =
            value.map { block ->
                @Suppress("UNCHECKED_CAST")
                val serializer = blockSerializer(typeOf(block)) as KSerializer<LessonBlock>
                val fields = json.json.encodeToJsonElement(serializer, block) as JsonObject
                buildJsonObject {
                    put("type", JsonPrimitive(typeOf(block)))
                    fields.forEach { (key, v) -> put(key, v) }
                }
            }
        json.encodeJsonElement(JsonArray(elements))
    }
}
