package ru.tinkoff.kora.application.graph;

import org.jspecify.annotations.Nullable;
import ru.tinkoff.kora.application.graph.internal.CompositeConditionalNode;
import ru.tinkoff.kora.application.graph.internal.GraphImpl;
import ru.tinkoff.kora.application.graph.internal.NodeImpl;

import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

public class ApplicationGraphDraw {

    private final List<NodeImpl<?>> graphNodes = new ArrayList<>();
    private final Class<?> root;

    public ApplicationGraphDraw(Class<?> root) {
        this.root = root;
    }

    public Class<?> getRoot() {
        return root;
    }

    public record CreateDependency(Node<?> node, boolean isAllOf) {}

    public <T> Node<T> addNode(
        Type type,
        @Nullable Class<?> tag,
        @Nullable Node<? extends NodeCondition> condition,
        List<CreateDependency> createDependencies,
        List<Node<?>> refreshDependencies,
        List<Node<? extends GraphInterceptor<T>>> interceptors,
        Graph.Factory<? extends T> factory) {
        for (var dependency : createDependencies) {
            switch (dependency.node()) {
                case CompositeConditionalNode<?> v -> {
                    // todo ?
                }
                case NodeImpl<?> node -> {
                    if (node.index >= 0 && node.graphDraw != this) {
                        throw new IllegalArgumentException("Dependency is from another graph");
                    }
                }
            }
        }

        var node = new NodeImpl<>(
            this,
            type,
            tag,
            this.graphNodes.size(),
            condition,
            createDependencies,
            refreshDependencies,
            interceptors,
            factory
        );
        this.graphNodes.add(node);
        return node;
    }

    public RefreshableGraph init() {
        var graph = new GraphImpl(this);
        graph.init();
        return graph;
    }

    public List<Node<?>> getNodes() {
        return Collections.unmodifiableList(this.graphNodes);
    }

    public int size() {
        return this.graphNodes.size();
    }

    @Nullable
    public Node<?> findNodeByType(Type type) {
        for (var graphNode : this.graphNodes) {
            if (graphNode.type().equals(type) && graphNode.tag() == null) {
                return graphNode;
            }
        }
        return null;
    }

    public List<Node<?>> findNodesByType(Type type, Class<?> tag) {
        var result = new ArrayList<Node<?>>();
        for (var graphNode : this.graphNodes) {
            if (graphNode.type().equals(type)) {
                if (Objects.equals(tag, graphNode.tag()) || tag != null && tag.getCanonicalName().equals("ru.tinkoff.kora.common.Tag.Any")) {
                    result.add(graphNode);
                }
            }
        }
        return result;
    }

    public <T> void replaceNode(Node<T> node, Graph.Factory<? extends T> factory) {
        var casted = (NodeImpl<T>) node;
        this.graphNodes.set(casted.index, new NodeImpl<T>(
            this, casted.type, casted.tag, casted.index, null, List.of(), List.of(), List.of(), factory
        ));
    }

    public <T> void replaceNodeKeepDependencies(Node<T> node, Graph.Factory<? extends T> factory) {
        var casted = (NodeImpl<T>) node;
        this.graphNodes.set(casted.index, new NodeImpl<T>(
            this, casted.type, casted.tag, casted.index, null, casted.createDependencies, casted.refreshDependencies, List.of(), factory
        ));
    }

    public ApplicationGraphDraw copy() {
        var draw = new ApplicationGraphDraw(this.root);
        for (var node : this.graphNodes) {
            class T {
                static <T> void addNode(ApplicationGraphDraw draw, NodeImpl<T> node) {
                    var createDependencies = new ArrayList<ApplicationGraphDraw.CreateDependency>(node.createDependencies.size());
                    for (var dependency : node.createDependencies) {
                        switch (dependency.node()) {
                            case CompositeConditionalNode<?> v -> {
                                var newNode = new CompositeConditionalNode<>(v.type(), v.tag(), new ArrayList<>());
                                for (var candidate : v.candidates) {
                                    newNode.candidates.add(CompositeConditionalNode.NodeWithMapper.fromOtherGraph(draw, candidate));
                                }
                                createDependencies.add(new CreateDependency(newNode, dependency.isAllOf));
                            }
                            case NodeImpl<?> v -> {
                                createDependencies.add(new CreateDependency(draw.graphNodes.get(v.index), dependency.isAllOf));
                            }
                        }
                    }
                    var refreshDependencies = new ArrayList<Node<?>>(node.refreshDependencies.size());
                    for (var dependency : node.refreshDependencies) {
                        switch (dependency) {
                            case CompositeConditionalNode<?> v -> {
                                var newNode = new CompositeConditionalNode<>(v.type(), v.tag(), new ArrayList<>());
                                for (var candidate : v.candidates) {
                                    newNode.candidates.add(CompositeConditionalNode.NodeWithMapper.fromOtherGraph(draw, candidate));
                                }
                                refreshDependencies.add(newNode);
                            }
                            case NodeImpl<?> v -> {
                                refreshDependencies.add(draw.graphNodes.get(v.index));
                            }
                        }
                    }
                    var interceptors = new ArrayList<Node<? extends GraphInterceptor<T>>>(node.interceptors.size());
                    for (var interceptor : node.interceptors) {
                        interceptors.add((Node<? extends GraphInterceptor<T>>) draw.graphNodes.get(((NodeImpl<?>) interceptor).index));
                    }
                    var condition = node.condition;
                    @SuppressWarnings("unchecked")
                    var newCondition = condition == null ? null : (Node<? extends NodeCondition>) draw.graphNodes.get(((NodeImpl<?>) condition).index);
                    draw.addNode(node.type(), node.tag(), newCondition, createDependencies, refreshDependencies, interceptors, node.factory);
                }
            }
            T.addNode(draw, node);
        }
        return draw;
    }

    public ApplicationGraphDraw subgraph(List<Node<?>> excludeTransitive, Iterable<Node<?>> rootNodes) {
        var seen = new TreeMap<Integer, Integer>();
        var excludeTransitiveSet = excludeTransitive.stream().map(n -> ((NodeImpl<?>) n).index).collect(Collectors.toSet());

        var subgraph = new ApplicationGraphDraw(this.root);
        var visitor = new Object() {
            public <T> Node<T> accept(NodeImpl<T> node) {
                if (!seen.containsKey(node.index)) {
                    var dependencies = new ArrayList<CreateDependency>();
                    var dependencyNodes = new ArrayList<Node<?>>();
                    var interceptors = new ArrayList<Node<? extends GraphInterceptor<T>>>();
                    if (!excludeTransitiveSet.contains(node.index)) {
                        for (var dependencyNode : node.createDependencies) {
                            switch (dependencyNode.node) {
                                case CompositeConditionalNode<?> v -> v.candidates.forEach(candidate -> {
                                    var n = this.accept(candidate.node());
                                    dependencies.add(new CreateDependency(n, dependencyNode.isAllOf));
                                    dependencyNodes.add(n);
                                });
                                case NodeImpl<?> v -> {
                                    var n = this.accept(v);
                                    dependencies.add(new CreateDependency(n, dependencyNode.isAllOf));
                                    dependencyNodes.add(n);
                                }
                            }
                        }
                    }
                    for (var interceptor : node.interceptors) {
                        interceptors.add(this.accept((NodeImpl<? extends GraphInterceptor<T>>) interceptor));
                    }
                    Graph.Factory<T> factory = graph -> node.factory.get(new Graph() {
                        @Override
                        public ApplicationGraphDraw draw() {
                            return subgraph;
                        }

                        @Override
                        public <Q> Q get(Node<Q> node1) {
                            var casted = (NodeImpl<Q>) node1;
                            @SuppressWarnings("unchecked")
                            var realNode = (Node<Q>) subgraph.graphNodes.get(seen.get(casted.index));
                            return graph.get(realNode);
                        }

                        @Override
                        public <Q> ValueOf<Q> valueOf(Node<? extends Q> node1) {
                            var casted = (NodeImpl<? extends Q>) node1;
                            @SuppressWarnings("unchecked")
                            var realNode = (Node<Q>) subgraph.graphNodes.get(seen.get(casted.index));
                            return graph.valueOf(realNode);
                        }

                        @Override
                        public <Q> PromiseOf<Q> promiseOf(Node<Q> node1) {
                            var casted = (NodeImpl<Q>) node1;
                            @SuppressWarnings("unchecked")
                            var realNode = (Node<Q>) subgraph.graphNodes.get(seen.get(casted.index));
                            return graph.promiseOf(realNode);
                        }

                        @Override
                        public boolean _shouldNodeBeCrated(Node<?> node) {
                            return switch (node) {
                                case CompositeConditionalNode<?> v -> true;
                                case NodeImpl<?> casted -> {
                                    var realNode = subgraph.graphNodes.get(seen.get(casted.index));
                                    yield graph._shouldNodeBeCrated(realNode);
                                }
                            };
                        }
                    });
                    var newNode = (NodeImpl<T>) subgraph.addNode(node.type(), node.tag(), node.condition, dependencies, dependencyNodes, interceptors, factory);// todo
                    seen.put(node.index, newNode.index);
                    return newNode;
                }
                var index = seen.get(node.index);
                @SuppressWarnings("unchecked")
                var newNode = (Node<T>) subgraph.graphNodes.get(index);
                return newNode;
            }
        };
        for (var rootNode : rootNodes) {
            var casted = (NodeImpl<?>) rootNode;
            visitor.accept(this.graphNodes.get(casted.index));
        }
        return subgraph;
    }
}
