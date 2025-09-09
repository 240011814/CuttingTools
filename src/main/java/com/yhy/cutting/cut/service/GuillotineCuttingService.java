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
            FreeRectangle bestRect = null;
            int bestRectIndex = -1;
            final double EPS = 1e-6;

            // 全局最优比较（primary waste, secondary minLeftover, tertiary maxLeftover, then prefer rotated）
            for (int i = 0; i < freeRects.size(); i++) {
                FreeRectangle rect = freeRects.get(i);

                List<Placement> candidates = new ArrayList<>();
                // 不旋转
                if (w <= rect.width + EPS && h <= rect.height + EPS) {
                    candidates.add(new Placement(rect.x, rect.y, w, h, false, i));
                }
                // 旋转
                if (h <= rect.width + EPS && w <= rect.height + EPS) {
                    candidates.add(new Placement(rect.x, rect.y, h, w, true, i));
                }

                for (Placement cand : candidates) {
                    if (bestPlacement == null) {
                        bestPlacement = cand;
                        bestRect = rect;
                        bestRectIndex = i;
                    } else {
                        double[] candScore = placementScore(rect, cand);
                        double[] bestScore = placementScore(bestRect, bestPlacement);
                        int cmp = compareScore(candScore, bestScore, EPS);
                        if (cmp < 0) { // cand 更优
                            bestPlacement = cand;
                            bestRect = rect;
                            bestRectIndex = i;
                        }
                    }
                }
            }

            if (bestPlacement != null) {
                // 使用选中的 freeRect（注意：rectIndex 在 candidate 中已设置为当时的 i）
                FreeRectangle target = freeRects.get(bestPlacement.rectIndex);
                freeRects.remove(bestPlacement.rectIndex);

                double placedW = bestPlacement.w;
                double placedH = bestPlacement.h;

                // Guillotine 切割：横切或竖切，选择剩余区域更“方正”的方案
                FreeRectangle cut1 = null, cut2 = null;

                // 方案1：竖切（按宽度切）
                if (target.width > placedW + 1e-9) {
                    cut1 = new FreeRectangle(target.x + placedW, target.y, target.width - placedW, placedH);
                    cut2 = new FreeRectangle(target.x, target.y + placedH, target.width, target.height - placedH);
                }

                // 方案2：横切（按高度切）
                if (target.height > placedH + 1e-9) {
                    FreeRectangle alt1 = new FreeRectangle(target.x, target.y + placedH, placedW, target.height - placedH);
                    FreeRectangle alt2 = new FreeRectangle(target.x + placedW, target.y, target.width - placedW, target.height);
                    if (cut1 == null || isMoreSquare(alt1, alt2, cut1, cut2)) {
                        cut1 = alt1;
                        cut2 = alt2;
                    }
                }

                if (cut1 != null && cut1.area() > 0) freeRects.add(cut1);
                if (cut2 != null && cut2.area() > 0) freeRects.add(cut2);

                return bestPlacement;
            }

            return null;
        }

        private double[] placementScore(FreeRectangle rect, Placement p) {
            // [0]=waste, [1]=minLeftover, [2]=maxLeftover, [3]=rotPref (越小越优，旋转优先)
            double waste = calculateWaste(rect, p.w, p.h);
            double leftoverW = rect.width - p.w;
            double leftoverH = rect.height - p.h;
            double minLeft = Math.min(leftoverW, leftoverH);
            double maxLeft = Math.max(leftoverW, leftoverH);
            double rotPref = p.rotated ? 0.0 : 1.0;
            return new double[]{waste, minLeft, maxLeft, rotPref};
        }

        private int compareScore(double[] a, double[] b, double EPS) {
            // 按先后优先级比较：waste, minLeft, maxLeft, rotPref（越小越好）
            for (int i = 0; i < 3; i++) {
                double diff = a[i] - b[i];
                if (Math.abs(diff) > EPS) return diff < 0 ? -1 : 1;
            }
            // 最后比较 rotPref
            double diff = a[3] - b[3];
            if (Math.abs(diff) > EPS) return diff < 0 ? -1 : 1;
            return 0;
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
            if (r == null || r.area() == 0) return Double.MAX_VALUE;
            double ratio = Math.max(r.width, r.height) / Math.min(r.width, r.height);
            return ratio;
        }

        // ========== 内部类 ==========
        public static class FreeRectangle {
            double x, y, width, height;
            public FreeRectangle(double x, double y, double width, double height) {
                this.x = x; this.y = y; this.width = width; this.height = height;
            }
            public double area() { return width * height; }
        }

        public static class Placement {
            public double x, y, w, h;
            public boolean rotated;
            public int rectIndex;
            public Placement(double x, double y, double w, double h, boolean rotated, int rectIndex) {
                this.x = x; this.y = y; this.w = w; this.h = h; this.rotated = rotated; this.rectIndex = rectIndex;
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