package io.quarkus.micrometer.runtime.meters;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

public class GaugeBuilder<T> {

    private final GaugeSupplier<T> supplier;
    private final Gauge.Builder<Supplier<Number>> builder;
    private final ConcurrentMap<Meter.Id, T> map;
    private final Supplier<T> factory;
    private final String name;
    private Tags tags = Tags.empty();
    private final ConcurrentMap<Gauges.RegistrationKey, Gauge> registrations;

    GaugeBuilder(String name, ConcurrentMap<Meter.Id, T> map,
            Supplier<T> factory, ToDoubleFunction<T> func) {
        this(name, map, factory, func, new ConcurrentHashMap<>());
    }

    GaugeBuilder(String name, ConcurrentMap<Meter.Id, T> map,
            Supplier<T> factory, ToDoubleFunction<T> func,
            ConcurrentMap<Gauges.RegistrationKey, Gauge> registrations) {
        this.name = name;
        this.registrations = registrations;
        this.supplier = new GaugeSupplier<>(map, func);
        this.builder = Gauge.builder(name, supplier);
        this.map = map;
        this.factory = factory;
    }

    public GaugeBuilder<T> description(String description) {
        builder.description(description);
        return this;
    }

    public GaugeBuilder<T> tags(Iterable<Tag> tags) {
        this.tags = this.tags.and(tags);
        builder.tags(tags);
        return this;
    }

    public GaugeBuilder<T> tag(String key, String value) {
        this.tags = this.tags.and(key, value);
        builder.tag(key, value);
        return this;
    }

    private Gauge registerGauge(MeterRegistry registry) {
        var key = new Gauges.RegistrationKey(registry, new Meter.Id(name, tags, null, null, Meter.Type.GAUGE));
        return registrations.compute(key, (ignored, existing) -> {
            if (existing != null && registry.find(existing.getId().getName())
                    .tags(existing.getId().getTags()).gauges().contains(existing)) {
                return existing;
            }
            return builder.register(registry);
        });
    }

    public T register(MeterRegistry registry) {
        Meter.Id meterId = registerGauge(registry).getId();
        supplier.setId(meterId);
        return map.computeIfAbsent(meterId, id -> factory.get());
    }

    public T register(MeterRegistry registry, T externalValue) {
        Meter.Id meterId = registerGauge(registry).getId();
        supplier.setId(meterId);
        return map.computeIfAbsent(meterId, id -> externalValue);
    }
}
