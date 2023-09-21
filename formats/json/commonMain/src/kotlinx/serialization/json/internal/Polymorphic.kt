/*
 * Copyright 2017-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */
@file:OptIn(ExperimentalSerializationApi::class)

package kotlinx.serialization.json.internal

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.internal.*
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.*
import kotlin.jvm.*

@Suppress("UNCHECKED_CAST")
internal inline fun <T> JsonEncoder.encodePolymorphically(
    serializer: SerializationStrategy<T>,
    value: T,
    ifPolymorphic: (String) -> Unit
) {
    if (json.configuration.useArrayPolymorphism) {
        serializer.serialize(this, value)
        return
    }
    val isPolymorphicSerializer = serializer is AbstractPolymorphicSerializer<*>
    val needDiscriminator =
        if (isPolymorphicSerializer) {
            json.configuration.classDiscriminatorMode != ClassDiscriminatorMode.NONE
        } else {
            when (json.configuration.classDiscriminatorMode) {
                ClassDiscriminatorMode.NONE, ClassDiscriminatorMode.POLYMORPHIC /* already handled in isPolymorphicSerializer */ -> false
                ClassDiscriminatorMode.POLYMORPHIC_AND_IMPLEMENTATIONS ->
                    serializer.descriptor.isPartOfSealedHierarchy || isPartOfHierarchy(this.serializersModule, value)
                ClassDiscriminatorMode.ALL_JSON_OBJECTS -> serializer.descriptor.kind in setOf(
                    StructureKind.CLASS,
                    StructureKind.OBJECT
                )
            }
        }
    val baseClassDiscriminator = if (needDiscriminator) serializer.descriptor.classDiscriminator(json) else null
    val actualSerializer: SerializationStrategy<T> = if (isPolymorphicSerializer) {
        val casted = serializer as AbstractPolymorphicSerializer<Any>
        requireNotNull(value) { "Value for serializer ${serializer.descriptor} should always be non-null. Please report issue to the kotlinx.serialization tracker." }
        val actual = casted.findPolymorphicSerializer(this, value)
        if (baseClassDiscriminator != null) validateIfSealed(serializer, actual, baseClassDiscriminator)
        checkKind(actual.descriptor.kind)
        actual as SerializationStrategy<T>
    } else serializer

    if (baseClassDiscriminator != null) ifPolymorphic(baseClassDiscriminator)
    actualSerializer.serialize(this, value)
}

private fun validateIfSealed(
    serializer: SerializationStrategy<*>,
    actualSerializer: SerializationStrategy<*>,
    classDiscriminator: String
) {
    if (serializer !is SealedClassSerializer<*>) return
    @Suppress("DEPRECATION_ERROR")
    if (classDiscriminator in actualSerializer.descriptor.jsonCachedSerialNames()) {
        val baseName = serializer.descriptor.serialName
        val actualName = actualSerializer.descriptor.serialName
        error(
            "Sealed class '$actualName' cannot be serialized as base class '$baseName' because" +
                    " it has property name that conflicts with JSON class discriminator '$classDiscriminator'. " +
                    "You can either change class discriminator in JsonConfiguration, " +
                    "rename property with @SerialName annotation or fall back to array polymorphism"
        )
    }
}

internal fun checkKind(kind: SerialKind) {
    if (kind is SerialKind.ENUM) error("Enums cannot be serialized polymorphically with 'type' parameter. You can use 'JsonBuilder.useArrayPolymorphism' instead")
    if (kind is PrimitiveKind) error("Primitives cannot be serialized polymorphically with 'type' parameter. You can use 'JsonBuilder.useArrayPolymorphism' instead")
    if (kind is PolymorphicKind) error("Actual serializer for polymorphic cannot be polymorphic itself")
}

internal fun <T> JsonDecoder.decodeSerializableValuePolymorphic(deserializer: DeserializationStrategy<T>): T {
    // NB: changes in this method should be reflected in StreamingJsonDecoder#decodeSerializableValue
    if (deserializer !is AbstractPolymorphicSerializer<*> || json.configuration.useArrayPolymorphism) {
        return deserializer.deserialize(this)
    }
    val discriminator = deserializer.descriptor.classDiscriminator(json)

    val jsonTree = cast<JsonObject>(decodeJsonElement(), deserializer.descriptor)
    val type = jsonTree[discriminator]?.jsonPrimitive?.content
    val actualSerializer = deserializer.findPolymorphicSerializerOrNull(this, type)
        ?: throwSerializerNotFound(type, jsonTree)

    @Suppress("UNCHECKED_CAST")
    return json.readPolymorphicJson(discriminator, jsonTree, actualSerializer as DeserializationStrategy<T>)
}

@JvmName("throwSerializerNotFound")
internal fun throwSerializerNotFound(type: String?, jsonTree: JsonObject): Nothing {
    val suffix =
        if (type == null) "missing class discriminator ('null')"
        else "class discriminator '$type'"
    throw JsonDecodingException(-1, "Polymorphic serializer was not found for $suffix", jsonTree.toString())
}

internal fun SerialDescriptor.classDiscriminator(json: Json): String {
    // Plain loop is faster than allocation of Sequence or ArrayList
    // We can rely on the fact that only one JsonClassDiscriminator is present —
    // compiler plugin checked that.
    for (annotation in annotations) {
        if (annotation is JsonClassDiscriminator) return annotation.discriminator
    }
    return json.configuration.classDiscriminator
}

