package com.bankvaluehistory.service;

import com.bankvaluehistory.BankValueHistoryConfig;
import com.bankvaluehistory.model.BankSnapshot;
import com.bankvaluehistory.model.BankTabSnapshot;
import com.bankvaluehistory.model.ItemSnapshot;
import java.awt.Rectangle;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.Player;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class SnapshotService
{
    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);
    private static final int PLATINUM_TOKEN_ID = 13204;
    private static final Pattern GE_PATTERN = Pattern.compile("\\(GE:\\s*([0-9,]+)\\)");
    private static final Pattern HA_PATTERN = Pattern.compile("\\(HA:\\s*([0-9,]+)\\)");

    private final Client client;
    private final ItemManager itemManager;
    private final BankValueHistoryConfig config;
    private final SnapshotStore snapshotStore;
    private final IconExportService iconExportService;
    private final BankImageRenderer bankImageRenderer;
    private final WikiPriceService wikiPriceService;

    private boolean dirty;
    private Instant lastBankChange = Instant.EPOCH;

    @Inject
    public SnapshotService(
        Client client,
        ItemManager itemManager,
        BankValueHistoryConfig config,
        SnapshotStore snapshotStore,
        IconExportService iconExportService,
        BankImageRenderer bankImageRenderer,
        WikiPriceService wikiPriceService)
    {
        this.client = client;
        this.itemManager = itemManager;
        this.config = config;
        this.snapshotStore = snapshotStore;
        this.iconExportService = iconExportService;
        this.bankImageRenderer = bankImageRenderer;
        this.wikiPriceService = wikiPriceService;
    }

    public SnapshotStore getSnapshotStore()
    {
        return snapshotStore;
    }

    public void markDirty()
    {
        dirty = true;
        lastBankChange = Instant.now();
    }

    public String getCurrentProfileKey()
    {
        Player player = client.getLocalPlayer();
        if (player == null || player.getName() == null || player.getName().trim().isEmpty())
        {
            return "unknown";
        }

        return player.getName().replaceAll("[^a-zA-Z0-9 _-]", "_").trim();
    }

    public BankSnapshot captureNow()
    {
        return captureSnapshot(false);
    }

    public boolean maybeAutoCapture()
    {
        return captureSnapshot(true) != null;
    }

    private BankSnapshot captureSnapshot(boolean auto)
    {
        if (auto && !config.autoCapture())
        {
            return null;
        }

        String profileKey = getCurrentProfileKey();
        if ("unknown".equals(profileKey))
        {
            return null;
        }

        if (auto)
        {
            if (snapshotStore.hasSnapshotForDay(profileKey, LocalDate.now()))
            {
                return null;
            }

            if (LocalTime.now().getHour() < config.captureHour())
            {
                return null;
            }

            if (dirty && Duration.between(lastBankChange, Instant.now()).getSeconds() < 3)
            {
                return null;
            }
        }

        wikiPriceService.refreshAsyncIfStale(Duration.ofMinutes(5));

        ItemContainer bank = client.getItemContainer(InventoryID.BANK);
        if (bank == null)
        {
            return null;
        }

        BankSnapshot snapshot = buildSnapshot(profileKey, bank);
        try
        {
            snapshotStore.save(snapshot);
            dirty = false;
            return snapshot;
        }
        catch (IOException ex)
        {
            log.warn("Unable to save bank snapshot for {}", profileKey, ex);
            return null;
        }
    }

    private BankSnapshot buildSnapshot(String profileKey, ItemContainer bank)
    {
        Instant capturedAt = Instant.now();

        Widget bankItemWidget = client.getWidget(InterfaceID.Bankmain.ITEMS);
        Rectangle captureBounds = resolveBankCaptureBounds();
        List<LayoutEntry> layoutEntries = readLayoutEntries(bankItemWidget, captureBounds);

        List<PendingItem> pending = new ArrayList<>();
        long totalGe = 0L;
        long totalHa = 0L;

        for (Item item : bank.getItems())
        {
            if (item == null)
            {
                continue;
            }

            int itemId = item.getId();
            if (itemId <= 0 || itemId == ItemID.BANK_FILLER)
            {
                continue;
            }

            int quantity = Math.max(0, item.getQuantity());
            boolean placeholder = quantity == 0;
            int canonicalItemId = itemManager.canonicalize(itemId);
            String name = itemManager.getItemComposition(itemId).getName();

            int geUnit = 0;
            int haUnit = 0;
            long geTotal = 0L;
            long haTotal = 0L;

            if (!placeholder)
            {
                geUnit = Math.max(0, itemManager.getItemPrice(itemId));
                haUnit = Math.max(0, getHaPrice(itemId));
                geTotal = (long) geUnit * quantity;
                haTotal = (long) haUnit * quantity;
            }

            if (config.exportIcons())
            {
                iconExportService.exportIconIfMissing(canonicalItemId, Math.max(1, quantity));
            }

            pending.add(new PendingItem(
                itemId,
                canonicalItemId,
                name,
                quantity,
                geUnit,
                geTotal,
                haUnit,
                haTotal,
                "/icons/" + canonicalItemId + ".png",
                placeholder
            ));

            totalGe += geTotal;
            totalHa += haTotal;
        }

        if (client.getVarbitValue(VarbitID.BANK_CURRENTTAB) == 0)
        {
            ContainerPrices displayed = getDisplayedBankPrices();
            if (displayed == null)
            {
                displayed = getWidgetContainerPrices();
            }

            if (displayed != null)
            {
                totalGe = displayed.gePrice;
                totalHa = displayed.haPrice;
            }
        }

        BankOrder bankOrder = determineBankOrder(
            pending,
            pending.size(),
            getBankTabCounts(),
            readVisibleTabItems()
        );

        List<BankTabSnapshot> tabs = buildBankTabs(pending.size(), bankOrder);
        List<ItemSnapshot> items = assignItemsToTabs(pending, tabs, layoutEntries);

        BankImageRenderer.CaptureResult capture = bankImageRenderer.capture(profileKey, capturedAt, captureBounds);

        return new BankSnapshot(
            profileKey,
            LocalDate.now(),
            capturedAt,
            totalGe,
            totalHa,
            capture.getPath(),
            capture.getWidth(),
            capture.getHeight(),
            tabs,
            items
        );
    }

    private Rectangle resolveBankCaptureBounds()
    {
        Rectangle union = null;

        union = union(union, getWidgetBounds(InterfaceID.Bankmain.UNIVERSE));
        union = union(union, getWidgetBounds(InterfaceID.Bankmain.ITEMS_CONTAINER));
        union = union(union, getWidgetBounds(InterfaceID.Bankmain.TABS));
        union = union(union, getWidgetBounds(InterfaceID.Bankmain.TITLE));
        union = union(union, getWidgetBounds(InterfaceID.Bankmain.ITEMS));

        if (union == null)
        {
            return null;
        }

        return new Rectangle(
            union.x,
            union.y,
            Math.max(1, union.width),
            Math.max(1, union.height)
        );
    }

    private Rectangle getWidgetBounds(int componentId)
    {
        Widget widget = client.getWidget(componentId);
        if (widget == null)
        {
            return null;
        }

        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
        {
            return null;
        }

        return new Rectangle(bounds);
    }

    private Rectangle union(Rectangle a, Rectangle b)
    {
        if (a == null)
        {
            return b == null ? null : new Rectangle(b);
        }

        if (b == null)
        {
            return new Rectangle(a);
        }

        return a.union(b);
    }

    private ContainerPrices getDisplayedBankPrices()
    {
        Widget title = client.getWidget(InterfaceID.Bankmain.TITLE);
        if (title == null)
        {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        appendWidgetText(title, sb);
        String allText = sb.toString();
        if (allText.isEmpty())
        {
            return null;
        }

        Long ge = parseExactBankValue(allText, GE_PATTERN);
        Long ha = parseExactBankValue(allText, HA_PATTERN);
        if (ge == null && ha == null)
        {
            return null;
        }

        return new ContainerPrices(ge != null ? ge : 0L, ha != null ? ha : 0L);
    }

    private void appendWidgetText(Widget widget, StringBuilder sb)
    {
        if (widget == null)
        {
            return;
        }

        String text = widget.getText();
        if (text != null && !text.isEmpty())
        {
            if (sb.length() > 0)
            {
                sb.append(' ');
            }
            sb.append(text);
        }

        Widget[] children = widget.getChildren();
        if (children == null)
        {
            return;
        }

        for (Widget child : children)
        {
            appendWidgetText(child, sb);
        }
    }

    private Long parseExactBankValue(String text, Pattern pattern)
    {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find())
        {
            return null;
        }

        try
        {
            return Long.parseLong(matcher.group(1).replace(",", "").trim());
        }
        catch (Exception ex)
        {
            return null;
        }
    }

    private ContainerPrices getWidgetContainerPrices()
    {
        Widget widget = client.getWidget(InterfaceID.Bankmain.ITEMS);
        ItemContainer itemContainer = client.getItemContainer(InventoryID.BANK);
        if (widget == null || itemContainer == null)
        {
            return null;
        }

        Widget[] children = widget.getChildren();
        if (children == null)
        {
            return null;
        }

        long geTotal = 0L;
        long haTotal = 0L;
        int max = Math.min(itemContainer.size(), children.length);

        for (int i = 0; i < max; i++)
        {
            Widget child = children[i];
            if (child == null || child.isSelfHidden() || child.getItemId() <= -1)
            {
                continue;
            }

            int itemId = child.getItemId();
            int quantity = child.getItemQuantity();
            geTotal += (long) itemManager.getItemPrice(itemId) * quantity;
            haTotal += (long) getHaPrice(itemId) * quantity;
        }

        return new ContainerPrices(geTotal, haTotal);
    }

    private int getHaPrice(int itemId)
    {
        switch (itemId)
        {
            case ItemID.COINS:
                return 1;
            case PLATINUM_TOKEN_ID:
                return 1000;
            default:
                return itemManager.getItemComposition(itemId).getHaPrice();
        }
    }

    private int[] getBankTabCounts()
    {
        return new int[] {
            Math.max(0, client.getVarbitValue(VarbitID.BANK_TAB_1)),
            Math.max(0, client.getVarbitValue(VarbitID.BANK_TAB_2)),
            Math.max(0, client.getVarbitValue(VarbitID.BANK_TAB_3)),
            Math.max(0, client.getVarbitValue(VarbitID.BANK_TAB_4)),
            Math.max(0, client.getVarbitValue(VarbitID.BANK_TAB_5)),
            Math.max(0, client.getVarbitValue(VarbitID.BANK_TAB_6)),
            Math.max(0, client.getVarbitValue(VarbitID.BANK_TAB_7)),
            Math.max(0, client.getVarbitValue(VarbitID.BANK_TAB_8)),
            Math.max(0, client.getVarbitValue(VarbitID.BANK_TAB_9))
        };
    }

    private List<BankTabSnapshot> buildBankTabs(int totalSlots, BankOrder bankOrder)
    {
        int[] counts = getBankTabCounts();
        int assigned = 0;
        for (int count : counts)
        {
            assigned += count;
        }
        assigned = Math.min(totalSlots, assigned);

        int mainCount = Math.max(0, totalSlots - assigned);
        List<BankTabSnapshot> tabs = new ArrayList<>();

        if (bankOrder == BankOrder.MAIN_FIRST)
        {
            tabs.add(new BankTabSnapshot(0, "Main", mainCount));
        }

        int remaining = totalSlots - mainCount;
        for (int i = 0; i < counts.length; i++)
        {
            int count = Math.min(counts[i], Math.max(0, remaining));
            tabs.add(new BankTabSnapshot(i + 1, "Tab " + (i + 1), count));
            remaining -= count;
        }

        if (bankOrder == BankOrder.MAIN_LAST)
        {
            tabs.add(new BankTabSnapshot(0, "Main", mainCount));
        }

        return tabs;
    }

    private BankOrder determineBankOrder(
        List<PendingItem> pending,
        int totalSlots,
        int[] counts,
        List<VisibleItem> visibleTabItems)
    {
        int currentTab = client.getVarbitValue(VarbitID.BANK_CURRENTTAB);
        if (currentTab >= 1 && currentTab <= counts.length && !visibleTabItems.isEmpty())
        {
            int mainCount = Math.max(0, totalSlots - sum(counts));
            int targetCount = Math.max(0, counts[currentTab - 1]);
            if (targetCount == visibleTabItems.size())
            {
                int mainFirstStart = mainCount + sum(counts, 0, currentTab - 1);
                int mainLastStart = sum(counts, 0, currentTab - 1);

                boolean mainFirstMatches = matchesVisibleSegment(pending, visibleTabItems, mainFirstStart);
                boolean mainLastMatches = matchesVisibleSegment(pending, visibleTabItems, mainLastStart);

                if (mainLastMatches && !mainFirstMatches)
                {
                    return BankOrder.MAIN_LAST;
                }

                if (mainFirstMatches && !mainLastMatches)
                {
                    return BankOrder.MAIN_FIRST;
                }
            }
        }

        return BankOrder.MAIN_LAST;
    }

    private List<VisibleItem> readVisibleTabItems()
    {
        List<VisibleItem> visible = new ArrayList<>();
        Widget widget = client.getWidget(InterfaceID.Bankmain.ITEMS);
        if (widget == null)
        {
            return visible;
        }

        Widget[] children = widget.getChildren();
        if (children == null)
        {
            return visible;
        }

        for (Widget child : children)
        {
            if (child == null || child.isSelfHidden())
            {
                continue;
            }

            int itemId = child.getItemId();
            int quantity = Math.max(0, child.getItemQuantity());
            if (itemId <= 0 || itemId == ItemID.BANK_FILLER)
            {
                continue;
            }

            visible.add(new VisibleItem(itemId, quantity));
        }

        return visible;
    }

    private List<LayoutEntry> readLayoutEntries(Widget bankItemWidget, Rectangle captureBounds)
    {
        List<LayoutEntry> entries = new ArrayList<>();
        if (bankItemWidget == null || captureBounds == null)
        {
            return entries;
        }

        Widget[] children = bankItemWidget.getChildren();
        if (children == null)
        {
            return entries;
        }

        for (Widget child : children)
        {
            if (child == null)
            {
                continue;
            }

            int itemId = child.getItemId();
            if (itemId <= 0 || itemId == ItemID.BANK_FILLER)
            {
                continue;
            }

            Rectangle bounds = child.getBounds();
            if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
            {
                continue;
            }

            entries.add(new LayoutEntry(
                itemId,
                Math.max(0, child.getItemQuantity()),
                bounds.x - captureBounds.x,
                bounds.y - captureBounds.y,
                bounds.width,
                bounds.height
            ));
        }

        return entries;
    }

    private boolean matchesVisibleSegment(List<PendingItem> pending, List<VisibleItem> visibleItems, int startIndex)
    {
        if (startIndex < 0 || startIndex + visibleItems.size() > pending.size())
        {
            return false;
        }

        for (int offset = 0; offset < visibleItems.size(); offset++)
        {
            PendingItem snapshotItem = pending.get(startIndex + offset);
            VisibleItem visibleItem = visibleItems.get(offset);
            if (snapshotItem.itemId != visibleItem.itemId || snapshotItem.quantity != visibleItem.quantity)
            {
                return false;
            }
        }

        return true;
    }

    private int sum(int[] counts)
    {
        return sum(counts, 0, counts.length);
    }

    private int sum(int[] counts, int startInclusive, int endExclusive)
    {
        int total = 0;
        for (int i = startInclusive; i < endExclusive; i++)
        {
            total += Math.max(0, counts[i]);
        }
        return total;
    }

    private List<ItemSnapshot> assignItemsToTabs(
        List<PendingItem> pending,
        List<BankTabSnapshot> tabs,
        List<LayoutEntry> layoutEntries)
    {
        List<ItemSnapshot> items = new ArrayList<>(pending.size());
        int cursor = 0;
        int layoutCursor = 0;

        for (BankTabSnapshot tab : tabs)
        {
            for (int i = 0; i < tab.getCount() && cursor < pending.size(); i++)
            {
                PendingItem item = pending.get(cursor);
                LayoutEntry layout = matchLayoutEntry(item, layoutEntries, layoutCursor);
                if (layout != null)
                {
                    layoutCursor = layout.nextCursor;
                }

                items.add(new ItemSnapshot(
                    item.itemId,
                    item.canonicalItemId,
                    item.name,
                    item.quantity,
                    item.geUnitPrice,
                    item.geTotal,
                    item.haUnitPrice,
                    item.haTotal,
                    cursor,
                    tab.getIndex(),
                    tab.getName(),
                    item.iconPath,
                    item.placeholder,
                    layout != null ? layout.x : -1,
                    layout != null ? layout.y : -1,
                    layout != null ? layout.w : 0,
                    layout != null ? layout.h : 0
                ));
                cursor++;
            }
        }

        while (cursor < pending.size())
        {
            PendingItem item = pending.get(cursor);
            LayoutEntry layout = matchLayoutEntry(item, layoutEntries, layoutCursor);
            if (layout != null)
            {
                layoutCursor = layout.nextCursor;
            }

            items.add(new ItemSnapshot(
                item.itemId,
                item.canonicalItemId,
                item.name,
                item.quantity,
                item.geUnitPrice,
                item.geTotal,
                item.haUnitPrice,
                item.haTotal,
                cursor,
                0,
                "Main",
                item.iconPath,
                item.placeholder,
                layout != null ? layout.x : -1,
                layout != null ? layout.y : -1,
                layout != null ? layout.w : 0,
                layout != null ? layout.h : 0
            ));
            cursor++;
        }

        return items;
    }

    private LayoutEntry matchLayoutEntry(PendingItem item, List<LayoutEntry> entries, int startCursor)
    {
        for (int i = startCursor; i < entries.size(); i++)
        {
            LayoutEntry entry = entries.get(i);
            if (entry.itemId == item.itemId && entry.quantity == item.quantity)
            {
                return new LayoutEntry(entry.itemId, entry.quantity, entry.x, entry.y, entry.w, entry.h, i + 1);
            }
        }

        for (int i = startCursor; i < entries.size(); i++)
        {
            LayoutEntry entry = entries.get(i);
            if (entry.itemId == item.itemId)
            {
                return new LayoutEntry(entry.itemId, entry.quantity, entry.x, entry.y, entry.w, entry.h, i + 1);
            }
        }

        return null;
    }

    private static class PendingItem
    {
        private final int itemId;
        private final int canonicalItemId;
        private final int quantity;
        private final int geUnitPrice;
        private final int haUnitPrice;
        private final String name;
        private final String iconPath;
        private final long geTotal;
        private final long haTotal;
        private final boolean placeholder;

        private PendingItem(
            int itemId,
            int canonicalItemId,
            String name,
            int quantity,
            int geUnitPrice,
            long geTotal,
            int haUnitPrice,
            long haTotal,
            String iconPath,
            boolean placeholder)
        {
            this.itemId = itemId;
            this.canonicalItemId = canonicalItemId;
            this.name = name;
            this.quantity = quantity;
            this.geUnitPrice = geUnitPrice;
            this.geTotal = geTotal;
            this.haUnitPrice = haUnitPrice;
            this.haTotal = haTotal;
            this.iconPath = iconPath;
            this.placeholder = placeholder;
        }
    }

    private static class VisibleItem
    {
        private final int itemId;
        private final int quantity;

        private VisibleItem(int itemId, int quantity)
        {
            this.itemId = itemId;
            this.quantity = quantity;
        }
    }

    private static class LayoutEntry
    {
        private final int itemId;
        private final int quantity;
        private final int x;
        private final int y;
        private final int w;
        private final int h;
        private final int nextCursor;

        private LayoutEntry(int itemId, int quantity, int x, int y, int w, int h)
        {
            this(itemId, quantity, x, y, w, h, 0);
        }

        private LayoutEntry(int itemId, int quantity, int x, int y, int w, int h, int nextCursor)
        {
            this.itemId = itemId;
            this.quantity = quantity;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.nextCursor = nextCursor;
        }
    }

    private static class ContainerPrices
    {
        private final long gePrice;
        private final long haPrice;

        private ContainerPrices(long gePrice, long haPrice)
        {
            this.gePrice = gePrice;
            this.haPrice = haPrice;
        }
    }

    private enum BankOrder
    {
        MAIN_FIRST,
        MAIN_LAST
    }
}
