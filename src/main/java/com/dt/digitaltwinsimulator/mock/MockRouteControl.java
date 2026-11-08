package com.dt.digitaltwinsimulator.mock;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Scenario facade. Validation uses an isolated store and never changes live routes/sequences. */
@Component
public class MockRouteControl {
    private final MockRouteStore store;
    private final MockProperties properties;
    private final MockTemplate template;
    private final JsonMapper mapper;

    MockRouteControl(MockRouteStore store, MockProperties properties, MockTemplate template, JsonMapper mapper) {
        this.store = store;
        this.properties = properties;
        this.template = template;
        this.mapper = mapper;
    }

    public MockRoute validate(String id, JsonNode definition) {
        try {
            MockRoute route = mapper.treeToValue(definition, MockRoute.class);
            if (route == null || !id.equals(route.id())) throw new IllegalArgumentException();
            return new MockRouteStore(properties, mapper, template).create(route);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("route must be a valid MockRoute with id matching routeId.");
        }
    }

    public void update(String id, MockRoute route) {
        synchronized (store) {
            try { store.get(id); }
            catch (MockNotFoundException exception) { store.create(route); return; }
            store.update(id, route);
        }
    }

    public void enabled(String id, boolean enabled) { store.enabled(id, enabled); }
}
