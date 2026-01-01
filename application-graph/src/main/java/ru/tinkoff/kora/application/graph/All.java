package ru.tinkoff.kora.application.graph;

import ru.tinkoff.kora.application.graph.internal.AllImpl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public sealed interface All<T> extends Collection<T> permits AllImpl {
    static <T> All<T> empty() {
        return new AllImpl<>(List.of());
    }

    static <T> All<T> allOf(Graph graph, List<Node.NodeWithMapper<?, T>> nodes) {
        var values = new ArrayList<T>(nodes.size());
        for (var node : nodes) {
            if (graph._shouldNodeBeCrated(node.node())) {
                values.add(node.get(graph));
            }
        }
        return new AllImpl<>(values);
    }

    static <T> All<ValueOf<T>> allOfValue(Graph graph, List<Node.NodeWithMapper<?, T>> nodes) {
        var values = new ArrayList<ValueOf<T>>(nodes.size());
        for (var node : nodes) {
            if (graph._shouldNodeBeCrated(node.node())) {
                values.add(node.getValueOf(graph));
            }
        }
        return new AllImpl<>(values);
    }

    static <T> All<PromiseOf<T>> allOfPromise(Graph graph, List<Node.NodeWithMapper<?, T>> nodes) {
        var values = new ArrayList<PromiseOf<T>>(nodes.size());
        for (var node : nodes) {
            if (graph._shouldNodeBeCrated(node.node())) {
                values.add(node.getPromiseOf(graph));
            }
        }
        return new AllImpl<>(values);
    }
}

