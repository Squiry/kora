package ru.tinkoff.kora.openapi.generator.kotlingen

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.openapitools.codegen.CodegenProperty
import org.openapitools.codegen.model.ModelsMap
import ru.tinkoff.kora.annotation.processor.common.CommonClassNames
import ru.tinkoff.kora.openapi.generator.KoraCodegen
import ru.tinkoff.kora.openapi.generator.kotlingen.KotlinClassNames.asKt

class KotlinModelGenerator(
    private val params: KoraCodegen.CodegenParams,
    private val modelPackage: String,
    private val additionalModelTypeAnnotations: List<String>
) : KotlinGenerator<ModelsMap> {
    override fun generate(ctx: ModelsMap): FileSpec {
        val model = ctx.models[0].model
        if (model.isEnum) {
            @Suppress("UNCHECKED_CAST")
            val enumVars = model.getAllowableValues()["enumVars"] as List<Map<String, String>>
            val enumType = this.generateEnum(null, model.name, model.dataType, enumVars)
            return FileSpec.get(modelPackage, enumType)
        }
        if (model.discriminator != null) {
            TODO("Not yet implemented")
        }

        return FileSpec.get(modelPackage, TypeSpec.classBuilder(model.name).build())

    }

    private fun generateEnum(parent: ClassName?, datatypeWithEnum: String, dataType: String, enumVars: List<Map<String, String>>): TypeSpec {
        val enumClassName = parent?.let { parent.nestedClass(datatypeWithEnum) } ?: ClassName(this.modelPackage, datatypeWithEnum)
        val b = TypeSpec.enumBuilder(enumClassName)
            .addAnnotation(generated())
        val constants = TypeSpec.objectBuilder("Constants")

        val enumDataType = toTypeName(null, CodegenProperty().apply { this.datatypeWithEnum = dataType; required = true })

        b.addProperty(PropertySpec.builder("value", enumDataType, KModifier.PRIVATE).initializer("value").build())
        b.addFunction(FunSpec.builder("toString").addModifiers(KModifier.OVERRIDE).returns(STRING).addStatement("return this.value.toString()").build())
        b.primaryConstructor(FunSpec.constructorBuilder().addParameter("value", enumDataType).build())

        for (constant in enumVars) {
            val constantName = constant["name"]!!
            val constantValue = constant["value"]!!
            b.addEnumConstant(
                constantName, TypeSpec.anonymousClassBuilder()
                    .addSuperclassConstructorParameter("%T.Constants.%N", enumClassName, constantName)
                    .build()
            )
            constants.addProperty(
                PropertySpec.builder(constantName, enumDataType)
                    .initializer("%L", constantValue)
                    .build()
            )
        }
        b.addType(constants.build())

        b.addType(
            TypeSpec.classBuilder("JsonWriter")
                .addAnnotation(generated())
                .addAnnotation(CommonClassNames.component.asKt())
                .primaryConstructor(
                    FunSpec.constructorBuilder()
                        .addParameter("delegate", KotlinClassNames.jsonWriter.parameterizedBy(enumDataType))
                        .build()
                )
                .addSuperinterface(
                    KotlinClassNames.jsonWriter.parameterizedBy(enumClassName),
                    CodeBlock.of("%T(%T.entries.toTypedArray(), %T::value, delegate)", KotlinClassNames.enumJsonWriter, enumClassName, enumClassName)
                )
                .build()
        )

        b.addType(
            TypeSpec.classBuilder("JsonReader")
                .addAnnotation(generated())
                .addAnnotation(CommonClassNames.component.asKt())
                .primaryConstructor(
                    FunSpec.constructorBuilder()
                        .addParameter("delegate", KotlinClassNames.jsonReader.parameterizedBy(enumDataType))
                        .build()
                )
                .addSuperinterface(
                    KotlinClassNames.jsonReader.parameterizedBy(enumClassName),
                    CodeBlock.of("%T(%T.entries.toTypedArray(), %T::value, delegate)", KotlinClassNames.enumJsonReader, enumClassName, enumClassName)
                )
                .build()
        )

        b.addType(
            TypeSpec.classBuilder("StringParameterReader")
                .addAnnotation(generated())
                .addAnnotation(CommonClassNames.component.asKt())
                .addSuperinterface(
                    KotlinClassNames.stringParameterReader.parameterizedBy(enumClassName),
                    CodeBlock.of("%T(%T.entries.toTypedArray(), { it.value.toString() })", KotlinClassNames.enumStringParameterReader, enumClassName)
                )
                .build()
        )

        b.addType(
            TypeSpec.classBuilder("StringParameterConverter")
                .addAnnotation(generated())
                .addAnnotation(CommonClassNames.component.asKt())
                .addSuperinterface(
                    KotlinClassNames.stringParameterConverter.parameterizedBy(enumClassName),
                    CodeBlock.of("%T(%T.entries.toTypedArray(), { it.value.toString() })", KotlinClassNames.enumStringParameterConverter, enumClassName)
                )
                .build()
        )

        return b.build()
    }

    private fun toTypeName(parent: ClassName?, property: CodegenProperty): TypeName {
        val value = when (property.datatypeWithEnum) {
            "Boolean" -> BOOLEAN.copy(true)
            "Byte" -> BYTE.copy(true)
            "Short" -> SHORT.copy(true)
            "Integer", "Int" -> INT.copy(true)
            "Long" -> LONG.copy(true)
            "Float" -> FLOAT.copy(true)
            "Double" -> DOUBLE.copy(true)
            "boolean" -> BOOLEAN
            "byte" -> BYTE
            "short" -> SHORT
            "int" -> INT
            "long" -> LONG
            "float" -> FLOAT
            "double" -> DOUBLE
            "String" -> String::class.asTypeName()
            else -> {
                if (property.isEnum) {
                    parent!!.nestedClass(property.datatypeWithEnum)
                } else if (property.ref != null) {
                    ClassName(this.modelPackage, property.datatypeWithEnum)
                } else if (property.isByteArray || property.isBinary) {
                    BYTE_ARRAY
                } else if (property.isMap) {
                    val propsType = property.getItems()
                    val valueType = toTypeName(parent, propsType)
                    Map::class.asClassName().parameterizedBy(STRING, valueType)
                } else if (property.isArray) {
                    val propsType = property.getItems()
                    val valueType = toTypeName(parent, propsType)
                    List::class.asClassName().parameterizedBy(valueType)
                } else {
                    ClassName.bestGuess(property.datatypeWithEnum)
                }
            }
        }
        if (!property.required) {
            return value.copy(true)
        }
        if (property.isNullable && params.enableJsonNullable) {
            return value.copy(true)
        }
        return value
    }

    private fun generated() = AnnotationSpec.builder(CommonClassNames.koraGenerated.asKt()).addMember("%S", "kora openapi generator").build()

}
