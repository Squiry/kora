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
            val enumType = this.generateEnum(null, model.classname, model.dataType, enumVars)
            return FileSpec.get(modelPackage, enumType)
        }
        if (model.discriminator != null) {
            TODO("Not yet implemented")
        }
        if (model.allVars.isEmpty()) {
            return FileSpec.get(modelPackage, TypeSpec.objectBuilder(model.classname).build())
        }
        val modelClassName = ClassName(modelPackage, model.classname)
        val b = TypeSpec.classBuilder(modelClassName)
            .addModifiers(KModifier.DATA)
            .addAnnotation(generated())
            .addAnnotation(KotlinClassNames.jsonWriterAnnotation)
        model.description?.let { b.addKdoc(it) }

        for (allVar in model.allVars) {
            if (allVar.isEnum) {
                if (allVar.isArray) {
                    b.addType(generateEnum(modelClassName, allVar.datatypeWithEnum, allVar.mostInnerItems.dataType, allVar.getAllowableValues()["enumVars"] as List<Map<String, String>>))
                } else {
                    b.addType(generateEnum(modelClassName, allVar.datatypeWithEnum, allVar.dataType, allVar.getAllowableValues()["enumVars"] as List<Map<String, String>>))
                }
            }
        }
        for (allVar in model.allVars) {
            val propertyType = toTypeName(modelClassName, allVar)
            val property = PropertySpec.builder(allVar.name, propertyType).initializer("%N", allVar.name)
            allVar.description?.let { property.addKdoc(it) }
            property.addAnnotation(AnnotationSpec.builder(KotlinClassNames.rangeAnnotation).build())
            if (params.enableValidation) {
                // todo validation
            }
        }

        val constructor = FunSpec.constructorBuilder()
            .addAnnotation(KotlinClassNames.jsonReaderAnnotation)
        for (allVar in model.allVars) {
            if (!allVar.required) {
                continue
            }
            val propertyType = toTypeName(modelClassName, allVar)
            if (allVar.isDiscriminator) {
                val enumVars = allVar.getAllowableValues()["enumVars"] as List<Map<String, String>>?
                val enumConstants = enumVars!!.map { it["name"] }
                val check = enumConstants
                    .map { v: String? -> CodeBlock.of("%N != %T.%N", allVar.name, propertyType, v) }
                    .joinToCode(" && ")

                constructor.beginControlFlow("if (%L)", check)
                    .addStatement(
                        "throw IllegalArgumentException(\"Field %N should have one of the following values: %L; got \" + %N)",
                        allVar.name,
                        enumConstants.joinToString(", "),
                        allVar.name
                    )
                    .endControlFlow()

                if (enumConstants.size == 1) {
                    val enumConstantName = enumConstants.first()
                    constructor.addParameter(ParameterSpec.builder(allVar.name, propertyType).defaultValue("%T.%N", propertyType, enumConstantName).build())
                }
                continue
            }
            constructor.addParameter(allVar.name, propertyType)
        }
        for (allVar in model.allVars) {
            if (allVar.required) {
                continue
            }
            val propertyType = toTypeName(modelClassName, allVar)
            constructor.addParameter(ParameterSpec.builder(allVar.name, propertyType).defaultValue("null").build())
        }

        b.primaryConstructor(constructor.build())

        return FileSpec.get(modelPackage, b.build())

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
        var value = when (property.datatypeWithEnum) {
            "Boolean" -> BOOLEAN
            "Byte" -> BYTE
            "Short" -> SHORT
            "Integer", "Int" -> INT
            "Long" -> LONG
            "Float" -> FLOAT
            "Double" -> DOUBLE
            "boolean" -> BOOLEAN
            "byte" -> BYTE
            "short" -> SHORT
            "int" -> INT
            "long" -> LONG
            "float" -> FLOAT
            "double" -> DOUBLE
            "String" -> String::class.asTypeName()
            else -> when {
                property.isEnum -> parent!!.nestedClass(property.datatypeWithEnum)
                property.ref != null -> ClassName(this.modelPackage, property.datatypeWithEnum)
                property.isByteArray || property.isBinary -> BYTE_ARRAY
                property.isMap -> toTypeName(parent, property.getItems())
                property.isArray -> toTypeName(parent, property.getItems())
                else -> ClassName.bestGuess(property.datatypeWithEnum)
            }
        }
        if (property.isArray) {
            value = List::class.asClassName().parameterizedBy(value)
        }
        if (property.isMap) {
            value = Map::class.asClassName().parameterizedBy(STRING, value)
        }
        if (!property.required) {
            return value.copy(true)
        }
        if (property.isNullable && params.enableJsonNullable) {
            return value.copy(false)
        }
        return value.copy(false)
    }

    private fun generated() = AnnotationSpec.builder(CommonClassNames.koraGenerated.asKt()).addMember("%S", "kora openapi generator").build()

}
