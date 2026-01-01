package ru.tinkoff.kora.application.graph;

import org.jspecify.annotations.Nullable;
import ru.tinkoff.kora.application.graph.internal.CompositeConditionalNode;
import ru.tinkoff.kora.application.graph.internal.NodeImpl;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public sealed interface Node<T> permits CompositeConditionalNode, NodeImpl {
    Type type();

    @Nullable
    Class<?> tag();

    record NodeWithMapper<N, T>(Node<N> node, Function<N, ? extends T> mapper) {
        public T get(Graph graph) {
            var value = graph.get(node);
            return mapper.apply(value);
        }

        public ValueOf<T> getValueOf(Graph graph) {
            return graph.valueOf(node).map(mapper);
        }

        public PromiseOf<T> getPromiseOf(Graph graph) {
            return graph.promiseOf(node).map(mapper);
        }
    }

    static <T> Node<T> oneOf(Type type, @Nullable Class<?> tag, List<NodeWithMapper<?, ? extends T>> candidates) {
        var realCandidates = new ArrayList<CompositeConditionalNode.NodeWithMapper<?, ? extends T>>(candidates.size());
        for (var candidate : candidates) {
            realCandidates.add(CompositeConditionalNode.NodeWithMapper.from(candidate));
        }
        return new CompositeConditionalNode<>(type, tag, realCandidates);
    }

    static <T> Node<T> oneOfNoMapper(Type type, @Nullable Class<?> tag, List<Node<T>> candidates) {
        var realCandidates = new ArrayList<CompositeConditionalNode.NodeWithMapper<?, ? extends T>>(candidates.size());
        for (var candidate : candidates) {
            realCandidates.add(new CompositeConditionalNode.NodeWithMapper<>((NodeImpl<T>) candidate, Function.identity()));
        }
        return new CompositeConditionalNode<>(type, tag, realCandidates);
    }
}
