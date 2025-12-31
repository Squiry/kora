package ru.tinkoff.kora.application.graph.internal;

import org.jspecify.annotations.Nullable;
import ru.tinkoff.kora.application.graph.Node;

import java.lang.reflect.Type;
import java.util.List;

public final class CompositeConditionalNode<T> implements Node<T> {
    private final Type type;
    @Nullable
    private final Class<?> tag;
    public final List<NodeImpl<? extends T>> candidates;

    public CompositeConditionalNode(Type type, @Nullable Class<?> tag, List<NodeImpl<? extends T>> candidates) {
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
