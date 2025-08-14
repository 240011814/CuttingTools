package com.yhy.cutting.service;

import com.yhy.cutting.vo.BinResult;
import com.yhy.cutting.vo.Item;
import com.yhy.cutting.vo.MaterialType;
import com.yhy.cutting.vo.Piece;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MaxRectsCuttingService {

    public List<BinResult> optimize(List<Item> items, List<MaterialType> availableMaterials) {
        if (items == null || items.isEmpty() || availableMaterials == null || availableMaterials.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建可用材料实例列表
        List<MaterialInstance> materials = new ArrayList<>();
        for (MaterialType m : availableMaterials) {
            int count = Math.max(0, m.getAvailableCount());
            for (int i = 0; i < count; i++) {
                materials.add(new MaterialInstance(m.getName(), m.getWidth(), m.getHeight(), 10));
            }
        }

        // 添加备用材料
        for (int i = 0; i < 100; i++) {
            materials.add(new MaterialInstance("新2x2板材", 2.0, 2.0, 0));
        }

        // 按优先级+面积排序
        materials.sort((a, b) -> {
            int cmp = Integer.compare(b.priority, a.priority);
            if (cmp != 0) return cmp;
            return Double.compare(b.width * b.height, a.width * a.height);
        });

        // 按面积从大到小排序物品
        List<Item> itemsSorted = new ArrayList<>(items);
        itemsSorted.sort((a, b) -> Double.compare(b.getWidth() * b.getHeight(), a.getWidth() * a.getHeight()));

        List<BinResult> results = new ArrayList<>();
        List<MaxRectsBin> bins = new ArrayList<>();
        int binId = 0;

        for (Item item : itemsSorted) {
            boolean placed = false;

            // 尝试放入已有 bin
            for (int i = 0; i < bins.size(); i++) {
                MaxRectsBin bin = bins.get(i);
                MaxRectsBin.Rect rect = bin.insert(item.getWidth(), item.getHeight(), true);
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
                    if (item.getWidth() <= m.width && item.getHeight() <= m.height) {
                        selectedMaterial = m;
                        break;
                    }
                }
                if (selectedMaterial == null) continue;

                MaxRectsBin bin = new MaxRectsBin(selectedMaterial.width, selectedMaterial.height);
                MaxRectsBin.Rect rect = bin.insert(item.getWidth(), item.getHeight(), true);
                if (rect != null) {
                    bins.add(bin);

                    BinResult br = new BinResult();
                    br.setBinId(binId++);
                    br.setMaterialType(selectedMaterial.name);
                    br.setMaterialWidth(selectedMaterial.width);
                    br.setMaterialHeight(selectedMaterial.height);
                    br.setPieces(new ArrayList<>(Collections.singletonList(createPiece(item, rect))));
                    results.add(br);

                    // 已使用一块材料，从列表中移除
                    materials.remove(selectedMaterial);
                }
            }
        }

        // 更新利用率
        for (BinResult br : results) {
            double usedArea = br.getPieces().stream().mapToDouble(p -> p.getW() * p.getH()).sum();
            double totalArea = br.getMaterialWidth() * br.getMaterialHeight();
            br.setUtilization(Math.round((usedArea / totalArea) * 10000.0) / 100.0);
        }

        return results;
    }

    private Piece createPiece(Item item, MaxRectsBin.Rect rect) {
        Piece p = new Piece();
        p.setLabel(item.getLabel());
        p.setX(rect.x);
        p.setY(rect.y);
        p.setW(rect.width);
        p.setH(rect.height);
        p.setRotated(rect.rotated);
        return p;
    }

    private static class MaxRectsBin {
        private double width, height;
        private List<Rect> freeRectangles;

        public MaxRectsBin(double width, double height) {
            this.width = width;
            this.height = height;
            freeRectangles = new ArrayList<>();
            freeRectangles.add(new Rect(0, 0, width, height, false));
        }

        public Rect insert(double w, double h, boolean allowRotate) {
            Rect bestRect = null;
            double bestAreaFit = Double.MAX_VALUE;

            // 按行优先、列靠左排序自由矩形
            freeRectangles.sort((a, b) -> {
                if (a.y != b.y) return Double.compare(a.y, b.y);
                return Double.compare(a.x, b.x);
            });

            for (Rect free : freeRectangles) {
                // 不旋转
                if (w <= free.width && h <= free.height) {
                    double areaFit = free.width * free.height - w * h;
                    if (areaFit < bestAreaFit) {
                        bestRect = new Rect(free.x, free.y, w, h, false);
                        bestAreaFit = areaFit;
                    }
                }
                // 旋转
                if (allowRotate && h <= free.width && w <= free.height) {
                    double areaFit = free.width * free.height - h * w;
                    if (areaFit < bestAreaFit) {
                        bestRect = new Rect(free.x, free.y, h, w, true);
                        bestAreaFit = areaFit;
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
            if (placed.x < free.x + free.width && placed.x + placed.width > free.x) {
                if (placed.y > free.y) {
                    double height = placed.y - free.y;
                    if (height > 0) newFree.add(new Rect(free.x, free.y, free.width, height, false));
                }
                if (placed.y + placed.height < free.y + free.height) {
                    double height = free.y + free.height - (placed.y + placed.height);
                    if (height > 0) newFree.add(new Rect(free.x, placed.y + placed.height, free.width, height, false));
                }
            }
            if (placed.y < free.y + free.height && placed.y + placed.height > free.y) {
                if (placed.x > free.x) {
                    double width = placed.x - free.x;
                    if (width > 0) newFree.add(new Rect(free.x, free.y, width, free.height, false));
                }
                if (placed.x + placed.width < free.x + free.width) {
                    double width = free.x + free.width - (placed.x + placed.width);
                    if (width > 0) newFree.add(new Rect(placed.x + placed.width, free.y, width, free.height, false));
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
            return !(b.x >= a.x + a.width || b.x + b.width <= a.x || b.y >= a.y + a.height || b.y + b.height <= a.y);
        }

        private boolean isContainedIn(Rect a, Rect b) {
            return a.x >= b.x && a.y >= b.y && a.x + a.width <= b.x + b.width && a.y + a.height <= b.y + b.height;
        }

        public static class Rect {
            public double x, y, width, height;
            public boolean rotated;
            public Rect(double x, double y, double width, double height, boolean rotated) {
                this.x = x; this.y = y; this.width = width; this.height = height; this.rotated = rotated;
            }
        }
    }

    private static class MaterialInstance {
        public String name;
        public double width, height;
        public int priority;
        public MaterialInstance(String name, double w, double h, int priority) {
            this.name = name; this.width = w; this.height = h; this.priority = priority;
        }
    }
}
