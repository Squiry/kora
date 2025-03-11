package ru.tinkoff.kora.openapi.generator.javagen;

import com.palantir.javapoet.*;
import jakarta.annotation.Nullable;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.model.ModelsMap;
import ru.tinkoff.kora.annotation.processor.common.CommonClassNames;
import ru.tinkoff.kora.annotation.processor.common.CommonUtils;
import ru.tinkoff.kora.openapi.generator.KoraCodegen;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

public class JavaModelGenerator implements JavaGenerator<ModelsMap> {
    private final String modelPackage;
    private final List<String> additionalModelTypeAnnotations;
    private final KoraCodegen.CodegenParams params;

    public JavaModelGenerator(KoraCodegen.CodegenParams params, String modelPackage, List<String> additionalModelTypeAnnotations) {
        this.modelPackage = modelPackage;
        this.additionalModelTypeAnnotations = additionalModelTypeAnnotations;
        this.params = params;
    }

    @Override
    public JavaFile generate(ModelsMap ctx) {
        var model = ctx.getModels().get(0).getModel();
        if (model.isEnum) {
            var enumType = this.generateEnum(null, model.name, model.dataType, (List<Map<String, String>>) model.getAllowableValues().get("enumVars"));
            return JavaFile.builder(this.modelPackage, enumType).build();
        }
        if (model.discriminator != null) {
            return this.generateSealedInterface(model);
        }

        var modelClassName = ClassName.get(this.modelPackage, model.classname);
        var b = TypeSpec.recordBuilder(modelClassName)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(JavaClassNames.JSON_ANNOTATION)
            .addAnnotation(AnnotationSpec.builder(CommonClassNames.koraGenerated)
                .addMember("value", "$S", "Openapi Kora generator")
                .build());

        for (var additionalModelTypeAnnotation : this.additionalModelTypeAnnotations) {
            b.addAnnotation(parseAnnotation(additionalModelTypeAnnotation));// TODO parse params
        }
        b.addAnnotation(JavaClassNames.JSON_WRITER_ANNOTATION);
        var discriminatorValue = (List<String>) model.getVendorExtensions().get("x-discriminator-value");
        if (discriminatorValue != null) {
            var discriminatorProperty = model.allVars.stream().filter(v -> v.isDiscriminator).findFirst().get();
            // let's just hope imports will work somehow
            var annotationValue = discriminatorValue.stream().map(s -> CodeBlock.of("$L.$L.Constants.$N", model.parent, discriminatorProperty.datatypeWithEnum, s)).collect(CodeBlock.joining(", ", "{", "}"));
            b.addAnnotation(AnnotationSpec.builder(JavaClassNames.JSON_DISCRIMINATOR_VALUE_ANNOTATION).addMember("value", "$L", annotationValue).build());
            b.addSuperinterface(ClassName.get(this.modelPackage, model.getParent()));
        }
        if (this.params.enableValidation()) {
            b.addAnnotation(JavaClassNames.VALID_ANNOTATION);
        }
        var recordConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC);
        var compactConstructor = MethodSpec.compactConstructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(JavaClassNames.JSON_READER_ANNOTATION);
        var requiredOnlyConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addCode("this(");

        var witherCode = CodeBlock.of("return new $T$L;\n", ClassName.get(this.modelPackage, model.classname), model.allVars.stream().map(s -> CodeBlock.of("$N", s.name)).collect(CodeBlock.joining(", ", "(", ")")));
        for (int i = 0; i < model.allVars.size(); i++) {
            if (i > 0) {
                requiredOnlyConstructor.addCode(", ");
            }
            var allVar = model.allVars.get(i);
            if (allVar.isEnum) {
                var enumType = allVar.isArray
                    ? this.generateEnum(modelClassName, allVar.datatypeWithEnum, allVar.mostInnerItems.dataType, (List<Map<String, String>>) allVar.getAllowableValues().get("enumVars"))
                    : this.generateEnum(modelClassName, allVar.datatypeWithEnum, allVar.dataType, (List<Map<String, String>>) allVar.getAllowableValues().get("enumVars"));
                b.addType(enumType);
            }
            var propertyTypeName = toTypeName(modelClassName, allVar);
            if (allVar.isDiscriminator) {
                var propertyEnumType = propertyTypeName;
                var enumVars = (List<Map<String, String>>) allVar.getAllowableValues().get("enumVars");
                var enumConstants = enumVars.stream().map(v -> v.get("name")).toList();
                var check = enumConstants.stream()
                    .map(v -> CodeBlock.of("$N != $T.$N", allVar.name, propertyEnumType, v))
                    .collect(CodeBlock.joining(" && "));
                ;
                compactConstructor.beginControlFlow("if ($L)", check)
                    .addStatement("throw new IllegalArgumentException(\"Field $N should have one of the following values: $L; got \" + $N)", allVar.name, String.join(", ", enumConstants), allVar.name)
                    .endControlFlow();
            }
            if (allVar.isArray) {
                propertyTypeName = ParameterizedTypeName.get(CommonClassNames.list, propertyTypeName);
            }
            if (this.params.enableJsonNullable() && allVar.isNullable) {
                propertyTypeName = ParameterizedTypeName.get(JavaClassNames.JSON_NULLABLE, propertyTypeName);
            }
            var property = ParameterSpec.builder(propertyTypeName, allVar.name)
                .addAnnotation(AnnotationSpec.builder(JavaClassNames.JSON_FIELD_ANNOTATION).addMember("value", "$S", allVar.baseName).build());
            if (this.params.enableJsonNullable() && allVar.isNullable) {
                property.addAnnotation(AnnotationSpec.builder(JavaClassNames.JSON_INCLUDE_ANNOTATION).addMember("value", "$T.IncludeType.ALWAYS", JavaClassNames.JSON_INCLUDE_ANNOTATION).build());
            }
            if (this.params.enableValidation()) {
                if (allVar.getMinimum() != null || allVar.getMaximum() != null) {
                    var boundary = (allVar.exclusiveMinimum ? "EXCLUSIVE" : "INCLUSIVE") + "_" + (allVar.exclusiveMaximum ? "EXCLUSIVE" : "INCLUSIVE");
                    property.addAnnotation(AnnotationSpec.builder(JavaClassNames.RANGE_ANNOTATION)
                        .addMember("from", "$L", allVar.getMinimum())
                        .addMember("to", "$L", allVar.getMaximum())
                        .addMember("boundary", "$T.Boundary.$L", JavaClassNames.RANGE_ANNOTATION, boundary)
                        .build()
                    );
                } else if (allVar.getMaxItems() != null) {
                    assert allVar.getMinItems() != null;
                    property.addAnnotation(AnnotationSpec.builder(JavaClassNames.SIZE_ANNOTATION)
                        .addMember("min", "$L", allVar.getMinItems())
                        .addMember("max", "$L", allVar.getMaxItems())
                        .build());
                } else if (allVar.getMinLength() != null) {
                    assert allVar.getMaxLength() != null;
                    property.addAnnotation(AnnotationSpec.builder(JavaClassNames.SIZE_ANNOTATION)
                        .addMember("min", "$L", allVar.getMinLength())
                        .addMember("max", "$L", allVar.getMaxLength())
                        .build());
                } else if (allVar.getPattern() != null) {
                    property.addAnnotation(AnnotationSpec.builder(JavaClassNames.PATTERN_ANNOTATION)
                        .addMember("value", "$S", allVar.getPattern())
                        .build()
                    );
                } else if (allVar.getRef() != null) {
                    property.addAnnotation(JavaClassNames.VALID_ANNOTATION);
                }
            }
            var isSingleDiscriminator = allVar.isDiscriminator && ((List<Map<String, String>>) allVar.getAllowableValues().get("enumVars")).size() == 1;
            if (allVar.required) {
                if (!propertyTypeName.isPrimitive()) {
                    compactConstructor.addStatement("$T.requireNonNull($N, $S)", Objects.class, allVar.name, "Field %s is not nullable".formatted(allVar.name));
                }
                if (isSingleDiscriminator) {
                    var enumConstantName = ((List<Map<String, String>>) allVar.getAllowableValues().get("enumVars")).get(0).get("name");
                    requiredOnlyConstructor.addCode("$T.$N", propertyTypeName, enumConstantName);
                } else {
                    requiredOnlyConstructor.addParameter(propertyTypeName, allVar.name);
                    requiredOnlyConstructor.addCode("$N", allVar.name);
                }
            } else if (allVar.isNullable) {
                if (params.enableJsonNullable()) {
                    requiredOnlyConstructor.addCode("$T.undefined()", JavaClassNames.JSON_NULLABLE);
                } else {
                    requiredOnlyConstructor.addCode("null");
                }
            } else {
                requiredOnlyConstructor.addCode("null");
                property.addAnnotation(CommonClassNames.nullable);
            }
            if (allVar.description != null) {
                property.addJavadoc("$L", allVar.description);
            }
            if (allVar.deprecated) {
                property.addAnnotation(Deprecated.class);
            }
            var finalProperty = property.build();
            recordConstructor.addParameter(finalProperty);

            var witherParam = ParameterSpec.builder(finalProperty.type(), finalProperty.name());
            if (!allVar.required) {
                witherParam.addAnnotation(CommonClassNames.nullable);
            }
            if (!isSingleDiscriminator) {
                var wither = MethodSpec.methodBuilder("with" + CommonUtils.capitalize(allVar.name))
                    .addModifiers(Modifier.PUBLIC)
                    .returns(modelClassName)
                    .addParameter(witherParam.build())
                    .addCode(witherCode);
                if (Objects.equals(allVar.isOverridden, Boolean.TRUE) && !allVar.isDiscriminator) {
                    wither.addAnnotation(Override.class);
                }
                b.addMethod(wither.build());
            }
        }

        b.recordConstructor(recordConstructor.build());
        b.addMethod(compactConstructor.build());
        var requireOnlyFinal = requiredOnlyConstructor.addCode(");\n").build();
        if (requireOnlyFinal.parameters().size() != model.allVars.size()) {
            b.addMethod(requireOnlyFinal);
        }

        return JavaFile.builder(modelPackage, b.build()).build();
    }

    private AnnotationSpec parseAnnotation(String additionalModelTypeAnnotation) {
        additionalModelTypeAnnotation = additionalModelTypeAnnotation.trim();
        if (!additionalModelTypeAnnotation.endsWith(")")) {
            return AnnotationSpec.builder(ClassName.bestGuess(additionalModelTypeAnnotation)).build();
        }
        var pattern = Pattern.compile("@(?<fullClassName>.*)\\((?<args>.*)\\)");
        var matcher = pattern.matcher(additionalModelTypeAnnotation);
        if (matcher.find()) {
            var fullClassName = Objects.requireNonNull(matcher.group("fullClassName"));
            var args = Objects.requireNonNull(matcher.group("args"));
            return AnnotationSpec.builder(ClassName.bestGuess(fullClassName)).addMember("value", "$L", args).build();
        }
        throw new RuntimeException("Cant parse annotation " + additionalModelTypeAnnotation);
    }

    public JavaFile generateSealedInterface(CodegenModel model) {
        if (model.discriminator == null) {
            throw new IllegalArgumentException();
        }
        var modelName = ClassName.get(this.modelPackage, model.classname);
        var b = TypeSpec.interfaceBuilder(modelName)
            .addModifiers(Modifier.PUBLIC, Modifier.SEALED)
            .addAnnotation(JavaClassNames.JSON_ANNOTATION)
            .addAnnotation(AnnotationSpec.builder(CommonClassNames.koraGenerated)
                .addMember("value", "$S", "Openapi Kora generator")
                .build())
            .addAnnotation(AnnotationSpec.builder(JavaClassNames.JSON_DISCRIMINATOR_FIELD_ANNOTATION).addMember("value", "$S", model.discriminator.getPropertyBaseName()).build());
        for (var child : (Set<String>) model.vendorExtensions.get("x-unique-mapped-models")) {
            b.addPermittedSubclass(ClassName.get(this.modelPackage, child));
        }
        var discriminatorProperty = (CodegenProperty) model.getVendorExtensions().get("x-discriminator-property");
        var discriminator = MethodSpec.methodBuilder(model.discriminator.getPropertyName())
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(modelName.nestedClass(discriminatorProperty.datatypeWithEnum));
        if (discriminatorProperty.getDescription() != null) {
            discriminator.addJavadoc("$N $L", model.discriminator.getPropertyBaseName(), discriminatorProperty.getDescription());
        }
        var discriminatorEnumType = this.generateEnum(modelName, discriminatorProperty.datatypeWithEnum, discriminatorProperty.dataType, (List<Map<String, String>>) discriminatorProperty.getAllowableValues().get("enumVars"));
        b.addType(discriminatorEnumType);

        b.addMethod(discriminator.build());

        var withers = new ArrayList<MethodSpec>();
        for (var allVar : model.getAllVars()) {
            if (allVar.isDiscriminator) {
                continue;
            }
            if (allVar.isEnum) {
                if (allVar.isArray) {
                    var property = allVar.mostInnerItems;
                    var enumType = this.generateEnum(modelName, property.datatypeWithEnum, property.dataType, (List<Map<String, String>>) property.getAllowableValues().get("enumVars"));
                    b.addType(enumType);
                } else {
                    var enumType = this.generateEnum(modelName, allVar.datatypeWithEnum, allVar.dataType, (List<Map<String, String>>) allVar.getAllowableValues().get("enumVars"));
                    b.addType(enumType);
                }
            }
            var propertyType = this.toTypeName(modelName, allVar);
            var property = MethodSpec.methodBuilder(allVar.name)
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);
            if (allVar.getDescription() != null) {
                property.addJavadoc(allVar.description);
            }
            if (this.params.enableJsonNullable()) {
                if (allVar.isNullable) {
                    property.returns(ParameterizedTypeName.get(JavaClassNames.JSON_NULLABLE, propertyType));
                } else {
                    if (!allVar.required) {
                        property.addAnnotation(CommonClassNames.nullable);
                    }
                    property.returns(propertyType);
                }
            } else {
                if (!allVar.required) {
                    property.addAnnotation(CommonClassNames.nullable);
                }
                property.returns(propertyType);
            }
            var propertyMethod = property.build();
            b.addMethod(propertyMethod);

            var witherParam = ParameterSpec.builder(propertyMethod.returnType(), allVar.name);
            if (!allVar.required) {
                witherParam.addAnnotation(CommonClassNames.nullable);
            }

            var wither = MethodSpec.methodBuilder("with" + CommonUtils.capitalize(allVar.name))
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter(witherParam.build())
                .returns(modelName)
                .build();
            withers.add(wither);
        }
        withers.forEach(b::addMethod);

        return JavaFile.builder(modelPackage, b.build()).build();
    }

    private TypeSpec generateEnum(@Nullable ClassName parent, String datatypeWithEnum, String dataType, List<Map<String, String>> enumVars) {
        var enumClassName = parent == null
            ? ClassName.get(this.modelPackage, datatypeWithEnum)
            : parent.nestedClass(datatypeWithEnum);
        var b = TypeSpec.enumBuilder(enumClassName)
            .addAnnotation(generated())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC);
        var constants = TypeSpec.classBuilder("Constants")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        var p = new CodegenProperty();
        p.datatypeWithEnum = dataType;
        var enumDataType = toTypeName(null, p);

        b.addField(FieldSpec.builder(enumDataType, "value").addModifiers(Modifier.PRIVATE, Modifier.FINAL).build());
        b.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).addParameter(enumDataType, "value").addCode("this.value = value;\n").build());
        b.addMethod(MethodSpec.methodBuilder("getValue").addModifiers(Modifier.PUBLIC).returns(enumDataType).addCode("return this.value;\n").build());
        b.addMethod(MethodSpec.methodBuilder("toString").addModifiers(Modifier.PUBLIC).returns(ClassName.get(String.class)).addCode("return $T.valueOf(this.value);\n", String.class).build());
        for (var constant : enumVars) {
            var constantName = constant.get("name");
            b.addEnumConstant(constantName, TypeSpec.anonymousClassBuilder("$T.$N", enumClassName.nestedClass("Constants"), constantName)
                .build());

            constants.addField(FieldSpec.builder(enumDataType, constantName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$L", constant.get("value"))
                .build());
        }
        b.addType(constants.build());

        b.addType(TypeSpec.classBuilder("JsonWriter").addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addAnnotation(generated())
            .addAnnotation(CommonClassNames.component)
            .addSuperinterface(ParameterizedTypeName.get(JavaClassNames.JSON_WRITER, enumClassName))
            .addField(ParameterizedTypeName.get(JavaClassNames.ENUM_JSON_WRITER, enumClassName, enumDataType), "delegate", Modifier.PRIVATE, Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ParameterizedTypeName.get(JavaClassNames.JSON_WRITER, enumDataType), "delegate")
                .addStatement("this.delegate = new $T<>($T.values(), $T::getValue, delegate)", JavaClassNames.ENUM_JSON_WRITER, enumClassName, enumClassName)
                .build()
            )
            .addMethod(MethodSpec.methodBuilder("write")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addException(IOException.class)
                .addParameter(JavaClassNames.JSON_GENERATOR, "gen")
                .addParameter(enumClassName, "value")
                .addStatement("this.delegate.write(gen, value)")
                .build()
            )
            .build()
        );

        b.addType(TypeSpec.classBuilder("JsonReader").addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addAnnotation(generated())
            .addAnnotation(CommonClassNames.component)
            .addSuperinterface(ParameterizedTypeName.get(JavaClassNames.JSON_READER, enumClassName))
            .addField(ParameterizedTypeName.get(JavaClassNames.ENUM_JSON_READER, enumClassName, enumDataType), "delegate", Modifier.PRIVATE, Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ParameterizedTypeName.get(JavaClassNames.JSON_READER, enumDataType), "delegate")
                .addStatement("this.delegate = new $T<>($T.values(), $T::getValue, delegate)", JavaClassNames.ENUM_JSON_READER, enumClassName, enumClassName)
                .build()
            )
            .addMethod(MethodSpec.methodBuilder("read")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addException(IOException.class)
                .addParameter(JavaClassNames.JSON_PARSER, "json")
                .returns(enumClassName)
                .addStatement("return this.delegate.read(json)")
                .build()
            )
            .build()
        );

        b.addType(TypeSpec.classBuilder("StringParameterReader")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addAnnotation(generated())
            .addAnnotation(CommonClassNames.component)
            .addSuperinterface(ParameterizedTypeName.get(JavaClassNames.STRING_PARAMETER_READER, enumClassName))
            .addField(FieldSpec.builder(ParameterizedTypeName.get(JavaClassNames.ENUM_STRING_PARAMETER_READER, enumClassName), "delegate")
                .initializer(CodeBlock.of("new $T<>($T.values(), v -> String.valueOf(v.value))", JavaClassNames.ENUM_STRING_PARAMETER_READER, enumClassName))
                .build())
            .addMethod(MethodSpec.methodBuilder("read")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(String.class, "value")
                .returns(enumClassName)
                .addStatement("return this.delegate.read(value)")
                .build()
            )
            .build()
        );

        b.addType(TypeSpec.classBuilder("StringParameterConverter")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addAnnotation(generated())
            .addAnnotation(CommonClassNames.component)
            .addSuperinterface(ParameterizedTypeName.get(JavaClassNames.STRING_PARAMETER_CONVERTER, enumClassName))
            .addField(FieldSpec.builder(ParameterizedTypeName.get(JavaClassNames.ENUM_STRING_PARAMETER_CONVERTER, enumClassName), "delegate")
                .initializer(CodeBlock.of("new $T<>($T.values(), v -> String.valueOf(v.value))", JavaClassNames.ENUM_STRING_PARAMETER_CONVERTER, enumClassName))
                .build())
            .addMethod(MethodSpec.methodBuilder("convert")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(enumClassName, "value")
                .returns(String.class)
                .addStatement("return this.delegate.convert(value)")
                .build()
            )
            .build()
        );

        return b.build();
    }

    private AnnotationSpec generated() {
        return AnnotationSpec.builder(CommonClassNames.koraGenerated).addMember("value", "$S", "Kora openapi generator").build();
    }

    private TypeName toTypeName(ClassName parent, CodegenProperty property) {
        return switch (property.datatypeWithEnum) {
            case "Boolean" -> TypeName.BOOLEAN.box();
            case "Byte" -> TypeName.BYTE.box();
            case "Short" -> TypeName.SHORT.box();
            case "Integer" -> TypeName.INT.box();
            case "Long" -> TypeName.LONG.box();
            case "Float" -> TypeName.FLOAT.box();
            case "Double" -> TypeName.DOUBLE.box();
            case "boolean" -> TypeName.BOOLEAN;
            case "byte" -> TypeName.BYTE;
            case "short" -> TypeName.SHORT;
            case "int" -> TypeName.INT;
            case "long" -> TypeName.LONG;
            case "float" -> TypeName.FLOAT;
            case "double" -> TypeName.DOUBLE;
            case "String" -> ClassName.get(String.class);
            default -> {
                if (property.isEnum) {
                    yield parent.nestedClass(property.datatypeWithEnum);
                }
                if (property.getRef() != null) {
                    yield ClassName.get(this.modelPackage, property.datatypeWithEnum);
                }
                if (property.isByteArray || property.isBinary) {
                    yield ArrayTypeName.of(TypeName.BYTE);
                }
                if (property.isMap) {
                    var propsType = property.getItems();
                    var valueType = toTypeName(parent, propsType);
                    yield ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class), valueType);
                }
                if (property.isArray) {
                    var propsType = property.getItems();
                    var valueType = toTypeName(parent, propsType);
                    yield ParameterizedTypeName.get(ClassName.get(List.class), valueType);
                }
                yield ClassName.bestGuess(property.datatypeWithEnum);
            }
        };
    }
}
