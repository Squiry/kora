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
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            b.addAnnotation(ClassName.bestGuess(additionalModelTypeAnnotation));// TODO parse params
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
        var primaryConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(JavaClassNames.JSON_READER_ANNOTATION);

        var witherCode = CodeBlock.of("return new $T$L;\n", ClassName.get(this.modelPackage, model.classname), model.allVars.stream().map(s -> CodeBlock.of("$N", s.name)).collect(CodeBlock.joining(", ", "(", ")")));
        for (var allVar : model.allVars) {
            if (allVar.isEnum) {

                var enumType = allVar.isArray
                    ? this.generateEnum(modelClassName, allVar.datatypeWithEnum, allVar.mostInnerItems.dataType, (List<Map<String, String>>) allVar.getAllowableValues().get("enumVars"))
                    : this.generateEnum(modelClassName, allVar.datatypeWithEnum, allVar.dataType, (List<Map<String, String>>) allVar.getAllowableValues().get("enumVars"));
                b.addType(enumType);
            }

//@ru.tinkoff.kora.json.common.annotation.JsonField("{{baseName}}"){{#vendorExtensions.x-json-include-always}}
//@ru.tinkoff.kora.json.common.annotation.JsonInclude(ru.tinkoff.kora.json.common.annotation.JsonInclude.IncludeType.ALWAYS){{/vendorExtensions.x-json-include-always}}
//  {{#vendorExtensions.x-has-min-max}}@ru.tinkoff.kora.validation.common.annotation.Range(from = {{minimum}}, to = {{maximum}}, boundary = ru.tinkoff.kora.validation.common.annotation.Range.Boundary.{{#exclusiveMinimum}}EXCLUSIVE{{/exclusiveMinimum}}{{^exclusiveMinimum}}INCLUSIVE{{/exclusiveMinimum}}_{{#exclusiveMaximum}}EXCLUSIVE{{/exclusiveMaximum}}{{^exclusiveMaximum}}INCLUSIVE{{/exclusiveMaximum}})
//    {{/vendorExtensions.x-has-min-max}}{{#vendorExtensions.x-has-min-max-items}}@ru.tinkoff.kora.validation.common.annotation.Size(min = {{minItems}}, max = {{maxItems}})
//{{/vendorExtensions.x-has-min-max-items}}{{#vendorExtensions.x-has-min-max-length}}@ru.tinkoff.kora.validation.common.annotation.Size(min = {{minLength}}, max = {{maxLength}})
//{{/vendorExtensions.x-has-min-max-length}}{{#vendorExtensions.x-has-pattern}}@ru.tinkoff.kora.validation.common.annotation.Pattern("{{{pattern}}}")
//{{/vendorExtensions.x-has-pattern}}{{#vendorExtensions.x-has-valid-model}}@ru.tinkoff.kora.validation.common.annotation.Valid
//{{/vendorExtensions.x-has-valid-model}}{{^vendorExtensions.x-json-nullable}}{{^required}}@Nullable {{/required}}{{{datatypeWithEnum}}}{{/vendorExtensions.x-json-nullable}}{{#vendorExtensions.x-json-nullable}}ru.tinkoff.kora.json.common.JsonNullable<{{{datatypeWithEnum}}}>{{/vendorExtensions.x-json-nullable}} {{name}}{{^-last}},{{/-last}}
//    {{/allVars}})
//
            var propertyTypeName = toTypeName(modelClassName, allVar);
            if (allVar.isArray) {
                propertyTypeName = ParameterizedTypeName.get(CommonClassNames.list, propertyTypeName);
            }
            var property = ParameterSpec.builder(propertyTypeName, allVar.name)
                .addAnnotation(AnnotationSpec.builder(JavaClassNames.JSON_FIELD_ANNOTATION).addMember("value", "$S", allVar.baseName).build());
            if (this.params.enableJsonNullable() && allVar.isNullable) {
                property.addAnnotation(AnnotationSpec.builder(JavaClassNames.JSON_INCLUDE_ANNOTATION).addMember("value", "$T.IncludeType.ALWAYS", JavaClassNames.JSON_INCLUDE_ANNOTATION).build());
            }
            if (this.params.enableValidation()) {
                if (allVar.getMinimum() != null || allVar.getMaximum() != null) {
//  {{#vendorExtensions.x-has-min-max}}@ru.tinkoff.kora.validation.common.annotation.Range(from = {{minimum}}, to = {{maximum}}, boundary = ru.tinkoff.kora.validation.common.annotation.Range.Boundary.{{#exclusiveMinimum}}EXCLUSIVE{{/exclusiveMinimum}}{{^exclusiveMinimum}}INCLUSIVE{{/exclusiveMinimum}}_{{#exclusiveMaximum}}EXCLUSIVE{{/exclusiveMaximum}}{{^exclusiveMaximum}}INCLUSIVE{{/exclusiveMaximum}})
                    property.addAnnotation(AnnotationSpec.builder(JavaClassNames.RANGE_ANNOTATION)
                        .addMember("from", "$L", allVar.getMinimum())
                        .addMember("to", "$L", allVar.getMaximum())
                        .build()
                    );
                }
//                {{#vendorExtensions.x-has-min-max-items}}@ru.tinkoff.kora.validation.common.annotation.Size(min = {{minItems}}, max = {{maxItems}}){{/vendorExtensions.x-has-min-max-items}}
//                {{#vendorExtensions.x-has-min-max-length}}@ru.tinkoff.kora.validation.common.annotation.Size(min = {{minLength}}, max = {{maxLength}}){/vendorExtensions.x-has-min-max-length}}
//                {{#vendorExtensions.x-has-pattern}}@ru.tinkoff.kora.validation.common.annotation.Pattern("{{{pattern}}}"){/vendorExtensions.x-has-pattern}}
//                {{#vendorExtensions.x-has-valid-model}}@ru.tinkoff.kora.validation.common.annotation.Valid{{/vendorExtensions.x-has-valid-model}}
            }
            if (!allVar.required) {
                property.addAnnotation(CommonClassNames.nullable);
            }
            if (allVar.description != null) {
                property.addJavadoc("$L", allVar.description);
            }
            if (allVar.deprecated) {
                property.addAnnotation(Deprecated.class);
            }
            var finalProperty = property.build();
            primaryConstructor.addParameter(finalProperty);

            var witherParam = ParameterSpec.builder(finalProperty.type(), finalProperty.name());
            if (!allVar.required) {
                witherParam.addAnnotation(CommonClassNames.nullable);
            }
            var wither = MethodSpec.methodBuilder("with" + CommonUtils.capitalize(allVar.name))
                .addModifiers(Modifier.PUBLIC)
                .returns(modelClassName)
                .addParameter(witherParam.build())
                .addCode(witherCode);
            b.addMethod(wither.build());
        }

        b.recordConstructor(primaryConstructor.build());

        return JavaFile.builder(modelPackage, b.build()).build();
    }

    private JavaFile generateSealedInterface(CodegenModel model) {
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
        if (discriminatorProperty.isArray) {
            var property = discriminatorProperty.mostInnerItems;
            var enumType = this.generateEnum(modelName, property.datatypeWithEnum, property.dataType, (List<Map<String, String>>) property.getAllowableValues().get("enumVars"));
            b.addType(enumType);
        } else {
            var enumType = this.generateEnum(modelName, discriminatorProperty.datatypeWithEnum, discriminatorProperty.dataType, (List<Map<String, String>>) discriminatorProperty.getAllowableValues().get("enumVars"));
            b.addType(enumType);
        }
        b.addMethod(discriminator.build());

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
            if (this.params.enableValidation()) {
                if (allVar.isNullable) {
                    property.addAnnotation(CommonClassNames.nullable);
                }
                property.returns(ParameterizedTypeName.get(JavaClassNames.JSON_NULLABLE, propertyType));
            } else {
                if (!allVar.required) {
                    property.addAnnotation(CommonClassNames.nullable);
                }
                property.returns(propertyType);
            }
            b.addMethod(property.build());

            var wither = MethodSpec.methodBuilder("with" + CommonUtils.capitalize(allVar.name))
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter(propertyType, allVar.name)
                .returns(modelName)
                .build();
            b.addMethod(wither);
        }

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

        var enumJsonWriter = ClassName.get("ru.tinkoff.kora.json.common", "EnumJsonWriter");
        b.addType(TypeSpec.classBuilder("JsonWriter").addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addAnnotation(generated())
            .addAnnotation(CommonClassNames.component)
            .addSuperinterface(ParameterizedTypeName.get(JavaClassNames.JSON_WRITER, enumClassName))
            .addField(ParameterizedTypeName.get(enumJsonWriter, enumClassName, enumDataType), "delegate", Modifier.PRIVATE, Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ParameterizedTypeName.get(JavaClassNames.JSON_WRITER, enumDataType), "delegate")
                .addStatement("this.delegate = new $T<>($T.values(), $T::getValue, delegate)", enumJsonWriter, enumClassName, enumClassName)
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

        var enumJsonReader = ClassName.get("ru.tinkoff.kora.json.common", "EnumJsonReader");
        b.addType(TypeSpec.classBuilder("JsonReader").addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addAnnotation(generated())
            .addAnnotation(CommonClassNames.component)
            .addSuperinterface(ParameterizedTypeName.get(JavaClassNames.JSON_READER, enumClassName))
            .addField(ParameterizedTypeName.get(enumJsonReader, enumClassName, enumDataType), "delegate", Modifier.PRIVATE, Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ParameterizedTypeName.get(JavaClassNames.JSON_READER, enumDataType), "delegate")
                .addStatement("this.delegate = new $T<>($T.values(), $T::getValue, delegate)", enumJsonReader, enumClassName, enumClassName)
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
                yield ClassName.get(this.modelPackage, property.datatypeWithEnum);
            }
        };
    }
}


/*


/**
 * NOTE: This class is auto generated by Kora OpenAPI Generator (https://openapi-generator.tech) ({{{generatorVersion}}}).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 * /
package {{package}};

    import jakarta.annotation.Nullable;

{{#models}}
    {{#model}}
    {{#isEnum}}

    {{>javaEnumClass}}

    {{/isEnum}}
    {{^isEnum}}

    {{#discriminator}}
/**
 * {{#description}}{{.}}{{/description}}{{^description}}{{classname}}{{/description}}
 * /
@ru.tinkoff.kora.common.annotation.Generated("openapi generator kora java")
@ru.tinkoff.kora.json.common.annotation.Json
@ru.tinkoff.kora.json.common.annotation.JsonDiscriminatorField("{{{propertyBaseName}}}")
public sealed interface {{classname}} permits {{#vendorExtensions.x-unique-mapped-models}}{{.}}{{^-last}}, {{/-last}}{{/vendorExtensions.x-unique-mapped-models}} {
    {{#vendorExtensions.x-discriminator-property}}
    /** {{#description}}{{.}}{{/description}}{{^description}}{{name}}{{/description}} * /
    {{{datatypeWithEnum}}} {{name}}();{{/vendorExtensions.x-discriminator-property}}
    {{#allVars}}
    {{^isDiscriminator}}
    {{#isEnum}}
    {{^isContainer}}
    {{>javaEnumClass}}
    {{/isContainer}}
    {{#isContainer}}
    {{#mostInnerItems}}
    {{>javaEnumClass}}
    {{/mostInnerItems}}
    {{/isContainer}}
    {{/isEnum}}

    /** {{#description}}{{.}}{{/description}}{{^description}}{{name}}{{/description}} * /
    {{^vendorExtensions.x-json-nullable}}{{^required}}@Nullable {{/required}}{{{datatypeWithEnum}}}{{/vendorExtensions.x-json-nullable}}{{#vendorExtensions.x-json-nullable}}ru.tinkoff.kora.json.common.JsonNullable<{{{datatypeWithEnum}}}>{{/vendorExtensions.x-json-nullable}} {{name}}();{{/isDiscriminator}}{{/allVars}}
    {{#vendorExtensions.x-discriminator-property}}

    {{^isContainer}}{{>javaEnumClass}}{{/isContainer}}
    {{#isContainer}}{{#mostInnerItems}}{{>javaEnumClass}}{{/mostInnerItems}}{{/isContainer}}
    {{/vendorExtensions.x-discriminator-property}}
    {{#allVars}}{{^isDiscriminator}}{{#example}}
    /** (example: {{.}}) * /{{/example}}
    {{{classname}}} with{{#lambda.titlecase}}{{name}}{{/lambda.titlecase}}({{^vendorExtensions.x-json-nullable}}{{^required}}@Nullable {{/required}}{{{datatypeWithEnum}}}{{/vendorExtensions.x-json-nullable}}{{#vendorExtensions.x-json-nullable}}ru.tinkoff.kora.json.common.JsonNullable<{{{datatypeWithEnum}}}>{{/vendorExtensions.x-json-nullable}} {{name}});
    {{/isDiscriminator}}{{/allVars}}
    }
    {{/discriminator}}
    {{^discriminator}}
/**
 * {{#description}}{{.}}{{/description}}{{^description}}{{classname}}{{/description}}{{#allVars}}
 * @param {{name}} {{#description}}{{.}}{{/description}}{{^description}}{{name}}{{/description}}{{#example}} (example: {{.}}){{/example}}{{/allVars}}
 * /
@ru.tinkoff.kora.common.annotation.Generated("openapi generator kora java")
{{#additionalModelTypeAnnotations}}
{{{.}}}
    {{/additionalModelTypeAnnotations}}
@ru.tinkoff.kora.json.common.annotation.JsonWriter{{#vendorExtensions.x-discriminator-value}}
@ru.tinkoff.kora.json.common.annotation.JsonDiscriminatorValue({{{vendorExtensions.x-discriminator-value}}}){{/vendorExtensions.x-discriminator-value}}{{#vendorExtensions.x-enable-validation}}
@ru.tinkoff.kora.validation.common.annotation.Valid{{/vendorExtensions.x-enable-validation}}





public record {{classname}}(
    {{#allVars}}
@ru.tinkoff.kora.json.common.annotation.JsonField("{{baseName}}"){{#vendorExtensions.x-json-include-always}}
@ru.tinkoff.kora.json.common.annotation.JsonInclude(ru.tinkoff.kora.json.common.annotation.JsonInclude.IncludeType.ALWAYS){{/vendorExtensions.x-json-include-always}}
  {{#vendorExtensions.x-has-min-max}}@ru.tinkoff.kora.validation.common.annotation.Range(from = {{minimum}}, to = {{maximum}}, boundary = ru.tinkoff.kora.validation.common.annotation.Range.Boundary.{{#exclusiveMinimum}}EXCLUSIVE{{/exclusiveMinimum}}{{^exclusiveMinimum}}INCLUSIVE{{/exclusiveMinimum}}_{{#exclusiveMaximum}}EXCLUSIVE{{/exclusiveMaximum}}{{^exclusiveMaximum}}INCLUSIVE{{/exclusiveMaximum}})
    {{/vendorExtensions.x-has-min-max}}{{#vendorExtensions.x-has-min-max-items}}@ru.tinkoff.kora.validation.common.annotation.Size(min = {{minItems}}, max = {{maxItems}})
{{/vendorExtensions.x-has-min-max-items}}{{#vendorExtensions.x-has-min-max-length}}@ru.tinkoff.kora.validation.common.annotation.Size(min = {{minLength}}, max = {{maxLength}})
{{/vendorExtensions.x-has-min-max-length}}{{#vendorExtensions.x-has-pattern}}@ru.tinkoff.kora.validation.common.annotation.Pattern("{{{pattern}}}")
{{/vendorExtensions.x-has-pattern}}{{#vendorExtensions.x-has-valid-model}}@ru.tinkoff.kora.validation.common.annotation.Valid
{{/vendorExtensions.x-has-valid-model}}{{^vendorExtensions.x-json-nullable}}{{^required}}@Nullable {{/required}}{{{datatypeWithEnum}}}{{/vendorExtensions.x-json-nullable}}{{#vendorExtensions.x-json-nullable}}ru.tinkoff.kora.json.common.JsonNullable<{{{datatypeWithEnum}}}>{{/vendorExtensions.x-json-nullable}} {{name}}{{^-last}},{{/-last}}
    {{/allVars}})


    {{#vendorExtensions.x-discriminator-value}} implements {{parent}} {{/vendorExtensions.x-discriminator-value}}{
    {{#isArray}}

public static String schema = """
 {{{items}}}
""";
{{/isArray}}

    {{#additionalConstructor}}
public {{classname}}(
    {{#requiredVars}}
    {{^vendorExtensions.x-discriminator-single}}{{^vendorExtensions.x-json-nullable}}{{^required}}@Nullable {{/required}}{{{datatypeWithEnum}}}{{/vendorExtensions.x-json-nullable}}{{#vendorExtensions.x-json-nullable}}ru.tinkoff.kora.json.common.JsonNullable<{{{datatypeWithEnum}}}>{{/vendorExtensions.x-json-nullable}} {{name}}{{^-last}}, {{/-last}}{{/vendorExtensions.x-discriminator-single}}
    {{/requiredVars}}  ) {
    this({{#allVars}}{{#required}}{{#vendorExtensions.x-discriminator-single}}{{vendorExtensions.x-discriminator-single}}{{/vendorExtensions.x-discriminator-single}}{{^vendorExtensions.x-discriminator-single}}{{name}}{{/vendorExtensions.x-discriminator-single}}{{/required}}{{^required}}null{{/required}}{{^-last}}, {{/-last}}{{/allVars}});
    }
    {{/additionalConstructor}}
    {{#vendorExtensions.x-discriminator-single}}
public {{classname}}({{#allVars}}{{^isDiscriminator}}
    {{^vendorExtensions.x-json-nullable}}{{^required}}@Nullable {{/required}}{{{datatypeWithEnum}}}{{/vendorExtensions.x-json-nullable}}{{#vendorExtensions.x-json-nullable}}ru.tinkoff.kora.json.common.JsonNullable<{{{datatypeWithEnum}}}>{{/vendorExtensions.x-json-nullable}} {{name}}{{^-last}}, {{/-last}}
    {{/isDiscriminator}}{{/allVars}}) {
    this({{#allVars}}{{#isDiscriminator}}{{vendorExtensions.x-discriminator-single}}{{/isDiscriminator}}{{^isDiscriminator}}{{name}}{{/isDiscriminator}}{{^-last}}, {{/-last}}{{/allVars}});
    }
    {{/vendorExtensions.x-discriminator-single}}
@ru.tinkoff.kora.json.common.annotation.JsonReader
public {{classname}} { {{#vendorExtensions.x-discriminator-values-check}}
    {{{vendorExtensions.x-discriminator-values-check}}}
    {{/vendorExtensions.x-discriminator-values-check}}
    }

    {{#allVars}}
    {{#isEnum}}
    {{^isContainer}}
    {{>javaEnumClass}}
    {{/isContainer}}
    {{#isContainer}}
    {{#mostInnerItems}}
    {{>javaEnumClass}}
    {{/mostInnerItems}}
    {{/isContainer}}
    {{/isEnum}}
    {{/allVars}}
    {{#allVars}}

    {{#example}}
    /** (example: {{.}}) * /{{/example}}{{#vendorExtensions.x-discriminator-value}}{{#isOverridden}}
@Override{{/isOverridden}}{{/vendorExtensions.x-discriminator-value}}
public {{{classname}}} with{{#lambda.titlecase}}{{name}}{{/lambda.titlecase}}({{^vendorExtensions.x-json-nullable}}{{^required}}@Nullable {{/required}}{{{datatypeWithEnum}}}{{/vendorExtensions.x-json-nullable}}{{#vendorExtensions.x-json-nullable}}ru.tinkoff.kora.json.common.JsonNullable<{{{datatypeWithEnum}}}>{{/vendorExtensions.x-json-nullable}} {{name}}) {
    if (this.{{name}} == {{name}}) return this;
    return new {{{classname}}}({{#allVars}}
    {{name}}{{^-last}},{{/-last}}{{/allVars}}
    );
    }
    {{/allVars}}
    }

    {{/discriminator}}
    {{/isEnum}}
    {{/model}}
    {{/models}}


    */
