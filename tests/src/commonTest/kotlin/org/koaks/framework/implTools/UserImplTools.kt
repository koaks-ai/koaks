package org.koaks.framework.implTools

import kotlinx.serialization.KSerializer
import org.koaks.framework.toolcall.toolinterface.NoneInput
import org.koaks.framework.toolcall.toolinterface.Tool

class UserImplTools() : Tool<NoneInput> {

    override val name: String = "userLocation"
    override val description: String = "get the city where the user is located"
    override val group: String = "location"
    override val serializer: KSerializer<NoneInput> = NoneInput.serializer()
    override val returnDirectly: Boolean = false

    override suspend fun execute(input: NoneInput): String {
        return "Shanghai"
    }

}
