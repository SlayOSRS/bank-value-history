package com.bankvaluehistory.service;

import com.bankvaluehistory.BankValueHistoryConfig;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class LocalDashboardServer
{
    private static final Logger log = LoggerFactory.getLogger(LocalDashboardServer.class);
    private static final int MAX_JSON_BODY_BYTES = 4096;
    private static final int MAX_QUERY_LENGTH = 8192;
    private static final long MAX_SERVED_FILE_BYTES = 25L * 1024L * 1024L;
    private static final long MIN_STATE_CHANGE_INTERVAL_MS = 250L;
    private static final Set<String> SAFE_IMAGE_EXTENSIONS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        ".png", ".jpg", ".jpeg", ".webp"
    )));
    private static final Set<String> SAFE_DATA_EXTENSIONS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        ".png", ".jpg", ".jpeg", ".webp", ".json", ".json.gz"
    )));

    private final BankValueHistoryConfig config;
    private final SnapshotStore snapshotStore;
    private final SnapshotService snapshotService;
    private final WikiPriceService wikiPriceService;

    private final String csrfToken = generateCsrfToken();
    private final AtomicLong lastStateChangingRequestMs = new AtomicLong(0L);

    private HttpServer server;
    private volatile String activeProfileKey = "";
    private volatile int boundPort = -1;

    @Inject
    public LocalDashboardServer(
        BankValueHistoryConfig config,
        SnapshotStore snapshotStore,
        SnapshotService snapshotService,
        WikiPriceService wikiPriceService)
    {
        this.config = config;
        this.snapshotStore = snapshotStore;
        this.snapshotService = snapshotService;
        this.wikiPriceService = wikiPriceService;
    }

    public synchronized void start() throws IOException
    {
        if (server != null)
        {
            return;
        }

        try
        {
            startOnPort(config.webPort());
        }
        catch (BindException ex)
        {
            log.warn("Configured dashboard port {} is already in use. Using a free local fallback port for this RuneLite client.", config.webPort());
            startOnPort(0);
        }

        log.info("Bank dashboard available at {}", getBaseUrl());
    }

    private void startOnPort(int port) throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", new ResourceHandler("web/index.html", "text/html; charset=utf-8"));
        server.createContext("/app.js", new ResourceHandler("web/app.js", "application/javascript; charset=utf-8"));
        server.createContext("/styles.css", new ResourceHandler("web/styles.css", "text/css; charset=utf-8"));
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/profiles", this::handleProfiles);
        server.createContext("/api/active-profile", this::handleActiveProfile);
        server.createContext("/api/latest", this::handleLatest);
        server.createContext("/api/snapshots", this::handleSnapshots);
        server.createContext("/api/snapshot", this::handleSnapshot);
        server.createContext("/api/open-data-dir", this::handleOpenDataDir);
        server.createContext("/api/wiki-prices", this::handleWikiPrices);
        server.createContext("/api/wiki-mapping", this::handleWikiMapping);
        server.createContext("/api/item-timeseries", this::handleItemTimeseries);
        server.createContext("/icons/", this::handleIcons);
        server.createContext("/data/", this::handleDataFiles);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        boundPort = server.getAddress().getPort();
    }

    public synchronized boolean ensureStarted()
    {
        if (server != null)
        {
            return true;
        }

        try
        {
            start();
            return true;
        }
        catch (IOException ex)
        {
            log.warn("Unable to start local bank dashboard on the configured port", ex);
            return false;
        }
    }

    public synchronized boolean restart()
    {
        stop();
        return ensureStarted();
    }

    public synchronized boolean isRunning()
    {
        return server != null;
    }

    public synchronized void stop()
    {
        if (server != null)
        {
            server.stop(0);
            server = null;
            boundPort = -1;
        }
    }

    public String getBaseUrl()
    {
        return "http://127.0.0.1:" + currentPort() + "/";
    }

    public void openBrowser()
    {
        openBrowser(snapshotService.getCurrentProfileKey());
    }

    public void openBrowser(String profileKey)
    {
        setActiveProfile(profileKey);
        if (!Desktop.isDesktopSupported())
        {
            return;
        }

        try
        {
            Desktop.getDesktop().browse(URI.create(getBaseUrl()));
        }
        catch (IOException ex)
        {
            log.warn("Unable to open dashboard browser", ex);
        }
    }

    private int currentPort()
    {
        return boundPort > 0 ? boundPort : config.webPort();
    }

    private void setActiveProfile(String profileKey)
    {
        activeProfileKey = snapshotStore.resolveProfileOrLatest(isSafeProfileKey(profileKey) ? profileKey : "");
    }

    private void handleStatus(HttpExchange exchange) throws IOException
    {
        if (!requireMethod(exchange, "GET"))
        {
            return;
        }
        writeJson(exchange, 200, addSecurityStatusFields(snapshotStore.statusJson(resolveProfile(exchange), currentPort())));
    }

    private void handleProfiles(HttpExchange exchange) throws IOException
    {
        if (!requireMethod(exchange, "GET"))
        {
            return;
        }
        writeJson(exchange, 200, buildProfilesJson());
    }

    private void handleActiveProfile(HttpExchange exchange) throws IOException
    {
        if (!requireMethod(exchange, "POST"))
        {
            return;
        }
        if (!requireLocalStateChangingRequest(exchange))
        {
            return;
        }
        if (!isJsonContentType(exchange))
        {
            writeJson(exchange, 415, "{\"error\":\"unsupported media type\"}");
            return;
        }

        String body = readSmallRequestBody(exchange, MAX_JSON_BODY_BYTES);
        if (body == null)
        {
            writeJson(exchange, 413, "{\"error\":\"request body too large\"}");
            return;
        }

        String profile = extractJsonString(body, "profile");
        if (profile == null || profile.trim().isEmpty())
        {
            writeJson(exchange, 400, "{\"error\":\"missing profile\"}");
            return;
        }

        setActiveProfile(profile);
        writeJson(exchange, 200, addSecurityStatusFields(snapshotStore.statusJson(resolveProfile(exchange), currentPort())));
    }

    private void handleLatest(HttpExchange exchange) throws IOException
    {
        if (!requireMethod(exchange, "GET"))
        {
            return;
        }
        Optional<String> json = readLatestSnapshotJson(resolveProfile(exchange)).filter(value -> !value.trim().isEmpty());
        writeJson(exchange, 200, json.orElse("{}"));
    }

    private void handleSnapshots(HttpExchange exchange) throws IOException
    {
        if (!requireMethod(exchange, "GET"))
        {
            return;
        }
        writeJson(exchange, 200, buildSnapshotsArrayJson(resolveProfile(exchange)));
    }

    private void handleSnapshot(HttpExchange exchange) throws IOException
    {
        String capturedAt = parseQueryParam(exchange.getRequestURI().getRawQuery(), "capturedAt");
        if (!isValidCapturedAt(capturedAt))
        {
            writeJson(exchange, 400, "{\"error\":\"invalid capturedAt\"}");
            return;
        }

        String profile = resolveProfile(exchange);
        if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod()))
        {
            if (!requireLocalStateChangingRequest(exchange))
            {
                return;
            }

            try
            {
                boolean deleted = snapshotStore.deleteSnapshot(profile, capturedAt);
                if (!deleted)
                {
                    writeJson(exchange, 404, "{\"error\":\"snapshot not found\"}");
                    return;
                }

                writeJson(exchange, 200, "{\"ok\":true}");
            }
            catch (IOException ex)
            {
                log.warn("Unable to delete snapshot {} for {}", capturedAt, profile, ex);
                writeJson(exchange, 500, "{\"error\":\"unable to delete snapshot\"}");
            }
            return;
        }

        if (!requireMethod(exchange, "GET"))
        {
            return;
        }

        Optional<String> snapshot = findSnapshotJson(profile, capturedAt);
        if (!snapshot.isPresent())
        {
            writeJson(exchange, 404, "{\"error\":\"snapshot not found\"}");
            return;
        }

        writeJson(exchange, 200, snapshot.get());
    }

    private void handleOpenDataDir(HttpExchange exchange) throws IOException
    {
        if (!requireMethod(exchange, "POST"))
        {
            return;
        }
        if (!requireLocalStateChangingRequest(exchange))
        {
            return;
        }

        if (!Desktop.isDesktopSupported())
        {
            writeJson(exchange, 500, "{\"error\":\"desktop integration not available\"}");
            return;
        }

        try
        {
            Desktop.getDesktop().open(snapshotStore.getBaseDir().toFile());
            writeJson(exchange, 200, "{\"ok\":true}");
        }
        catch (IOException ex)
        {
            log.warn("Unable to open data directory", ex);
            writeJson(exchange, 500, "{\"error\":\"unable to open data directory\"}");
        }
    }

    private void handleWikiPrices(HttpExchange exchange) throws IOException
    {
        if (!requireMethod(exchange, "GET"))
        {
            return;
        }
        wikiPriceService.refreshAsyncIfStale(Duration.ofMinutes(5));
        String idsParam = parseQueryParam(exchange.getRequestURI().getRawQuery(), "ids");
        Set<Integer> ids = parseIdSet(idsParam);
        writeJson(exchange, 200, wikiPriceService.buildSubsetJson(ids));
    }

    private void handleWikiMapping(HttpExchange exchange) throws IOException
    {
        if (!requireMethod(exchange, "GET"))
        {
            return;
        }
        writeJson(exchange, 200, wikiPriceService.fetchMappingJson());
    }

    private void handleItemTimeseries(HttpExchange exchange) throws IOException
    {
        if (!requireMethod(exchange, "GET"))
        {
            return;
        }

        String idParam = parseQueryParam(exchange.getRequestURI().getRawQuery(), "id");
        String timestep = parseQueryParam(exchange.getRequestURI().getRawQuery(), "timestep");
        if (!isAllowedTimestep(timestep))
        {
            writeJson(exchange, 400, "{\"error\":\"invalid timestep\"}");
            return;
        }
        if (idParam == null)
        {
            writeJson(exchange, 400, "{\"error\":\"missing id\"}");
            return;
        }

        int itemId;
        try
        {
            itemId = Integer.parseInt(idParam);
        }
        catch (NumberFormatException ex)
        {
            writeJson(exchange, 400, "{\"error\":\"invalid id\"}");
            return;
        }

        writeJson(exchange, 200, wikiPriceService.fetchTimeseriesJson(itemId, timestep));
    }

    private void handleIcons(HttpExchange exchange) throws IOException
    {
        if (!requireMethod(exchange, "GET"))
        {
            return;
        }

        String relative = exchange.getRequestURI().getPath().substring("/icons/".length());
        Path root = snapshotStore.getIconsDir().normalize();
        Path path = root.resolve(relative).normalize();
        if (!isSafeServedFile(root, path, SAFE_IMAGE_EXTENSIONS))
        {
            writeText(exchange, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }
        serveFile(exchange, path);
    }

    private void handleDataFiles(HttpExchange exchange) throws IOException
    {
        if (!requireMethod(exchange, "GET"))
        {
            return;
        }

        String relative = exchange.getRequestURI().getPath().substring("/data/".length());
        Path root = snapshotStore.getDataDir().normalize();
        Path path = root.resolve(relative).normalize();
        if (!isSafeServedFile(root, path, SAFE_DATA_EXTENSIONS))
        {
            writeText(exchange, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }
        serveFile(exchange, path);
    }

    private Set<Integer> parseIdSet(String idsParam)
    {
        Set<Integer> ids = new LinkedHashSet<>();
        if (idsParam == null || idsParam.trim().isEmpty())
        {
            return ids;
        }

        Arrays.stream(idsParam.split(","))
            .limit(500)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .forEach(value -> {
                try
                {
                    ids.add(Integer.parseInt(value));
                }
                catch (NumberFormatException ignored)
                {
                    // ignore malformed ids
                }
            });
        return ids;
    }

    private String resolveProfile(HttpExchange exchange)
    {
        if (activeProfileKey == null || activeProfileKey.trim().isEmpty())
        {
            setActiveProfile(snapshotService.getCurrentProfileKey());
        }
        return snapshotStore.resolveProfileOrLatest(activeProfileKey);
    }

    private static String generateCsrfToken()
    {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private String addSecurityStatusFields(String json)
    {
        String value = json == null || json.trim().isEmpty() ? "{}" : json.trim();
        int end = value.lastIndexOf('}');
        if (end < 0)
        {
            return "{\"csrfToken\":\"" + escapeJson(csrfToken) + "\"}";
        }

        String prefix = value.substring(0, end).trim();
        String suffix = prefix.endsWith("{") ? "" : ",";
        return value.substring(0, end) + suffix + "\"csrfToken\":\"" + escapeJson(csrfToken) + "\"}";
    }

    private boolean requireMethod(HttpExchange exchange, String method) throws IOException
    {
        if (!isLoopbackRequest(exchange))
        {
            writeJson(exchange, 403, "{\"error\":\"forbidden\"}");
            return false;
        }

        if (method.equalsIgnoreCase(exchange.getRequestMethod()))
        {
            return true;
        }

        exchange.getResponseHeaders().set("Allow", method);
        writeJson(exchange, 405, "{\"error\":\"method not allowed\"}");
        return false;
    }

    private boolean requireLocalStateChangingRequest(HttpExchange exchange) throws IOException
    {
        Headers headers = exchange.getRequestHeaders();
        String requestedWith = headers.getFirst("X-Requested-With");
        if (!"XMLHttpRequest".equals(requestedWith))
        {
            writeJson(exchange, 403, "{\"error\":\"forbidden\"}");
            return false;
        }

        String csrf = headers.getFirst("X-BVH-CSRF");
        if (!constantTimeEquals(csrfToken, csrf))
        {
            writeJson(exchange, 403, "{\"error\":\"forbidden\"}");
            return false;
        }

        if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod()))
        {
            String action = headers.getFirst("X-BVH-Action");
            if (!"delete-snapshot".equals(action))
            {
                writeJson(exchange, 403, "{\"error\":\"forbidden\"}");
                return false;
            }
        }

        String origin = headers.getFirst("Origin");
        if (origin != null && !isLocalDashboardOrigin(origin))
        {
            writeJson(exchange, 403, "{\"error\":\"forbidden\"}");
            return false;
        }

        String referer = headers.getFirst("Referer");
        if (referer != null && !isLocalDashboardOrigin(referer))
        {
            writeJson(exchange, 403, "{\"error\":\"forbidden\"}");
            return false;
        }

        if (!allowStateChangingRequest())
        {
            writeJson(exchange, 429, "{\"error\":\"too many requests\"}");
            return false;
        }

        return true;
    }

    private boolean isLoopbackRequest(HttpExchange exchange)
    {
        return exchange.getRemoteAddress() != null
            && exchange.getRemoteAddress().getAddress() != null
            && exchange.getRemoteAddress().getAddress().isLoopbackAddress();
    }

    private boolean constantTimeEquals(String expected, String actual)
    {
        if (expected == null || actual == null)
        {
            return false;
        }

        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private boolean allowStateChangingRequest()
    {
        long now = System.currentTimeMillis();
        long previous = lastStateChangingRequestMs.get();
        if (previous > 0L && now - previous < MIN_STATE_CHANGE_INTERVAL_MS)
        {
            return false;
        }

        return lastStateChangingRequestMs.compareAndSet(previous, now);
    }

    private boolean isJsonContentType(HttpExchange exchange)
    {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null)
        {
            return false;
        }

        return contentType.toLowerCase(Locale.ROOT).startsWith("application/json");
    }

    private boolean isLocalDashboardOrigin(String value)
    {
        if (value == null)
        {
            return false;
        }

        try
        {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();

            if (!"http".equalsIgnoreCase(scheme))
            {
                return false;
            }

            boolean localHost = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
            return localHost && port == currentPort();
        }
        catch (RuntimeException ex)
        {
            return false;
        }
    }

    private String readSmallRequestBody(HttpExchange exchange, int maxBytes) throws IOException
    {
        byte[] body = exchange.getRequestBody().readNBytes(maxBytes + 1);
        if (body.length > maxBytes)
        {
            return null;
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private boolean isSafeServedFile(Path root, Path path, Set<String> allowedExtensions)
    {
        if (path == null || !Files.isRegularFile(path))
        {
            return false;
        }

        try
        {
            Path realRoot = root.toRealPath();
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(realRoot) || Files.size(realPath) > MAX_SERVED_FILE_BYTES)
            {
                return false;
            }

            String filename = realPath.getFileName().toString().toLowerCase(Locale.ROOT);
            return allowedExtensions.stream().anyMatch(filename::endsWith);
        }
        catch (IOException ex)
        {
            return false;
        }
    }

    private boolean isValidCapturedAt(String capturedAt)
    {
        if (capturedAt == null || capturedAt.length() > 64)
        {
            return false;
        }

        try
        {
            Instant.parse(capturedAt);
            return true;
        }
        catch (RuntimeException ex)
        {
            return false;
        }
    }

    private boolean isAllowedTimestep(String timestep)
    {
        if (timestep == null || timestep.trim().isEmpty())
        {
            return true;
        }

        String normalized = timestep.trim().toLowerCase(Locale.ROOT);
        return "5m".equals(normalized)
            || "1h".equals(normalized)
            || "6h".equals(normalized)
            || "24h".equals(normalized)
            || "1d".equals(normalized);
    }

    private boolean isSafeProfileKey(String profileKey)
    {
        if (profileKey == null || profileKey.trim().isEmpty() || profileKey.length() > 128)
        {
            return false;
        }

        return !profileKey.contains("..")
            && !profileKey.contains("/")
            && !profileKey.contains("\\")
            && !profileKey.contains(":")
            && profileKey.chars().allMatch(ch -> ch >= 32 && ch != 127);
    }

    private String buildProfilesJson()
    {
        Path dataDir = snapshotStore.getDataDir();
        if (!Files.isDirectory(dataDir))
        {
            return "{\"profiles\":[]}";
        }

        try (Stream<Path> stream = Files.list(dataDir))
        {
            String profiles = stream
                .filter(Files::isDirectory)
                .sorted(Comparator.comparingLong(this::profileLatestModifiedTime).reversed())
                .map(path -> {
                    String name = path.getFileName().toString();
                    long count = countSnapshots(name);
                    String latest = readLatestSnapshotJson(name)
                        .flatMap(this::extractCapturedAt)
                        .orElse(null);
                    StringBuilder sb = new StringBuilder();
                    sb.append('{');
                    appendJsonField(sb, "name", name).append(',');
                    appendJsonField(sb, "snapshotCount", count);
                    if (latest != null)
                    {
                        sb.append(',');
                        appendJsonField(sb, "latestCapturedAt", latest);
                    }
                    sb.append('}');
                    return sb.toString();
                })
                .collect(Collectors.joining(","));
            return "{\"profiles\":[" + profiles + "]}";
        }
        catch (IOException ex)
        {
            return "{\"profiles\":[]}";
        }
    }

    private long countSnapshots(String profileKey)
    {
        try (Stream<Path> stream = Files.list(snapshotStore.getSnapshotsDir(profileKey)))
        {
            return stream.filter(this::isSnapshotDataFile).count();
        }
        catch (IOException ex)
        {
            return 0L;
        }
    }

    private long profileLatestModifiedTime(Path profileDir)
    {
        try
        {
            Path latestJsonGz = profileDir.resolve("latest.json.gz");
            if (Files.exists(latestJsonGz))
            {
                return Files.getLastModifiedTime(latestJsonGz).toMillis();
            }

            Path latestJson = profileDir.resolve("latest.json");
            if (Files.exists(latestJson))
            {
                return Files.getLastModifiedTime(latestJson).toMillis();
            }

            return Files.getLastModifiedTime(profileDir).toMillis();
        }
        catch (IOException ex)
        {
            return 0L;
        }
    }

    private Optional<String> readLatestSnapshotJson(String profileKey)
    {
        Path latestJsonGz = snapshotStore.getProfileDir(profileKey).resolve("latest.json.gz");
        if (Files.exists(latestJsonGz))
        {
            return readSnapshotFile(latestJsonGz);
        }

        Path latestJson = snapshotStore.getLatestFile(profileKey);
        if (Files.exists(latestJson))
        {
            return readSnapshotFile(latestJson);
        }

        try (Stream<Path> stream = Files.list(snapshotStore.getSnapshotsDir(profileKey)))
        {
            return stream.filter(this::isSnapshotDataFile)
                .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                .findFirst()
                .flatMap(this::readSnapshotFile);
        }
        catch (IOException ex)
        {
            return Optional.empty();
        }
    }

    private String buildSnapshotsArrayJson(String profileKey)
    {
        Path dir = snapshotStore.getSnapshotsDir(profileKey);
        if (!Files.isDirectory(dir))
        {
            return "[]";
        }

        try (Stream<Path> stream = Files.list(dir))
        {
            return stream.filter(this::isSnapshotDataFile)
                .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()))
                .map(this::readSnapshotFile)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(json -> !json.trim().isEmpty())
                .collect(Collectors.joining(",", "[", "]"));
        }
        catch (IOException ex)
        {
            return "[]";
        }
    }

    private Optional<String> findSnapshotJson(String profileKey, String capturedAt)
    {
        Path dir = snapshotStore.getSnapshotsDir(profileKey);
        if (!Files.isDirectory(dir))
        {
            return Optional.empty();
        }

        try (Stream<Path> stream = Files.list(dir))
        {
            return stream.filter(this::isSnapshotDataFile)
                .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                .map(this::readSnapshotFile)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(json -> json.contains("\"capturedAt\":\"" + escapeJson(capturedAt) + "\""))
                .findFirst();
        }
        catch (IOException ex)
        {
            return Optional.empty();
        }
    }

    private boolean isSnapshotDataFile(Path path)
    {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".json") || name.endsWith(".json.gz");
    }

    private Optional<String> readSnapshotFile(Path path)
    {
        try
        {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".gz"))
            {
                try (InputStream in = new GZIPInputStream(Files.newInputStream(path)))
                {
                    return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
        }
        catch (IOException ex)
        {
            return Optional.empty();
        }
    }

    private Optional<String> extractCapturedAt(String json)
    {
        String marker = "\"capturedAt\":\"";
        int start = json.indexOf(marker);
        if (start < 0)
        {
            return Optional.empty();
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        if (end <= start)
        {
            return Optional.empty();
        }
        return Optional.of(json.substring(start, end));
    }

    private String parseQueryParam(String rawQuery, String key)
    {
        if (rawQuery == null || rawQuery.isEmpty() || rawQuery.length() > MAX_QUERY_LENGTH)
        {
            return null;
        }

        for (String pair : rawQuery.split("&", 64))
        {
            int idx = pair.indexOf('=');
            if (idx <= 0)
            {
                continue;
            }

            String name = decodeComponent(pair.substring(0, idx));
            if (key.equals(name))
            {
                return decodeComponent(pair.substring(idx + 1));
            }
        }
        return null;
    }

    private String extractJsonString(String json, String key)
    {
        if (json == null || key == null)
        {
            return null;
        }

        String marker = "\"" + key + "\"";
        int keyIndex = json.indexOf(marker);
        if (keyIndex < 0)
        {
            return null;
        }

        int colon = json.indexOf(':', keyIndex + marker.length());
        if (colon < 0)
        {
            return null;
        }

        int start = json.indexOf('\"', colon + 1);
        if (start < 0)
        {
            return null;
        }

        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = start + 1; i < json.length(); i++)
        {
            char ch = json.charAt(i);
            if (escaped)
            {
                value.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\')
            {
                escaped = true;
                continue;
            }
            if (ch == '\"')
            {
                return value.toString();
            }
            value.append(ch);
        }
        return null;
    }

    private String decodeComponent(String value)
    {
        try
        {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        catch (RuntimeException ex)
        {
            return "";
        }
    }

    private void serveFile(HttpExchange exchange, Path path) throws IOException
    {
        byte[] body = Files.readAllBytes(path);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType(path.getFileName().toString()));
        applySecurityHeaders(headers);
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void writeJson(HttpExchange exchange, int status, String json) throws IOException
    {
        writeText(exchange, status, json, "application/json; charset=utf-8");
    }

    private void writeText(HttpExchange exchange, int status, String body, String contentType) throws IOException
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        applySecurityHeaders(headers);
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void applySecurityHeaders(Headers headers)
    {
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Cross-Origin-Opener-Policy", "same-origin");
        headers.set("X-Permitted-Cross-Domain-Policies", "none");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), interest-cohort=()");
        headers.set("Content-Security-Policy",
            "default-src 'self'; "
                + "script-src 'self'; "
                + "style-src 'self'; "
                + "img-src 'self' data:; "
                + "connect-src 'self' https://prices.runescape.wiki; "
                + "object-src 'none'; "
                + "base-uri 'none'; "
                + "frame-ancestors 'none'; "
                + "form-action 'none'");
    }

    private String contentType(String filename)
    {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png"))
        {
            return "image/png";
        }
        if (lower.endsWith(".json.gz") || lower.endsWith(".gz"))
        {
            return "application/gzip";
        }
        if (lower.endsWith(".json"))
        {
            return "application/json; charset=utf-8";
        }
        if (lower.endsWith(".css"))
        {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".js"))
        {
            return "application/javascript; charset=utf-8";
        }
        return "text/plain; charset=utf-8";
    }

    private static StringBuilder appendJsonField(StringBuilder sb, String name, String value)
    {
        sb.append('"').append(escapeJson(name)).append('"').append(':');
        if (value == null)
        {
            sb.append("null");
        }
        else
        {
            sb.append('"').append(escapeJson(value)).append('"');
        }
        return sb;
    }

    private static StringBuilder appendJsonField(StringBuilder sb, String name, long value)
    {
        sb.append('"').append(escapeJson(name)).append('"').append(':').append(value);
        return sb;
    }

    private static String escapeJson(String value)
    {
        if (value == null)
        {
            return "";
        }

        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }

    private class ResourceHandler implements HttpHandler
    {
        private final String resourcePath;
        private final String contentType;

        private ResourceHandler(String resourcePath, String contentType)
        {
            this.resourcePath = resourcePath;
            this.contentType = contentType;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException
        {
            if (!requireMethod(exchange, "GET"))
            {
                return;
            }

            try (InputStream in = LocalDashboardServer.class.getClassLoader().getResourceAsStream(resourcePath))
            {
                if (in == null)
                {
                    byte[] body = "Missing resource".getBytes(StandardCharsets.UTF_8);
                    Headers headers = exchange.getResponseHeaders();
                    headers.set("Content-Type", "text/plain; charset=utf-8");
                    applySecurityHeaders(headers);
                    headers.set("Cache-Control", "no-store");
                    exchange.sendResponseHeaders(404, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                    return;
                }

                byte[] body = in.readAllBytes();
                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", contentType);
                applySecurityHeaders(headers);
                headers.set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
        }
    }
}
