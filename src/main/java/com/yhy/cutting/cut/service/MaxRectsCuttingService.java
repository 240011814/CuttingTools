package com.yhy.cutting.cut.service;

import com.yhy.cutting.cut.vo.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class MaxRectsCuttingService implements IPlaneService{

    private static final int SCALE = 2;

    public List<BinResult> optimize(BinRequest request) {
        List<Item> items = request.getItems();
        List<MaterialType> availableMaterials = request.getMaterials();
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建可用材料实例列表
        List<MaterialInstance> materials = new ArrayList<>();
        for (MaterialType m : availableMaterials) {
            int count = Math.max(0, m.getQuantity());
            for (int i = 0; i < count; i++) {
                materials.add(new MaterialInstance(m.getLabel(), m.getWidth(), m.getHeight(), 10));
            }
        }

        // 添加备用材料
        for (int i = 0; i < 100; i++) {
            materials.add(new MaterialInstance("新板材", request.getWidth().longValue(), request.getHeight().longValue(), 0));
        }

        // 按优先级+面积排序
        materials.sort((a, b) -> {
            int cmp = Integer.compare(b.priority, a.priority);
            if (cmp != 0) return cmp;
            BigDecimal areaB = b.width.multiply(b.height);
            BigDecimal areaA = a.width.multiply(a.height);
            return areaB.compareTo(areaA);
        });

        // 按面积从大到小排序物品
        List<Item> itemsSorted = new ArrayList<>(items);
        itemsSorted.sort((a, b) -> {
            BigDecimal areaB = BigDecimal.valueOf(b.getWidth()).multiply(BigDecimal.valueOf(b.getHeight()));
            BigDecimal areaA = BigDecimal.valueOf(a.getWidth()).multiply(BigDecimal.valueOf(a.getHeight()));
            return areaB.compareTo(areaA);
        });

        List<BinResult> results = new ArrayList<>();
        List<MaxRectsBin> bins = new ArrayList<>();
        int binId = 0;

        for (Item item : itemsSorted) {
            boolean placed = false;

            // 尝试放入已有 bin
            for (int i = 0; i < bins.size(); i++) {
                MaxRectsBin bin = bins.get(i);
                MaxRectsBin.Rect rect = bin.insert(
                        BigDecimal.valueOf(item.getWidth()),
                        BigDecimal.valueOf(item.getHeight()),
                        true
                );
                if (rect != null) {
                    results.get(i).getPieces().add(createPiece(item, rect));
                    placed = true;
                    break;
                }
            }

            // 放不下则选择新的材料开 bin
            if (!placed) {
                MaterialInstance selectedMaterial = null;
                for (MaterialInstance m : materials) {
                    if (BigDecimal.valueOf(item.getWidth()).compareTo(m.width) <= 0 &&
                            BigDecimal.valueOf(item.getHeight()).compareTo(m.height) <= 0) {
                        selectedMaterial = m;
                        break;
                    }
                }
                if (selectedMaterial == null) continue;

                MaxRectsBin bin = new MaxRectsBin(selectedMaterial.width, selectedMaterial.height);
                MaxRectsBin.Rect rect = bin.insert(
                        BigDecimal.valueOf(item.getWidth()),
                        BigDecimal.valueOf(item.getHeight()),
                        true
                );
                if (rect != null) {
                    bins.add(bin);

                    BinResult br = new BinResult();
                    br.setBinId(binId++);
                    br.setMaterialType(selectedMaterial.name);
                    br.setMaterialWidth(selectedMaterial.width.doubleValue());
                    br.setMaterialHeight(selectedMaterial.height.doubleValue());
                    br.setPieces(new ArrayList<>(Collections.singletonList(createPiece(item, rect))));
                    results.add(br);

                    materials.remove(selectedMaterial);
                }
            }
        }

        // 更新利用率
        for (BinResult br : results) {
            BigDecimal usedArea = br.getPieces().stream()
                    .map(p -> BigDecimal.valueOf(p.getW()).multiply(BigDecimal.valueOf(p.getH())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalArea = BigDecimal.valueOf(br.getMaterialWidth())
                    .multiply(BigDecimal.valueOf(br.getMaterialHeight()));
            BigDecimal utilization = usedArea.divide(totalArea, SCALE + 2, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            br.setUtilization(utilization.doubleValue());
        }

        return results;
    }

    @Override
    public String getName() {
        return "MaxRects";
    }

    private Piece createPiece(Item item, MaxRectsBin.Rect rect) {
        Piece p = new Piece();
        p.setLabel(item.getLabel());
        p.setX(rect.x.doubleValue());
        p.setY(rect.y.doubleValue());
        p.setW(rect.width.doubleValue());
        p.setH(rect.height.doubleValue());
        p.setRotated(rect.rotated);
        return p;
    }

    private static class MaxRectsBin {
        private BigDecimal width, height;
        private List<Rect> freeRectangles;

        public MaxRectsBin(BigDecimal width, BigDecimal height) {
            this.width = width;
            this.height = height;
            freeRectangles = new ArrayList<>();
            freeRectangles.add(new Rect(BigDecimal.ZERO, BigDecimal.ZERO, width, height, false));
        }

        public Rect insert(BigDecimal w, BigDecimal h, boolean allowRotate) {
            Rect bestRect = null;
            BigDecimal bestShortSideFit = new BigDecimal(Double.MAX_VALUE);
            BigDecimal bestLongSideFit = new BigDecimal(Double.MAX_VALUE);

            freeRectangles.sort((a, b) -> {
                int cmp = a.y.compareTo(b.y);
                return cmp != 0 ? cmp : a.x.compareTo(b.x);
            });

            for (Rect free : freeRectangles) {
                // 尝试不旋转
                if (w.compareTo(free.width) <= 0 && h.compareTo(free.height) <= 0) {
                    BigDecimal leftoverHoriz = free.width.subtract(w).abs();
                    BigDecimal leftoverVert = free.height.subtract(h).abs();
                    BigDecimal shortSideFit = leftoverHoriz.min(leftoverVert);
                    BigDecimal longSideFit = leftoverHoriz.max(leftoverVert);

                    if (shortSideFit.compareTo(bestShortSideFit) < 0 ||
                            (shortSideFit.compareTo(bestShortSideFit) == 0 && longSideFit.compareTo(bestLongSideFit) < 0)) {
                        bestRect = new Rect(free.x, free.y, w, h, false);
                        bestShortSideFit = shortSideFit;
                        bestLongSideFit = longSideFit;
                    }
                }

                // 尝试旋转
                if (allowRotate && h.compareTo(free.width) <= 0 && w.compareTo(free.height) <= 0) {
                    BigDecimal leftoverHoriz = free.width.subtract(h).abs();
                    BigDecimal leftoverVert = free.height.subtract(w).abs();
                    BigDecimal shortSideFit = leftoverHoriz.min(leftoverVert);
                    BigDecimal longSideFit = leftoverHoriz.max(leftoverVert);

                    if (shortSideFit.compareTo(bestShortSideFit) < 0 ||
                            (shortSideFit.compareTo(bestShortSideFit) == 0 && longSideFit.compareTo(bestLongSideFit) < 0)) {
                        bestRect = new Rect(free.x, free.y, h, w, true);
                        bestShortSideFit = shortSideFit;
                        bestLongSideFit = longSideFit;
                    }
                }
            }

            if (bestRect != null) placeRect(bestRect);
            return bestRect;
        }


        private void placeRect(Rect rect) {
            List<Rect> newFree = new ArrayList<>();
            for (Rect free : freeRectangles) {
                if (!intersect(free, rect)) newFree.add(free);
                else splitFreeRectangle(free, rect, newFree);
            }
            freeRectangles = newFree;
            pruneFreeList();
        }

        private void splitFreeRectangle(Rect free, Rect placed, List<Rect> newFree) {
            BigDecimal zero = BigDecimal.ZERO;

            if (placed.x.compareTo(free.x.add(free.width)) < 0 && placed.x.add(placed.width).compareTo(free.x) > 0) {
                if (placed.y.compareTo(free.y) > 0) {
                    BigDecimal height = placed.y.subtract(free.y);
                    if (height.compareTo(zero) > 0) newFree.add(new Rect(free.x, free.y, free.width, height, false));
                }
                if (placed.y.add(placed.height).compareTo(free.y.add(free.height)) < 0) {
                    BigDecimal height = free.y.add(free.height).subtract(placed.y.add(placed.height));
                    if (height.compareTo(zero) > 0) newFree.add(new Rect(free.x, placed.y.add(placed.height), free.width, height, false));
                }
            }

            if (placed.y.compareTo(free.y.add(free.height)) < 0 && placed.y.add(placed.height).compareTo(free.y) > 0) {
                if (placed.x.compareTo(free.x) > 0) {
                    BigDecimal width = placed.x.subtract(free.x);
                    if (width.compareTo(zero) > 0) newFree.add(new Rect(free.x, free.y, width, free.height, false));
                }
                if (placed.x.add(placed.width).compareTo(free.x.add(free.width)) < 0) {
                    BigDecimal width = free.x.add(free.width).subtract(placed.x.add(placed.width));
                    if (width.compareTo(zero) > 0) newFree.add(new Rect(placed.x.add(placed.width), free.y, width, free.height, false));
                }
            }
        }

        private void pruneFreeList() {
            for (int i = 0; i < freeRectangles.size(); i++) {
                Rect a = freeRectangles.get(i);
                boolean removed = false;
                for (int j = 0; j < freeRectangles.size(); j++) {
                    if (i == j) continue;
                    Rect b = freeRectangles.get(j);
                    if (isContainedIn(a, b)) { freeRectangles.remove(i); i--; removed = true; break; }
                }
                if (!removed) {
                    for (int j = i + 1; j < freeRectangles.size(); j++) {
                        Rect b = freeRectangles.get(j);
                        if (isContainedIn(b, a)) { freeRectangles.remove(j); j--; }
                    }
                }
            }
        }

        private boolean intersect(Rect a, Rect b) {
            return !(b.x.compareTo(a.x.add(a.width)) >= 0 ||
                    b.x.add(b.width).compareTo(a.x) <= 0 ||
                    b.y.compareTo(a.y.add(a.height)) >= 0 ||
                    b.y.add(b.height).compareTo(a.y) <= 0);
        }

        private boolean isContainedIn(Rect a, Rect b) {
            return a.x.compareTo(b.x) >= 0 && a.y.compareTo(b.y) >= 0 &&
                    a.x.add(a.width).compareTo(b.x.add(b.width)) <= 0 &&
                    a.y.add(a.height).compareTo(b.y.add(b.height)) <= 0;
        }

        public static class Rect {
            public BigDecimal x, y, width, height;
            public boolean rotated;

            public Rect(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height, boolean rotated) {
                this.x = x.setScale(SCALE, RoundingMode.HALF_UP);
                this.y = y.setScale(SCALE, RoundingMode.HALF_UP);
                this.width = width.setScale(SCALE, RoundingMode.HALF_UP);
                this.height = height.setScale(SCALE, RoundingMode.HALF_UP);
                this.rotated = rotated;
            }
        }
    }

    private static class MaterialInstance {
        public String name;
        public BigDecimal width, height;
        public int priority;

        public MaterialInstance(String name, double w, double h, int priority) {
            this.name = name;
            this.width = BigDecimal.valueOf(w).setScale(SCALE, RoundingMode.HALF_UP);
            this.height = BigDecimal.valueOf(h).setScale(SCALE, RoundingMode.HALF_UP);
            this.priority = priority;
        }
    }
}
