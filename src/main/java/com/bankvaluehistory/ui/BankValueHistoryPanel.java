package com.bankvaluehistory.ui;

import com.bankvaluehistory.BankValueHistoryConfig;
import com.bankvaluehistory.service.LocalDashboardServer;
import com.bankvaluehistory.service.SnapshotService;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Path;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

public class BankValueHistoryPanel extends PluginPanel
{
    private final SnapshotService snapshotService;
    private final LocalDashboardServer dashboardServer;
    private final ClientThread clientThread;
    private final BankValueHistoryConfig config;

    private final JLabel statusValue = new JLabel();
    private final JLabel profileValue = new JLabel();
    private final JLabel dashboardValue = new JLabel();
    private final JLabel dataValue = new JLabel();
    private final JLabel settingsValue = new JLabel();
    private final JLabel hintValue = new JLabel();

    @Inject
    public BankValueHistoryPanel(SnapshotService snapshotService, LocalDashboardServer dashboardServer,
        ClientThread clientThread, BankValueHistoryConfig config)
    {
        this.snapshotService = snapshotService;
        this.dashboardServer = dashboardServer;
        this.clientThread = clientThread;
        this.config = config;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(ColorScheme.DARK_GRAY_COLOR);
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        root.add(section("Bank Value History", "Capture snapshots and control the local dashboard."));
        root.add(Box.createRigidArea(new Dimension(0, 8)));
        root.add(actionsSection());
        root.add(Box.createRigidArea(new Dimension(0, 8)));
        root.add(statusSection());
        root.add(Box.createRigidArea(new Dimension(0, 8)));
        root.add(settingsSection());
        root.add(Box.createVerticalGlue());

        add(root, BorderLayout.NORTH);
        refreshStatus();
    }

    private JPanel section(String title, String subtitle)
    {
        JPanel panel = card();
        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));

        JLabel subtitleLabel = new JLabel("<html><div style='width:250px'>" + escape(subtitle) + "</div></html>");
        subtitleLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);

        panel.add(titleLabel);
        panel.add(Box.createRigidArea(new Dimension(0, 4)));
        panel.add(subtitleLabel);
        return panel;
    }

    private JPanel actionsSection()
    {
        JPanel panel = card();
        panel.add(sectionLabel("Actions"));
        panel.add(Box.createRigidArea(new Dimension(0, 6)));

        JPanel grid = new JPanel(new GridLayout(4, 1, 0, 4));
        grid.setOpaque(false);

        JButton capture = button("Capture snapshot");
        capture.addActionListener(this::captureNow);

        JButton open = button("Open dashboard");
        open.addActionListener(e -> {
            dashboardServer.ensureStarted();
            dashboardServer.openBrowser();
            refreshStatus();
        });

        JButton restart = button("Restart dashboard");
        restart.addActionListener(e -> {
            dashboardServer.restart();
            refreshStatus();
        });

        JButton folder = button("Open data folder");
        folder.addActionListener(e -> openDataFolder());

        grid.add(capture);
        grid.add(open);
        grid.add(restart);
        grid.add(folder);
        panel.add(grid);
        return panel;
    }

    private JPanel statusSection()
    {
        JPanel panel = card();
        panel.add(sectionLabel("Status"));
        panel.add(Box.createRigidArea(new Dimension(0, 6)));
        panel.add(infoRow("State", statusValue));
        panel.add(Box.createRigidArea(new Dimension(0, 4)));
        panel.add(infoRow("Profile", profileValue));
        panel.add(Box.createRigidArea(new Dimension(0, 4)));
        panel.add(infoRow("Dashboard", dashboardValue));
        panel.add(Box.createRigidArea(new Dimension(0, 4)));
        panel.add(infoRow("Data path", dataValue));
        panel.add(Box.createRigidArea(new Dimension(0, 6)));
        hintValue.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
        panel.add(hintValue);
        return panel;
    }

    private JPanel settingsSection()
    {
        JPanel panel = card();
        panel.add(sectionLabel("Config summary"));
        panel.add(Box.createRigidArea(new Dimension(0, 6)));
        settingsValue.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        panel.add(settingsValue);
        return panel;
    }

    private void captureNow(ActionEvent event)
    {
        setHint("Capturing bank snapshot...");
        clientThread.invoke(() -> {
            boolean ok = snapshotService.captureNow() != null;
            SwingUtilities.invokeLater(() -> {
                if (ok)
                {
                    if (config.openDashboardAfterCapture())
                    {
                        dashboardServer.ensureStarted();
                        dashboardServer.openBrowser();
                    }
                    setHint("Snapshot captured. Refresh the browser if it is already open.");
                }
                else
                {
                    setHint("Open your bank first, then capture again.");
                }
                refreshStatus();
            });
        });
    }

    public void refreshStatus()
    {
        Runnable update = () -> {
            boolean running = dashboardServer.isRunning();
            String profile = snapshotService.getCurrentProfileKey();
            Path baseDir = snapshotService.getSnapshotStore().getBaseDir();

            statusValue.setText(valueHtml(running ? "Running" : "Stopped"));
            statusValue.setForeground(running ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);
            profileValue.setText(valueHtml(profile));
            dashboardValue.setText(valueHtml((running ? "Live" : "Offline") + " • " + dashboardServer.getBaseUrl()));
            dataValue.setText(valueHtml(baseDir.toString()));
            settingsValue.setText("<html><div style='width:250px'>"
                + bullet("Auto capture", yesNo(config.autoCapture()) + " after " + config.captureHour() + ":00")
                + bullet("Open dashboard after capture", yesNo(config.openDashboardAfterCapture()))
                + bullet("Auto-start dashboard", yesNo(config.autoStartDashboard()))
                + bullet("Open dashboard on startup", yesNo(config.openDashboardOnStartup()))
                + bullet("Port", Integer.toString(config.webPort()))
                + bullet("Export icons", yesNo(config.exportIcons()))
                + bullet("Ignore zero-value items", yesNo(config.ignoreZeroValueItems()))
                + "</div></html>");

            if (hintValue.getText() == null || hintValue.getText().trim().isEmpty())
            {
                setHint(running
                    ? "The local dashboard is ready."
                    : "Use Open dashboard or Restart dashboard to start the local site.");
            }
        };

        if (SwingUtilities.isEventDispatchThread())
        {
            update.run();
        }
        else
        {
            SwingUtilities.invokeLater(update);
        }
    }

    private JPanel card()
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setOpaque(true);
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ColorScheme.DARK_GRAY_HOVER_COLOR),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)
        ));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return panel;
    }

    private JLabel sectionLabel(String text)
    {
        JLabel label = new JLabel(text);
        label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        return label;
    }

    private JPanel infoRow(String labelText, JLabel valueLabel)
    {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        JLabel label = new JLabel(labelText);
        label.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
        valueLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        row.add(label, BorderLayout.WEST);
        row.add(valueLabel, BorderLayout.CENTER);
        return row;
    }

    private JButton button(String text)
    {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setMargin(new Insets(3, 8, 3, 8));
        button.setFont(button.getFont().deriveFont(Font.PLAIN, 12f));
        button.setPreferredSize(new Dimension(0, 24));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        return button;
    }

    private void openDataFolder()
    {
        if (!Desktop.isDesktopSupported())
        {
            setHint("Desktop integration is not available on this system.");
            return;
        }

        try
        {
            Desktop.getDesktop().open(snapshotService.getSnapshotStore().getBaseDir().toFile());
            setHint("Opened the data folder.");
        }
        catch (IOException ex)
        {
            setHint("Unable to open the data folder.");
        }
    }

    private void setHint(String text)
    {
        hintValue.setText("<html><div style='width:250px'>" + escape(text) + "</div></html>");
    }

    private String valueHtml(String text)
    {
        return "<html><div style='width:170px'>" + escape(text) + "</div></html>";
    }

    private String bullet(String key, String value)
    {
        return "&#8226; <b>" + escape(key) + ":</b> " + escape(value) + "<br/>";
    }

    private String yesNo(boolean value)
    {
        return value ? "Enabled" : "Disabled";
    }

    private String escape(String value)
    {
        if (value == null)
        {
            return "";
        }

        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }
}
