package bank.internalgateway.gateway.resilience;

import bank.internalgateway.gateway.config.GatewayProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class EventDedupCache {

    private final int maximumSize;
    private final Duration defaultTtl;
    private final Map<String, Cache<String, Boolean>> caches = new ConcurrentHashMap<>();
    private final AtomicLong dedupHits = new AtomicLong();

    public EventDedupCache(GatewayProperties properties) {
        GatewayProperties.Dedup dedup = properties.dedup();
        this.maximumSize = dedup != null ? dedup.maximumSize() : 10_000;
        this.defaultTtl = dedup != null && dedup.defaultTtl() != null
                ? dedup.defaultTtl()
                : Duration.ofDays(7);
    }

    public boolean isDuplicate(String bindingId, Duration ttl, String dedupValue) {
        if (dedupValue == null || dedupValue.isBlank()) {
            return false;
        }
        Duration effectiveTtl = ttl != null ? ttl : defaultTtl;
        Cache<String, Boolean> cache = caches.computeIfAbsent(bindingId, ignored -> Caffeine.newBuilder()
                .expireAfterWrite(effectiveTtl)
                .maximumSize(maximumSize)
                .build());
        if (cache.asMap().putIfAbsent(dedupValue, Boolean.TRUE) != null) {
            dedupHits.incrementAndGet();
            return true;
        }
        return false;
    }

    public long dedupHits() {
        return dedupHits.get();
    }

    public Map<String, Long> cacheSizes() {
        Map<String, Long> sizes = new ConcurrentHashMap<>();
        caches.forEach((bindingId, cache) -> sizes.put(bindingId, cache.estimatedSize()));
        return sizes;
    }
}
