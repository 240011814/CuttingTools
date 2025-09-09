package com.yhy.cutting.cut.service;

import com.yhy.cutting.cut.vo.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Guillotine + Best-Fit 切割排样算法
 * - 每次切割必须贯穿板材（横切/竖切）
 * - 支持旋转
 * - 按面积降序排序
 * - Best-Fit: 选择切割后 waste 最小的方案
 */
@Service
public class GuillotineCuttingService implements IPlaneService {

    public List<BinResult> optimize(BinRequest request) {
        List<Item> items = request.getItems();
        if (items == null || items.isEmpty()) return Collections.emptyList();

        double binWidth = request.getWidth().doubleValue();
        double binHeight = request.getHeight().doubleValue();

        // 过滤无效件 + 按面积降序排序
        List<Item> sortedItems = items.stream()
                .filter(it -> it != null && it.getWidth() > 0 && it.getHeight() > 0)
                .sorted((a, b) -> {
                    double areaA = a.getWidth() * a.getHeight();
                    double areaB = b.getWidth() * b.getHeight();
                    int cmp = Double.compare(areaB, areaA);
                    if (cmp != 0) return cmp;
                    return Double.compare(b.getHeight(), a.getHeight());
                })
                .toList();

        List<BinResult> results = new ArrayList<>();
        int binId = 0;

        List<GuillotineBin> bins = new ArrayList<>();

        for (Item item : sortedItems) {
            boolean placed = false;

            // 尝试放入现有板材
            for (int i = 0; i < bins.size(); i++) {
                GuillotineBin bin = bins.get(i);
                GuillotineBin.Placement placement = bin.insert(item);
                if (placement != null) {
                    BinResult br = results.get(i);
                    Piece p = createPiece(item, placement);
                    br.getPieces().add(p);
                    placed = true;
                    break;
                }
            }

            // 若无板材可放，新开板材
            if (!placed) {
                GuillotineBin newBin = new GuillotineBin(binWidth, binHeight);
                GuillotineBin.Placement placement = newBin.insert(item);
                if (placement != null) {
                    bins.add(newBin);
                    BinResult br = createNewBin(binId++, request);
                    Piece p = createPiece(item, placement);
                    br.getPieces().add(p);
                    results.add(br);
                } else {
                    System.err.println("❌ 无法放置 item: " + item.getLabel() + " 尺寸过大");
                }
            }
        }

        // 计算利用率
        for (BinResult br : results) {
            calculateUtilization(br);
        }

        return results;
    }

    @Override
    public String getName() {
        return "Guillotine";
    }

    // ========== GuillotineBin 核心类 ==========

    public static class GuillotineBin {
        private double width, height;
        private List<FreeRectangle> freeRects;

        public GuillotineBin(double width, double height) {
            this.width = width;
            this.height = height;
            this.freeRects = new ArrayList<>();
            this.freeRects.add(new FreeRectangle(0, 0, width, height));
        }

        // 返回放置结果（含是否旋转、切割方式）
        public Placement insert(Item item) {
            double w = item.getWidth();
            double h = item.getHeight();

            Placement bestPlacement = null;
            double bestWaste = Double.MAX_VALUE;

            // 尝试每个自由矩形
            for (int i = 0; i < freeRects.size(); i++) {
                FreeRectangle rect = freeRects.get(i);

                // 尝试不旋转
                if (w <= rect.width && h <= rect.height) {
                    Placement placement = new Placement(rect.x, rect.y, w, h, false, i);
                    double waste = calculateWaste(rect, w, h);
                    if (waste < bestWaste) {
                        bestWaste = waste;
                        bestPlacement = placement;
                    }
                }

                // 尝试旋转
                if (h <= rect.width && w <= rect.height) {
                    Placement placement = new Placement(rect.x, rect.y, h, w, true, i);
                    double waste = calculateWaste(rect, h, w);
                    if (waste < bestWaste) {
                        bestWaste = waste;
                        bestPlacement = placement;
                    }
                }
            }

            if (bestPlacement != null) {
                // 执行切割
                FreeRectangle target = freeRects.get(bestPlacement.rectIndex);
                freeRects.remove(bestPlacement.rectIndex);

                double placedW = bestPlacement.rotated ? item.getHeight() : item.getWidth();
                double placedH = bestPlacement.rotated ? item.getWidth() : item.getHeight();

                // Guillotine 切割：横切或竖切，选择剩余区域更“方正”的方案
                FreeRectangle cut1 = null, cut2 = null;

                // 方案1：竖切（按宽度切）
                if (target.width > placedW) {
                    cut1 = new FreeRectangle(target.x + placedW, target.y, target.width - placedW, placedH);
                    cut2 = new FreeRectangle(target.x, target.y + placedH, target.width, target.height - placedH);
                }

                // 方案2：横切（按高度切）
                if (target.height > placedH) {
                    FreeRectangle alt1 = new FreeRectangle(target.x, target.y + placedH, placedW, target.height - placedH);
                    FreeRectangle alt2 = new FreeRectangle(target.x + placedW, target.y, target.width - placedW, target.height);
                    // 选择更“方正”的切割方案（长宽比更接近1）
                    if (cut1 == null || isMoreSquare(alt1, alt2, cut1, cut2)) {
                        cut1 = alt1;
                        cut2 = alt2;
                    }
                }

                // 添加新自由矩形（非空）
                if (cut1 != null && cut1.area() > 0) freeRects.add(cut1);
                if (cut2 != null && cut2.area() > 0) freeRects.add(cut2);

                return bestPlacement;
            }

            return null;
        }

        private double calculateWaste(FreeRectangle rect, double w, double h) {
            return rect.area() - w * h;
        }

        // 判断方案2是否比方案1更“方正”
        private boolean isMoreSquare(FreeRectangle a1, FreeRectangle a2, FreeRectangle b1, FreeRectangle b2) {
            double ratioA = getSquareRatio(a1) + getSquareRatio(a2);
            double ratioB = getSquareRatio(b1) + getSquareRatio(b2);
            return ratioA < ratioB; // 越小越方正
        }

        private double getSquareRatio(FreeRectangle r) {
            if (r == null || r.area() == 0) return 0;
            double ratio = Math.max(r.width, r.height) / Math.min(r.width, r.height);
            return ratio;
        }

        // ========== 内部类 ==========

        public static class FreeRectangle {
            double x, y, width, height;

            public FreeRectangle(double x, double y, double width, double height) {
                this.x = x;
                this.y = y;
                this.width = width;
                this.height = height;
            }

            public double area() {
                return width * height;
            }
        }

        public static class Placement {
            public double x, y, w, h;
            public boolean rotated;
            public int rectIndex; // 在 freeRects 中的索引

            public Placement(double x, double y, double w, double h, boolean rotated, int rectIndex) {
                this.x = x;
                this.y = y;
                this.w = w;
                this.h = h;
                this.rotated = rotated;
                this.rectIndex = rectIndex;
            }
        }
    }

    // ========== 工具方法 ==========

    private Piece createPiece(Item item, GuillotineBin.Placement placement) {
        Piece p = new Piece();
        p.setLabel(item.getLabel());
        p.setX(placement.x);
        p.setY(placement.y);
        p.setW(placement.w);
        p.setH(placement.h);
        p.setRotated(placement.rotated);
        return p;
    }

    private BinResult createNewBin(int binId, BinRequest request) {
        BinResult br = new BinResult();
        br.setBinId(binId);
        br.setMaterialType("新板材");
        br.setMaterialWidth(request.getWidth().doubleValue());
        br.setMaterialHeight(request.getHeight().doubleValue());
        br.setPieces(new ArrayList<>());
        return br;
    }

    private void calculateUtilization(BinResult br) {
        double usedArea = br.getPieces().stream()
                .mapToDouble(p -> p.getW() * p.getH())
                .sum();
        double totalArea = br.getMaterialWidth() * br.getMaterialHeight();
        double utilization = totalArea > 0 ? (usedArea / totalArea) * 100 : 0;
        br.setUtilization(Math.round(utilization * 100.0) / 100.0);
    }
}