package ru.tinkoff.kora.kora.app.annotation.processor;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import jakarta.annotation.Nullable;
import ru.tinkoff.kora.annotation.processor.common.CommonClassNames;
import ru.tinkoff.kora.annotation.processor.common.ProcessingErrorException;
import ru.tinkoff.kora.kora.app.annotation.processor.component.ComponentDependency;
import ru.tinkoff.kora.kora.app.annotation.processor.component.DependencyClaim;
import ru.tinkoff.kora.kora.app.annotation.processor.component.ResolvedComponent;
import ru.tinkoff.kora.kora.app.annotation.processor.declaration.ComponentDeclaration;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.*;

public sealed interface ProcessingState {
    sealed interface ResolutionFrame {
        record Root(int rootIndex) implements ResolutionFrame {}

        record Component(ComponentDeclaration declaration, List<DependencyClaim> dependenciesToFind, List<ComponentDependency> resolvedDependencies, int currentDependency) implements ResolutionFrame {
            public Component(ComponentDeclaration declaration, List<DependencyClaim> dependenciesToFind) {
                this(declaration, dependenciesToFind, new ArrayList<>(dependenciesToFind.size()), 0);
            }

            public Component withCurrentDependency(int currentDependency) {
                return new Component(declaration, dependenciesToFind, resolvedDependencies, currentDependency);
            }
        }
    }

    default Deque<ResolutionFrame> stack() {
        return this instanceof Processing processing
            ? processing.resolutionStack
            : new ArrayDeque<>();
    }

    record None(TypeElement root, List<TypeElement> allModules, List<ComponentDeclaration> sourceDeclarations, List<ComponentDeclaration> templates,
                List<ComponentDeclaration> rootSet) implements ProcessingState {}

    record Processing(TypeElement root, List<TypeElement> allModules, List<ComponentDeclaration> sourceDeclarations, Map<TypeName, List<ComponentDeclaration>> typeToDeclMap,
                      List<ComponentDeclaration> templates, List<ComponentDeclaration> rootSet,
                      List<ResolvedComponent> resolvedComponents, Deque<ResolutionFrame> resolutionStack) implements ProcessingState {

        public void addSourceDeclaration(ComponentDeclaration declaration) {
            class Visitor {
                void visit(TypeMirror type, ComponentDeclaration d) {
                    if (type.getKind() == TypeKind.NONE) {
                        return;
                    }
                    var typeName = TypeName.get(type);
                    if (typeName instanceof ParameterizedTypeName ptn) {
                        typeName = ptn.rawType;
                    }
                    typeToDeclMap.computeIfAbsent(typeName, k -> new ArrayList<>()).add(d);
                    if (type instanceof DeclaredType dt) {
                        var typeElement = (TypeElement) dt.asElement();
                        if (ClassName.get(typeElement).equals(CommonClassNames.wrapped)) {
                            visit(dt.getTypeArguments().get(0), d);
                        }
                        visit(typeElement.getSuperclass(), d);
                        for (var anInterface : typeElement.getInterfaces()) {
                            visit(anInterface, d);
                        }
                    }
                }
            }
            new Visitor().visit(declaration.type(), declaration);
            sourceDeclarations.add(declaration);

        }

        @Nullable
        public ResolvedComponent findResolvedComponent(ComponentDeclaration declaration) {
            for (var resolvedComponent : this.resolvedComponents()) {
                if (declaration == resolvedComponent.declaration()) {
                    return resolvedComponent;
                }
            }
            return null;
        }
    }

    record Ok(TypeElement root, List<TypeElement> allModules, List<ResolvedComponent> components, Map<TypeName, List<ComponentDeclaration>> declarationMap) implements ProcessingState {}

    record NewRoundRequired(Object source, TypeMirror type, Set<String> tag, Processing processing) implements ProcessingState {}

    record Failed(ProcessingErrorException detailedException, Deque<ResolutionFrame> stack) implements ProcessingState {}
}
