package ru.tinkoff.kora.ksp.common

import com.google.devtools.ksp.isProtected
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.*
import ru.tinkoff.kora.ksp.common.AnnotationUtils.isAnnotationPresent
import ru.tinkoff.kora.ksp.common.CommonClassNames.aopAnnotation
import ru.tinkoff.kora.ksp.common.KspCommonUtils.resolveToUnderlying

object CommonAopUtils {

    fun KSClassDeclaration.extendsKeepAop(newName: String, resolver: Resolver): TypeSpec.Builder {
        val type = this
        val b: TypeSpec.Builder = TypeSpec.classBuilder(newName)
            .addOriginatingKSFile(type.containingFile!!)
        if (type.classKind == ClassKind.INTERFACE) {
            b.addSuperinterface(type.toClassName())
        } else {
            b.superclass(type.toClassName())
        }

        var hasAop = type.hasAopAnnotation()
        val methods = findMethods(type) { f -> f.isPublic() || f.isProtected() }
        for (method in methods) {
            var isMethodAop = method.hasAopAnnotation()

            for (parameter in method.parameters) {
                if (parameter.hasAopAnnotation()) {
                    isMethodAop = true
                }
            }

            if (isMethodAop && !method.isAbstract) {
                val superParameters = method.parameters.joinToString(", ") { p -> p.name!!.asString() }
                b.addFunction(
                    method.overridingKeepAop(resolver)
                        .addCode("return super.%L(%L)", method.simpleName.asString(), superParameters)
                        .build()
                )
            }

            if (!hasAop) {
                hasAop = isMethodAop
            }
        }

        if (hasAop) {
            b.addModifiers(KModifier.OPEN)
        } else {
            b.addModifiers(KModifier.FINAL)
        }

        for (annotationMirror in type.annotations) {
            if (annotationMirror.isAopAnnotation()) {
                b.addAnnotation(annotationMirror.toAnnotationSpec())
            }
        }
        return b
    }

    fun KSFunctionDeclaration.overridingKeepAop(resolver: Resolver): FunSpec.Builder {
        val funDeclaration = this
        val funBuilder = FunSpec.builder(funDeclaration.simpleName.asString())
        if (funDeclaration.modifiers.contains(Modifier.SUSPEND)) {
            funBuilder.addModifiers(KModifier.SUSPEND)
        }
        if (funDeclaration.modifiers.contains(Modifier.PROTECTED)) {
            funBuilder.addModifiers(KModifier.PROTECTED)
        }
        if (funDeclaration.modifiers.contains(Modifier.PUBLIC)) {
            funBuilder.addModifiers(KModifier.PUBLIC)
        }

        for (typeParameter in funDeclaration.typeParameters) {
            funBuilder.addTypeVariable(typeParameter.toTypeVariableName())
        }
        funBuilder.addModifiers(KModifier.OVERRIDE)
        for (annotation in funDeclaration.annotations) {
            if (annotation.isAopAnnotation()) {
                funBuilder.addAnnotation(annotation.toAnnotationSpec())
            }
        }
        val returnType = funDeclaration.returnType!!.resolve()
        if (returnType != resolver.builtIns.unitType) {
            funBuilder.returns(returnType.toTypeName())
        }
        for (parameter in funDeclaration.parameters) {
            val parameterType = parameter.type
            val name = parameter.name!!.asString()
            val pb = ParameterSpec.builder(name, parameterType.toTypeName())
            if (parameter.isVararg) {
                pb.addModifiers(KModifier.VARARG)
            }
            for (annotation in parameter.annotations) {
                if (annotation.isAopAnnotation()) {
                    pb.addAnnotation(annotation.toAnnotationSpec())
                }
            }
            funBuilder.addParameter(pb.build())
        }

        return funBuilder
    }

    fun KSAnnotated.hasAopAnnotations(): Boolean {
        if (this.hasAopAnnotation()) {
            return true
        }
        val methods = findMethods(this) { f ->
            f.isPublic() || f.isProtected()
        }
        for (method in methods) {
            if (method.hasAopAnnotation()) {
                return true
            }
            for (parameter in method.parameters) {
                if (parameter.hasAopAnnotation()) {
                    return true
                }
            }
        }
        return false
    }

    fun KSAnnotated.hasAopAnnotation(): Boolean {
        return annotations.any { it.isAopAnnotation() }
    }

    fun KSAnnotation.isAopAnnotation(): Boolean {
        return annotationType.resolveToUnderlying().declaration.isAnnotationPresent(aopAnnotation)
    }

}
