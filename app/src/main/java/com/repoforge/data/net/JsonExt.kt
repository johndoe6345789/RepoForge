package com.repoforge.data.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

// Lenient accessors: forge APIs differ in which fields are present or null, so every
// lookup returns null rather than throwing when a field is missing or has another type.

private fun JsonObject.prim(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull

fun JsonObject.int(key: String): Int? = prim(key)?.intOrNull

fun JsonObject.long(key: String): Long? = prim(key)?.longOrNull

fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** Follows a chain of nested objects, e.g. `path("links", "html")`. */
fun JsonObject.path(vararg keys: String): JsonObject? {
    var current: JsonObject? = this
    for (key in keys) current = current?.obj(key)
    return current
}

fun JsonObject.instant(key: String): Instant? = str(key)?.let(::parseInstant)

fun JsonElement.objects(): List<JsonObject> = (this as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

fun parseInstant(value: String): Instant? = try {
    OffsetDateTime.parse(value).toInstant()
} catch (_: DateTimeParseException) {
    try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }
}
