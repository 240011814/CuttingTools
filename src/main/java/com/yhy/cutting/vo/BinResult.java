package com.yhy.cutting.vo;

import java.util.List;

public class BinResult {
    private int binId;
    private List<Piece> pieces;
    private double utilization; // %

    public BinResult() {}

    // Getters and Setters
    public int getBinId() { return binId; }
    public void setBinId(int binId) { this.binId = binId; }

    public List<Piece> getPieces() { return pieces; }
    public void setPieces(List<Piece> pieces) { this.pieces = pieces; }

    public double getUtilization() { return utilization; }
    public void setUtilization(double utilization) { this.utilization = utilization; }
}
