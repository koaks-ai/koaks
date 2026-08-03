package org.koaks.java.json

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.victools.jsonschema.generator.FieldScope
import com.github.victools.jsonschema.generator.MethodScope
import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.OptionPreset
import com.github.victools.jsonschema.generator.SchemaGenerator
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaVersion
import com.github.victools.jsonschema.module.jackson.JacksonOption
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule
import java.lang.reflect.RecordComponent
import java.lang.reflect.Type
import java.util.Optional
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.koaks.framework.annotation.Param
import org.koaks.framework.annotation.resolvedDescription
import org.koaks.framework.loop.OutputSpec
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JavaType
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

/** Jackson-backed JSON binding shared by Java tools and structured output. */
class JacksonType<T> private constructor(
    val mapper: ObjectMapper,
    val javaType: JavaType,
    val schemaName: String,
    internal val schema: JsonObject,
) {
    fun decode(json: String): T = mapper.readValue(json, javaType)

    /** Returns the cached schema as JSON without exposing kotlinx.serialization types. */
    fun schemaJson(): String = mapper.writeValueAsString(schema)

    fun named(name: String): JacksonType<T> {
        require(name.isNotBlank()) { "schema name must not be blank" }
        return JacksonType(mapper, javaType, name, schema)
    }

    internal fun outputSpec(): OutputSpec = OutputSpec(schema, schemaName)

    companion object {
        private val defaultMapper: ObjectMapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

        @JvmStatic
        fun <T> of(type: Class<T>): JacksonType<T> = using(defaultMapper, type)

        @JvmStatic
        fun <T> of(type: TypeReference<T>): JacksonType<T> = using(defaultMapper, type)

        @JvmStatic
        fun of(type: Type): JacksonType<Any?> = using(defaultMapper, type)

        @JvmStatic
        fun <T> using(mapper: ObjectMapper, type: Class<T>): JacksonType<T> =
            create(mapper, mapper.constructType(type), type, type.simpleName)

        @JvmStatic
        fun <T> using(mapper: ObjectMapper, type: TypeReference<T>): JacksonType<T> {
            val javaType = mapper.constructType(type)
            return create(mapper, javaType, type.type, javaType.rawClass.simpleName)
        }

        @JvmStatic
        fun using(mapper: ObjectMapper, type: Type): JacksonType<Any?> {
            val javaType = mapper.constructType(type)
            return create(mapper, javaType, type, javaType.rawClass.simpleName)
        }

        @JvmStatic
        fun <T> using(mapper: ObjectMapper, type: JavaType): JacksonType<T> =
            create(mapper, type, type, type.rawClass.simpleName)

        private fun <T> create(
            mapper: ObjectMapper,
            javaType: JavaType,
            reflectionType: Type,
            schemaName: String,
        ): JacksonType<T> {
            val configBuilder = SchemaGeneratorConfigBuilder(
                mapper,
                SchemaVersion.DRAFT_2020_12,
                OptionPreset.PLAIN_JSON,
            )
                .with(
                    JacksonSchemaModule(
                        JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
                        JacksonOption.FLATTENED_ENUMS_FROM_JSONPROPERTY,
                    ),
                )
                .with(Option.FLATTENED_OPTIONALS)

            configBuilder.forFields()
                .withRequiredCheck { field -> isRequiredRecordMember(field) || isExplicitlyRequired(field) }
                .withDescriptionResolver(::paramDescription)
            configBuilder.forMethods()
                .withRequiredCheck { method -> isRequiredRecordMember(method) || isExplicitlyRequired(method) }
                .withDescriptionResolver(::paramDescription)

            val schemaNode = SchemaGenerator(configBuilder.build()).generateSchema(reflectionType)
            schemaNode.remove("\$schema")
            javaType.rawClass.getAnnotation(Param::class.java)
                ?.resolvedDescription
                ?.takeIf(String::isNotBlank)
                ?.let { schemaNode.put("description", it) }
            val schema = Json.parseToJsonElement(mapper.writeValueAsString(schemaNode)).jsonObject
            return JacksonType(mapper, javaType, schemaName.ifBlank { "Output" }, schema)
        }

        private fun isRequiredRecordMember(scope: FieldScope): Boolean =
            recordComponent(scope.rawMember.declaringClass, scope.name)?.let(::isRequiredRecordComponent) == true

        private fun isRequiredRecordMember(scope: MethodScope): Boolean =
            recordComponent(scope.rawMember.declaringClass, scope.name)?.let(::isRequiredRecordComponent) == true

        private fun recordComponent(owner: Class<*>, name: String): RecordComponent? =
            owner.takeIf { it.isRecord }?.recordComponents?.firstOrNull { it.name == name }

        private fun isRequiredRecordComponent(component: RecordComponent): Boolean =
            component.type != Optional::class.java

        private fun isExplicitlyRequired(scope: FieldScope): Boolean =
            scope.getAnnotationConsideringFieldAndGetterIfSupported(JsonProperty::class.java)?.required == true

        private fun isExplicitlyRequired(scope: MethodScope): Boolean =
            scope.getAnnotationConsideringFieldAndGetterIfSupported(JsonProperty::class.java)?.required == true

        private fun paramDescription(scope: FieldScope): String? =
            scope.getAnnotationConsideringFieldAndGetterIfSupported(Param::class.java)
                ?.resolvedDescription
                ?.takeIf(String::isNotBlank)

        private fun paramDescription(scope: MethodScope): String? =
            scope.getAnnotationConsideringFieldAndGetterIfSupported(Param::class.java)
                ?.resolvedDescription
                ?.takeIf(String::isNotBlank)
    }
}
