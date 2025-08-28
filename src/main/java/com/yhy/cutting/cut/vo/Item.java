package com.yhy.cutting.cut.vo;

public class Item {
    private String label;
    private double width;
    private double height;

    // 无参构造函数（JSON 反序列化需要）
    public Item() {}

    public Item(String label, double width, double height) {
        this.label = label;
        this.width = width;
        this.height = height;
    }

    // Getters and Setters
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public double getWidth() { return width; }
    public void setWidth(double width) { this.width = width; }

    public double getHeight() { return height; }
    public void setHeight(double height) { this.height = height; }
}
