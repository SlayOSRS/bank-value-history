package com.bankvaluehistory.service;

import com.bankvaluehistory.BankValueHistoryConfig;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class LocalDashboardServer
{
    private static final Logger log = LoggerFactory.getLogger(LocalDashboardServer.class);

    private final BankValueHistoryConfig config;
    private final SnapshotStore snapshotStore;
    private final SnapshotService snapshotService;
    private final WikiPriceService wikiPriceService;

    private HttpServer server;

    @Inject
    public LocalDashboardServer(BankValueHistoryConfig config, SnapshotStore snapshotStore, SnapshotService snapshotService,
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

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", config.webPort()), 0);
        server.createContext("/", new ResourceHandler("web/index.html", "text/html; charset=utf-8"));
        server.createContext("/app.js", new ResourceHandler("web/app.js", "application/javascript; charset=utf-8"));
        server.createContext("/styles.css", new ResourceHandler("web/styles.css", "text/css; charset=utf-8"));
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/latest", this::handleLatest);
        server.createContext("/api/snapshots", this::handleSnapshots);
        server.createContext("/api/wiki-prices", this::handleWikiPrices);
        server.createContext("/api/wiki-mapping", this::handleWikiMapping);
        server.createContext("/api/item-timeseries", this::handleItemTimeseries);
        server.createContext("/icons/", this::handleIcons);
        server.createContext("/data/", this::handleDataFiles);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        log.info("Bank dashboard available at {}", getBaseUrl());
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
        }
    }

    public String getBaseUrl()
    {
        return "http://127.0.0.1:" + config.webPort() + "/";
    }

    public void openBrowser()
    {
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

    private void handleStatus(HttpExchange exchange) throws IOException
    {
        writeJson(exchange, 200, snapshotStore.statusJson(resolveProfile(), config.webPort()));
    }

    private void handleLatest(HttpExchange exchange) throws IOException
    {
        Optional<String> json = snapshotStore.readLatestJson(resolveProfile()).filter(value -> !value.trim().isEmpty());
        writeJson(exchange, 200, json.orElse("{}"));
    }

    private void handleSnapshots(HttpExchange exchange) throws IOException
    {
        writeJson(exchange, 200, snapshotStore.buildSnapshotsArrayJson(resolveProfile()));
    }

    private void handleWikiPrices(HttpExchange exchange) throws IOException
    {
        wikiPriceService.refreshAsyncIfStale(Duration.ofMinutes(5));
        String idsParam = parseQueryParam(exchange.getRequestURI().getRawQuery(), "ids");
        Set<Integer> ids = parseIdSet(idsParam);
        writeJson(exchange, 200, wikiPriceService.buildSubsetJson(ids));
    }

    private void handleWikiMapping(HttpExchange exchange) throws IOException
    {
        writeJson(exchange, 200, wikiPriceService.fetchMappingJson());
    }

    private void handleItemTimeseries(HttpExchange exchange) throws IOException
    {
        String idParam = parseQueryParam(exchange.getRequestURI().getRawQuery(), "id");
        String timestep = parseQueryParam(exchange.getRequestURI().getRawQuery(), "timestep");
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
        String relative = exchange.getRequestURI().getPath().substring("/icons/".length());
        Path path = snapshotStore.getIconsDir().resolve(relative).normalize();
        if (!path.startsWith(snapshotStore.getIconsDir()) || !Files.exists(path))
        {
            writeText(exchange, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }
        serveFile(exchange, path);
    }

    private void handleDataFiles(HttpExchange exchange) throws IOException
    {
        String relative = exchange.getRequestURI().getPath().substring("/data/".length());
        Path root = snapshotStore.getDataDir();
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || !Files.exists(path))
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

    private String resolveProfile()
    {
        return snapshotStore.resolveProfileOrLatest(snapshotService.getCurrentProfileKey());
    }

    private String parseQueryParam(String rawQuery, String key)
    {
        if (rawQuery == null || rawQuery.isEmpty())
        {
            return null;
        }

        for (String pair : rawQuery.split("&"))
        {
            int idx = pair.indexOf('=');
            if (idx <= 0)
            {
                continue;
            }
            String name = pair.substring(0, idx);
            if (key.equals(name))
            {
                return pair.substring(idx + 1);
            }
        }
        return null;
    }

    private void serveFile(HttpExchange exchange, Path path) throws IOException
    {
        byte[] body = Files.readAllBytes(path);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType(path.getFileName().toString()));
        headers.set("Cache-Control", "no-cache");
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
        headers.set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String contentType(String filename)
    {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png"))
        {
            return "image/png";
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

    private static class ResourceHandler implements HttpHandler
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
            try (InputStream in = LocalDashboardServer.class.getClassLoader().getResourceAsStream(resourcePath))
            {
                if (in == null)
                {
                    byte[] body = "Missing resource".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(404, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                    return;
                }

                byte[] body = in.readAllBytes();
                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", contentType);
                headers.set("Cache-Control", "no-cache");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
        }
    }
}
