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
    private final String bankImagePath;
    private final int bankImageWidth;
    private final int bankImageHeight;
    private final List<BankTabSnapshot> bankTabs;
    private final List<ItemSnapshot> items;

    public BankSnapshot(String profileKey, LocalDate day, Instant capturedAt, long totalGe, long totalHa,
        String bankImagePath, int bankImageWidth, int bankImageHeight, List<BankTabSnapshot> bankTabs, List<ItemSnapshot> items)
    {
        this.profileKey = profileKey;
        this.day = day;
        this.capturedAt = capturedAt;
        this.totalGe = totalGe;
        this.totalHa = totalHa;
        this.bankImagePath = bankImagePath;
        this.bankImageWidth = bankImageWidth;
        this.bankImageHeight = bankImageHeight;
        this.bankTabs = Collections.unmodifiableList(new ArrayList<>(bankTabs));
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    public String getProfileKey() { return profileKey; }
    public LocalDate getDay() { return day; }
    public Instant getCapturedAt() { return capturedAt; }
    public long getTotalGe() { return totalGe; }
    public long getTotalHa() { return totalHa; }
    public String getBankImagePath() { return bankImagePath; }
    public int getBankImageWidth() { return bankImageWidth; }
    public int getBankImageHeight() { return bankImageHeight; }
    public List<BankTabSnapshot> getBankTabs() { return bankTabs; }
    public List<ItemSnapshot> getItems() { return items; }
}
