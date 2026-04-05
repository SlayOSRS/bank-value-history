package com.bankvaluehistory.model;

public class BankTabSnapshot
{
    private final int index;
    private final String name;
    private final int count;

    public BankTabSnapshot(int index, String name, int count)
    {
        this.index = index;
        this.name = name;
        this.count = count;
    }

    public int getIndex()
    {
        return index;
    }

    public String getName()
    {
        return name;
    }

    public int getCount()
    {
        return count;
    }
}
