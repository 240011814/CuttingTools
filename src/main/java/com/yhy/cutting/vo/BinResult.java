// 在 BinResult.java 中添加字段
package com.yhy.cutting.vo;

import java.util.List;

public class BinResult {
    private int binId;
    private String materialType; // 新增：材料类型
    private double materialWidth; // 新增：材料宽度
    private double materialHeight; // 新增：材料高度
    private List<Piece> pieces;
    private double utilization;

    // 原有getter和setter
    public int getBinId() {
        return binId;
    }

    public void setBinId(int binId) {
        this.binId = binId;
    }

    public List<Piece> getPieces() {
        return pieces;
    }

    public void setPieces(List<Piece> pieces) {
        this.pieces = pieces;
    }

    public double getUtilization() {
        return utilization;
    }

    public void setUtilization(double utilization) {
        this.utilization = utilization;
    }

    // 新增getter和setter
    public String getMaterialType() {
        return materialType;
    }

    public void setMaterialType(String materialType) {
        this.materialType = materialType;
    }

    public double getMaterialWidth() {
        return materialWidth;
    }

    public void setMaterialWidth(double materialWidth) {
        this.materialWidth = materialWidth;
    }

    public double getMaterialHeight() {
        return materialHeight;
    }

    public void setMaterialHeight(double materialHeight) {
        this.materialHeight = materialHeight;
    }
}