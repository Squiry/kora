package ru.tinkoff.kora.application.graph.internal;

import ru.tinkoff.kora.application.graph.All;

import java.util.AbstractCollection;
import java.util.Iterator;
import java.util.List;

public final class AllImpl<T> extends AbstractCollection<T> implements All<T> {
    private final List<T> values;

    public AllImpl(List<T> values) {
        this.values = values;
    }

    @Override
    public Iterator<T> iterator() {
        return values.iterator();
    }

    @Override
    public int size() {
        return values.size();
    }
}
