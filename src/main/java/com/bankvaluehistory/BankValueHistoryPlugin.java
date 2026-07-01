package com.bankvaluehistory;

import com.bankvaluehistory.service.LocalDashboardServer;
import com.bankvaluehistory.service.SnapshotService;
import com.bankvaluehistory.service.WikiPriceService;
import com.bankvaluehistory.ui.BankValueHistoryPanel;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
    name = "Bank Value History",
    description = "Exports bank snapshots, icons, and a local bank dashboard",
    tags = {"bank", "value", "tracker", "dashboard", "history"}
)
public class BankValueHistoryPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(BankValueHistoryPlugin.class);
    private static final int INVENTORY_CONTAINER_ID = 93;
    private static final int EQUIPMENT_CONTAINER_ID = 94;

    @Inject private Client client;
    @Inject private ClientToolbar clientToolbar;
    @Inject private SnapshotService snapshotService;
    @Inject private LocalDashboardServer dashboardServer;
    @Inject private WikiPriceService wikiPriceService;
    @Inject private BankValueHistoryPanel panel;
    @Inject private BankValueHistoryConfig config;

    private NavigationButton navigationButton;
    private String lastDisplayedProfileKey = "unknown";

    @Provides
    BankValueHistoryConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(BankValueHistoryConfig.class);
    }

    @Override
    protected void startUp() throws Exception
    {
        wikiPriceService.start();
        if (config.autoStartDashboard() || config.openDashboardOnStartup())
        {
            startDashboard();
        }

        BufferedImage icon = ImageUtil.loadImageResource(BankValueHistoryPlugin.class, "/icon.png");
        navigationButton = NavigationButton.builder()
            .tooltip("Bank Value History")
            .icon(icon)
            .priority(5)
            .panel(panel)
            .build();

        clientToolbar.addNavigation(navigationButton);
        lastDisplayedProfileKey = snapshotService.getCurrentProfileKey();
        panel.refreshStatus();

        if (config.openDashboardOnStartup())
        {
            dashboardServer.ensureStarted();
            dashboardServer.openBrowser(snapshotService.getCurrentProfileKey());
        }

        log.info("Bank Value History started");
    }

    @Override
    protected void shutDown()
    {
        if (navigationButton != null)
        {
            clientToolbar.removeNavigation(navigationButton);
            navigationButton = null;
        }
        dashboardServer.stop();
        wikiPriceService.stop();
        log.info("Bank Value History stopped");
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        ItemContainer bank = client.getItemContainer(InventoryID.BANK);
        ItemContainer inventory = client.getItemContainer(INVENTORY_CONTAINER_ID);
        ItemContainer equipment = client.getItemContainer(EQUIPMENT_CONTAINER_ID);
        if ((bank != null && event.getItemContainer() == bank)
            || (inventory != null && event.getItemContainer() == inventory)
            || (equipment != null && event.getItemContainer() == equipment))
        {
            snapshotService.markDirty();
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        boolean shouldRefresh = false;

        if (snapshotService.maybeAutoCapture())
        {
            shouldRefresh = true;
        }

        boolean manualPending = snapshotService.isManualCapturePending();
        boolean manualCaptured = snapshotService.maybeManualCapture();
        if (manualPending || manualCaptured)
        {
            shouldRefresh = true;
        }

        if (manualCaptured && config.openDashboardAfterCapture())
        {
            dashboardServer.ensureStarted();
            dashboardServer.openBrowser(snapshotService.getCurrentProfileKey());
        }

        String currentProfileKey = snapshotService.getCurrentProfileKey();
        if (!currentProfileKey.equals(lastDisplayedProfileKey))
        {
            lastDisplayedProfileKey = currentProfileKey;
            shouldRefresh = true;
        }

        if (shouldRefresh)
        {
            panel.refreshStatus();
        }
    }

    private void startDashboard()
    {
        try
        {
            dashboardServer.start();
        }
        catch (IOException ex)
        {
            log.warn("Unable to start local bank dashboard on the configured port", ex);
        }
    }
}
