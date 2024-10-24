package ru.tinkoff.kora.logging.aspect;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import jakarta.annotation.Nullable;
import ru.tinkoff.kora.annotation.processor.common.*;
import ru.tinkoff.kora.aop.annotation.processor.KoraAspect;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import java.util.*;

import static ru.tinkoff.kora.logging.aspect.LogAspectClassNames.*;
import static ru.tinkoff.kora.logging.aspect.LogAspectUtils.*;

public class LogAspect implements KoraAspect {

    private static final String RESULT_VAR_NAME = "__result";
    private static final String DATA_IN_VAR_NAME = "__dataIn";
    private static final String DATA_OUT_VAR_NAME = "__dataOut";
    private static final String MESSAGE_IN = ">";
    private static final String MESSAGE_OUT = "<";

    private final ProcessingEnvironment env;

    public LogAspect(ProcessingEnvironment env) {
        this.env = env;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(log.canonicalName(), logIn.canonicalName(), logOut.canonicalName());
    }

    @Override
    public ApplyResult apply(ExecutableElement executableElement, String superCall, AspectContext aspectContext) {
        if (MethodUtils.isFlux(executableElement)) {
            throw new ProcessingErrorException("@Log can't be applied for types assignable from " + CommonClassNames.flux, executableElement);
        }

        var loggerName = executableElement.getEnclosingElement() + "." + executableElement.getSimpleName();
        var loggerFactoryFieldName = aspectContext.fieldFactory().constructorParam(
            loggerFactory,
            List.of()
        );
        var loggerFieldName = aspectContext.fieldFactory().constructorInitialized(
            logger,
            CodeBlock.builder().add("$N.getLogger($S)", loggerFactoryFieldName, loggerName).build()
        );

        var isMono = MethodUtils.isMono(executableElement);
        var isFuture = MethodUtils.isFuture(executableElement);
        if (isMono) {
            return this.monoBody(aspectContext, executableElement, superCall, loggerFieldName);
        } else if (isFuture) {
            return this.futureBody(aspectContext, executableElement, superCall, loggerFieldName);
        } else {
            return this.blockingBody(aspectContext, executableElement, superCall, loggerFieldName);
        }
    }

    private ApplyResult blockingBody(AspectContext aspectContext, ExecutableElement executableElement, String superCall, String loggerFieldName) {
        var logInLevel = logInLevel(executableElement, env);
        var logOutLevel = logOutLevel(executableElement, env);

        var b = CodeBlock.builder();
        if (logInLevel != null) {
            b.add(this.buildLogIn(aspectContext, executableElement, logInLevel, loggerFieldName));
        }
        var isVoid = executableElement.getReturnType().getKind() == TypeKind.VOID;
        if (isVoid) {
            b.add(KoraAspect.callSuper(executableElement, superCall)).add(";");
        } else {
            b.add("var $N = $L;\n", RESULT_VAR_NAME, KoraAspect.callSuper(executableElement, superCall));
        }
        if (logOutLevel != null) {
            var logResultLevel = logResultLevel(executableElement, logOutLevel, env);
            final CodeBlock resultWriter;
            if (!isVoid && logResultLevel != null) {
                var mapping = CommonUtils.parseMapping(executableElement).getMapping(structuredArgumentMapper);
                var mapperType = mapping != null && mapping.mapperClass() != null
                    ? mapping.isGeneric() ? mapping.parameterized(TypeName.get(executableElement.getReturnType())) : TypeName.get(mapping.mapperClass())
                    : ParameterizedTypeName.get(structuredArgumentMapper, TypeName.get(executableElement.getReturnType()).box());
                var mapper = aspectContext.fieldFactory().constructorParam(
                    mapperType,
                    List.of(AnnotationSpec.builder(CommonClassNames.nullable).build())
                );
                var resultWriterBuilder = CodeBlock.builder().add("gen -> {$>\n")
                    .add("gen.writeStartObject();\n")
                    .beginControlFlow("if (this.$N != null)", mapper)
                    .addStatement("gen.writeFieldName($S)", "out")
                    .addStatement("this.$N.write(gen, $N)", mapper, RESULT_VAR_NAME)
                    .nextControlFlow("else")
                    .addStatement("gen.writeStringField($S, String.valueOf($N))", "out", RESULT_VAR_NAME)
                    .endControlFlow()
                    .add("gen.writeEndObject();")
                    .add("$<\n}");
                resultWriter = resultWriterBuilder.build();
            } else {
                resultWriter = null;
            }

            if (isVoid || logResultLevel == null) {
                b.addStatement("$N.$L($S)", loggerFieldName, logOutLevel.toLowerCase(), MESSAGE_OUT);
            } else if (logOutLevel.equals(logResultLevel)) {
                ifLogLevelEnabled(b, loggerFieldName, logOutLevel, () -> {
                    b.add("var $N = $T.marker($S, $L);\n", DATA_OUT_VAR_NAME, structuredArgument, "data", resultWriter);
                    b.add("$N.$L($N, $S);", loggerFieldName, logOutLevel.toLowerCase(), DATA_OUT_VAR_NAME, MESSAGE_OUT);
                }).add("\n");
            } else {
                ifLogLevelEnabled(b, loggerFieldName, logResultLevel, () -> {
                    b.add("var $N = $T.marker($S, $L);\n", DATA_OUT_VAR_NAME, structuredArgument, "data", resultWriter);
                    b.add("$N.$L($N, $S);", loggerFieldName, logOutLevel.toLowerCase(), DATA_OUT_VAR_NAME, MESSAGE_OUT);
                    b.add("$<\n} else {$>\n");
                    b.add("$N.$L($S);", loggerFieldName, logOutLevel.toLowerCase(), MESSAGE_OUT);
                });
            }
        }
        if (!isVoid) {
            b.addStatement("return $N", RESULT_VAR_NAME);
        }
        return new ApplyResult.MethodBody(b.build());
    }

    private ApplyResult monoBody(AspectContext aspectContext, ExecutableElement executableElement, String superCall, String loggerFieldName) {
        var logInLevel = logInLevel(executableElement, env);
        var logOutLevel = logOutLevel(executableElement, env);
        var b = CodeBlock.builder();

        b.add("var $N = $L;\n", RESULT_VAR_NAME, KoraAspect.callSuper(executableElement, superCall));
        if (logInLevel != null) {
            var finalResultName = RESULT_VAR_NAME + "_final";
            b.add("var $N = $N;\n", finalResultName, RESULT_VAR_NAME);
            ifLogLevelEnabled(b, loggerFieldName, logInLevel, () -> {
                b.add("$N = $T.defer(() -> {$>\n", RESULT_VAR_NAME, CommonClassNames.mono);
                b.add(this.buildLogIn(aspectContext, executableElement, logInLevel, loggerFieldName));
                b.add("return $N;", finalResultName);
                b.add("$<\n});");
            }).add("\n");
        }
        if (logOutLevel != null) {
            var logResultLevel = logResultLevel(executableElement, logOutLevel, env);
            ifLogLevelEnabled(b, loggerFieldName, logOutLevel, () -> {
                var returnType = (ParameterizedTypeName) TypeName.get(executableElement.getReturnType());
                if (logResultLevel == null || returnType.typeArguments.get(0).equals(TypeName.VOID.box())) {
                    b.add("$N = $N.doOnSuccess(v -> $N.$L($S));", RESULT_VAR_NAME, RESULT_VAR_NAME, loggerFieldName, logOutLevel.toLowerCase(), MESSAGE_OUT);
                } else {
                    var resultValue = RESULT_VAR_NAME + "_value";
                    b.add("var $N = this.$N;\n", loggerFieldName, loggerFieldName);
                    b.add("$N = $N.doOnSuccess($N -> $>{\n", RESULT_VAR_NAME, RESULT_VAR_NAME, resultValue);
                    b.add("if ($N != null) {$>\n", resultValue);
                    if (logOutLevel.equals(logResultLevel)) {
                        ifLogLevelEnabled(b, loggerFieldName, logOutLevel, () -> {
                            b.addStatement("var $N = $T.marker($S, gen -> gen.writeStringField($S, String.valueOf($N)))", DATA_OUT_VAR_NAME, structuredArgument, "data", "out", resultValue);
                            b.add("$N.$L($N, $S);", loggerFieldName, logOutLevel.toLowerCase(), DATA_OUT_VAR_NAME, MESSAGE_OUT);
                        });
                    } else {
                        ifLogLevelEnabled(b, loggerFieldName, logResultLevel, () -> {
                            b.addStatement("var $N = $T.marker($S, gen -> gen.writeStringField($S, String.valueOf($N)))", DATA_OUT_VAR_NAME, structuredArgument, "data", "out", resultValue);
                            b.add("$N.$L($N, $S);", loggerFieldName, logOutLevel.toLowerCase(), DATA_OUT_VAR_NAME, MESSAGE_OUT);
                            b.add("$<\n} else {$>\n");
                            b.add("$N.$L($S);", loggerFieldName, logOutLevel.toLowerCase(), MESSAGE_OUT);
                        });
                    }
                    b.add("$<\n} else {$>\n");
                    b.add("$N.$L($S);", loggerFieldName, logOutLevel.toLowerCase(), MESSAGE_OUT);
                    b.add("$<\n}");
                    b.add("$<\n});");
                }
            }).add("\n");
        }

        return new ApplyResult.MethodBody(b.add("return $N;\n", RESULT_VAR_NAME).build());
    }

    private ApplyResult futureBody(AspectContext aspectContext, ExecutableElement executableElement, String superCall, String loggerFieldName) {
        var logInLevel = logInLevel(executableElement, env);
        var logOutLevel = logOutLevel(executableElement, env);

        var b = CodeBlock.builder();
        if (logInLevel != null) {
            b.add(this.buildLogIn(aspectContext, executableElement, logInLevel, loggerFieldName));
        }

        var methodGeneric = MethodUtils.getGenericType(executableElement.getReturnType()).orElseThrow();

        var isVoid = MethodUtils.getGenericType(executableElement.getReturnType())
            .map(CommonUtils::isVoid)
            .orElse(false);

        b.add("return $L", KoraAspect.callSuper(executableElement, superCall));

        if (logOutLevel != null) {
            var logResultLevel = logResultLevel(executableElement, logOutLevel, env);
            final CodeBlock resultWriter;
            b.beginControlFlow(".thenApply($L -> ", RESULT_VAR_NAME);
            if (!isVoid && logResultLevel != null) {
                var mapping = CommonUtils.parseMapping(executableElement).getMapping(structuredArgumentMapper);
                var mapperType = mapping != null && mapping.mapperClass() != null
                    ? mapping.isGeneric() ? mapping.parameterized(TypeName.get(methodGeneric)) : TypeName.get(mapping.mapperClass())
                    : ParameterizedTypeName.get(structuredArgumentMapper, TypeName.get(methodGeneric));
                var mapper = aspectContext.fieldFactory().constructorParam(
                    mapperType,
                    List.of(AnnotationSpec.builder(CommonClassNames.nullable).build())
                );
                var resultWriterBuilder = CodeBlock.builder().add("gen -> {$>\n")
                    .add("gen.writeStartObject();\n")
                    .beginControlFlow("if (this.$N != null)", mapper)
                    .addStatement("gen.writeFieldName($S)", "out")
                    .addStatement("this.$N.write(gen, $N)", mapper, RESULT_VAR_NAME)
                    .nextControlFlow("else")
                    .addStatement("gen.writeStringField($S, String.valueOf($N))", "out", RESULT_VAR_NAME)
                    .endControlFlow()
                    .add("gen.writeEndObject();")
                    .add("$<\n}");
                resultWriter = resultWriterBuilder.build();

            } else {
                resultWriter = null;
            }

            if (isVoid || logResultLevel == null) {
                b.addStatement("$N.$L($S)", loggerFieldName, logOutLevel.toLowerCase(), MESSAGE_OUT);
            } else if (logOutLevel.equals(logResultLevel)) {
                ifLogLevelEnabled(b, loggerFieldName, logOutLevel, () -> {
                    b.add("var $N = $T.marker($S, $L);\n", DATA_OUT_VAR_NAME, structuredArgument, "data", resultWriter);
                    b.add("$N.$L($N, $S);", loggerFieldName, logOutLevel.toLowerCase(), DATA_OUT_VAR_NAME, MESSAGE_OUT);
                }).add("\n");
            } else {
                ifLogLevelEnabled(b, loggerFieldName, logResultLevel, () -> {
                    b.add("var $N = $T.marker($S, $L);\n", DATA_OUT_VAR_NAME, structuredArgument, "data", resultWriter);
                    b.add("$N.$L($N, $S);", loggerFieldName, logOutLevel.toLowerCase(), DATA_OUT_VAR_NAME, MESSAGE_OUT);
                    b.add("$<\n} else {$>\n");
                    b.add("$N.$L($S);", loggerFieldName, logOutLevel.toLowerCase(), MESSAGE_OUT);
                });
            }

            b.add("\n");
            b.addStatement("return $L", RESULT_VAR_NAME);
            b.endControlFlow(")");
        } else {
            b.add(";");
        }

        return new ApplyResult.MethodBody(b.build());
    }

    private CodeBlock buildLogIn(AspectContext aspectContext, ExecutableElement executableElement, String logInLevel, String loggerFieldName) {
        var b = CodeBlock.builder();
        var methodLevelIdx = LEVELS.indexOf(logInLevel);
        var logMarkerCode = this.logInMarker(aspectContext, loggerFieldName, executableElement, logInLevel);
        if (logMarkerCode != null) {
            if (LEVELS.indexOf(logMarkerCode.minLogLevel()) <= methodLevelIdx) {
                ifLogLevelEnabled(b, loggerFieldName, logInLevel, () -> {
                    b.add(logMarkerCode.codeBlock());
                    b.add("$N.$L($N, $S);", loggerFieldName, logInLevel.toLowerCase(), DATA_IN_VAR_NAME, MESSAGE_IN);
                }).add("\n");
            } else {
                ifLogLevelEnabled(b, loggerFieldName, logMarkerCode.minLogLevel(), () -> {
                    b.add(logMarkerCode.codeBlock());
                    b.add("$N.$L($N, $S);", loggerFieldName, logInLevel.toLowerCase(), DATA_IN_VAR_NAME, MESSAGE_IN);
                    b.add("$<\n} else {$>\n");
                    b.add("$N.$L($S);", loggerFieldName, logInLevel.toLowerCase(), MESSAGE_IN);
                }).add("\n");
            }
        } else {
            b.add("$N.$L($S);\n", loggerFieldName, logInLevel.toLowerCase(), MESSAGE_IN);
        }
        return b.build();
    }

    record LogInMarker(CodeBlock codeBlock, String minLogLevel) {
    }

    /**
     * <pre> {@code var __dataIn = StructuredArgument.marker("data", gen -> {
     *      gen.writeStartObject();
     *      gen.writeStringField("param1", String.valueOf(param1));
     *      gen.writeStringField("param2", String.valueOf(param2));
     *      gen.writeEndObject();
     *  });
     * } </pre>
     */
    @Nullable
    private LogInMarker logInMarker(AspectContext aspectContext, String loggerField, ExecutableElement executableElement, String logInLevel) {
        var parametersToLog = new ArrayList<VariableElement>(executableElement.getParameters().size());
        for (var parameter : executableElement.getParameters()) {
            if (AnnotationUtils.findAnnotation(parameter, logOff) == null) {
                parametersToLog.add(parameter);
            }
        }
        if (parametersToLog.isEmpty()) {
            return null;
        }

        var b = CodeBlock.builder();
        b.add("var $N = $T.marker($S, gen -> {$>", DATA_IN_VAR_NAME, structuredArgument, "data");
        b.add("\ngen.writeStartObject();");

        var params = new HashMap<String, List<VariableElement>>();
        var minLevelIdx = Integer.MAX_VALUE;

        for (var parameter : parametersToLog) {
            var level = Objects.requireNonNull(logParameterLevel(parameter, logInLevel, env));
            minLevelIdx = Math.min(minLevelIdx, LEVELS.indexOf(level));
            params.computeIfAbsent(level, l -> new ArrayList<>()).add(parameter);
        }
        for (int i = 0; i < LEVELS.size(); i++) {
            var level = LEVELS.get(i);
            var paramsForLevel = params.getOrDefault(level, List.of());
            if (paramsForLevel.isEmpty()) {
                continue;
            }
            if (i > minLevelIdx) {
                b.add("\nif ($N.$N()) {$>", loggerField, "is" + CommonUtils.capitalize(level.toLowerCase()) + "Enabled");
            }
            for (var param : paramsForLevel) {
                var mapping = CommonUtils.parseMapping(param).getMapping(structuredArgumentMapper);
                var mapperType = mapping != null && mapping.mapperClass() != null
                    ? mapping.isGeneric() ? mapping.parameterized(TypeName.get(param.asType())) : TypeName.get(mapping.mapperClass())
                    : ParameterizedTypeName.get(structuredArgumentMapper, TypeName.get(param.asType()).box());
                var mapper = aspectContext.fieldFactory().constructorParam(
                    mapperType,
                    List.of(AnnotationSpec.builder(CommonClassNames.nullable).build())
                );
                b.add("\nif (this.$N != null) {", mapper);
                b.add("\n  gen.writeFieldName($S);", param.getSimpleName());
                b.add("\n  this.$N.write(gen, $N);", mapper, param.getSimpleName());
                b.add("\n} else {");
                b.add("\ngen.writeStringField($S, String.valueOf($N));", param.getSimpleName(), param.getSimpleName());
                b.add("\n}");
            }
            if (i > minLevelIdx) {
                b.add("$<\n}");
            }
        }
        b.add("\ngen.writeEndObject();");

        return new LogInMarker(b.add("$<\n});\n").build(), LEVELS.get(minLevelIdx));
    }

    private CodeBlock.Builder ifLogLevelEnabled(CodeBlock.Builder cb, String loggerFieldName, String logLevel, Runnable r) {
        cb.add("if ($N.$N()) {$>\n", loggerFieldName, "is" + CommonUtils.capitalize(logLevel.toLowerCase()) + "Enabled");
        r.run();
        cb.add("$<\n}");

        return cb;
    }
}
