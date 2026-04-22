package hu.lpapi.artemis.metrics;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.AtomicReference;

class RegistryHolder {
    private static final AtomicReference<MeterRegistry> instance = new AtomicReference<>();

    private RegistryHolder() {}

    static void set(MeterRegistry registry) { instance.set(registry); }
    static MeterRegistry get() { return instance.get(); }
}
