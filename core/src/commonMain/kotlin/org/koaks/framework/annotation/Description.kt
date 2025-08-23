package org.koaks.framework.annotation

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@OptIn(ExperimentalSerializationApi::class)
annotation class Description(val text: String)
