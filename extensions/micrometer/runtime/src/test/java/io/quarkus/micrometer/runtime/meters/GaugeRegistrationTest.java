package io.quarkus.micrometer.runtime.meters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class GaugeRegistrationTest {

    @Test
    void repeatedAndConcurrentRegistrationsDoNotLogDuplicates() throws Exception {
        List<String> duplicates = new ArrayList<>();
        Logger logger = Logger.getLogger(MeterRegistry.class.getName());
        Level previous = logger.getLevel();
        Handler handler = new Handler() {
            @Override
            public synchronized void publish(LogRecord record) {
                if (record.getMessage().contains("already registered")) {
                    duplicates.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.setLevel(Level.ALL);
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        var registry = new SimpleMeterRegistry();
        try (var executor = Executors.newFixedThreadPool(4)) {
            Gauges<LongAdder> gauges = Gauges.longAdder();
            gauges.builder("registration.concurrent", LongAdder::doubleValue)
                    .tag("server.port", "8080").tag("instance", "other").register(registry);
            List<Callable<LongAdder>> registrations = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                registrations.add(() -> gauges.builder("registration.concurrent", LongAdder::doubleValue)
                        .tag("server.port", "8080").register(registry));
            }
            var results = executor.invokeAll(registrations);
            LongAdder counter = results.get(0).get();
            for (var result : results) {
                assertSame(counter, result.get());
                result.get().increment();
            }
            assertEquals(16.0, registry.find("registration.concurrent").gauges().stream()
                    .filter(gauge -> gauge.getId().getTags().size() == 1).findFirst().orElseThrow().value());
            assertEquals(2, registry.getMeters().size());
            assertTrue(duplicates.isEmpty(), () -> "Duplicate gauge registrations: " + duplicates);
        } finally {
            registry.close();
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
    }

    @Test
    void registrationPreservesFiltersTagsExternalValuesAndRemoval() {
        Gauges<LongAdder> gauges = Gauges.longAdder();
        var registry = new SimpleMeterRegistry();
        var otherRegistry = new SimpleMeterRegistry();
        try {
            registry.config().commonTags("application", "test");
            registry.config().meterFilter(MeterFilter.replaceTagValues("server.port", value -> "mapped"));
            LongAdder external = new LongAdder();
            LongAdder first = gauges.builder("registration.filtered", LongAdder::doubleValue)
                    .tags(Tags.of("server.port", "8080")).register(registry, external);
            LongAdder second = gauges.builder("registration.filtered", LongAdder::doubleValue)
                    .tag("server.port", "8080").register(registry, new LongAdder());
            assertSame(external, first);
            assertSame(first, second);
            first.add(7);
            var gauge = registry.get("registration.filtered").tags("server.port", "mapped", "application", "test")
                    .gauge();
            assertEquals(7.0, gauge.value());
            registry.remove(gauge);
            assertSame(first, gauges.builder("registration.filtered", LongAdder::doubleValue)
                    .tag("server.port", "8080").register(registry));
            assertEquals(7.0, registry.get("registration.filtered").gauge().value());
            LongAdder other = gauges.builder("registration.filtered", LongAdder::doubleValue)
                    .tag("server.port", "8080").register(otherRegistry);
            other.add(3);
            assertEquals(3.0, otherRegistry.get("registration.filtered").gauge().value());
            assertEquals(7.0, registry.get("registration.filtered").gauge().value());
        } finally {
            registry.close();
            otherRegistry.close();
        }
    }
}
