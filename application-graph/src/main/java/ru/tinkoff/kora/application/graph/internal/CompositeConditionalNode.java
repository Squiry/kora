package ru.tinkoff.kora.application.graph.internal;

import org.jspecify.annotations.Nullable;
import ru.tinkoff.kora.application.graph.ApplicationGraphDraw;
import ru.tinkoff.kora.application.graph.Node;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Function;

public final class CompositeConditionalNode<T> implements Node<T> {
    private final Type type;
    @Nullable
    private final Class<?> tag;
    public final List<NodeWithMapper<?, ? extends T>> candidates;

    public record NodeWithMapper<N, T>(NodeImpl<N> node, Function<N, ? extends T> mapper) {
        public static <N, T> NodeWithMapper<N, T> from(Node.NodeWithMapper<N, ? extends T> nodeWithMapper) {
            return new NodeWithMapper<>((NodeImpl<N>) nodeWithMapper.node(), nodeWithMapper.mapper());
        }

        public static <N, T> NodeWithMapper<N, T> fromOtherGraph(ApplicationGraphDraw draw, NodeWithMapper<N, T> nodeWithMapper) {
            return new NodeWithMapper<>((NodeImpl<N>) draw.getNodes().get(nodeWithMapper.node().index), nodeWithMapper.mapper());
        }

        public T get(AtomicReferenceArray<Object> graph) {
            @SuppressWarnings("unchecked")
            var object = (N) graph.get(node.index);
            return mapper.apply(object);
        }

        @SuppressWarnings("unchecked")
        public T applyMapper(Object object) {
            return mapper.apply((N) object);
        }
    }


    public CompositeConditionalNode(Type type, @Nullable Class<?> tag, List<NodeWithMapper<?, ? extends T>> candidates) {
        this.type = type;
        this.tag = tag;
        this.candidates = candidates;
    }

    @Override
    public Type type() {
        return type;
    }

    @Override
    @Nullable
    public Class<?> tag() {
        return tag;
    }
}
