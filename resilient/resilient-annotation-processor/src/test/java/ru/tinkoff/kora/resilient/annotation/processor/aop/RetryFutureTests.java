package ru.tinkoff.kora.resilient.annotation.processor.aop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import ru.tinkoff.kora.resilient.annotation.processor.aop.testdata.*;

import java.util.concurrent.CompletionException;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetryFutureTests extends AppRunner {

    private RetryTarget getService() {
        final InitializedGraph graph = getGraph(AppWithConfig.class,
            CircuitBreakerTarget.class,
            RetryTarget.class,
            TimeoutTarget.class,
            FallbackTarget.class);

        return getServiceFromGraph(graph, RetryTarget.class);
    }

    private static final int RETRY_SUCCESS = 1;
    private static final int RETRY_FAIL = 5;

    private final RetryTarget retryableTarget = getService();

    @BeforeEach
    void setup() {
        retryableTarget.reset();
    }

    @Test
    void futureRetrySuccess() {
        // given
        var service = retryableTarget;

        // then
        service.setRetryAttempts(RETRY_SUCCESS);

        // then
        assertEquals("1", service.retryFuture("1").toCompletableFuture().join());
    }

    @Test
    void futureRetryFail() {
        // given
        var service = retryableTarget;

        // then
        service.setRetryAttempts(RETRY_FAIL);

        // then
        try {
            service.retryFuture("1").toCompletableFuture().join();
            fail("Should not happen");
        } catch (CompletionException e) {
            assertNotNull(e.getMessage());
            assertInstanceOf(IllegalStateException.class, e.getCause().getCause());
        }
    }
}
