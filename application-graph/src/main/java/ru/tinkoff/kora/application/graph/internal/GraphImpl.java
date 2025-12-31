package ru.tinkoff.kora.application.graph.internal;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.kora.application.graph.*;
import ru.tinkoff.kora.application.graph.internal.loom.VirtualThreadExecutorHolder;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public final class GraphImpl implements RefreshableGraph, Lifecycle {
    private static final long SLOW_NODE_INIT_THRESHOLD = Long.parseLong(System.getProperty("kora.graph.slowNodeInitThresholdMillis", "100"));
    private static final CompletableFuture<Void> EMPTY_FUTURE = CompletableFuture.completedFuture(null);

    private final Executor executor;
    private final ApplicationGraphDraw draw;
    private final Logger log;
    private final Lock initLock = new ReentrantLock();
    private final Set<Integer> refreshListenerNodes = new HashSet<>();

    private volatile AtomicReferenceArray<@Nullable Object> objects;

    public GraphImpl(ApplicationGraphDraw draw) {
        this.draw = draw;
        this.log = LoggerFactory.getLogger(this.draw.getRoot());
        this.objects = new AtomicReferenceArray<>(this.draw.size());
        var loomExecutor = VirtualThreadExecutorHolder.executor();
        this.executor = Objects.requireNonNullElse(loomExecutor, ForkJoinPool.commonPool());

        var loomLogger = LoggerFactory.getLogger(VirtualThreadExecutorHolder.class);
        var status = VirtualThreadExecutorHolder.status();
        if (status == VirtualThreadExecutorHolder.VirtualThreadStatus.ENABLED) {
            loomLogger.info("VirtualThreadExecutor enabled");
        } else if (status == VirtualThreadExecutorHolder.VirtualThreadStatus.DISABLED) {
            loomLogger.info("VirtualThreadExecutor disabled");
        } else {
            loomLogger.info("VirtualThreadExecutor unavailable");
        }
    }

    @Override
    public ApplicationGraphDraw draw() {
        return this.draw;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Node<T> node) {
        return getImpl(draw, objects, node);
    }

    private static <T> T getImpl(ApplicationGraphDraw draw, AtomicReferenceArray<@Nullable Object> objects, Node<T> node) {
        switch (node) {
            case CompositeConditionalNode<T> composite -> {
                var candidates = new ArrayList<>(composite.candidates.size());
                for (var candidate : composite.candidates) {
                    var object = objects.get(candidate.index);
                    if (object instanceof NodeCondition.ConditionResult.Failed) {
                        continue;
                    }
                    candidates.add(object);
                }
                if (candidates.size() == 1) {
                    return (T) candidates.getFirst();
                }
                if (candidates.isEmpty()) {
                    var message = new StringBuilder("All candidates for type ").append(node.type()).append(" ");
                    if (node.tag() != null) {
                        message.append("with tag ").append(node.tag()).append(" ");
                    }
                    message.append("failed requirement to be present in graph:\n");
                    for (var candidate : composite.candidates) {
                        var object = objects.get(candidate.index);
                        if (object instanceof NodeCondition.ConditionResult.Failed(var description)) {
                            message.append("  - ").append(candidate.index).append(" ").append(candidate.type).append("\n");
                            for (var s : description) {
                                message.append("    * ").append(s).append("\n");
                            }
                        }
                    }
                    throw new IllegalStateException(message.toString());
                }
                var message = new StringBuilder("Multiple conditional components present in graph for type ").append(node.type());
                if (node.tag() != null) {
                    message.append(" ").append("with tag ").append(node.tag());
                }
                message.append("\n");
                for (var candidate : composite.candidates) {
                    var object = objects.get(candidate.index);
                    if (object instanceof NodeCondition.ConditionResult.Failed(var description)) {
                        continue;
                    }
                    assert candidate.condition != null;
                    var condition = getImpl(draw, objects, candidate.condition);
                    message.append("  - ").append(condition).append("\n");
                }
                throw new IllegalStateException(message.toString());
            }
            case NodeImpl<T> casted -> {
                if (casted.graphDraw != draw) {
                    throw new IllegalArgumentException("Node is from another graph");
                }
                var value = objects.get(casted.index);
                if (value == null) {
                    throw new IllegalStateException("Value was not initialized");
                }
                return (T) value;
            }
        }
    }

    @Override
    public <T> ValueOf<T> valueOf(final Node<? extends T> node) {
        var casted = (NodeImpl<? extends T>) node;
        if (casted.graphDraw != this.draw) {
            throw new IllegalArgumentException("Node is from another graph");
        }
        return new ValueOf<>() {
            @Override
            public T get() {
                return GraphImpl.this.get(node);
            }

            @Override
            public void refresh() {
                GraphImpl.this.refresh(casted);
            }
        };
    }

    @Override
    public <T> PromiseOf<T> promiseOf(final Node<T> node) {
        var casted = (NodeImpl<T>) node;
        if (casted.index >= 0 && casted.graphDraw != this.draw) {
            throw new IllegalArgumentException("Node is from another graph");
        }
        return new PromiseOfImpl<>(this, casted);
    }

    @Override
    public void refresh(Node<?> fromNodeRaw) {
        var fromNode = (NodeImpl<?>) fromNodeRaw;
        var root = new BitSet(this.objects.length());
        root.set(fromNode.index);
        this.initLock.lock();

        log.debug("Dependency container refreshing from node {} of class {}...", fromNode.index, this.objects.get(fromNode.index).getClass());
        final long started = log.isDebugEnabled() ? started() : 0;
        try {
            this.initializeSubgraph(fromNode.index);
            if (log.isDebugEnabled()) {
                log.debug("Dependency container refreshed in {}", tookForLogging(started));
            }
        } catch (Throwable e) {
            log.debug("Dependency container refresh error", e);
            throw e;
        } finally {
            this.initLock.unlock();
        }
    }

    @Override
    public void init() {
        var root = new BitSet(this.objects.length());
        root.set(0, this.objects.length());
        this.initLock.lock();

        log.debug("Dependency container initializing...");
        final long started = started();
        try {
            this.initializeSubgraph(0);
            log.debug("Dependency container initialized in {}", tookForLogging(started));
        } catch (Exception e) {
            log.debug("Dependency container initialization failed", e);
            throw e;
        } finally {
            this.initLock.unlock();
        }
    }

    @Override
    public void release() {
        var root = new BitSet(this.objects.length());
        root.set(0, this.objects.length());
        this.initLock.lock();
        log.debug("Dependency container releasing...");
        final long started = started();
        try {
            this.releaseNodes(this.objects, root);
            log.debug("Dependency container released in {}", tookForLogging(started));
        } catch (Exception e) {
            log.debug("Dependency container releasing failed", e);
            throw e;
        } finally {
            this.initLock.unlock();
        }
    }

    private void initializeSubgraph(int startFrom) {
        log.trace("Materializing graph objects {}", startFrom);
        var tmpGraph = new TmpGraph(this);
        var errors = tmpGraph.init(startFrom);
        if (!errors.isEmpty()) {
            try {
                this.releaseNodes(tmpGraph.tmpArray, tmpGraph.initialized);
            } catch (Throwable e) {
                this.log.warn("Error on releasing temporary objects after init error", e);
            }
            var re = new RuntimeException("Failed to initialize graph");
            for (var error : errors) {
                if (error != re) {
                    re.addSuppressed(error);
                }
            }
            throw re;
        }
        var oldObjects = this.objects;
        this.objects = tmpGraph.tmpArray;
        for (var newValue : tmpGraph.newValueOf) {
            newValue.tmpGraph = GraphImpl.this;
        }
        for (var newPromise : tmpGraph.newPromises) {
            newPromise.graph = GraphImpl.this;
        }
        try {
            this.releaseNodes(oldObjects, tmpGraph.initialized);
        } catch (Throwable e) {
            this.log.warn("Error on releasing temporary objects after init error", e);
        }
        log.trace("Dependency container refreshed, calling interceptors...");
        for (var refreshListenerNode : this.refreshListenerNodes) {
            if (this.objects.get(refreshListenerNode) instanceof RefreshListener refreshListener) {
                try {
                    refreshListener.graphRefreshed();
                } catch (Exception e) {
                    log.warn("Exception caught when calling listener.graphRefreshed(), object={}", refreshListener);
                }
            }
        }
        log.trace("Dependency container refreshed, ");
    }

    private void releaseNodes(AtomicReferenceArray<Object> objects, BitSet root) {
        var release = new CompletableFuture<?>[objects.length()];
        var locks = new ArrayList<ReadWriteLock>(objects.length());
        for (int i = 0; i < this.draw.getNodes().size(); i++) {
            var lock = new ReentrantReadWriteLock();
            locks.add(lock);
        }
        var barrier = new CyclicBarrier(root.cardinality());
        for (int i = objects.length() - 1; i >= 0; i--) {
            if (!root.get(i)) {
                release[i] = EMPTY_FUTURE;
                continue;
            }
            var node = (NodeImpl<?>) this.draw.getNodes().get(i);
            var future = new CompletableFuture<@Nullable Void>();
            var lock = locks.get(i);
            Thread.ofVirtual().name("release-" + i).start(() -> {
                for (var dependencyNode : node.createDependencies) {
                    switch (dependencyNode) {
                        case CompositeConditionalNode<?> v -> {
                            for (var candidate : v.candidates) {
                                System.out.println("lock " + candidate.index + " thread " + Thread.currentThread().getName());
                                locks.get(candidate.index).readLock().lock();
                            }
                        }
                        case NodeImpl<?> v -> {
                            System.out.println("lock " + v.index + " thread " + Thread.currentThread().getName());
                            locks.get(v.index).readLock().lock();
                        }
                    }
                }
                for (var interceptorNode : node.interceptors) {
                    switch (interceptorNode) {
                        case CompositeConditionalNode<?> v -> {
                            for (var candidate : v.candidates) {
                                System.out.println("lock " + candidate.index + " thread " + Thread.currentThread().getName());
                                locks.get(candidate.index).readLock().lock();
                            }
                        }
                        case NodeImpl<?> v -> {
                            System.out.println("lock " + v.index + " thread " + Thread.currentThread().getName());
                            locks.get(v.index).readLock().lock();
                        }
                    }
                }
                try {
                    barrier.await();
                } catch (InterruptedException | BrokenBarrierException e) {
                    future.completeExceptionally(e);// todo ?
                    return;
                }


                lock.writeLock().lock();
                try {
                    this.release(objects, node);
                    future.complete(null);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                } finally {
                    lock.writeLock().unlock();
                    for (var dependencyNode : node.createDependencies) {
                        switch (dependencyNode) {
                            case CompositeConditionalNode<?> v -> {
                                for (var candidate : v.candidates) {
                                    System.out.println("unlock " + candidate.index + " thread " + Thread.currentThread().getName());
                                    locks.get(candidate.index).readLock().unlock();
                                }
                            }
                            case NodeImpl<?> v -> {
                                System.out.println("unlock " + v.index + " thread " + Thread.currentThread().getName());
                                locks.get(v.index).readLock().unlock();
                            }
                        }
                    }
                    for (var interceptorNode : node.interceptors) {
                        switch (interceptorNode) {
                            case CompositeConditionalNode<?> v -> {
                                for (var candidate : v.candidates) {
                                    System.out.println("unlock " + candidate.index + " thread " + Thread.currentThread().getName());
                                    locks.get(candidate.index).readLock().unlock();
                                }
                            }
                            case NodeImpl<?> v -> {
                                System.out.println("unlock " + v.index + " thread " + Thread.currentThread().getName());
                                locks.get(v.index).readLock().unlock();
                            }
                        }
                    }
                }
            });
            release[i] = future;
        }
        // todo await
        CompletableFuture.allOf(release).join();
    }

    private <T> void release(AtomicReferenceArray<@Nullable Object> objects, NodeImpl<T> node) throws Throwable {
        @SuppressWarnings("unchecked")
        var object = (T) objects.get(node.index);
        if (object == null) {
            return;
        }
        var i = node.interceptors.listIterator(node.interceptors.size());
        var error = (Throwable) null;
        while (i.hasPrevious()) {
            var interceptorNode = (NodeImpl<? extends GraphInterceptor<T>>) i.previous();
            @SuppressWarnings("unchecked")
            var interceptor = (GraphInterceptor<T>) objects.get(interceptorNode.index);
            this.log.trace("Intercepting release node {} of class {} with node {} of class {}", node.index, object.getClass(), interceptorNode.index, interceptor.getClass());
            try {
                var intercepted = interceptor.release(object);
                log.trace("Intercepting release node {} of class {} with node {} of class {} complete", node.index, object.getClass(), interceptorNode.index, interceptor.getClass());
                object = intercepted;
            } catch (Throwable e) {
                this.log.trace("Intercepting release node {} of class {} with node {} of class {} error", node.index, object.getClass(), interceptorNode.index, interceptor.getClass(), e);
                if (error == null) {
                    error = e;
                } else {
                    error.addSuppressed(e);
                }
            }
        }
        if (object instanceof Lifecycle lifecycle) {
            try {
                lifecycle.release();
            } catch (Throwable e) {
                if (error == null) {
                    error = e;
                } else {
                    error.addSuppressed(e);
                }
            }
            log.trace("Node {} of class {} released", node.index, object.getClass());
        }
        if (object instanceof AutoCloseable closeable) {
            log.trace("Releasing node {} of class {}", node.index, object.getClass());
            try {
                closeable.close();
            } catch (Throwable e) {
                if (error == null) {
                    error = e;
                } else {
                    error.addSuppressed(e);
                }
            }
            log.trace("Node {} of class {} released", node.index, object.getClass());
        }
        if (error != null) {
            throw error;
        }
    }

    private static class TmpGraph implements Graph {
        private final GraphImpl rootGraph;
        private final AtomicReferenceArray<Object> tmpArray;
        private final Collection<TmpValueOf<?>> newValueOf = new ConcurrentLinkedDeque<>();
        private final Collection<PromiseOfImpl<?>> newPromises = new ConcurrentLinkedDeque<>();
        private final AtomicReferenceArray<@Nullable CompletableFuture<Void>> inits;
        private final BitSet initialized;
        private final Executor executor;
        private final boolean debugEnabled;

        private TmpGraph(GraphImpl rootGraph) {
            this.rootGraph = rootGraph;
            this.tmpArray = new AtomicReferenceArray<>(this.rootGraph.objects.length());
            for (int i = 0; i < this.rootGraph.objects.length(); i++) {
                this.tmpArray.set(i, this.rootGraph.objects.get(i));
            }
            this.inits = new AtomicReferenceArray<>(this.tmpArray.length());
            this.initialized = new BitSet(this.tmpArray.length());
            this.executor = rootGraph.executor;
            this.debugEnabled = this.rootGraph.log.isDebugEnabled();
        }

        @Override
        public ApplicationGraphDraw draw() {
            return this.rootGraph.draw();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Node<T> node) {
            return getImpl(this.rootGraph.draw, this.tmpArray, node);
        }

        @Override
        public <T> ValueOf<T> valueOf(Node<? extends T> node) {
            var casted = (NodeImpl<? extends T>) node;
            // dirty hack to make copied graph work with valueOf
            @SuppressWarnings("unchecked")
            var fixed = (NodeImpl<? extends T>) this.rootGraph.draw.getNodes().get(casted.index);
            var value = new TmpValueOf<T>(fixed, this, this.rootGraph);
            this.newValueOf.add(value);
            return value;
        }

        @Override
        public <T> PromiseOf<T> promiseOf(Node<T> node) {
            var casted = (NodeImpl<T>) node;
            // dirty hack to make copied graph work with valueOf
            @SuppressWarnings("unchecked")
            var fixed = (NodeImpl<T>) this.rootGraph.draw.getNodes().get(casted.index);
            var promise = new PromiseOfImpl<T>(null, fixed);
            this.newPromises.add(promise);
            return promise;
        }

        private <T> void createNode(int startFrom, NodeImpl<T> node) {
            @SuppressWarnings("unchecked")
            var oldObject = (T) this.rootGraph.objects.get(node.index);
            var create = (Callable<@Nullable Void>) () -> {
                var conditionFailed = new HashSet<String>();
                for (var dependencyNode : node.createDependencies) {
                    switch (dependencyNode) {
                        case CompositeConditionalNode<?> v -> {
                            for (var candidate : v.candidates) {
                                try {
                                    var init = this.inits.get(candidate.index);
                                    if (init != null) {
                                        init.get();
                                    }
                                } catch (ExecutionException _) {
                                    throw new DependencyInitializationFailedException();
                                }
                            }
                        }
                        case NodeImpl<?> v -> {
                            try {
                                var init = this.inits.get(v.index);
                                if (init != null) {
                                    init.get();
                                }
                            } catch (ExecutionException _) {
                                throw new DependencyInitializationFailedException();
                            }
                            var dependencyObject = this.tmpArray.get(v.index);
                            if (dependencyObject instanceof NodeCondition.ConditionResult.Failed(var description)) {
                                conditionFailed.addAll(description);
                            }
                        }
                    }
                }
                for (var interceptorNode : node.interceptors) {
                    switch (interceptorNode) {
                        case CompositeConditionalNode<?> _ -> throw new IllegalStateException();
                        case NodeImpl<?> v -> {
                            var init = this.inits.get(v.index);
                            if (init != null) {
                                init.get();
                            }
                            var dependencyObject = this.tmpArray.get(v.index);
                            if (dependencyObject instanceof NodeCondition.ConditionResult.Failed(var description)) {
                                conditionFailed.addAll(description);
                            }
                        }
                    }
                }
                if (node.condition != null) {
                    var init = this.inits.get(((NodeImpl<?>) node.condition).index);
                    if (init != null) {
                        init.get();
                    }
                }
                if (oldObject != null && !node.createDependencies.isEmpty() && node.index != startFrom) {
                    var dependencyChanged = false;
                    for (var dependency : node.refreshDependencies) {
                        if (rootGraph.get(dependency) != get(dependency)) { // ref equals is intended
                            dependencyChanged = true;
                        }
                    }
                    for (var dependency : node.interceptors) {
                        if (rootGraph.get(dependency) != get(dependency)) { // ref equals is intended
                            dependencyChanged = true;
                        }
                    }
                    if (node.condition != null) {
                        if (rootGraph.get(node.condition) != get(node.condition)) { // ref equals is intended
                            dependencyChanged = true;
                        }
                    }
                    if (!dependencyChanged) {
                        return null;
                    }
                }
                if (this.rootGraph.log.isTraceEnabled()) {
                    var dependenciesStr = node.createDependencies.stream().map(Node::toString).collect(Collectors.joining(",", "[", "]"));
                    this.rootGraph.log.trace("Creating node {}, dependencies {}", node.index, dependenciesStr);
                }

                Object newObject;
                if (!conditionFailed.isEmpty()) {
                    newObject = NodeCondition.ConditionResult.failed(conditionFailed);
                } else if (node.condition != null && this.get(node.condition).eval() instanceof NodeCondition.ConditionResult.Failed failed) {
                    this.rootGraph.log.trace("Node {} is not created because dependency condition failed", node.index);
                    newObject = failed;
                } else {
                    newObject = Objects.requireNonNull(node.factory.get(this));
                }
                if (Objects.equals(newObject, oldObject)) {
                    return null;
                }
                if (newObject instanceof NodeCondition.ConditionResult.Failed) {
                    this.tmpArray.set(node.index, newObject);
                    return null;
                }
                synchronized (TmpGraph.this) {
                    this.initialized.set(node.index);
                }
                this.tmpArray.set(node.index, newObject);
                if (newObject instanceof RefreshListener) {
                    synchronized (this.rootGraph.refreshListenerNodes) {
                        this.rootGraph.refreshListenerNodes.add(node.index);
                    }
                }
                this.rootGraph.log.trace("Created node {} {}", node.index, newObject.getClass());
                if (newObject instanceof Lifecycle lifecycle) {
                    this.initializeNode(node, lifecycle);
                }
                for (var interceptorNode : node.interceptors) {
                    var interceptor = (NodeImpl<? extends GraphInterceptor<T>>) interceptorNode;
                    var interceptorObject = (GraphInterceptor<T>) this.get(interceptor);
                    // todo handle somehow errors on that stage
                    this.rootGraph.log.trace("Intercepting init node {} of class {} with node {} of class {}", node.index, newObject.getClass(), interceptor.index, interceptorObject.getClass());
                    try {
                        var intercepted = interceptorObject.init((T) newObject);
                        this.rootGraph.log.trace("Intercepting init node {} of class {} with node {} of class {} complete", node.index, newObject.getClass(), interceptor.index, interceptorObject.getClass());
                        newObject = intercepted;
                    } catch (RuntimeException | Error e) {
                        this.rootGraph.log.trace("Intercepting init node {} of class {} with node {} of class {} error", node.index, newObject.getClass(), interceptor.index, interceptorObject.getClass(), e);
                        throw e;
                    } catch (Throwable e) {
                        this.rootGraph.log.trace("Intercepting init node {} of class {} with node {} of class {} error", node.index, newObject.getClass(), interceptor.index, interceptorObject.getClass(), e);
                        throw new IllegalStateException(e);
                    }
                }
                this.tmpArray.set(node.index, newObject);
                return null;
            };
            var future = new CompletableFuture<@Nullable Void>();
            this.executor.execute(() -> {
                var startTime = this.debugEnabled ? System.nanoTime() : 0L;
                try {
                    create.call();
                    if (this.debugEnabled) {
                        var took = System.nanoTime() - startTime;
                        if (took > SLOW_NODE_INIT_THRESHOLD * 1_000_000) {
                            this.rootGraph.log.debug("Initialized node {} at index {} in {}ms", node.type(), node.index, took / 1_000_000);
                        }
                    }
                    future.complete(null);
                } catch (Throwable t) {
                    if (this.debugEnabled) {
                        var took = System.nanoTime() - startTime;
                        if (took > SLOW_NODE_INIT_THRESHOLD * 1_000_000) {
                            this.rootGraph.log.debug("Initialized node {} at index {} in {}ms", node.type(), node.index, took / 1_000_000);
                        }
                    }
                    future.completeExceptionally(t);
                }
            });
            this.inits.set(node.index, future);
        }

        private static class DependencyInitializationFailedException extends RuntimeException {
            @Override
            public Throwable fillInStackTrace() {
                return this;
            }
        }

        private void initializeNode(NodeImpl<?> node, Lifecycle lifecycle) {
            var index = node.index;
            this.rootGraph.log.trace("Initializing node {} of class {} cancelled", index, lifecycle.getClass());
            try {
                lifecycle.init();
                this.rootGraph.log.trace("Node Initializing {} of class {} complete", index, lifecycle.getClass());
            } catch (CancellationException e) {
                this.rootGraph.log.trace("Node Initializing {} of class {} cancelled", index, lifecycle.getClass());
                throw e;
            } catch (CompletionException ce) {
                this.rootGraph.log.trace("Node Initializing {} of class {} error", index, lifecycle.getClass(), ce.getCause());
                throw ce;
            } catch (RuntimeException | Error e) {
                this.rootGraph.log.trace("Node Initializing {} of class {} error", index, lifecycle.getClass(), e);
                throw e;
            } catch (Throwable e) {
                this.rootGraph.log.trace("Initializing node {} of class {} error", index, lifecycle.getClass(), e);
                throw new IllegalStateException(e);
            }
        }

        private List<Throwable> init(int startFrom) {
            var nodes = this.rootGraph.draw.getNodes();
            for (int i = startFrom; i < nodes.size(); i++) {
                var node = (NodeImpl<?>) nodes.get(i);
                this.createNode(startFrom, node);
            }
            var errors = new ArrayList<Throwable>();
            for (var i = startFrom; i < GraphImpl.TmpGraph.this.inits.length(); i++) {
                var init = GraphImpl.TmpGraph.this.inits.get(i);
                try {
                    init.get();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof DependencyInitializationFailedException || e.getCause().getCause() instanceof DependencyInitializationFailedException) {
                        continue;
                    }
                    errors.add(Objects.requireNonNull(e.getCause()));
                }
            }
            return errors;
        }
    }


    private static class TmpValueOf<T> implements ValueOf<T> {
        public volatile Graph tmpGraph;
        private final GraphImpl rootGraph;
        private final NodeImpl<? extends T> node;

        private TmpValueOf(NodeImpl<? extends T> node, Graph tmpGraph, GraphImpl rootGraph) {
            this.node = node;
            this.tmpGraph = tmpGraph;
            this.rootGraph = rootGraph;
        }

        @Override
        public T get() {
            return this.tmpGraph.get(this.node);
        }

        @Override
        public void refresh() {
            this.rootGraph.refresh(this.node);
        }
    }

    private static long started() {
        return System.nanoTime();
    }

    private static String tookForLogging(long started) {
        return Duration.ofNanos(System.nanoTime() - started).truncatedTo(ChronoUnit.MILLIS).toString().substring(2).toLowerCase();
    }
}
