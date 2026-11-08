package com.dt.digitaltwinsimulator.broker;

/** Optional local broker defaults, shared by every consumer of resolve(). */
public interface BrokerDefaultsProvider {
    BrokerSettings defaults(BrokerSettings externalDefaults);
}
