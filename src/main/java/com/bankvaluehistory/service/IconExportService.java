package com.bankvaluehistory.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public Path exportIconIfMissing(int canonicalItemId, int quantity)
    {
        Path file = snapshotStore.getIconsDir().resolve(canonicalItemId + ".png");
        if (Files.exists(file))
        {
            return file;
        }

        try
        {
            Files.createDirectories(snapshotStore.getIconsDir());
            BufferedImage image = itemManager.getImage(canonicalItemId, quantity, quantity > 1);
            if (image != null)
            {
                ImageIO.write(image, "png", file.toFile());
            }
        }
        catch (IOException ex)
        {
            log.warn("Unable to export icon for item {}", canonicalItemId, ex);
        }

        return file;
    }
}
