package com.yhy.cutting.cut.vo;

public class MaterialType {
    private String name;
    private double width;
    private double height;
    private int availableCount;

    public MaterialType(String name, double width, double height, int availableCount) {
        this.name = name;
        this.width = width;
        this.height = height;
        this.availableCount = availableCount;
    }

    // getters and setters
    public String getName() { return name; }
    public double getWidth() { return width; }
    public double getHeight() { return height; }
    public int getAvailableCount() { return availableCount; }
}
