package com.bankvaluehistory.model;

public class ItemSnapshot
{
    private final int itemId;
    private final int canonicalItemId;
    private final String name;
    private final int quantity;
    private final int geUnitPrice;
    private final long geTotal;
    private final int haUnitPrice;
    private final long haTotal;
    private final int slotIndex;
    private final int tabIndex;
    private final String tabName;
    private final String iconPath;
    private final boolean placeholder;
    private final int layoutX;
    private final int layoutY;
    private final int layoutW;
    private final int layoutH;

    public ItemSnapshot(int itemId, int canonicalItemId, String name, int quantity, int geUnitPrice, long geTotal,
        int haUnitPrice, long haTotal, int slotIndex, int tabIndex, String tabName, String iconPath,
        boolean placeholder, int layoutX, int layoutY, int layoutW, int layoutH)
    {
        this.itemId = itemId;
        this.canonicalItemId = canonicalItemId;
        this.name = name;
        this.quantity = quantity;
        this.geUnitPrice = geUnitPrice;
        this.geTotal = geTotal;
        this.haUnitPrice = haUnitPrice;
        this.haTotal = haTotal;
        this.slotIndex = slotIndex;
        this.tabIndex = tabIndex;
        this.tabName = tabName;
        this.iconPath = iconPath;
        this.placeholder = placeholder;
        this.layoutX = layoutX;
        this.layoutY = layoutY;
        this.layoutW = layoutW;
        this.layoutH = layoutH;
    }

    public int getItemId() { return itemId; }
    public int getCanonicalItemId() { return canonicalItemId; }
    public String getName() { return name; }
    public int getQuantity() { return quantity; }
    public int getGeUnitPrice() { return geUnitPrice; }
    public long getGeTotal() { return geTotal; }
    public int getHaUnitPrice() { return haUnitPrice; }
    public long getHaTotal() { return haTotal; }
    public int getSlotIndex() { return slotIndex; }
    public int getTabIndex() { return tabIndex; }
    public String getTabName() { return tabName; }
    public String getIconPath() { return iconPath; }
    public boolean isPlaceholder() { return placeholder; }
    public int getLayoutX() { return layoutX; }
    public int getLayoutY() { return layoutY; }
    public int getLayoutW() { return layoutW; }
    public int getLayoutH() { return layoutH; }
}
