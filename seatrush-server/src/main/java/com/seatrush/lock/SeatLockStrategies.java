package com.seatrush.lock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every seat lock strategy by name, plus the one that new holds currently use. It starts as
 * seatrush.lock.strategy and can be switched while the application runs.
 */
@Component
public class SeatLockStrategies {

    private static final Logger log = LoggerFactory.getLogger(SeatLockStrategies.class);

    private final Map<String, SeatLockStrategy> byName = new LinkedHashMap<>();
    private final AtomicReference<SeatLockStrategy> current = new AtomicReference<>();

    /** Spring passes every SeatLockStrategy bean, in the order of their @Order annotations. */
    public SeatLockStrategies(List<SeatLockStrategy> strategies, LockProperties properties) {
        for (SeatLockStrategy strategy : strategies) {
            if (byName.putIfAbsent(strategy.name(), strategy) != null) {
                throw new IllegalStateException("Two seat lock strategies are called " + strategy.name());
            }
        }
        current.set(get(properties.strategy()));
    }

    /**
     * The strategy for the next hold. A request reads it once and keeps it, so a switch in the middle
     * of a request cannot make it use two different strategies.
     */
    public SeatLockStrategy current() {
        return current.get();
    }

    /** Switches the strategy for every hold that starts from now on; holds already running keep theirs. */
    public SeatLockStrategy use(String name) {
        SeatLockStrategy next = get(name);
        SeatLockStrategy previous = current.getAndSet(next);
        if (previous != next) {
            log.info("Seat lock strategy switched from {} to {}", previous.name(), next.name());
        }
        return next;
    }

    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    private SeatLockStrategy get(String name) {
        SeatLockStrategy strategy = byName.get(name);
        if (strategy == null) {
            throw new UnknownLockStrategyException(name, names());
        }
        return strategy;
    }
}
