package com.yhy.cutting.service;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.*;
import com.yhy.cutting.vo.BarRequest;
import com.yhy.cutting.vo.BarResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.IntStream;

@Service
public class CuttingBarService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CuttingBarService.class);

    // ===== 基本数据（聚合后） =====
    static class Agg {
        final double[] lens;      // 不同长度（升序）
        final int[] demand;       // 各长度对应的需求数量
        final int types;          // 类型个数
        final int SCALE = 10;     // 0.1cm -> 1 个刻度
        final int[] w;            // scaled 长度

        Agg(double[] lens, int[] demand) {
            this.lens = lens;
            this.demand = demand;
            this.types = lens.length;
            this.w = new int[types];
            for (int i = 0; i < types; i++) {
                this.w[i] = (int) Math.round(lens[i] * SCALE + 1e-9);
            }
        }

        Agg cloneWithDemand(int[] newDemand) {
            return new Agg(this.lens, Arrays.copyOf(newDemand, newDemand.length));
        }
    }

    // 输入 items 映射为类型 + 每类型对应的实际 item 索引列表，用于结果展开
    static class AggMap {
        final Agg agg;
        final List<List<Integer>> typeToItemIdx; // 每个类型的原始 item 下标

        AggMap(Agg agg, List<List<Integer>> typeToItemIdx) {
            this.agg = agg;
            this.typeToItemIdx = typeToItemIdx;
        }
    }

    // 列（模式）：记录每种类型的数量
    static class Column {
        enum Type { NEW, SCRAP }

        final Type type;
        final int scrapIdx;    // SCRAP 时有效；NEW 为 -1
        final int[] qty;       // 每种类型切几件
        final double used;     // 实际使用长度（含 kerf 后）
        final double capacity; // NEW=L，SCRAP=对应余料长度
        final int cuts;        // 本列总切段数

        Column(Type type, int scrapIdx, int[] qty, double used, double capacity, int cuts) {
            this.type = type;
            this.scrapIdx = scrapIdx;
            this.qty = qty.clone();
            this.used = used;
            this.capacity = capacity;
            this.cuts = cuts;
        }

        boolean isEmpty() {
            for (int q : qty) if (q > 0) return false;
            return true;
        }
    }

    // ===== 公共入口 =====
    public List<BarResult> bar(BarRequest request) {
        Loader.loadNativeLibraries();

        double L = request.getNewMaterialLength().doubleValue();
        List<BigDecimal> itemsBD = request.getItems();
        List<BigDecimal> scrapsBD = request.getMaterials();

        // Kerf（锯缝，单位：cm），允许为 null
        double kerf = 0.0;
        try {
            BigDecimal k = (BigDecimal) BarRequest.class.getMethod("getKerf").invoke(request);
            if (k != null) kerf = k.doubleValue();
        } catch (Throwable ignore) { /* 若没有 getKerf() 则按 0 处理 */ }
        final double kerfNonNeg = Math.max(0.0, kerf);

        double[] items = itemsBD.stream().mapToDouble(BigDecimal::doubleValue).toArray();
        double[] scrapsAll = (scrapsBD == null || scrapsBD.isEmpty()) ? new double[0] :
                scrapsBD.stream().mapToDouble(BigDecimal::doubleValue).toArray();

        AggMap map = aggregate(items);
        Agg agg0 = map.agg;

        // ===== 0) 旧料直配预分配：旧料长度 == 需求长度（cuts=1 无 kerf）=====
        PreAssign pa = preAssignExactScraps(agg0, map.typeToItemIdx, scrapsAll, kerfNonNeg);
        Agg agg = pa.aggAfter;             // 扣除后的需求
        double[] scraps = pa.remainingScraps; // 未使用旧料
        List<BarResult> fixed = pa.fixedResults; // 已锁定到结果的旧料切割

        // ===== 1) 初始列：多样化生成（计入 kerf） =====
        List<Column> columns = new ArrayList<>();
        Set<String> colSeen = new HashSet<>();
        seedColumns(agg, L, scraps, kerfNonNeg, columns, colSeen);

        // ===== 2) 主问题 LP（GLOP）=====
        MPSolver master = MPSolver.createSolver("GLOP");
        if (master == null) throw new IllegalStateException("GLOP not available");
        MPObjective obj = master.objective();
        obj.setMinimization();

        MPConstraint[] dem = new MPConstraint[agg.types];
        for (int t = 0; t < agg.types; t++) {
            dem[t] = master.makeConstraint(agg.demand[t], Double.POSITIVE_INFINITY, "dem_" + t);
        }
        MPConstraint[] suse = new MPConstraint[scraps.length];
        for (int i = 0; i < scraps.length; i++) {
            suse[i] = master.makeConstraint(Double.NEGATIVE_INFINITY, 1.0, "scr_" + i);
        }

        List<MPVariable> x = new ArrayList<>();
        for (Column c : columns) {
            addColumnToMaster(master, obj, dem, suse, x, c);
        }

        // ===== 3) 列生成（定价 DP 计入 kerf）=====
        final int MAX_ITER = 300;
        for (int it = 0; it < MAX_ITER; it++) {
            MPSolver.ResultStatus st = master.solve();
            if (st != MPSolver.ResultStatus.OPTIMAL) {
                LOGGER.warn("Master not optimal at iter {}: {}", it, st);
                break;
            }

            double[] dualDem = new double[agg.types];
            for (int t = 0; t < agg.types; t++) dualDem[t] = dem[t].dualValue();

            double[] dualScr = new double[scraps.length];
            for (int i = 0; i < scraps.length; i++) dualScr[i] = suse[i].dualValue();

            // 剩余需求（向下取整当前产出）
            int[] remainingDemand = new int[agg.types];
            for (int t = 0; t < agg.types; t++) {
                double fulfilled = 0;
                for (int k = 0; k < columns.size(); k++) {
                    fulfilled += columns.get(k).qty[t] * x.get(k).solutionValue();
                }
                remainingDemand[t] = Math.max(0, agg.demand[t] - (int) Math.floor(fulfilled + 1e-6));
            }

            List<Column> newCols = new ArrayList<>();

            // 新料列
            Column nc = priceNew(agg, L, dualDem, remainingDemand, kerfNonNeg);
            if (nc != null && reducedCostNew(nc, dualDem) < -1e-9) {
                String key = colKey(nc);
                if (!colSeen.contains(key)) { colSeen.add(key); newCols.add(nc); }
            }

            // 旧料列
            for (int i = 0; i < scraps.length; i++) {
                Column sc = priceScrap(agg, scraps[i], i, dualDem, remainingDemand, kerfNonNeg);
                if (sc != null && reducedCostScrap(sc, dualDem, dualScr[i]) < -1e-9) {
                    String key = colKey(sc);
                    if (!colSeen.contains(key)) { colSeen.add(key); newCols.add(sc); }
                }
            }

            if (newCols.isEmpty()) {
                LOGGER.info("Column generation converged at iter {}", it);
                break;
            }
            for (Column c : newCols) { columns.add(c); addColumnToMaster(master, obj, dem, suse, x, c); }
        }

        // ===== 4) 整数化：阶段一（最少新料根数，ms）=====
        IntSolution solStage1;
        try {
            solStage1 = integerizeStage1(columns, agg, scraps);
        } catch (Exception e) {
            LOGGER.warn("SCIP stage1 failed: {}, fallback to relaxed.", e.getMessage());
            IntSolution relaxed = solveWithPenalizedRelaxation(columns, agg, scraps);
            List<BarResult> res = toBarResults(columns, relaxed, agg, map.typeToItemIdx, L, scraps);
            // 合并预分配的固定旧料结果
            fixed.addAll(0, res); // 先后顺序不敏感
            return fixed;
        }

        // ===== 5) 整数化：阶段二（固定新料根数，最小浪费 + 偏向多用旧料）=====
        IntSolution sol;
        try {
            sol = integerizeStage2MinWaste(columns, agg, scraps, solStage1);
        } catch (Exception e) {
            LOGGER.warn("SCIP stage2 failed: {}, use stage1.", e.getMessage());
            sol = solStage1;
        }

        // ===== 6) 展开结果并合并预分配 =====
        List<BarResult> res = toBarResults(columns, sol, agg, map.typeToItemIdx, L, scraps);
        fixed.addAll(res);
        return fixed;
    }

    private String colKey(Column c) { return c.type + "_" + c.scrapIdx + "_" + Arrays.toString(c.qty); }

    // ===== 旧料直配预分配 =====
    static class PreAssign {
        final Agg aggAfter;
        final double[] remainingScraps;
        final List<BarResult> fixedResults;

        PreAssign(Agg aggAfter, double[] remainingScraps, List<BarResult> fixedResults) {
            this.aggAfter = aggAfter;
            this.remainingScraps = remainingScraps;
            this.fixedResults = fixedResults;
        }
    }

    /**
     * 若 scrap 长度与某种需求长度完全相等（考虑 SCALE=10 的 0.1cm 精度），
     * 则优先直接用该 scrap 满足该长度的需求（cuts=1，不产生 kerf）。
     */
    private PreAssign preAssignExactScraps(Agg agg0, List<List<Integer>> typeToItemIdx,
                                           double[] scrapsAll, double kerf) {
        List<BarResult> fixed = new ArrayList<>();
        int[] demand = Arrays.copyOf(agg0.demand, agg0.demand.length);

        // scrap 长度 -> 其在 scrapsAll 的索引队列
        Map<Integer, Deque<Integer>> lengthToScrapIdx = new HashMap<>();
        for (int i = 0; i < scrapsAll.length; i++) {
            int key = (int) Math.round(scrapsAll[i] * agg0.SCALE + 1e-9);
            lengthToScrapIdx.computeIfAbsent(key, k -> new ArrayDeque<>()).add(i);
        }

        boolean[] used = new boolean[scrapsAll.length];

        for (int t = 0; t < agg0.types; t++) {
            int lensScaled = agg0.w[t];
            Deque<Integer> pool = lengthToScrapIdx.get(lensScaled);
            if (pool == null || pool.isEmpty() || demand[t] <= 0) continue;

            // 可直配数量 = min(该长度需求, 等长 scrap 数量)
            int assign = Math.min(demand[t], pool.size());
            for (int r = 0; r < assign; r++) {
                int sIdx = pool.pollFirst();
                used[sIdx] = true;
                double cap = scrapsAll[sIdx];
                // 无需锯缝：cuts=1 => used = lens
                double usedLen = round2(agg0.lens[t]);
                fixed.add(BarResult.builder()
                        .index(sIdx + 1)
                        .totalLength(cap)
                        .cuts(Collections.singletonList(agg0.lens[t]))
                        .used(usedLen)
                        .remaining(round2(cap - usedLen))
                        .build());
                demand[t] -= 1;
                // 从 typeToItemIdx 弹出一个原始 item（与展开一致）
                if (typeToItemIdx.get(t) != null && !typeToItemIdx.get(t).isEmpty()) {
                    typeToItemIdx.get(t).remove(0);
                }
            }
        }

        // 收集剩余未用 scraps
        List<Double> rest = new ArrayList<>();
        for (int i = 0; i < scrapsAll.length; i++) if (!used[i]) rest.add(scrapsAll[i]);
        double[] remainingScraps = rest.stream().mapToDouble(d -> d).toArray();

        Agg aggAfter = new Agg(agg0.lens, demand);
        return new PreAssign(aggAfter, remainingScraps, fixed);
    }

    // ===== 初始列（计入 kerf） =====
    private void seedColumns(Agg agg, double L, double[] scraps, double kerf, List<Column> out, Set<String> seen) {
        // 1. 单一类型逐段加入
        for (int t = 0; t < agg.types; t++) {
            int[] qty = new int[agg.types];
            double used = 0.0;
            int cuts = 0;
            int need = agg.demand[t];
            while (need > 0) {
                double next = used + agg.lens[t] + (cuts > 0 ? kerf : 0.0);
                if (next <= L + 1e-9) { qty[t]++; cuts++; used = round2(next); need--; }
                else break;
            }
            if (cuts > 0 && used <= L + 1e-6) addColumnIfNotExists(out, seen, new Column(Column.Type.NEW, -1, qty, used, L, cuts));
        }

        // 2. 贪心降序
        addGreedyPattern(agg, L, agg.demand, out, seen, false, kerf);
        // 3. 贪心升序
        addGreedyPattern(agg, L, agg.demand, out, seen, true, kerf);
        // 4. 交替
        addAlternatingPattern(agg, L, agg.demand, out, seen, kerf);

        // 混合
        addMixedPattern(agg, L, agg.demand, out, seen, kerf);

        // 5. SCRAP 贪心
        for (int i = 0; i < scraps.length; i++) {
            if (scraps[i] <= 0) continue;
            int[] qs = greedyPack(agg, scraps[i], agg.demand, kerf);
            int cuts = Arrays.stream(qs).sum();
            if (cuts <= 0) continue;
            double used = round2(dot(qs, agg.lens) + kerf * Math.max(0, cuts - 1));
            if (used <= scraps[i] + 1e-6) addColumnIfNotExists(out, seen, new Column(Column.Type.SCRAP, i, qs, used, scraps[i], cuts));
        }
    }

    private void addMixedPattern(Agg agg, double L, int[] maxCount, List<Column> out, Set<String> seen, double kerf) {
        int[] qty = new int[agg.types];
        double used = 0.0;
        int cuts = 0;

        // 尝试随机组合：先大后小、穿插、固定比例等
        Integer[] indices = IntStream.range(0, agg.types).boxed().toArray(Integer[]::new);
        Collections.shuffle(Arrays.asList(indices)); // 随机顺序

        boolean updated;
        do {
            updated = false;
            for (int id : indices) {
                if (qty[id] >= maxCount[id]) continue;
                double next = used + agg.lens[id] + (cuts > 0 ? kerf : 0.0);
                if (next <= L + 1e-9) {
                    qty[id]++;
                    cuts++;
                    used = round2(next);
                    updated = true;
                }
            }
        } while (updated);

        if (cuts > 0) {
            addColumnIfNotExists(out, seen, new Column(Column.Type.NEW, -1, qty, used, L, cuts));
        }
    }

    private void addGreedyPattern(Agg agg, double L, int[] maxCount, List<Column> out, Set<String> seen, boolean ascending, double kerf) {
        int[] qty = new int[agg.types];
        double used = 0.0;
        int cuts = 0;
        Integer[] idx = IntStream.range(0, agg.types).boxed().toArray(Integer[]::new);
        Arrays.sort(idx, (a, b) -> ascending ? Double.compare(agg.lens[a], agg.lens[b]) : Double.compare(agg.lens[b], agg.lens[a]));
        for (int id : idx) {
            int left = maxCount[id];
            while (left > 0) {
                double next = used + agg.lens[id] + (cuts > 0 ? kerf : 0.0);
                if (next <= L + 1e-9) { qty[id]++; cuts++; used = round2(next); left--; }
                else break;
            }
        }
        if (cuts > 0) addColumnIfNotExists(out, seen, new Column(Column.Type.NEW, -1, qty, used, L, cuts));
    }

    private void addAlternatingPattern(Agg agg, double L, int[] maxCount, List<Column> out, Set<String> seen, double kerf) {
        int[] qty = new int[agg.types];
        double used = 0.0;
        int cuts = 0;
        Integer[] idx = IntStream.range(0, agg.types).boxed().toArray(Integer[]::new);
        Arrays.sort(idx, (a, b) -> Double.compare(agg.lens[b], agg.lens[a]));
        List<Integer> cand = new ArrayList<>(Arrays.asList(idx));
        boolean pickLarge = true;

        while (!cand.isEmpty()) {
            Integer chosen = null;
            List<Integer> order = new ArrayList<>(cand);
            if (!pickLarge) Collections.reverse(order);
            for (Integer id : order) {
                if (qty[id] < maxCount[id]) {
                    double next = used + agg.lens[id] + (cuts > 0 ? kerf : 0.0);
                    if (next <= L + 1e-9) { chosen = id; break; }
                }
            }
            if (chosen == null) break;
            qty[chosen]++; cuts++; used = round2(used + agg.lens[chosen] + (cuts > 1 ? kerf : 0.0));
            pickLarge = !pickLarge;
        }
        if (cuts > 0) addColumnIfNotExists(out, seen, new Column(Column.Type.NEW, -1, qty, used, L, cuts));
    }

    private void addColumnIfNotExists(List<Column> out, Set<String> seen, Column col) {
        String key = colKey(col);
        if (!seen.contains(key)) { seen.add(key); out.add(col); }
    }

    // ===== 主问题添加列 =====
    private void addColumnToMaster(MPSolver master, MPObjective obj, MPConstraint[] dem, MPConstraint[] suse,
                                   List<MPVariable> x, Column c) {
        MPVariable var = master.makeNumVar(0.0, Double.POSITIVE_INFINITY, "col_" + x.size());
        x.add(var);
        if (c.type == Column.Type.NEW) obj.setCoefficient(var, 1.0);
        for (int t = 0; t < dem.length; t++) if (c.qty[t] > 0) dem[t].setCoefficient(var, c.qty[t]);
        if (c.type == Column.Type.SCRAP && c.scrapIdx >= 0 && c.scrapIdx < suse.length) suse[c.scrapIdx].setCoefficient(var, 1.0);
    }

    // ===== 定价：NEW（完全背包 DP，计入 kerf）=====
    private Column priceNew(Agg agg, double L, double[] dualDem, int[] remainingDemand, double kerf) {
        final int CAP = (int) Math.round(L * agg.SCALE + 1e-9);
        final int K = (int) Math.round(kerf * agg.SCALE + 1e-9);
        final int CAP_PRIME = CAP + K;

        double[] dp = new double[CAP_PRIME + 1];
        int[][] prev = new int[agg.types][CAP_PRIME + 1];

        for (int t = 0; t < agg.types; t++) {
            int wt = agg.w[t] + K;
            if (wt <= 0 || remainingDemand[t] <= 0) continue;
            int maxRep = Math.min(remainingDemand[t], CAP_PRIME / wt);
            if (maxRep <= 0) continue;

            double[] ndp = Arrays.copyOf(dp, dp.length);
            int[][] nprev = new int[agg.types][CAP_PRIME + 1];
            for (int i = 0; i < t; i++) System.arraycopy(prev[i], 0, nprev[i], 0, CAP_PRIME + 1);

            for (int w = wt; w <= CAP_PRIME; w++) {
                int bestR = 0; double bestVal = ndp[w];
                int can = Math.min(maxRep, w / wt);
                for (int r = 1; r <= can; r++) {
                    double cand = dp[w - r * wt] + r * dualDem[t];
                    if (cand > bestVal + 1e-12) { bestVal = cand; bestR = r; }
                }
                if (bestR > 0) {
                    ndp[w] = bestVal;
                    for (int i = 0; i < t; i++) nprev[i][w] = prev[i][w - bestR * wt];
                    nprev[t][w] = bestR;
                } else {
                    for (int i = 0; i < t; i++) nprev[i][w] = prev[i][w];
                }
            }
            dp = ndp; prev = nprev;
        }

        int bestW = -1; double best = -1e100;
        for (int w = 0; w <= CAP_PRIME; w++) if (dp[w] > best + 1e-12) { best = dp[w]; bestW = w; }
        if (bestW < 0) return null;

        int[] qty = new int[agg.types]; int cuts = 0;
        for (int t = 0; t < agg.types; t++) { qty[t] = prev[t][bestW]; cuts += qty[t]; }
        if (cuts <= 0) return null;

        double used = round2(dot(qty, agg.lens) + kerf * Math.max(0, cuts - 1));
        if (used > L + 1e-6) return null;

        return new Column(Column.Type.NEW, -1, qty, used, L, cuts);
    }

    // ===== 定价：SCRAP（完全背包 DP，计入 kerf）=====
    private Column priceScrap(Agg agg, double scrapLen, int scrapIdx, double[] dualDem, int[] remainingDemand, double kerf) {
        int CAP = (int) Math.round(scrapLen * agg.SCALE + 1e-9);
        if (CAP <= 0) return null;

        final int K = (int) Math.round(kerf * agg.SCALE + 1e-9);
        final int CAP_PRIME = CAP + K;

        double[] dp = new double[CAP_PRIME + 1];
        int[][] prev = new int[agg.types][CAP_PRIME + 1];

        for (int t = 0; t < agg.types; t++) {
            int wt = agg.w[t] + K;
            if (wt <= 0 || remainingDemand[t] <= 0) continue;
            int maxRep = Math.min(remainingDemand[t], CAP_PRIME / wt);
            if (maxRep <= 0) continue;

            double[] ndp = Arrays.copyOf(dp, dp.length);
            int[][] nprev = new int[agg.types][CAP_PRIME + 1];
            for (int i = 0; i < t; i++) System.arraycopy(prev[i], 0, nprev[i], 0, CAP_PRIME + 1);

            for (int w = wt; w <= CAP_PRIME; w++) {
                int bestR = 0; double bestVal = ndp[w];
                int can = Math.min(maxRep, w / wt);
                for (int r = 1; r <= can; r++) {
                    double cand = dp[w - r * wt] + r * dualDem[t];
                    if (cand > bestVal + 1e-12) { bestVal = cand; bestR = r; }
                }
                if (bestR > 0) {
                    ndp[w] = bestVal;
                    for (int i = 0; i < t; i++) nprev[i][w] = prev[i][w - bestR * wt];
                    nprev[t][w] = bestR;
                } else {
                    for (int i = 0; i < t; i++) nprev[i][w] = prev[i][w];
                }
            }
            dp = ndp; prev = nprev;
        }

        int bestW = -1; double best = -1e100;
        for (int w = 0; w <= CAP_PRIME; w++) if (dp[w] > best + 1e-12) { best = dp[w]; bestW = w; }
        if (bestW < 0) return null;

        int[] qty = new int[agg.types]; int cuts = 0;
        for (int t = 0; t < agg.types; t++) { qty[t] = prev[t][bestW]; cuts += qty[t]; }
        if (cuts <= 0) return null;

        double used = round2(dot(qty, agg.lens) + kerf * Math.max(0, cuts - 1));
        if (used > scrapLen + 1e-6) return null;

        return new Column(Column.Type.SCRAP, scrapIdx, qty, used, scrapLen, cuts);
    }

    private double reducedCostNew(Column c, double[] dualDem) {
        return 1.0 - dotIntDouble(c.qty, dualDem);
    }

    private double reducedCostScrap(Column c, double[] dualDem, double dualScr) {
        return dualScr - dotIntDouble(c.qty, dualDem);
    }

    // ===== 整数化：阶段一（最少新料根数） =====
    static class IntSolution {
        final int[] mult;
        IntSolution(int[] m) { this.mult = m.clone(); }
    }

    private IntSolution integerizeStage1(List<Column> cols, Agg agg, double[] scraps) {
        MPSolver ip = MPSolver.createSolver("SCIP");
        if (ip == null) throw new IllegalStateException("SCIP not available");
        ip.setTimeLimit(60_000); // 毫秒

        MPObjective obj = ip.objective();
        obj.setMinimization();
        MPVariable[] z = new MPVariable[cols.size()];
        for (int k = 0; k < cols.size(); k++) {
            Column c = cols.get(k);
            if (c.type == Column.Type.NEW) {
                z[k] = ip.makeIntVar(0.0, 1000.0, "z_" + k);
                obj.setCoefficient(z[k], 1.0);
            } else {
                z[k] = ip.makeIntVar(0.0, 1.0, "z_" + k);
            }
        }

        // 精确需求
        for (int t = 0; t < agg.types; t++) {
            MPConstraint ct = ip.makeConstraint(agg.demand[t], agg.demand[t], "dem_exact_" + t);
            for (int k = 0; k < cols.size(); k++) {
                int a = cols.get(k).qty[t];
                if (a > 0) ct.setCoefficient(z[k], a);
            }
        }

        // 每根余料 ≤ 1 次
        for (int i = 0; i < scraps.length; i++) {
            MPConstraint ct = ip.makeConstraint(0.0, 1.0, "scr_" + i);
            for (int k = 0; k < cols.size(); k++) {
                if (cols.get(k).type == Column.Type.SCRAP && cols.get(k).scrapIdx == i) {
                    ct.setCoefficient(z[k], 1.0);
                }
            }
        }

        MPSolver.ResultStatus st = ip.solve();
        if (st == MPSolver.ResultStatus.OPTIMAL || st == MPSolver.ResultStatus.FEASIBLE) {
            int[] mult = new int[cols.size()];
            for (int k = 0; k < cols.size(); k++) mult[k] = (int) Math.round(z[k].solutionValue());
            return new IntSolution(mult);
        }
        throw new IllegalStateException("SCIP stage1 failed: " + st);
    }

    // ===== 整数化：阶段二（固定新料根数，最小浪费 + 偏向用旧料）=====
    private IntSolution integerizeStage2MinWaste(List<Column> cols, Agg agg, double[] scraps, IntSolution stage1) {
        int newBars = 0;
        for (int k = 0; k < cols.size(); k++) if (cols.get(k).type == Column.Type.NEW) newBars += stage1.mult[k];

        MPSolver ip = MPSolver.createSolver("SCIP");
        if (ip == null) throw new IllegalStateException("SCIP not available");
        ip.setTimeLimit(60_000); // 毫秒

        MPObjective obj = ip.objective();
        obj.setMinimization();

        MPVariable[] z = new MPVariable[cols.size()];
        for (int k = 0; k < cols.size(); k++) {
            Column c = cols.get(k);
            if (c.type == Column.Type.NEW) {
                z[k] = ip.makeIntVar(0.0, 1000.0, "z2_" + k);
            } else {
                z[k] = ip.makeIntVar(0.0, 1.0, "z2_" + k);
            }
            double waste = Math.max(0.0, c.capacity - c.used);
            // tie-breaker：在同样浪费下更偏向用 SCRAP（给 SCRAP -1e-3 的“奖励”）
            double bonus = (c.type == Column.Type.SCRAP ? -1e-3 : 0.0);
            obj.setCoefficient(z[k], waste + bonus);
        }

        // 精确需求
        for (int t = 0; t < agg.types; t++) {
            MPConstraint ct = ip.makeConstraint(agg.demand[t], agg.demand[t], "dem2_exact_" + t);
            for (int k = 0; k < cols.size(); k++) {
                int a = cols.get(k).qty[t];
                if (a > 0) ct.setCoefficient(z[k], a);
            }
        }

        // 每根余料 ≤ 1 次
        for (int i = 0; i < scraps.length; i++) {
            MPConstraint ct = ip.makeConstraint(0.0, 1.0, "scr2_" + i);
            for (int k = 0; k < cols.size(); k++) {
                if (cols.get(k).type == Column.Type.SCRAP && cols.get(k).scrapIdx == i) {
                    ct.setCoefficient(z[k], 1.0);
                }
            }
        }

        // 固定新料根数
        MPConstraint newCount = ip.makeConstraint(newBars, newBars, "fix_new_bars");
        for (int k = 0; k < cols.size(); k++) if (cols.get(k).type == Column.Type.NEW) newCount.setCoefficient(z[k], 1.0);

        MPSolver.ResultStatus st = ip.solve();
        if (st == MPSolver.ResultStatus.OPTIMAL || st == MPSolver.ResultStatus.FEASIBLE) {
            int[] mult = new int[cols.size()];
            for (int k = 0; k < cols.size(); k++) mult[k] = (int) Math.round(z[k].solutionValue());
            return new IntSolution(mult);
        }
        throw new IllegalStateException("SCIP stage2 failed: " + st);
    }

    // ===== 放松回退 =====
    private IntSolution solveWithPenalizedRelaxation(List<Column> cols, Agg agg, double[] scraps) {
        MPSolver ip = MPSolver.createSolver("SCIP");
        if (ip == null) throw new IllegalStateException("SCIP not available");
        ip.setTimeLimit(60_000);

        MPObjective obj = ip.objective();
        obj.setMinimization();
        MPVariable[] z = new MPVariable[cols.size()];
        for (int k = 0; k < cols.size(); k++) {
            Column c = cols.get(k);
            if (c.type == Column.Type.NEW) {
                z[k] = ip.makeIntVar(0.0, 1000.0, "z_relax_" + k);
                obj.setCoefficient(z[k], 1.0);
            } else {
                z[k] = ip.makeIntVar(0.0, 1.0, "z_relax_" + k);
            }
        }

        for (int t = 0; t < agg.types; t++) {
            double lb = Math.floor(0.95 * agg.demand[t] + 1e-9);
            MPConstraint ct = ip.makeConstraint(lb, Double.POSITIVE_INFINITY, "dem_min_" + t);
            for (int k = 0; k < cols.size(); k++) {
                int a = cols.get(k).qty[t];
                if (a > 0) ct.setCoefficient(z[k], a);
            }
        }

        for (int i = 0; i < scraps.length; i++) {
            MPConstraint ct = ip.makeConstraint(0.0, 1.0, "scr_relax_" + i);
            for (int k = 0; k < cols.size(); k++) {
                if (cols.get(k).type == Column.Type.SCRAP && cols.get(k).scrapIdx == i) ct.setCoefficient(z[k], 1.0);
            }
        }

        MPSolver.ResultStatus st = ip.solve();
        if (st == MPSolver.ResultStatus.OPTIMAL || st == MPSolver.ResultStatus.FEASIBLE) {
            int[] mult = new int[cols.size()];
            for (int k = 0; k < cols.size(); k++) mult[k] = (int) Math.round(z[k].solutionValue());
            return new IntSolution(mult);
        }
        throw new IllegalStateException("Relaxed solve failed: " + st);
    }

    // ===== 结果展开 =====
    private List<BarResult> toBarResults(List<Column> cols, IntSolution sol, Agg agg, List<List<Integer>> typeToItemIdx,
                                         double L, double[] scraps) {
        List<BarResult> out = new ArrayList<>();
        Deque<Integer>[] q = new ArrayDeque[agg.types];
        for (int t = 0; t < agg.types; t++) q[t] = new ArrayDeque<>(typeToItemIdx.get(t));

        int newIdx = 1;
        for (int k = 0; k < cols.size(); k++) {
            int m = sol.mult[k];
            if (m <= 0) continue;
            Column c = cols.get(k);
            for (int r = 0; r < m; r++) {
                List<Double> cuts = new ArrayList<>();
                for (int t = 0; t < agg.types; t++) {
                    for (int cnt = 0; cnt < c.qty[t]; cnt++) {
                        if (!q[t].isEmpty()) q[t].pollFirst();
                        cuts.add(agg.lens[t]);
                    }
                }
                double used = round2(c.used);
                double rem = round2(c.capacity - used);
                if (c.type == Column.Type.NEW) {
                    out.add(BarResult.builder()
                            .index(newIdx++)
                            .totalLength(L)
                            .cuts(cuts)
                            .used(used)
                            .remaining(rem)
                            .build());
                } else if (c.scrapIdx >= 0 && c.scrapIdx < scraps.length) {
                    out.add(BarResult.builder()
                            .index(c.scrapIdx + 1)
                            .totalLength(scraps[c.scrapIdx])
                            .cuts(cuts)
                            .used(used)
                            .remaining(rem)
                            .build());
                }
            }
        }
        return out;
    }

    // ===== 聚合 =====
    private AggMap aggregate(double[] items) {
        Map<Double, List<Integer>> map = new TreeMap<>();
        for (int i = 0; i < items.length; i++) {
            double rounded = Math.round(items[i] * 10.0) / 10.0; // 0.1cm 精度
            map.computeIfAbsent(rounded, k -> new ArrayList<>()).add(i);
        }

        double[] lens = new double[map.size()];
        int[] demand = new int[map.size()];
        List<List<Integer>> idx = new ArrayList<>();
        int t = 0;
        for (Map.Entry<Double, List<Integer>> e : map.entrySet()) {
            lens[t] = e.getKey();
            demand[t] = e.getValue().size();
            idx.add(e.getValue());
            t++;
        }
        return new AggMap(new Agg(lens, demand), idx);
    }

    // ===== 小工具 =====
    private double dot(int[] q, double[] v) { double s = 0; for (int i = 0; i < q.length; i++) s += q[i] * v[i]; return round2(s); }
    private double dotIntDouble(int[] q, double[] v) { double s = 0; for (int i = 0; i < q.length; i++) s += q[i] * v[i]; return s; }
    private double round2(double v) { return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP).doubleValue(); }

    /**
     * 逐段贪心装箱：给定余料长度 capLen（cm），在 demand 限制下尽可能多装（计入 kerf）。
     */
    private int[] greedyPack(Agg agg, double capLen, int[] maxCount, double kerf) {
        int[] take = new int[agg.types];
        double used = 0.0; int cuts = 0;

        Integer[] indices = IntStream.range(0, agg.types).boxed().toArray(Integer[]::new);
        Arrays.sort(indices, (a, b) -> Double.compare(agg.lens[b], agg.lens[a]));

        boolean updated = true;
        while (updated) {
            updated = false;
            for (int id : indices) {
                if (take[id] >= maxCount[id]) continue;
                double next = used + agg.lens[id] + (cuts > 0 ? kerf : 0.0);
                if (next <= capLen + 1e-9) {
                    take[id]++; cuts++; used = round2(next); updated = true;
                }
            }
        }
        return take;
    }
}
