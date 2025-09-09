package com.yhy.cutting.cut.service;

import com.yhy.cutting.cut.vo.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;


@Service
public class MaxRectsCuttingService implements IPlaneService {

    private static final int SCALE = 2;

    public List<BinResult> optimize(BinRequest request) {
        List<Item> rawItems = request.getItems();
        if (rawItems == null || rawItems.isEmpty()) {
            return Collections.emptyList();
        }

        // ---------- 防御性校验：板材尺寸必须有效 ----------
        BigDecimal binWidth = (request.getWidth() != null) ?
                request.getWidth().setScale(SCALE, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal binHeight = (request.getHeight() != null) ?
                request.getHeight().setScale(SCALE, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        if (binWidth.compareTo(BigDecimal.ZERO) <= 0 || binHeight.compareTo(BigDecimal.ZERO) <= 0) {
            // 可选：抛异常或返回空
            return Collections.emptyList();
        }

        // ---------- 1) 按高度分组 -> 每个高度里按宽度维护 Deque（便于 poll） ----------
        Map<Double, Map<Double, Deque<Item>>> groups = new TreeMap<>(Collections.reverseOrder());
        for (Item it : rawItems) {
            if (it == null || it.getWidth() <= 0 || it.getHeight() <= 0) continue; // 过滤无效件
            groups
                    .computeIfAbsent(it.getHeight(), h -> new TreeMap<>(Collections.reverseOrder()))
                    .computeIfAbsent(it.getWidth(), w -> new ArrayDeque<>())
                    .add(it);
        }

        // ---------- 2) 构建材料池（优先使用 request.materials） ----------
        List<MaterialInstance> materials = new ArrayList<>();
        List<MaterialType> availableMaterials = request.getMaterials();
        if (availableMaterials != null) {
            for (MaterialType m : availableMaterials) {
                int count = Math.max(0, m.getQuantity());
                for (int i = 0; i < count; i++) {
                    materials.add(new MaterialInstance(m.getLabel(),
                            toDouble(m.getWidth()), toDouble(m.getHeight()), 10));
                }
            }
        }
        // 预留若干默认新板
        for (int i = 0; i < 10; i++) {
            materials.add(new MaterialInstance("新板材", binWidth.doubleValue(), binHeight.doubleValue(), 0));
        }
        materials.sort((a, b) -> {
            int cmp = Integer.compare(b.priority, a.priority); // 优先级高者优先
            if (cmp != 0) return cmp;
            // 同优先级按面积降序
            return b.width.multiply(b.height).compareTo(a.width.multiply(a.height));
        });

        // itemsToPlace: 包含单件和“组合虚拟件（COMBO_x）”
        List<Item> itemsToPlace = new ArrayList<>();
        Map<String, List<Item>> comboChildrenMap = new HashMap<>();
        int comboId = 0; // local, thread-safe

        // DP 量化因子
        final int factor = pow10(SCALE);
        final int capUnits = safeToInt(binWidth.multiply(BigDecimal.valueOf(factor)).setScale(0, RoundingMode.HALF_UP));

        // ---------- 3) 对每个高度组做“best-fill”组合（重复直到无法再找到组合） ----------
        List<Double> heights = new ArrayList<>(groups.keySet());
        for (Double hKey : heights) {
            Map<Double, Deque<Item>> widthMap = groups.get(hKey);
            if (widthMap == null || widthMap.isEmpty()) continue;

            while (true) {
                BestFillResult best = boundedSubsetSumBestFill(widthMap, capUnits, factor);
                if (best == null || best.totalUnits == 0) break;

                // 根据 best.counts 实际从队列上 poll 出具体 Item
                List<Item> childList = new ArrayList<>();
                BigDecimal totalWidth = BigDecimal.ZERO;
                for (Map.Entry<Double, Integer> e : best.counts.entrySet()) {
                    double w = e.getKey();
                    int cnt = e.getValue();
                    Deque<Item> dq = widthMap.get(w);
                    if (dq == null) continue;
                    for (int k = 0; k < cnt; k++) {
                        Item polled = dq.poll();
                        if (polled != null) {
                            childList.add(polled);
                            totalWidth = totalWidth.add(BigDecimal.valueOf(polled.getWidth()));
                        }
                    }
                }
                purgeEmptyWidths(widthMap);
                if (widthMap.isEmpty()) groups.remove(hKey);

                if (childList.isEmpty()) break;

                // ✅ 校验组合总宽不超过板材宽度（防浮点误差导致超限）
                if (totalWidth.compareTo(binWidth) > 0) {
                    // 弃用该组合，将子件放回（简单处理：加入待放列表）
                    itemsToPlace.addAll(childList);
                    continue;
                }

                // 生成虚拟 combo item
                String comboLabel = "COMBO_" + (comboId++);
                Item combo = new Item();
                combo.setLabel(comboLabel);
                combo.setWidth(totalWidth.doubleValue());
                combo.setHeight(hKey);
                itemsToPlace.add(combo);
                comboChildrenMap.put(comboLabel, childList);
            }

            // 将剩余单件也加入待放队列
            if (groups.containsKey(hKey)) {
                Map<Double, Deque<Item>> remainMap = groups.get(hKey);
                for (Deque<Item> dq : remainMap.values()) {
                    while (!dq.isEmpty()) {
                        itemsToPlace.add(dq.poll());
                    }
                }
                groups.remove(hKey);
            }
        }

        // 如果 itemsToPlace 为空（极端），回退到原始列表
        if (itemsToPlace.isEmpty()) {
            itemsToPlace.addAll(rawItems.stream()
                    .filter(it -> it != null && it.getWidth() > 0 && it.getHeight() > 0)
                    .collect(Collectors.toList()));
        }

        // ---------- 4) 排序：按面积降序（启发式） ----------
        itemsToPlace.sort((a, b) -> {
            double areaB = b.getWidth() * b.getHeight();
            double areaA = a.getWidth() * a.getHeight();
            int cmp = Double.compare(areaB, areaA);
            if (cmp != 0) return cmp;
            // 面积相同时，按高度降序（利于行内排列）
            return Double.compare(b.getHeight(), a.getHeight());
        });

        // ---------- 5) 用 MaxRects 放置 itemsToPlace ----------
        List<BinResult> results = new ArrayList<>();
        List<MaxRectsBin> bins = new ArrayList<>();
        int binIdCounter = 0;

        for (Item it : new ArrayList<>(itemsToPlace)) {
            if (it == null || it.getWidth() <= 0 || it.getHeight() <= 0) continue;

            boolean placed = false;
            for (int i = 0; i < bins.size(); i++) {
                MaxRectsBin bin = bins.get(i);
                MaxRectsBin.Rect r = bin.insert(
                        BigDecimal.valueOf(it.getWidth()),
                        BigDecimal.valueOf(it.getHeight()),
                        true
                );
                if (r != null) {
                    BinResult br = results.get(i);
                    if (isCombo(it)) {
                        placeComboChildren(br, comboChildrenMap.getOrDefault(it.getLabel(), Collections.emptyList()), r);
                    } else {
                        Piece piece = createPiece(it, r);
                        br.getPieces().add(piece);
                    }
                    placed = true;
                    break;
                }
            }

            if (!placed) {
                // 新开一块板（优先使用材料池）
                MaterialInstance sel = null;
                Iterator<MaterialInstance> mit = materials.iterator();
                while (mit.hasNext()) {
                    MaterialInstance m = mit.next();
                    if (BigDecimal.valueOf(it.getWidth()).compareTo(m.width) <= 0 &&
                            BigDecimal.valueOf(it.getHeight()).compareTo(m.height) <= 0) {
                        sel = m;
                        mit.remove();
                        break;
                    }
                }
                if (sel == null) {
                    sel = new MaterialInstance("新板材", binWidth.doubleValue(), binHeight.doubleValue(), 0);
                }

                MaxRectsBin newBin = new MaxRectsBin(sel.width, sel.height);
                MaxRectsBin.Rect r = newBin.insert(
                        BigDecimal.valueOf(it.getWidth()),
                        BigDecimal.valueOf(it.getHeight()),
                        true
                );
                if (r != null) {
                    bins.add(newBin);
                    BinResult br = new BinResult();
                    br.setBinId(binIdCounter++);
                    br.setMaterialType(sel.name);
                    br.setMaterialWidth(sel.width.doubleValue());
                    br.setMaterialHeight(sel.height.doubleValue());
                    br.setPieces(new ArrayList<>());

                    if (isCombo(it)) {
                        placeComboChildren(br, comboChildrenMap.getOrDefault(it.getLabel(), Collections.emptyList()), r);
                    } else {
                        Piece piece = createPiece(it, r);
                        br.getPieces().add(piece);
                    }
                    results.add(br);
                } else {
                    // ❗ 插入失败：记录日志或抛异常，不静默丢弃
                    System.err.println("警告：无法放置 item - " + it.getLabel() +
                            " (尺寸: " + it.getWidth() + "x" + it.getHeight() + ")");
                    // 可选：加入失败列表或抛异常
                }
            }
        }

        // ---------- 6) 更新所有结果的利用率 ----------
        for (BinResult br : results) {
            BigDecimal usedArea = br.getPieces().stream()
                    .map(p -> BigDecimal.valueOf(p.getW()).multiply(BigDecimal.valueOf(p.getH())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalArea = BigDecimal.valueOf(br.getMaterialWidth())
                    .multiply(BigDecimal.valueOf(br.getMaterialHeight()));
            BigDecimal utilization = BigDecimal.ZERO;
            if (totalArea.compareTo(BigDecimal.ZERO) > 0) {
                utilization = usedArea.divide(totalArea, SCALE + 2, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(SCALE, RoundingMode.HALF_UP);
            }
            br.setUtilization(utilization.doubleValue());
        }

        return results;
    }

    @Override
    public String getName() {
        return "MaxRects";
    }

    // ========== Helper Methods ==========

    private boolean isCombo(Item it) {
        return it != null && it.getLabel() != null && it.getLabel().startsWith("COMBO_");
    }

    // ✅ 修复：combo 被旋转时，子件坐标变换但自身不标记 rotated
    private void placeComboChildren(BinResult br, List<Item> children, MaxRectsBin.Rect comboRect) {
        if (children == null || children.isEmpty()) return;

        BigDecimal x0 = comboRect.x;
        BigDecimal y0 = comboRect.y;
        boolean comboRotated = comboRect.rotated; // combo 整体是否旋转

        BigDecimal offset = BigDecimal.ZERO;
        for (Item child : children) {
            if (child == null) continue;
            BigDecimal cw = BigDecimal.valueOf(child.getWidth());
            BigDecimal ch = BigDecimal.valueOf(child.getHeight());

            MaxRectsBin.Rect childRect;
            if (!comboRotated) {
                // 水平排列
                childRect = new MaxRectsBin.Rect(x0.add(offset), y0, cw, ch, false);
                offset = offset.add(cw);
            } else {
                // combo 旋转了 → 子件垂直堆叠（子件自身不旋转！）
                childRect = new MaxRectsBin.Rect(x0, y0.add(offset), cw, ch, false); // 注意：w/h 未交换
                offset = offset.add(ch); // 每个子件占高度 = ch
            }
            Piece piece = createPiece(child, childRect);
            // 可选：记录来源
            // piece.setSourceCombo(comboRect.label); // 需在 Piece 类中增加字段
            br.getPieces().add(piece);
        }
    }

    private Piece createPiece(Item item, MaxRectsBin.Rect rect) {
        Piece p = new Piece();
        p.setLabel(item.getLabel());
        p.setX(rect.x.doubleValue());
        p.setY(rect.y.doubleValue());
        p.setW(rect.width.doubleValue());
        p.setH(rect.height.doubleValue());
        p.setRotated(rect.rotated); // 仅当该 piece 自身被旋转时为 true
        return p;
    }

    // ========== Best-Fill with Bounded Knapsack ==========

    private static class BestFillResult {
        Map<Double, Integer> counts;
        int totalUnits;
        BestFillResult(Map<Double, Integer> counts, int totalUnits) {
            this.counts = counts;
            this.totalUnits = totalUnits;
        }
    }

    private BestFillResult boundedSubsetSumBestFill(Map<Double, Deque<Item>> widthMap, int capUnits, int factor) {
        List<Double> widths = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (Map.Entry<Double, Deque<Item>> e : widthMap.entrySet()) {
            int cnt = (e.getValue() == null) ? 0 : e.getValue().size();
            if (cnt > 0) {
                widths.add(e.getKey());
                counts.add(cnt);
            }
        }
        if (widths.isEmpty()) return null;

        int n = widths.size();
        int[] wUnits = new int[n];
        for (int i = 0; i < n; i++) {
            BigDecimal wu = BigDecimal.valueOf(widths.get(i)).multiply(BigDecimal.valueOf(factor));
            // ✅ 校验防溢出
            if (wu.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                return null; // 跳过超大宽度
            }
            wUnits[i] = wu.setScale(0, RoundingMode.HALF_UP).intValue();
            if (wUnits[i] <= 0) return null;
        }

        // 二进制拆分
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int cnt = counts.get(i);
            int k = 1;
            while (cnt > 0) {
                int take = Math.min(k, cnt);
                chunks.add(new Chunk(i, wUnits[i] * take, take));
                cnt -= take;
                if (k > Integer.MAX_VALUE / 2) break; // 防溢出
                k <<= 1;
            }
        }
        if (chunks.isEmpty()) return null;

        int cap = capUnits;
        int m = chunks.size();
        int[] best = new int[cap + 1];
        int[] from = new int[cap + 1];
        Arrays.fill(from, -1);

        // 0/1 背包 DP
        for (int j = 0; j < m; j++) {
            int w = chunks.get(j).weight;
            if (w > cap) continue;
            for (int c = cap; c >= w; c--) {
                int cand = best[c - w] + w;
                if (cand > best[c]) {
                    best[c] = cand;
                    from[c] = j;
                }
            }
        }

        // 找最优解
        int cStar = 0;
        for (int c = 1; c <= cap; c++) {
            if (best[c] > best[cStar]) cStar = c;
        }
        if (best[cStar] <= 0) return null;

        // 回溯
        int[] takeCounts = new int[n];
        int s = cStar;
        while (s > 0 && from[s] != -1) {
            int ci = from[s];
            Chunk ch = chunks.get(ci);
            takeCounts[ch.typeIndex] += ch.take;
            s -= ch.weight;
        }

        // 校验库存
        for (int i = 0; i < n; i++) {
            if (takeCounts[i] > counts.get(i)) {
                return null; // 不应发生
            }
        }

        Map<Double, Integer> result = new LinkedHashMap<>();
        int totalUnits = 0;
        for (int i = 0; i < n; i++) {
            if (takeCounts[i] > 0) {
                result.put(widths.get(i), takeCounts[i]);
                totalUnits += takeCounts[i] * wUnits[i];
            }
        }
        return new BestFillResult(result, totalUnits);
    }

    private static class Chunk {
        int typeIndex;
        int weight;
        int take;
        Chunk(int idx, int w, int t) {
            typeIndex = idx;
            weight = w;
            take = t;
        }
    }

    // ========== MaxRectsBin (Enhanced) ==========

    public static class MaxRectsBin { // 改为 public static 便于测试
        private BigDecimal width, height;
        private List<Rect> freeRectangles;

        public MaxRectsBin(BigDecimal width, BigDecimal height) {
            this.width = width.setScale(SCALE, RoundingMode.HALF_UP);
            this.height = height.setScale(SCALE, RoundingMode.HALF_UP);
            this.freeRectangles = new ArrayList<>();
            this.freeRectangles.add(new Rect(BigDecimal.ZERO, BigDecimal.ZERO, width, height, false));
        }

        public Rect insert(BigDecimal w, BigDecimal h, boolean allowRotate) {
            Rect bestRect = null;
            BigDecimal bestAreaFit = new BigDecimal(Double.MAX_VALUE);
            BigDecimal bestY = null, bestX = null;

            // 按 y 升序、x 升序排序（靠下靠左优先）
            freeRectangles.sort((a, b) -> {
                int cmp = a.y.compareTo(b.y);
                if (cmp != 0) return cmp;
                return a.x.compareTo(b.x);
            });

            for (Rect free : freeRectangles) {
                // 尝试不旋转
                if (w.compareTo(free.width) <= 0 && h.compareTo(free.height) <= 0) {
                    BigDecimal areaFit = free.width.multiply(free.height).subtract(w.multiply(h));
                    // ✅ 增强：面积相同时，优先选 y 小、x 小的位置
                    if (areaFit.compareTo(bestAreaFit) < 0 ||
                            (areaFit.compareTo(bestAreaFit) == 0 &&
                                    (free.y.compareTo(bestY) < 0 ||
                                            (free.y.compareTo(bestY) == 0 && free.x.compareTo(bestX) < 0)))) {
                        bestRect = new Rect(free.x, free.y, w, h, false);
                        bestAreaFit = areaFit;
                        bestY = free.y;
                        bestX = free.x;
                    }
                }
                // 尝试旋转
                if (allowRotate && h.compareTo(free.width) <= 0 && w.compareTo(free.height) <= 0) {
                    BigDecimal areaFit = free.width.multiply(free.height).subtract(h.multiply(w));
                    if (areaFit.compareTo(bestAreaFit) < 0 ||
                            (areaFit.compareTo(bestAreaFit) == 0 &&
                                    (free.y.compareTo(bestY) < 0 ||
                                            (free.y.compareTo(bestY) == 0 && free.x.compareTo(bestX) < 0)))) {
                        bestRect = new Rect(free.x, free.y, h, w, true);
                        bestAreaFit = areaFit;
                        bestY = free.y;
                        bestX = free.x;
                    }
                }
            }

            if (bestRect != null) {
                placeRect(bestRect);
            }
            return bestRect;
        }

        private void placeRect(Rect rect) {
            List<Rect> newFree = new ArrayList<>();
            for (Rect free : freeRectangles) {
                if (!intersect(free, rect)) {
                    newFree.add(free);
                } else {
                    splitFreeRectangle(free, rect, newFree);
                }
            }
            freeRectangles = newFree;
            pruneFreeList();
        }

        private void splitFreeRectangle(Rect free, Rect placed, List<Rect> newFree) {
            BigDecimal zero = BigDecimal.ZERO;

            // 上下切割
            if (placed.x.compareTo(free.x.add(free.width)) < 0 && placed.x.add(placed.width).compareTo(free.x) > 0) {
                // 上方剩余
                if (placed.y.compareTo(free.y) > 0) {
                    BigDecimal height = placed.y.subtract(free.y);
                    if (height.compareTo(zero) > 0) {
                        newFree.add(new Rect(free.x, free.y, free.width, height, false));
                    }
                }
                // 下方剩余
                if (placed.y.add(placed.height).compareTo(free.y.add(free.height)) < 0) {
                    BigDecimal height = free.y.add(free.height).subtract(placed.y.add(placed.height));
                    if (height.compareTo(zero) > 0) {
                        newFree.add(new Rect(free.x, placed.y.add(placed.height), free.width, height, false));
                    }
                }
            }

            // 左右切割
            if (placed.y.compareTo(free.y.add(free.height)) < 0 && placed.y.add(placed.height).compareTo(free.y) > 0) {
                // 左侧剩余
                if (placed.x.compareTo(free.x) > 0) {
                    BigDecimal width = placed.x.subtract(free.x);
                    if (width.compareTo(zero) > 0) {
                        newFree.add(new Rect(free.x, free.y, width, free.height, false));
                    }
                }
                // 右侧剩余
                if (placed.x.add(placed.width).compareTo(free.x.add(free.width)) < 0) {
                    BigDecimal width = free.x.add(free.width).subtract(placed.x.add(placed.width));
                    if (width.compareTo(zero) > 0) {
                        newFree.add(new Rect(placed.x.add(placed.width), free.y, width, free.height, false));
                    }
                }
            }
        }

        // ✅ 修复：安全去重，避免 ConcurrentModification
        private void pruneFreeList() {
            if (freeRectangles.size() <= 1) return;

            Set<Rect> toRemove = new HashSet<>();
            for (int i = 0; i < freeRectangles.size(); i++) {
                Rect a = freeRectangles.get(i);
                for (int j = 0; j < freeRectangles.size(); j++) {
                    if (i == j) continue;
                    Rect b = freeRectangles.get(j);
                    if (isContainedIn(a, b)) {
                        toRemove.add(a);
                        break; // a 被包含，无需再比
                    }
                }
            }
            freeRectangles.removeAll(toRemove);
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

    // ========== Utilities ==========

    private static class MaterialInstance {
        public String name;
        public BigDecimal width, height;
        public int priority; // higher = more preferred

        public MaterialInstance(String name, double w, double h, int priority) {
            this.name = name;
            this.width = BigDecimal.valueOf(w).setScale(SCALE, RoundingMode.HALF_UP);
            this.height = BigDecimal.valueOf(h).setScale(SCALE, RoundingMode.HALF_UP);
            this.priority = priority;
        }
    }

    private static void purgeEmptyWidths(Map<Double, Deque<Item>> widthMap) {
        if (widthMap == null) return;
        widthMap.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isEmpty());
    }

    private static int pow10(int k) {
        int r = 1;
        for (int i = 0; i < k; i++) r *= 10;
        return r;
    }

    private static int safeToInt(BigDecimal bd) {
        if (bd == null) return 0;
        double d = bd.doubleValue();
        if (d < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        if (d > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.round(d);
    }

    private static double toDouble(Object num) {
        if (num == null) return 0d;
        if (num instanceof BigDecimal) return ((BigDecimal) num).doubleValue();
        if (num instanceof Number) return ((Number) num).doubleValue();
        try {
            return Double.parseDouble(num.toString());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }
}