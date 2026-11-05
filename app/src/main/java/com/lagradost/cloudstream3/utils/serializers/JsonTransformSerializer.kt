package com.lagradost.cloudstream3.utils.serializers

import com.lagradost.cloudstream3.Prerelease
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

@Prerelease
abstract class JsonTransformSerializer<T : Any>(
    tSerializer: KSerializer<T>,
    private val singleValueAsListKeys: Set<String> = emptySet(),
    private val emptyArrayAsNullKeys: Set<String> = emptySet(),
    private val emptyStringAsNullKeys: Set<String> = emptySet(),
) : JsonTransformingSerializer<T>(tSerializer) {

    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        return JsonObject(element.mapValues { (key, value) ->
            var result = value
            if (key in singleValueAsListKeys) {
                result = when (result) {
                    is JsonArray -> result
                    JsonNull -> JsonArray(emptyList())
                    else -> JsonArray(listOf(result))
                }
            }

            if (key in emptyStringAsNullKeys) {
                if (result is JsonPrimitive && result.isString && result.content.isEmpty()) {
                    result = JsonNull
                }
            }

            if (key in emptyArrayAsNullKeys) {
                if (result is JsonArray && result.isEmpty()) {
                    result = JsonNull
                }
            }

            result
        })
    }
}
