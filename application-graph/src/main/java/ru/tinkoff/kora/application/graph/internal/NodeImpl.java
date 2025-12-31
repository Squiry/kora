package ru.tinkoff.kora.application.graph.internal;

import org.jspecify.annotations.Nullable;
import ru.tinkoff.kora.application.graph.*;

import java.lang.reflect.Type;
import java.util.List;

public final class NodeImpl<T> implements Node<T> {
    public final ApplicationGraphDraw graphDraw;
    public final int index;
    public final Graph.Factory<? extends T> factory;

    public final Type type;
    public final Class<?> tag;

    public final @Nullable Node<? extends NodeCondition> condition;
    public final List<Node<?>> createDependencies;
    public final List<Node<?>> refreshDependencies;
    public final List<Node<? extends GraphInterceptor<T>>> interceptors;

    public NodeImpl(
        ApplicationGraphDraw graphDraw,
        Type type,
        @Nullable Class<?> tag,
        int index,
        @Nullable Node<? extends NodeCondition> condition,
        List<Node<?>> createDependencies,
        List<Node<?>> refreshDependencies,
        List<Node<? extends GraphInterceptor<T>>> interceptors,
        Graph.Factory<? extends T> factory) {
        this.graphDraw = graphDraw;
        this.index = index;
        this.createDependencies = createDependencies;
        this.refreshDependencies = refreshDependencies;
        this.factory = factory;
        this.type = type;
        this.interceptors = List.copyOf(interceptors);
        this.tag = tag;
        this.condition = condition;
    }

    @Override
    public Type type() {
        return this.type;
    }

    @Override
    public Class<?> tag() {
        return tag;
    }

    @Override
    public String toString() {
        return "" + index;
    }
}
