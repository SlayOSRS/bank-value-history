package com.bankvaluehistory.service;

import com.bankvaluehistory.model.BankSnapshot;
import com.bankvaluehistory.model.BankTabSnapshot;
import com.bankvaluehistory.model.ItemSnapshot;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
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
    public Path getLatestFile(String profileKey) { return getProfileDir(profileKey).resolve("latest.json.gz"); }
    public String getSnapshotDirectoryName(Instant capturedAt) { return FILE_TS.format(capturedAt); }
    public Path getSnapshotFile(String profileKey, Instant capturedAt) { return getSnapshotsDir(profileKey).resolve(getSnapshotDirectoryName(capturedAt) + ".json.gz"); }
    public Path getSnapshotImageFile(String profileKey, Instant capturedAt) { return getImagesDir(profileKey).resolve(getSnapshotDirectoryName(capturedAt) + ".png"); }
    public Path getSnapshotIconsDir(String profileKey, Instant capturedAt) { return getProfileDir(profileKey).resolve("icons").resolve(getSnapshotDirectoryName(capturedAt)); }

    private Path getLegacyLatestFile(String profileKey)
    {
        return getProfileDir(profileKey).resolve("latest.json");
    }

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
        writeJson(getSnapshotFile(snapshot.getProfileKey(), snapshot.getCapturedAt()), json);
        writeJson(getLatestFile(snapshot.getProfileKey()), json);
    }

    public boolean deleteSnapshot(String profileKey, String capturedAt) throws IOException
    {
        if (profileKey == null || profileKey.trim().isEmpty() || capturedAt == null || capturedAt.trim().isEmpty())
        {
            return false;
        }

        Optional<Path> snapshotFile = findSnapshotFile(profileKey, capturedAt);
        if (!snapshotFile.isPresent())
        {
            return false;
        }

        String json = readJson(snapshotFile.get());

        deleteFileIfInside(getSnapshotsDir(profileKey), snapshotFile.get());
        deleteBankImageFromSnapshotJson(json);
        deleteNewSnapshotIconFoldersFromSnapshotJson(json);
        deleteNewSnapshotIconFolderFromTimestamp(profileKey, capturedAt);
        rebuildLatestSnapshot(profileKey);

        return true;
    }

    private Optional<Path> findSnapshotFile(String profileKey, String capturedAt)
    {
        Path dir = getSnapshotsDir(profileKey);
        if (!Files.isDirectory(dir))
        {
            return Optional.empty();
        }

        try (Stream<Path> stream = Files.list(dir))
        {
            List<Path> candidates = stream
                .filter(this::isSnapshotJsonFile)
                .sorted(Comparator.comparing(Path::getFileName))
                .collect(Collectors.toList());

            for (Path path : candidates)
            {
                try
                {
                    String json = readJson(path);
                    if (json.contains("\"capturedAt\":\"" + escapeJson(capturedAt) + "\""))
                    {
                        return Optional.of(path);
                    }
                }
                catch (IOException ignored)
                {
                    // ignore unreadable snapshot files
                }
            }
        }
        catch (IOException ignored)
        {
            // fall through
        }

        return Optional.empty();
    }

    private void rebuildLatestSnapshot(String profileKey) throws IOException
    {
        Path dir = getSnapshotsDir(profileKey);
        Optional<Path> newest = Optional.empty();

        if (Files.isDirectory(dir))
        {
            try (Stream<Path> stream = Files.list(dir))
            {
                newest = stream
                    .filter(this::isSnapshotJsonFile)
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .findFirst();
            }
        }

        Path latest = getLatestFile(profileKey);
        Path legacyLatest = getLegacyLatestFile(profileKey);

        if (newest.isPresent())
        {
            writeJson(latest, readJson(newest.get()));
            deleteFileIfInside(getProfileDir(profileKey), legacyLatest);
        }
        else
        {
            deleteFileIfInside(getProfileDir(profileKey), latest);
            deleteFileIfInside(getProfileDir(profileKey), legacyLatest);
        }
    }

    private void deleteBankImageFromSnapshotJson(String json) throws IOException
    {
        String bankImagePath = extractJsonString(json, "bankImagePath");
        if (bankImagePath == null || !bankImagePath.startsWith("/data/"))
        {
            return;
        }

        Path path = getDataDir().resolve(bankImagePath.substring("/data/".length())).normalize();
        deleteFileIfInside(getDataDir(), path);
    }

    private void deleteNewSnapshotIconFolderFromTimestamp(String profileKey, String capturedAt) throws IOException
    {
        try
        {
            Instant instant = Instant.parse(capturedAt);
            deleteDirectoryIfInside(getIconsDir(), getSnapshotIconsDir(profileKey, instant));
        }
        catch (RuntimeException ignored)
        {
            // Older or malformed timestamps are handled by iconPath cleanup.
        }
    }

    private void deleteNewSnapshotIconFoldersFromSnapshotJson(String json) throws IOException
    {
        for (String iconPath : extractIconPaths(json))
        {
            String normalized = iconPath.replace('\\', '/');

            if (normalized.startsWith("/data/"))
            {
                String relative = normalized.substring("/data/".length());
                String[] parts = relative.split("/");
                if (parts.length >= 4 && "icons".equals(parts[1]))
                {
                    Path iconFile = getDataDir().resolve(relative).normalize();
                    Path snapshotIconDir = iconFile.getParent();
                    if (snapshotIconDir != null)
                    {
                        deleteDirectoryIfInside(getDataDir(), snapshotIconDir);
                    }
                }
                continue;
            }

            if (normalized.startsWith("/icons/"))
            {
                String relative = normalized.substring("/icons/".length());
                String[] parts = relative.split("/");
                if (parts.length < 3)
                {
                    // Legacy global icon style, for example /icons/4151.png.
                    // Do not delete because old snapshots can share these icons.
                    continue;
                }

                // Cleanup compatibility for the previous attempted structure:
                // /icons/<profile>/<snapshot>/<file>.png
                Path iconFile = getIconsDir().resolve(relative).normalize();
                Path snapshotIconDir = iconFile.getParent();
                if (snapshotIconDir != null)
                {
                    deleteDirectoryIfInside(getIconsDir(), snapshotIconDir);
                }
            }
        }
    }

    private List<String> extractIconPaths(String json)
    {
        List<String> paths = new ArrayList<>();
        if (json == null || json.isEmpty())
        {
            return paths;
        }

        String marker = "\"iconPath\":\"";
        int index = 0;
        while ((index = json.indexOf(marker, index)) >= 0)
        {
            int start = index + marker.length();
            int end = json.indexOf('"', start);
            if (end <= start)
            {
                break;
            }
            paths.add(json.substring(start, end).replace("\\/", "/"));
            index = end + 1;
        }
        return paths;
    }

    private String extractJsonString(String json, String key)
    {
        if (json == null || key == null)
        {
            return null;
        }

        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0)
        {
            return null;
        }

        start += marker.length();
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++)
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
            if (ch == '"')
            {
                return value.toString();
            }
            value.append(ch);
        }
        return null;
    }

    private void deleteFileIfInside(Path root, Path path) throws IOException
    {
        Path normalizedRoot = root.normalize();
        Path normalizedPath = path.normalize();
        if (normalizedPath.startsWith(normalizedRoot) && Files.isRegularFile(normalizedPath))
        {
            Files.deleteIfExists(normalizedPath);
        }
    }

    private void deleteDirectoryIfInside(Path root, Path dir) throws IOException
    {
        Path normalizedRoot = root.normalize();
        Path normalizedDir = dir.normalize();
        if (!normalizedDir.startsWith(normalizedRoot) || normalizedDir.equals(normalizedRoot) || !Files.isDirectory(normalizedDir))
        {
            return;
        }

        try (Stream<Path> walk = Files.walk(normalizedDir))
        {
            List<Path> paths = walk
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
            for (Path path : paths)
            {
                Files.deleteIfExists(path);
            }
        }
    }

    public boolean hasSnapshotForDay(String profileKey, LocalDate day)
    {
        return readLatestJson(profileKey)
            .map(json -> json.contains("\"day\":\"" + day + "\""))
            .orElse(false);
    }

    public Optional<String> readLatestJson(String profileKey)
    {
        Path latest = getLatestFile(profileKey);
        if (Files.exists(latest))
        {
            try
            {
                return Optional.of(readJson(latest));
            }
            catch (IOException ex)
            {
                return Optional.empty();
            }
        }

        Path legacyLatest = getLegacyLatestFile(profileKey);
        if (Files.exists(legacyLatest))
        {
            try
            {
                return Optional.of(readJson(legacyLatest));
            }
            catch (IOException ex)
            {
                return Optional.empty();
            }
        }

        return Optional.empty();
    }

    public String buildSnapshotsArrayJson(String profileKey)
    {
        Path dir = getSnapshotsDir(profileKey);
        if (!Files.isDirectory(dir)) return "[]";

        try (Stream<Path> stream = Files.list(dir))
        {
            return stream
                .filter(this::isSnapshotJsonFile)
                .sorted(Comparator.comparing(Path::getFileName))
                .map(path -> {
                    try
                    {
                        return readJson(path);
                    }
                    catch (IOException ex)
                    {
                        return null;
                    }
                })
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
        String fallback = isSafeProfileKey(preferred) ? preferred : "unknown";
        if (isSafeProfileKey(preferred) && Files.isDirectory(getProfileDir(preferred))) return preferred;

        Path dataDir = getDataDir();
        if (!Files.isDirectory(dataDir)) return fallback;

        try (Stream<Path> stream = Files.list(dataDir))
        {
            return stream.filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .filter(this::isSafeProfileKey)
                .max(Comparator.comparingLong(name -> latestModifiedTime(getProfileDir(name))))
                .orElse(fallback);
        }
        catch (IOException ex)
        {
            return fallback;
        }
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

    private long latestModifiedTime(Path profileDir)
    {
        try
        {
            Path latestGz = profileDir.resolve("latest.json.gz");
            if (Files.exists(latestGz)) return Files.getLastModifiedTime(latestGz).toMillis();

            Path latestJson = profileDir.resolve("latest.json");
            if (Files.exists(latestJson)) return Files.getLastModifiedTime(latestJson).toMillis();

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

    private boolean isSnapshotJsonFile(Path path)
    {
        String filename = path.getFileName().toString().toLowerCase();
        return filename.endsWith(".json") || filename.endsWith(".json.gz");
    }

    private void writeJson(Path path, String json) throws IOException
    {
        if (path.getFileName().toString().endsWith(".gz"))
        {
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(path));
                 BufferedWriter writer = new BufferedWriter(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8)))
            {
                writer.write(json);
            }
            return;
        }

        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    private String readJson(Path path) throws IOException
    {
        String json;
        if (path.getFileName().toString().endsWith(".gz"))
        {
            try (InputStream in = new GZIPInputStream(Files.newInputStream(path));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
            {
                json = reader.lines().collect(Collectors.joining("\n"));
            }
        }
        else
        {
            json = Files.readString(path, StandardCharsets.UTF_8);
        }

        return repairKnownBatch3SnapshotJson(json);
    }

    private static String repairKnownBatch3SnapshotJson(String json)
    {
        if (json == null)
        {
            return null;
        }

        String trimmed = json.trim();
        if (trimmed.endsWith("]]}") && trimmed.contains("\"equipmentItems\""))
        {
            int removeIndex = json.lastIndexOf("]}");
            if (removeIndex > 0)
            {
                return json.substring(0, removeIndex) + "}";
            }
        }

        return json;
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
        appendJsonField(sb, "bankGe", snapshot.getBankGe()).append(',');
        appendJsonField(sb, "bankHa", snapshot.getBankHa()).append(',');
        appendJsonField(sb, "inventoryGe", snapshot.getInventoryGe()).append(',');
        appendJsonField(sb, "inventoryHa", snapshot.getInventoryHa()).append(',');
        appendJsonField(sb, "equipmentGe", snapshot.getEquipmentGe()).append(',');
        appendJsonField(sb, "equipmentHa", snapshot.getEquipmentHa()).append(',');
        appendJsonField(sb, "combinedGe", snapshot.getCombinedGe()).append(',');
        appendJsonField(sb, "combinedHa", snapshot.getCombinedHa()).append(',');
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
        sb.append(']');
        appendItemArray(sb, "items", snapshot.getItems());
        appendItemArray(sb, "inventoryItems", snapshot.getInventoryItems());
        appendItemArray(sb, "equipmentItems", snapshot.getEquipmentItems());
        sb.append('}');
        return sb.toString();
    }

    private static void appendItemArray(StringBuilder sb, String fieldName, java.util.List<ItemSnapshot> items)
    {
        sb.append(',').append('"').append(escapeJson(fieldName)).append('"').append(':').append('[');
        boolean first = true;
        for (ItemSnapshot item : items)
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
        sb.append(']');
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
