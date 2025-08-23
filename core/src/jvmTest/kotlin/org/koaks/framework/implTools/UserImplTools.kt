package org.koaks.framework.implTools

import kotlinx.serialization.KSerializer
import org.koaks.framework.toolcall.NoInput
import org.koaks.framework.toolcall.Tool

class UserImplTools() : Tool<NoInput> {

    override val name: String = "userLocation"
    override val description: String = "get the city where the user is located"
    override val group: String = "location"
    override val serializer: KSerializer<NoInput> = NoInput.serializer()

    override suspend fun execute(input: NoInput): String {
        return "Shanghai"
    }

}
