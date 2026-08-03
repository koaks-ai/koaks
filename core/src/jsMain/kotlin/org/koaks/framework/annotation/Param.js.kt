@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.koaks.framework.annotation

import kotlinx.serialization.SerialInfo

@SerialInfo
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.TYPE,
)
@Retention(AnnotationRetention.BINARY)
actual annotation class Param actual constructor(
    actual val name: String,
    actual val description: String,
    actual val required: Boolean,
)
