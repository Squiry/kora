package ru.tinkoff.kora.kora.app.ksp.component

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver
import ru.tinkoff.kora.application.graph.TypeRef
import ru.tinkoff.kora.kora.app.ksp.GraphResolutionHelper
import ru.tinkoff.kora.kora.app.ksp.GraphResolutionHelper.findDependency
import ru.tinkoff.kora.kora.app.ksp.ProcessingContext
import ru.tinkoff.kora.kora.app.ksp.declaration.ComponentDeclaration
import ru.tinkoff.kora.ksp.common.CommonClassNames

sealed interface ComponentDependency {
    val claim: DependencyClaim

    context(_: ProcessingContext)
    fun write(): CodeBlock

    sealed interface SingleDependency : ComponentDependency {
        val component: ResolvedComponent?
    }

    data class TargetDependency(override val claim: DependencyClaim, override val component: ResolvedComponent) : SingleDependency {
        context(_: ProcessingContext)
        override fun write(): CodeBlock {
            return CodeBlock.of("it.get(%N.%N)", component.holderName, component.fieldName)
        }

    }

    data class WrappedTargetDependency(override val claim: DependencyClaim, override val component: ResolvedComponent) : SingleDependency {
        context(_: ProcessingContext)
        override fun write(): CodeBlock {
            return CodeBlock.of("it.get(%N.%N).value()", component.holderName, component.fieldName)
        }
    }

    data class NullDependency(override val claim: DependencyClaim) : SingleDependency {
        override val component = null

        context(_: ProcessingContext)
        override fun write(): CodeBlock {
            return when (claim.claimType) {
                DependencyClaim.DependencyClaimType.NULLABLE_ONE -> CodeBlock.of("null as %T", claim.type.toTypeName().copy(true))
                DependencyClaim.DependencyClaimType.NULLABLE_VALUE_OF -> CodeBlock.of("null as %T", CommonClassNames.valueOf.parameterizedBy(claim.type.toTypeName()).copy(true))
                DependencyClaim.DependencyClaimType.NULLABLE_PROMISE_OF -> CodeBlock.of("null as %T", CommonClassNames.promiseOf.parameterizedBy(claim.type.toTypeName()).copy(true))
                else -> throw IllegalArgumentException(claim.claimType.toString())
            }
        }
    }


    data class ValueOfDependency(override val claim: DependencyClaim, val delegate: SingleDependency) : SingleDependency {
        override val component
            get() = delegate.component

        context(_: ProcessingContext)
        override fun write(): CodeBlock {
            if (delegate is NullDependency) {
                return CodeBlock.of("%T.valueOfNull()", CommonClassNames.valueOf)
            }
            val component = delegate.component!!
            if (delegate is WrappedTargetDependency) {
                return CodeBlock.of("it.valueOf(%N.%N).map { it.value() }.map { it as %T }", component.holderName, component.fieldName, claim.type.toTypeName())
            }
            return CodeBlock.of("it.valueOf(%N.%N).map { it as %T }", component.holderName, component.fieldName, claim.type.toTypeName())
        }
    }

    data class PromiseOfDependency(override val claim: DependencyClaim, val delegate: SingleDependency) : SingleDependency {
        override val component
            get() = delegate.component

        context(_: ProcessingContext)
        override fun write(): CodeBlock {
            if (delegate is NullDependency) {
                return CodeBlock.of("%T.promiseOfNull()", CommonClassNames.promiseOf)
            }
            val component = delegate.component!!
            if (delegate is WrappedTargetDependency) {
                return CodeBlock.of("it.promiseOf(%N.%N).map { it.value() }.map { it as %T }", component.holderName, component.fieldName, claim.type.toTypeName())
            }
            return CodeBlock.of("it.promiseOf(%N.%N).map { it as %T }", component.holderName, component.fieldName, claim.type.toTypeName())
        }
    }

    data class TypeOfDependency(override val claim: DependencyClaim) : ComponentDependency {
        context(_: ProcessingContext)
        override fun write(): CodeBlock {
            return buildTypeRef(claim.type)
        }

        private fun buildTypeRef(typeRef: KSType): CodeBlock {
            val typeParameterResolver = typeRef.declaration.typeParameters.toTypeParameterResolver()
            var declaration = typeRef.declaration
            if (declaration is KSTypeAlias) {
                declaration = declaration.type.resolve().declaration
            }
            if (declaration is KSClassDeclaration) {
                val b = CodeBlock.builder()
                val typeArguments = typeRef.arguments

                if (typeArguments.isEmpty()) {
                    b.add("%T.of(%T::class.java)", TypeRef::class, declaration.toClassName())
                } else {
                    b.add("%T.of(%T::class.java", TypeRef::class, declaration.toClassName())
                    for (typeArgument in typeArguments) {
                        b.add(",\n%L", buildTypeRef(typeArgument.type!!.resolve()))
                    }
                    b.add("\n)")
                }
                return b.build()
            } else {
                return CodeBlock.of("%T.of(%T::class.java)", TypeRef::class, typeRef.toTypeName(typeParameterResolver))
            }
        }
    }

    data class AllOfDependency(override val claim: DependencyClaim) : ComponentDependency {
        context(ctx: ProcessingContext)
        override fun write(): CodeBlock {
            val codeBlock = CodeBlock.builder().add("%T.of(", CommonClassNames.all)
            val dependencies = GraphResolutionHelper.findDependenciesForAllOf(ctx, claim, ctx.components)
            for ((i, dependency) in dependencies.withIndex()) {
                if (i == 0) {
                    codeBlock.indent().add("\n")
                }
                codeBlock.add(dependency.write())
                if (i == dependencies.size - 1) {
                    codeBlock.unindent()
                } else {
                    codeBlock.add(", ")
                }
                codeBlock.add("\n")
            }
            return codeBlock.add(")").build()
        }
    }

    data class PromisedProxyParameterDependency(val declaration: ComponentDeclaration, override val claim: DependencyClaim) : ComponentDependency {
        context(ctx: ProcessingContext)
        override fun write(): CodeBlock {
            val dependencies = findDependency(declaration, claim)
            return CodeBlock.of("it.promiseOf(self.%N.%N)", dependencies!!.component!!.holderName, dependencies.component!!.fieldName)
        }

    }

}
