package com.dt.digitaltwinsimulator.scenario;

import com.dt.digitaltwinsimulator.broker.BrokerSettings;
import com.dt.digitaltwinsimulator.mock.MockRoute;
import com.dt.digitaltwinsimulator.realtime.DtMessageFactory;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** Immutable source definitions. Repeats are never expanded into an event list. */
record ScenarioPlan(String scenarioId, String name, String description, double speed, boolean loop,
                    boolean waitOnDisconnect, BrokerSettings broker, List<Series> sources,
                    long eventCount, int trackCount, long durationMs, List<String> topics) {
    record Publish(String topic, DtMessageFactory.Spec spec, JsonNode payload, boolean literal) { }
    record Event(long at, String type, Publish publish, String routeId, MockRoute route, String label) {
        Event at(long time) { return new Event(time, type, publish, routeId, route, label); }
    }
    interface Series {
        long size();
        long time(long index);
        Event event(long index);
    }
    record Repeat(Event base, long size, long everyMs) implements Series {
        public long time(long index) { return base.at + index * everyMs; }
        public Event event(long index) { return base.at(time(index)); }
    }
    Cursor cursor(long seek) { return new Cursor(sources, seek); }

    /** One head per series; ties retain event definition order, then track and row order. */
    static final class Cursor {
        private record Head(Series series, int order, long index) { long time() { return series.time(index); } }
        private final PriorityQueue<Head> queue = new PriorityQueue<>(Comparator.comparingLong(Head::time)
                .thenComparingInt(Head::order).thenComparingLong(Head::index));
        Cursor(List<Series> sources, long seek) {
            for (int order = 0; order < sources.size(); order++) {
                Series source = sources.get(order);
                long low = 0, high = source.size();
                while (low < high) {
                    long middle = low + (high - low) / 2;
                    if (source.time(middle) < seek) low = middle + 1; else high = middle;
                }
                if (low < source.size()) queue.add(new Head(source, order, low));
            }
        }
        boolean empty() { return queue.isEmpty(); }
        long time() { return queue.element().time(); }
        Event event() { Head h = queue.element(); return h.series.event(h.index); }
        void advance() {
            Head h = queue.remove();
            if (h.index + 1 < h.series.size()) queue.add(new Head(h.series, h.order, h.index + 1));
        }
    }
}
