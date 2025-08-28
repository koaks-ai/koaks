package org.koaks.framework.annotation

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

@SerialInfo
@OptIn(ExperimentalSerializationApi::class)
annotation class Description(val text: String)
