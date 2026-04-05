package com.bankvaluehistory;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("bankvaluehistory")
public interface BankValueHistoryConfig extends Config
{
    @ConfigSection(
        name = "Capture",
        description = "Snapshot capture behavior",
        position = 0,
        closedByDefault = false
    )
    String captureSection = "captureSection";

    @ConfigSection(
        name = "Dashboard",
        description = "Local web dashboard behavior",
        position = 1,
        closedByDefault = false
    )
    String dashboardSection = "dashboardSection";

    @ConfigSection(
        name = "Export",
        description = "Snapshot export options",
        position = 2,
        closedByDefault = true
    )
    String exportSection = "exportSection";

    @ConfigItem(
        keyName = "autoCapture",
        name = "Auto capture",
        description = "Automatically save one snapshot per day after the configured hour",
        section = captureSection,
        position = 0
    )
    default boolean autoCapture()
    {
        return true;
    }

    @Range(min = 0, max = 23)
    @ConfigItem(
        keyName = "captureHour",
        name = "Capture hour",
        description = "Local hour after which auto-capture may save today's snapshot",
        section = captureSection,
        position = 1
    )
    default int captureHour()
    {
        return 20;
    }

    @ConfigItem(
        keyName = "openDashboardAfterCapture",
        name = "Open dashboard after manual capture",
        description = "Open the local dashboard in your browser after pressing Capture now",
        section = captureSection,
        position = 2
    )
    default boolean openDashboardAfterCapture()
    {
        return true;
    }

    @ConfigItem(
        keyName = "autoStartDashboard",
        name = "Auto-start dashboard",
        description = "Start the local dashboard web server when the plugin starts",
        section = dashboardSection,
        position = 0
    )
    default boolean autoStartDashboard()
    {
        return true;
    }

    @ConfigItem(
        keyName = "openDashboardOnStartup",
        name = "Open dashboard on startup",
        description = "Open the local dashboard in your browser when the plugin starts",
        section = dashboardSection,
        position = 1
    )
    default boolean openDashboardOnStartup()
    {
        return false;
    }

    @Range(min = 1024, max = 65535)
    @ConfigItem(
        keyName = "webPort",
        name = "Dashboard port",
        description = "Local port used by the built-in bank dashboard web server",
        section = dashboardSection,
        position = 2
    )
    default int webPort()
    {
        return 17865;
    }

    @ConfigItem(
        keyName = "exportIcons",
        name = "Export item icons",
        description = "Save item icons to disk for the local dashboard",
        section = exportSection,
        position = 0
    )
    default boolean exportIcons()
    {
        return true;
    }

    @ConfigItem(
        keyName = "ignoreZeroValueItems",
        name = "Ignore zero-value items",
        description = "Skip items whose GE and HA values are both zero when saving snapshots",
        section = exportSection,
        position = 1
    )
    default boolean ignoreZeroValueItems()
    {
        return false;
    }
}
