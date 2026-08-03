@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.koaks.framework.annotation

import kotlinx.serialization.SerialInfo

/**
 * Describes an Agent tool input type or property.
 *
 * The annotation lives in commonMain and is recorded in kotlinx.serialization's
 * [kotlinx.serialization.descriptors.SerialDescriptor], so Kotlin/JVM, JS and Native
 * can all expose the same descriptions without reflection. On JVM the same
 * annotation is also visible to Java reflection for Java records, POJOs and method
 * parameters.
 *
 * [name] is required for a Java method's direct scalar parameter; the Java facade
 * intentionally does not infer source parameter names. Kotlin properties should use
 * `kotlinx.serialization.SerialName` when their JSON name needs to change.
 *
 * Setting [required] to false removes an otherwise required Kotlin property from the
 * generated schema and makes a direct Java method parameter optional.
 */
@SerialInfo
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.TYPE,
)
@Retention(AnnotationRetention.BINARY)
expect annotation class Param(
    /** Explicit JSON property name. */
    val name: String,

    /** Description exposed in the generated JSON Schema. */
    val description: String,

    /** Whether the parameter is required. */
    val required: Boolean = true,
)

internal val Param.resolvedDescription: String
    get() = description
