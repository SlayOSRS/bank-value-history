package com.bankvaluehistory.service;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.IllegalComponentStateException;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
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
            Path file = snapshotStore.getSnapshotImageFile(profileKey, capturedAt);
            BufferedImage image = createCapture(captureBounds);
            ImageIO.write(image, "png", file.toFile());
            return new CaptureResult(
                "/data/" + profileKey + "/images/" + file.getFileName().toString(),
                image.getWidth(),
                image.getHeight()
            );
        }
        catch (IOException | AWTException | IllegalComponentStateException ex)
        {
            log.warn("Unable to capture bank image for {}", profileKey, ex);
            return new CaptureResult(null, 0, 0);
        }
    }

    private BufferedImage createCapture(Rectangle captureBounds) throws AWTException
    {
        if (captureBounds == null || captureBounds.width <= 0 || captureBounds.height <= 0)
        {
            return blankImage(720, 520);
        }

        Point canvasLocation = client.getCanvas().getLocationOnScreen();
        Rectangle screenRect = new Rectangle(
            canvasLocation.x + captureBounds.x,
            canvasLocation.y + captureBounds.y,
            captureBounds.width,
            captureBounds.height
        );

        try
        {
            return new Robot().createScreenCapture(screenRect);
        }
        catch (SecurityException ex)
        {
            return blankImage(captureBounds.width, captureBounds.height);
        }
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
