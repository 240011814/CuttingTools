package com.yhy.cutting.cut.vo;

public class MaterialType {
    private String label;
    private double width;
    private double height;
    private int quantity;

    public MaterialType(String label, double width, double height, int quantity) {
        this.label = label;
        this.width = width;
        this.height = height;
        this.quantity = quantity;
    }

    // getters and setters
    public String getLabel() { return label; }
    public double getWidth() { return width; }
    public double getHeight() { return height; }
    public int getQuantity() { return quantity; }
}
