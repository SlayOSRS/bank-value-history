package com.bankvaluehistory.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.game.ItemManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class IconExportService
{
    private static final Logger log = LoggerFactory.getLogger(IconExportService.class);

    private final ItemManager itemManager;
    private final SnapshotStore snapshotStore;

    @Inject
    public IconExportService(ItemManager itemManager, SnapshotStore snapshotStore)
    {
        this.itemManager = itemManager;
        this.snapshotStore = snapshotStore;
    }

    public String exportSnapshotIcon(String profileKey, Instant capturedAt, int itemId, int canonicalItemId, int quantity, boolean placeholder)
    {
        int safeQuantity = Math.max(0, quantity);
        String fileName = buildIconFileName(itemId, canonicalItemId, safeQuantity, placeholder);
        Path snapshotDir = snapshotStore.getSnapshotIconsDir(profileKey, capturedAt);
        Path file = snapshotDir.resolve(fileName);

        if (Files.exists(file))
        {
            return buildSnapshotIconUrl(profileKey, capturedAt, fileName);
        }

        try
        {
            Files.createDirectories(snapshotDir);
            log.debug("Exporting snapshot icon to {}", file);

            Path legacyFile = snapshotStore.getIconsDir().resolve(fileName);
            if (Files.exists(legacyFile))
            {
                Files.copy(legacyFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return buildSnapshotIconUrl(profileKey, capturedAt, fileName);
            }

            int imageItemId = itemId > 0 ? itemId : canonicalItemId;
            int imageQuantity = Math.max(1, safeQuantity);
            boolean stackableQuantity = !placeholder && imageQuantity > 1;

            BufferedImage image = itemManager.getImage(imageItemId, imageQuantity, stackableQuantity);
            if (image != null)
            {
                ImageIO.write(image, "png", file.toFile());
            }
        }
        catch (IOException ex)
        {
            log.warn("Unable to export icon for item {} (canonical {}, quantity {}, placeholder {})",
                itemId, canonicalItemId, quantity, placeholder, ex);
        }

        return buildSnapshotIconUrl(profileKey, capturedAt, fileName);
    }

    public String exportSnapshotIcon(int itemId, int canonicalItemId, int quantity, boolean placeholder)
    {
        int safeQuantity = Math.max(0, quantity);
        String fileName = buildIconFileName(itemId, canonicalItemId, safeQuantity, placeholder);
        Path file = snapshotStore.getIconsDir().resolve(fileName);

        if (Files.exists(file))
        {
            return "/icons/" + file.getFileName().toString();
        }

        // Legacy/global icon export is intentionally read-only now.
        // New captures must use exportSnapshotIcon(profileKey, capturedAt, ...)
        // so icons are written under data/<profile>/icons/<snapshot-date>/.
        log.warn("Legacy icon export was called for item {} (canonical {}, quantity {}, placeholder {}) without profile/snapshot context. No global icon was written.",
            itemId, canonicalItemId, quantity, placeholder);

        return "";
    }

    private String buildSnapshotIconUrl(String profileKey, Instant capturedAt, String fileName)
    {
        return "/data/"
            + encodePathSegment(profileKey)
            + "/icons/"
            + encodePathSegment(snapshotStore.getSnapshotDirectoryName(capturedAt))
            + "/"
            + encodePathSegment(fileName);
    }

    private String encodePathSegment(String value)
    {
        return URLEncoder.encode(value == null ? "unknown" : value, StandardCharsets.UTF_8)
            .replace("+", "%20");
    }

    private String buildIconFileName(int itemId, int canonicalItemId, int quantity, boolean placeholder)
    {
        StringBuilder sb = new StringBuilder(64);
        sb.append(itemId > 0 ? itemId : canonicalItemId);
        sb.append("__q").append(Math.max(0, quantity));
        if (placeholder)
        {
            sb.append("__ph");
        }
        sb.append(".png");
        return sb.toString();
    }
}
