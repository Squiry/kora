package ru.tinkoff.kora.application.graph;

import org.jspecify.annotations.Nullable;
import ru.tinkoff.kora.application.graph.internal.CompositeConditionalNode;
import ru.tinkoff.kora.application.graph.internal.NodeImpl;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public sealed interface Node<T> permits CompositeConditionalNode, NodeImpl {
    Type type();

    @Nullable
    Class<?> tag();

    static <T> Node<T> oneOf(Type type, @Nullable Class<?> tag, List<Node<? extends T>> candidates) {
        var realCandidates = new ArrayList<NodeImpl<? extends T>>(candidates.size());
        for (var candidate : candidates) {
            realCandidates.add((NodeImpl<? extends T>) candidate);
        }
        return new CompositeConditionalNode<>(type, tag, realCandidates);
    }
}
