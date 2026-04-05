package com.bankvaluehistory.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class WikiPriceService
{
    private static final Logger log = LoggerFactory.getLogger(WikiPriceService.class);
    private static final URI LATEST_URI = URI.create("https://prices.runescape.wiki/api/v1/osrs/latest");
    private static final URI MAPPING_URI = URI.create("https://prices.runescape.wiki/api/v1/osrs/mapping");
    private static final String TIMESERIES_BASE = "https://prices.runescape.wiki/api/v1/osrs/timeseries";
    private static final Pattern ITEM_PATTERN = Pattern.compile("\"(\\d+)\"\\s*:\\s*\\{\\s*\"high\"\\s*:\\s*(null|\\d+)\\s*,\\s*\"highTime\"\\s*:\\s*(null|\\d+)\\s*,\\s*\"low\"\\s*:\\s*(null|\\d+)\\s*,\\s*\"lowTime\"\\s*:\\s*(null|\\d+)\\s*\\}");
    private static final String USER_AGENT = "bank-value-tracker/0.7 (local RuneLite plugin dashboard)";

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
    private final ConcurrentHashMap<Integer, PricePoint> latestPrices = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedJson> timeseriesCache = new ConcurrentHashMap<>();
    private volatile CachedJson mappingCache;

    private ScheduledExecutorService executor;
    private volatile Instant lastRefresh = Instant.EPOCH;

    public synchronized void start()
    {
        if (executor != null)
        {
            return;
        }

        executor = Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread thread = new Thread(r, "bank-value-tracker-wiki-prices");
            thread.setDaemon(true);
            return thread;
        });
        executor.execute(this::refreshNow);
        executor.scheduleAtFixedRate(this::refreshNow, 5, 5, TimeUnit.MINUTES);
    }

    public synchronized void stop()
    {
        if (executor != null)
        {
            executor.shutdownNow();
            executor = null;
        }
    }

    public void refreshAsyncIfStale(Duration maxAge)
    {
        if (Duration.between(lastRefresh, Instant.now()).compareTo(maxAge) > 0 && executor != null)
        {
            executor.execute(this::refreshNow);
        }
    }

    public Instant getLastRefresh()
    {
        return lastRefresh;
    }

    public String buildSubsetJson(Collection<Integer> ids)
    {
        Set<Integer> uniqueIds = ids == null ? Collections.emptySet() : Set.copyOf(ids);
        StringBuilder sb = new StringBuilder(256 + uniqueIds.size() * 72);
        sb.append('{');
        appendJsonField(sb, "fetchedAt", lastRefresh.equals(Instant.EPOCH) ? null : lastRefresh.toString()).append(',');
        appendJsonField(sb, "source", LATEST_URI.toString()).append(',');
        sb.append("\"data\":{");
        boolean first = true;
        for (Integer id : uniqueIds)
        {
            if (id == null)
            {
                continue;
            }
            PricePoint point = latestPrices.get(id);
            if (point == null)
            {
                continue;
            }
            if (!first)
            {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(id.intValue()).append('"').append(':').append('{');
            appendJsonField(sb, "high", point.high).append(',');
            appendJsonField(sb, "highTime", point.highTime).append(',');
            appendJsonField(sb, "low", point.low).append(',');
            appendJsonField(sb, "lowTime", point.lowTime).append(',');
            appendJsonField(sb, "mid", point.mid());
            sb.append('}');
        }
        sb.append("}}");
        return sb.toString();
    }

    public String fetchTimeseriesJson(int itemId, String timestep)
    {
        String normalized = normalizeTimestep(timestep);
        String key = itemId + ":" + normalized;
        CachedJson cached = timeseriesCache.get(key);
        if (cached != null && Duration.between(cached.fetchedAt, Instant.now()).toMinutes() < 30)
        {
            return cached.body;
        }

        try
        {
            String uri = TIMESERIES_BASE
                + "?id=" + itemId
                + "&timestep=" + URLEncoder.encode(normalized, StandardCharsets.UTF_8);
            HttpResponse<String> response = httpClient.send(buildJsonRequest(URI.create(uri), Duration.ofSeconds(20)), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2 && response.body() != null && !response.body().isEmpty())
            {
                String body = response.body();
                timeseriesCache.put(key, new CachedJson(body, Instant.now()));
                return body;
            }
        }
        catch (IOException | InterruptedException ex)
        {
            log.debug("Unable to fetch wiki timeseries for {} {}", itemId, normalized, ex);
            if (ex instanceof InterruptedException)
            {
                Thread.currentThread().interrupt();
            }
        }

        return "{\"data\":[]}";
    }

    private String normalizeTimestep(String timestep)
    {
        if (timestep == null)
        {
            return "24h";
        }
        switch (timestep)
        {
            case "5m":
            case "1h":
            case "6h":
            case "24h":
                return timestep;
            default:
                return "24h";
        }
    }


    public String fetchMappingJson()
    {
        CachedJson cached = mappingCache;
        if (cached != null && Duration.between(cached.fetchedAt, Instant.now()).toHours() < 12)
        {
            return cached.body;
        }

        try
        {
            HttpResponse<String> response = httpClient.send(buildJsonRequest(MAPPING_URI, Duration.ofSeconds(20)), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2 && response.body() != null && !response.body().isEmpty())
            {
                mappingCache = new CachedJson(response.body(), Instant.now());
                return response.body();
            }
        }
        catch (IOException | InterruptedException ex)
        {
            log.debug("Unable to fetch wiki mapping", ex);
            if (ex instanceof InterruptedException)
            {
                Thread.currentThread().interrupt();
            }
        }

        return cached != null ? cached.body : "[]";
    }

    private HttpRequest buildJsonRequest(URI uri, Duration timeout)
    {
        return HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("User-Agent", USER_AGENT)
            .header("x-user-agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
            .build();
    }

    private void refreshNow()
    {
        if (!refreshInFlight.compareAndSet(false, true))
        {
            return;
        }

        try
        {
            HttpResponse<String> response = httpClient.send(buildJsonRequest(LATEST_URI, Duration.ofSeconds(20)), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2)
            {
                log.debug("Wiki price refresh returned HTTP {}", response.statusCode());
                return;
            }

            Map<Integer, PricePoint> parsed = parseLatest(response.body());
            if (!parsed.isEmpty())
            {
                latestPrices.clear();
                latestPrices.putAll(parsed);
                lastRefresh = Instant.now();
            }
        }
        catch (IOException | InterruptedException ex)
        {
            log.debug("Unable to refresh live wiki prices", ex);
            if (ex instanceof InterruptedException)
            {
                Thread.currentThread().interrupt();
            }
        }
        finally
        {
            refreshInFlight.set(false);
        }
    }

    private Map<Integer, PricePoint> parseLatest(String body)
    {
        if (body == null || body.isEmpty())
        {
            return Collections.emptyMap();
        }

        Map<Integer, PricePoint> map = new HashMap<>();
        Matcher matcher = ITEM_PATTERN.matcher(body);
        while (matcher.find())
        {
            int id = Integer.parseInt(matcher.group(1));
            int high = parseNullableInt(matcher.group(2));
            long highTime = parseNullableLong(matcher.group(3));
            int low = parseNullableInt(matcher.group(4));
            long lowTime = parseNullableLong(matcher.group(5));
            map.put(id, new PricePoint(high, highTime, low, lowTime));
        }
        return map;
    }

    private int parseNullableInt(String value)
    {
        return value == null || "null".equals(value) ? 0 : Integer.parseInt(value);
    }

    private long parseNullableLong(String value)
    {
        return value == null || "null".equals(value) ? 0L : Long.parseLong(value);
    }

    private static StringBuilder appendJsonField(StringBuilder sb, String name, String value)
    {
        sb.append('"').append(escape(name)).append('"').append(':');
        if (value == null)
        {
            sb.append("null");
        }
        else
        {
            sb.append('"').append(escape(value)).append('"');
        }
        return sb;
    }

    private static StringBuilder appendJsonField(StringBuilder sb, String name, long value)
    {
        sb.append('"').append(escape(name)).append('"').append(':').append(value);
        return sb;
    }

    private static String escape(String value)
    {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static class PricePoint
    {
        private final int high;
        private final long highTime;
        private final int low;
        private final long lowTime;

        private PricePoint(int high, long highTime, int low, long lowTime)
        {
            this.high = high;
            this.highTime = highTime;
            this.low = low;
            this.lowTime = lowTime;
        }

        private long mid()
        {
            if (high > 0 && low > 0)
            {
                return (high + (long) low) / 2L;
            }
            return high > 0 ? high : low;
        }
    }

    private static class CachedJson
    {
        private final String body;
        private final Instant fetchedAt;

        private CachedJson(String body, Instant fetchedAt)
        {
            this.body = body;
            this.fetchedAt = fetchedAt;
        }
    }
}
