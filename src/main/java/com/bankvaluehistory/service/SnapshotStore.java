package com.bankvaluehistory.service;

import com.bankvaluehistory.model.BankSnapshot;
import com.bankvaluehistory.model.BankTabSnapshot;
import com.bankvaluehistory.model.ItemSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Singleton;

@Singleton
public class SnapshotStore
{
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
        .withZone(java.time.ZoneId.systemDefault());

    private final Path baseDir;

    public SnapshotStore()
    {
        this(Paths.get(System.getProperty("user.home"), ".runelite", "bank-value-tracker-web"));
    }

    public SnapshotStore(Path baseDir)
    {
        this.baseDir = baseDir;
    }

    public Path getBaseDir() { return baseDir; }
    public Path getIconsDir() { return baseDir.resolve("icons"); }
    public Path getDataDir() { return baseDir.resolve("data"); }
    public Path getProfileDir(String profileKey) { return getDataDir().resolve(profileKey); }
    public Path getSnapshotsDir(String profileKey) { return getProfileDir(profileKey).resolve("snapshots"); }
    public Path getImagesDir(String profileKey) { return getProfileDir(profileKey).resolve("images"); }
    public Path getLatestFile(String profileKey) { return getProfileDir(profileKey).resolve("latest.json"); }
    public Path getSnapshotFile(String profileKey, Instant capturedAt) { return getSnapshotsDir(profileKey).resolve(FILE_TS.format(capturedAt) + ".json"); }
    public Path getSnapshotImageFile(String profileKey, Instant capturedAt) { return getImagesDir(profileKey).resolve(FILE_TS.format(capturedAt) + ".png"); }

    public void ensureDirectories(String profileKey) throws IOException
    {
        Files.createDirectories(getIconsDir());
        Files.createDirectories(getSnapshotsDir(profileKey));
        Files.createDirectories(getImagesDir(profileKey));
    }

    public void save(BankSnapshot snapshot) throws IOException
    {
        ensureDirectories(snapshot.getProfileKey());
        String json = toJson(snapshot);
        Files.writeString(getSnapshotFile(snapshot.getProfileKey(), snapshot.getCapturedAt()), json, StandardCharsets.UTF_8);
        Files.writeString(getLatestFile(snapshot.getProfileKey()), json, StandardCharsets.UTF_8);
    }

    public boolean hasSnapshotForDay(String profileKey, LocalDate day)
    {
        Path latest = getLatestFile(profileKey);
        if (!Files.exists(latest)) return false;
        try { return Files.readString(latest, StandardCharsets.UTF_8).contains("\"day\":\"" + day + "\""); }
        catch (IOException ex) { return false; }
    }

    public Optional<String> readLatestJson(String profileKey)
    {
        Path latest = getLatestFile(profileKey);
        if (!Files.exists(latest)) return Optional.empty();
        try { return Optional.of(Files.readString(latest, StandardCharsets.UTF_8)); }
        catch (IOException ex) { return Optional.empty(); }
    }

    public String buildSnapshotsArrayJson(String profileKey)
    {
        Path dir = getSnapshotsDir(profileKey);
        if (!Files.isDirectory(dir)) return "[]";
        try (Stream<Path> stream = Files.list(dir))
        {
            return stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparing(Path::getFileName))
                .map(path -> { try { return Files.readString(path, StandardCharsets.UTF_8); } catch (IOException ex) { return null; } })
                .filter(json -> json != null && !json.trim().isEmpty())
                .collect(Collectors.joining(",", "[", "]"));
        }
        catch (IOException ex)
        {
            return "[]";
        }
    }

    public String resolveProfileOrLatest(String preferred)
    {
        if (preferred != null && !preferred.trim().isEmpty() && Files.isDirectory(getProfileDir(preferred))) return preferred;
        Path dataDir = getDataDir();
        if (!Files.isDirectory(dataDir)) return preferred == null || preferred.trim().isEmpty() ? "unknown" : preferred;
        try (Stream<Path> stream = Files.list(dataDir))
        {
            return stream.filter(Files::isDirectory)
                .max(Comparator.comparingLong(this::latestModifiedTime))
                .map(path -> path.getFileName().toString())
                .orElse(preferred == null || preferred.trim().isEmpty() ? "unknown" : preferred);
        }
        catch (IOException ex)
        {
            return preferred == null || preferred.trim().isEmpty() ? "unknown" : preferred;
        }
    }

    private long latestModifiedTime(Path profileDir)
    {
        try
        {
            Path latest = profileDir.resolve("latest.json");
            if (Files.exists(latest)) return Files.getLastModifiedTime(latest).toMillis();
            return Files.getLastModifiedTime(profileDir).toMillis();
        }
        catch (IOException ex)
        {
            return 0L;
        }
    }

    public String statusJson(String profileKey, int port)
    {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        appendJsonField(sb, "currentProfile", profileKey).append(',');
        appendJsonField(sb, "baseDir", baseDir.toString()).append(',');
        appendJsonField(sb, "dashboardUrl", "http://127.0.0.1:" + port + "/");
        sb.append('}');
        return sb.toString();
    }

    public static String toJson(BankSnapshot snapshot)
    {
        StringBuilder sb = new StringBuilder(16384);
        sb.append('{');
        appendJsonField(sb, "profileKey", snapshot.getProfileKey()).append(',');
        appendJsonField(sb, "day", snapshot.getDay().toString()).append(',');
        appendJsonField(sb, "capturedAt", snapshot.getCapturedAt().toString()).append(',');
        appendJsonField(sb, "totalGe", snapshot.getTotalGe()).append(',');
        appendJsonField(sb, "totalHa", snapshot.getTotalHa()).append(',');
        appendJsonField(sb, "bankImagePath", snapshot.getBankImagePath()).append(',');
        appendJsonField(sb, "bankImageWidth", snapshot.getBankImageWidth()).append(',');
        appendJsonField(sb, "bankImageHeight", snapshot.getBankImageHeight()).append(',');
        sb.append("\"bankTabs\":[");
        boolean firstTab = true;
        for (BankTabSnapshot tab : snapshot.getBankTabs())
        {
            if (!firstTab) sb.append(',');
            firstTab = false;
            sb.append('{');
            appendJsonField(sb, "index", tab.getIndex()).append(',');
            appendJsonField(sb, "name", tab.getName()).append(',');
            appendJsonField(sb, "count", tab.getCount());
            sb.append('}');
        }
        sb.append("],\"items\":[");
        boolean first = true;
        for (ItemSnapshot item : snapshot.getItems())
        {
            if (!first) sb.append(',');
            first = false;
            sb.append('{');
            appendJsonField(sb, "itemId", item.getItemId()).append(',');
            appendJsonField(sb, "canonicalItemId", item.getCanonicalItemId()).append(',');
            appendJsonField(sb, "name", item.getName()).append(',');
            appendJsonField(sb, "quantity", item.getQuantity()).append(',');
            appendJsonField(sb, "geUnitPrice", item.getGeUnitPrice()).append(',');
            appendJsonField(sb, "geTotal", item.getGeTotal()).append(',');
            appendJsonField(sb, "haUnitPrice", item.getHaUnitPrice()).append(',');
            appendJsonField(sb, "haTotal", item.getHaTotal()).append(',');
            appendJsonField(sb, "slotIndex", item.getSlotIndex()).append(',');
            appendJsonField(sb, "tabIndex", item.getTabIndex()).append(',');
            appendJsonField(sb, "tabName", item.getTabName()).append(',');
            appendJsonField(sb, "iconPath", item.getIconPath()).append(',');
            appendJsonField(sb, "placeholder", item.isPlaceholder() ? 1 : 0).append(',');
            appendJsonField(sb, "layoutX", item.getLayoutX()).append(',');
            appendJsonField(sb, "layoutY", item.getLayoutY()).append(',');
            appendJsonField(sb, "layoutW", item.getLayoutW()).append(',');
            appendJsonField(sb, "layoutH", item.getLayoutH());
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static StringBuilder appendJsonField(StringBuilder sb, String name, String value)
    {
        sb.append('"').append(escapeJson(name)).append('"').append(':');
        if (value == null) sb.append("null"); else sb.append('"').append(escapeJson(value)).append('"');
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
}
