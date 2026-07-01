package com.bankvaluehistory.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BankSnapshot
{
    private final String profileKey;
    private final LocalDate day;
    private final Instant capturedAt;
    private final long totalGe;
    private final long totalHa;
    private final long bankGe;
    private final long bankHa;
    private final long inventoryGe;
    private final long inventoryHa;
    private final long equipmentGe;
    private final long equipmentHa;
    private final long combinedGe;
    private final long combinedHa;
    private final String bankImagePath;
    private final int bankImageWidth;
    private final int bankImageHeight;
    private final List<BankTabSnapshot> bankTabs;
    private final List<ItemSnapshot> items;
    private final List<ItemSnapshot> inventoryItems;
    private final List<ItemSnapshot> equipmentItems;

    public BankSnapshot(String profileKey, LocalDate day, Instant capturedAt, long totalGe, long totalHa,
        long bankGe, long bankHa, long inventoryGe, long inventoryHa, long equipmentGe, long equipmentHa,
        long combinedGe, long combinedHa, String bankImagePath, int bankImageWidth, int bankImageHeight,
        List<BankTabSnapshot> bankTabs, List<ItemSnapshot> items, List<ItemSnapshot> inventoryItems, List<ItemSnapshot> equipmentItems)
    {
        this.profileKey = profileKey;
        this.day = day;
        this.capturedAt = capturedAt;
        this.totalGe = totalGe;
        this.totalHa = totalHa;
        this.bankGe = bankGe;
        this.bankHa = bankHa;
        this.inventoryGe = inventoryGe;
        this.inventoryHa = inventoryHa;
        this.equipmentGe = equipmentGe;
        this.equipmentHa = equipmentHa;
        this.combinedGe = combinedGe;
        this.combinedHa = combinedHa;
        this.bankImagePath = bankImagePath;
        this.bankImageWidth = bankImageWidth;
        this.bankImageHeight = bankImageHeight;
        this.bankTabs = Collections.unmodifiableList(new ArrayList<>(bankTabs));
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.inventoryItems = Collections.unmodifiableList(new ArrayList<>(inventoryItems));
        this.equipmentItems = Collections.unmodifiableList(new ArrayList<>(equipmentItems));
    }

    public String getProfileKey() { return profileKey; }
    public LocalDate getDay() { return day; }
    public Instant getCapturedAt() { return capturedAt; }
    public long getTotalGe() { return totalGe; }
    public long getTotalHa() { return totalHa; }
    public long getBankGe() { return bankGe; }
    public long getBankHa() { return bankHa; }
    public long getInventoryGe() { return inventoryGe; }
    public long getInventoryHa() { return inventoryHa; }
    public long getEquipmentGe() { return equipmentGe; }
    public long getEquipmentHa() { return equipmentHa; }
    public long getCombinedGe() { return combinedGe; }
    public long getCombinedHa() { return combinedHa; }
    public String getBankImagePath() { return bankImagePath; }
    public int getBankImageWidth() { return bankImageWidth; }
    public int getBankImageHeight() { return bankImageHeight; }
    public List<BankTabSnapshot> getBankTabs() { return bankTabs; }
    public List<ItemSnapshot> getItems() { return items; }
    public List<ItemSnapshot> getInventoryItems() { return inventoryItems; }
    public List<ItemSnapshot> getEquipmentItems() { return equipmentItems; }
}
