package com.bankvaluehistory.service;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class BankImageRenderer
{
    private static final Logger log = LoggerFactory.getLogger(BankImageRenderer.class);

    private final SnapshotStore snapshotStore;
    private final Client client;

    @Inject
    public BankImageRenderer(SnapshotStore snapshotStore, Client client)
    {
        this.snapshotStore = snapshotStore;
        this.client = client;
    }

    public CaptureResult capture(String profileKey, Instant capturedAt, Rectangle captureBounds)
    {
        try
        {
            snapshotStore.ensureDirectories(profileKey);
            BufferedImage image = createCapture(captureBounds);

            Path reusable = findReusableImage(profileKey, image).orElse(null);
            if (reusable != null)
            {
                return new CaptureResult(
                    "/data/" + profileKey + "/images/" + reusable.getFileName().toString(),
                    image.getWidth(),
                    image.getHeight()
                );
            }

            Path file = snapshotStore.getSnapshotImageFile(profileKey, capturedAt);
            ImageIO.write(image, "png", file.toFile());
            return new CaptureResult(
                "/data/" + profileKey + "/images/" + file.getFileName().toString(),
                image.getWidth(),
                image.getHeight()
            );
        }
        catch (IOException | RuntimeException ex)
        {
            log.warn("Unable to capture bank image for {}", profileKey, ex);
            return new CaptureResult(null, 0, 0);
        }
    }

    private Optional<Path> findReusableImage(String profileKey, BufferedImage currentImage)
    {
        Path imagesDir = snapshotStore.getImagesDir(profileKey);
        if (!Files.isDirectory(imagesDir))
        {
            return Optional.empty();
        }

        try (Stream<Path> stream = Files.list(imagesDir))
        {
            Path latest = stream
                .filter(this::isPngFile)
                .max(Comparator.comparingLong(this::lastModifiedSafe))
                .orElse(null);

            if (latest == null)
            {
                return Optional.empty();
            }

            BufferedImage existing = ImageIO.read(latest.toFile());
            if (existing == null)
            {
                return Optional.empty();
            }

            return imagesEqual(existing, currentImage) ? Optional.of(latest) : Optional.empty();
        }
        catch (IOException ex)
        {
            log.debug("Unable to inspect previous bank image for {}", profileKey, ex);
            return Optional.empty();
        }
    }

    private boolean isPngFile(Path path)
    {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".png");
    }

    private long lastModifiedSafe(Path path)
    {
        try
        {
            return Files.getLastModifiedTime(path).toMillis();
        }
        catch (IOException ex)
        {
            return 0L;
        }
    }

    private boolean imagesEqual(BufferedImage a, BufferedImage b)
    {
        if (a == null || b == null)
        {
            return false;
        }

        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight())
        {
            return false;
        }

        for (int y = 0; y < a.getHeight(); y++)
        {
            for (int x = 0; x < a.getWidth(); x++)
            {
                if (a.getRGB(x, y) != b.getRGB(x, y))
                {
                    return false;
                }
            }
        }

        return true;
    }

    private BufferedImage createCapture(Rectangle captureBounds)
    {
        Component canvas = client.getCanvas();
        if (canvas == null || canvas.getWidth() <= 0 || canvas.getHeight() <= 0)
        {
            return blankImage(720, 520);
        }

        BufferedImage canvasImage = new BufferedImage(
            Math.max(1, canvas.getWidth()),
            Math.max(1, canvas.getHeight()),
            BufferedImage.TYPE_INT_ARGB
        );

        Graphics2D g = canvasImage.createGraphics();
        try
        {
            canvas.printAll(g);
        }
        finally
        {
            g.dispose();
        }

        Rectangle crop = resolveCropBounds(captureBounds, canvasImage.getWidth(), canvasImage.getHeight());
        if (crop == null)
        {
            return canvasImage;
        }

        return copyRegion(canvasImage, crop);
    }

    private Rectangle resolveCropBounds(Rectangle captureBounds, int canvasWidth, int canvasHeight)
    {
        if (captureBounds == null || captureBounds.width <= 0 || captureBounds.height <= 0)
        {
            return new Rectangle(0, 0, Math.max(1, canvasWidth), Math.max(1, canvasHeight));
        }

        Rectangle canvasBounds = new Rectangle(0, 0, Math.max(1, canvasWidth), Math.max(1, canvasHeight));
        Rectangle crop = captureBounds.intersection(canvasBounds);
        if (crop.width <= 0 || crop.height <= 0)
        {
            return new Rectangle(0, 0, Math.max(1, canvasWidth), Math.max(1, canvasHeight));
        }

        return crop;
    }

    private BufferedImage copyRegion(BufferedImage source, Rectangle region)
    {
        BufferedImage copy = new BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        try
        {
            g.drawImage(
                source,
                0,
                0,
                region.width,
                region.height,
                region.x,
                region.y,
                region.x + region.width,
                region.y + region.height,
                null
            );
        }
        finally
        {
            g.dispose();
        }
        return copy;
    }

    private BufferedImage blankImage(int width, int height)
    {
        BufferedImage image = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(24, 18, 12));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.dispose();
        return image;
    }

    public static final class CaptureResult
    {
        private final String path;
        private final int width;
        private final int height;

        public CaptureResult(String path, int width, int height)
        {
            this.path = path;
            this.width = width;
            this.height = height;
        }

        public String getPath()
        {
            return path;
        }

        public int getWidth()
        {
            return width;
        }

        public int getHeight()
        {
            return height;
        }
    }
}
