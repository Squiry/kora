package ru.tinkoff.kora.application.graph.internal;

import ru.tinkoff.kora.application.graph.All;

import java.util.Iterator;
import java.util.List;

public final class AllImpl<T> implements All<T> {
    private final List<T> values;

    public AllImpl(List<T> values) {
        // todo filter values with failed conditions
        this.values = values;
    }

    @Override
    public Iterator<T> iterator() {
        return values.iterator();
    }
}
