package ru.tinkoff.kora.application.graph;

import java.util.Set;

public interface NodeCondition {
    ConditionResult eval();

    @Override
    String toString();

    sealed interface ConditionResult {
        Matches MATCHES = new Matches();

        static ConditionResult matches() {
            return MATCHES;
        }

        static ConditionResult failed(Set<String> description) {
            return new Failed(description);
        }

        static ConditionResult failed(String description) {
            return new Failed(Set.of(description));
        }

        record Matches() implements ConditionResult {}

        record Failed(Set<String> description) implements ConditionResult {}
    }
}
