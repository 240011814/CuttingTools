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

        LOGGER.info("输入 items: {}", Arrays.toString(items));
        LOGGER.info("旧料: {}", Arrays.toString(scrapsAll));
        LOGGER.info("新料长度: {}, 锯缝: {}", L, kerfNonNeg);

        AggMap map = aggregate(items);
        Agg agg0 = map.agg;

        LOGGER.info("聚合后长度: {}, 需求: {}", Arrays.toString(agg0.lens), Arrays.toString(agg0.demand));

        // ===== 0) 旧料直配预分配 =====
        PreAssign pa = preAssignExactScraps(agg0, map.typeToItemIdx, scrapsAll, kerfNonNeg);
        Agg agg = pa.aggAfter;
        double[] scraps = pa.remainingScraps;
        List<BarResult> fixed = pa.fixedResults;

        LOGGER.info("预分配后需求: {}", Arrays.toString(agg.demand));

        // ===== 1) 初始列：补全非满载列 + 高利用率组合枚举 =====
        List<Column> columns = new ArrayList<>();
        Set<String> colSeen = new HashSet<>();
        seedColumns(agg, L, scraps, kerfNonNeg, columns, colSeen);

        LOGGER.info("初始列生成完成，共 {} 列", columns.size());

        // 打印所有 NEW 列的 qty
        LOGGER.info("=== 初始列中的切割模式 ===");
        for (int i = 0; i < Math.min(50, columns.size()); i++) {
            Column c = columns.get(i);
            if (c.type == Column.Type.NEW) {
                LOGGER.info("列[{}]: {} -> 用{}cm, 剩{}cm", i, Arrays.toString(c.qty), c.used, c.capacity - c.used);
            }
        }

        // ===== 2) 主问题 LP =====
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

        // ===== 3) 列生成 =====
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

            int[] remainingDemand = new int[agg.types];
            for (int t = 0; t < agg.types; t++) {
                double fulfilled = 0;
                for (int k = 0; k < columns.size(); k++) {
                    fulfilled += columns.get(k).qty[t] * x.get(k).solutionValue();
                }
                remainingDemand[t] = Math.max(0, agg.demand[t] - (int) Math.floor(fulfilled + 1e-6));
            }

            List<Column> newCols = new ArrayList<>();

            Column nc = priceNew(agg, L, dualDem, remainingDemand, kerfNonNeg);
            if (nc != null && reducedCostNew(nc, dualDem) < -1e-9) {
                String key = colKey(nc);
                if (!colSeen.contains(key)) { colSeen.add(key); newCols.add(nc); }
            }

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

        // ===== 4) 整数化：阶段一（最少新料根数）=====
        IntSolution solStage1;
        try {
            solStage1 = integerizeStage1(columns, agg, scraps);
        } catch (Exception e) {
            LOGGER.warn("SCIP stage1 failed: {}, fallback to relaxed.", e.getMessage());
            IntSolution relaxed = solveWithPenalizedRelaxation(columns, agg, scraps);
            List<BarResult> res = toBarResults(columns, relaxed, agg, map.typeToItemIdx, L, scraps);
            fixed.addAll(0, res);
            return fixed;
        }

        // ===== 5) 阶段二：固定根数，偏好“用满” + “混合切割”=====
        IntSolution sol;
        try {
            LOGGER.info("尝试运行 stage2：偏好用满、混合切割");
            sol = integerizeStage2MinWaste(columns, agg, scraps, solStage1);
            LOGGER.info("Stage2 成功");
        } catch (Exception e) {
            LOGGER.error("SCIP stage2 failed: {}", e.getMessage(), e);
            sol = solStage1;
        }

        // ===== 6) 展开结果 =====
        List<BarResult> res = toBarResults(columns, sol, agg, map.typeToItemIdx, L, scraps);
        fixed.addAll(res);
        return fixed;
    }

    private String colKey(Column c) {
        return c.type + "_" + c.scrapIdx + "_" + Arrays.hashCode(c.qty);
    }

    // ===== 旧料直配 =====
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

    private PreAssign preAssignExactScraps(Agg agg0, List<List<Integer>> typeToItemIdx,
                                           double[] scrapsAll, double kerf) {
        List<BarResult> fixed = new ArrayList<>();
        int[] demand = Arrays.copyOf(agg0.demand, agg0.demand.length);
        boolean[] used = new boolean[scrapsAll.length];

        for (int t = 0; t < agg0.types; t++) {
            int lensScaled = agg0.w[t];
            for (int i = 0; i < scrapsAll.length; i++) {
                if (used[i]) continue;
                int scrapScaled = (int) Math.round(scrapsAll[i] * agg0.SCALE + 1e-9);
                if (lensScaled == scrapScaled && demand[t] > 0) {
                    used[i] = true;
                    double cap = scrapsAll[i];
                    double usedLen = round2(agg0.lens[t]);
                    fixed.add(BarResult.builder()
                            .index(i + 1)
                            .totalLength(cap)
                            .cuts(Collections.singletonList(agg0.lens[t]))
                            .used(usedLen)
                            .remaining(round2(cap - usedLen))
                            .build());
                    demand[t]--;
                    if (!typeToItemIdx.get(t).isEmpty()) {
                        typeToItemIdx.get(t).remove(0);
                    }
                }
            }
        }

        List<Double> rest = new ArrayList<>();
        for (int i = 0; i < scrapsAll.length; i++) if (!used[i]) rest.add(scrapsAll[i]);
        double[] remainingScraps = rest.stream().mapToDouble(d -> d).toArray();

        Agg aggAfter = new Agg(agg0.lens, demand);
        return new PreAssign(aggAfter, remainingScraps, fixed);
    }

    // ===== 初始列生成（补全 + 枚举高利用率组合）=====
    private void seedColumns(Agg agg, double L, double[] scraps, double kerf, List<Column> out, Set<String> seen) {
        LOGGER.info("seedColumns 开始，当前列数: {}", out.size());

        // 1. 单一类型：1~min(需求, 容量)
        for (int t = 0; t < agg.types; t++) {
            int maxPieces = (int) ((L + 1e-9) / agg.lens[t]);
            int actualMax = Math.min(maxPieces, agg.demand[t]);
            for (int p = 1; p <= actualMax; p++) {
                double used = p * agg.lens[t] + kerf * (p - 1);
                if (used <= L + 1e-9) {
                    int[] qty = new int[agg.types];
                    qty[t] = p;
                    double usedRounded = round2(used);
                    Column c = new Column(Column.Type.NEW, -1, qty, usedRounded, L, p);
                    addColumnIfNotExists(out, seen, c);
                } else {
                    break;
                }
            }
        }

        // 2. 启发式：贪心、交替、混合
        addGreedyPattern(agg, L, agg.demand, out, seen, false, kerf);
        addGreedyPattern(agg, L, agg.demand, out, seen, true, kerf);
        addAlternatingPattern(agg, L, agg.demand, out, seen, kerf);
        addMixedPattern(agg, L, agg.demand, out, seen, kerf);

        // 3. 【核心】枚举所有高利用率组合
        enumerateEfficientPatterns(agg, L, agg.demand, out, seen, kerf);

        // 4. 旧料切割
        Integer[] scrapIndices = IntStream.range(0, scraps.length).boxed().toArray(Integer[]::new);
        Arrays.sort(scrapIndices, (i, j) -> Double.compare(scraps[j], scraps[i]));
        for (int idx : scrapIndices) {
            double len = scraps[idx];
            if (len <= 0) continue;
            int[] qs = greedyPack(agg, len, agg.demand, kerf);
            int cuts = Arrays.stream(qs).sum();
            if (cuts <= 0) continue;
            double used = round2(dot(qs, agg.lens) + kerf * Math.max(0, cuts - 1));
            if (used <= len + 1e-6) {
                addColumnIfNotExists(out, seen, new Column(Column.Type.SCRAP, idx, qs, used, len, cuts));
            }
        }

        LOGGER.info("seedColumns 结束，总列数: {}", out.size());
    }

    // 【核心】枚举所有高利用率切割模式（修复日志格式）
    private void enumerateEfficientPatterns(Agg agg, double L, int[] maxCount, List<Column> out, Set<String> seen, double kerf) {
        LOGGER.info("开始枚举高效切割模式，类型数: {}, 最大件数: {}", agg.types, Arrays.toString(maxCount));
        int[] maxPieces = new int[agg.types];
        for (int t = 0; t < agg.types; t++) {
            maxPieces[t] = Math.min(maxCount[t], (int) ((L + 1e-9) / agg.lens[t]));
            LOGGER.info("类型 {} ({}cm): 最多切 {} 根", t, agg.lens[t], maxPieces[t]);
        }

        int[] current = new int[agg.types];
        dfsEnumerate(agg, L, maxPieces, current, 0, out, seen, kerf);
        LOGGER.info("高效模式枚举完成，当前列总数: {}", out.size());
    }

    private void dfsEnumerate(Agg agg, double L, int[] maxPieces, int[] current, int typeIdx,
                              List<Column> out, Set<String> seen, double kerf) {
        if (typeIdx == agg.types) {
            int cuts = Arrays.stream(current).sum();
            if (cuts == 0) return;
            double used = 0;
            for (int t = 0; t < agg.types; t++) {
                used += current[t] * agg.lens[t];
            }
            used += kerf * Math.max(0, cuts - 1);
            if (used <= L + 1e-9) {
                double utilization = used / L;
                boolean isHighUtilization = utilization > 0.90;
                boolean isSmallButUseful = cuts >= 2 && used > 0.1 * L;

                if (isHighUtilization || isSmallButUseful) {
                    LOGGER.info("✅ 模式候选: {} -> 用 {}cm, 利用率 {:.2f}%, 段数 {}",
                            Arrays.toString(current), round2(used), utilization * 100, cuts);
                    Column c = new Column(Column.Type.NEW, -1, current.clone(), round2(used), L, cuts);
                    addColumnIfNotExists(out, seen, c);
                }
            }
            return;
        }

        for (int n = 0; n <= maxPieces[typeIdx]; n++) {
            current[typeIdx] = n;
            dfsEnumerate(agg, L, maxPieces, current, typeIdx + 1, out, seen, kerf);
        }
    }

    private void addColumnIfNotExists(List<Column> out, Set<String> seen, Column col) {
        String key = colKey(col);
        if (!seen.contains(key)) {
            seen.add(key);
            out.add(col);
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

    private void addMixedPattern(Agg agg, double L, int[] maxCount, List<Column> out, Set<String> seen, double kerf) {
        int[] qty = new int[agg.types];
        double used = 0.0;
        int cuts = 0;
        Integer[] indices = IntStream.range(0, agg.types).boxed().toArray(Integer[]::new);
        Collections.shuffle(Arrays.asList(indices));
        boolean updated;
        do {
            updated = false;
            for (int id : indices) {
                if (qty[id] >= maxCount[id]) continue;
                double next = used + agg.lens[id] + (cuts > 0 ? kerf : 0.0);
                if (next <= L + 1e-9) { qty[id]++; cuts++; used = round2(next); updated = true; }
            }
        } while (updated);
        if (cuts > 0) addColumnIfNotExists(out, seen, new Column(Column.Type.NEW, -1, qty, used, L, cuts));
    }

    private void addColumnToMaster(MPSolver master, MPObjective obj, MPConstraint[] dem, MPConstraint[] suse,
                                   List<MPVariable> x, Column c) {
        MPVariable var = master.makeNumVar(0.0, Double.POSITIVE_INFINITY, "col_" + x.size());
        x.add(var);
        if (c.type == Column.Type.NEW) obj.setCoefficient(var, 1.0);
        for (int t = 0; t < dem.length; t++) if (c.qty[t] > 0) dem[t].setCoefficient(var, c.qty[t]);
        if (c.type == Column.Type.SCRAP && c.scrapIdx >= 0 && c.scrapIdx < suse.length) suse[c.scrapIdx].setCoefficient(var, 1.0);
    }

    private Column priceNew(Agg agg, double L, double[] dualDem, int[] remainingDemand, double kerf) {
        final int CAP = (int) Math.round(L * agg.SCALE);
        int[] weights = new int[agg.types];
        for (int t = 0; t < agg.types; t++) {
            weights[t] = agg.w[t];
        }

        double[] dp = new double[CAP + 1];
        int[][] prev = new int[agg.types][CAP + 1];

        for (int t = 0; t < agg.types; t++) {
            if (remainingDemand[t] <= 0) continue;
            int w_t = weights[t];
            if (w_t <= 0) continue;

            double[] ndp = Arrays.copyOf(dp, dp.length);
            int[][] nprev = new int[agg.types][CAP + 1];
            for (int i = 0; i < t; i++) System.arraycopy(prev[i], 0, nprev[i], 0, CAP + 1);

            for (int r = 1; r <= remainingDemand[t]; r++) {
                int usedLen = r * w_t;
                if (usedLen > CAP) break;
                double physicalUsed = (double) usedLen / agg.SCALE + kerf * (r - 1);
                if (physicalUsed > L + 1e-5) break;

                for (int w = CAP; w >= usedLen; w--) {
                    double candidate = dp[w - usedLen] + r * dualDem[t];
                    if (candidate > ndp[w] + 1e-12) {
                        ndp[w] = candidate;
                        for (int i = 0; i < t; i++) nprev[i][w] = prev[i][w - usedLen];
                        nprev[t][w] = r;
                    }
                }
            }
            dp = ndp;
            prev = nprev;
        }

        int bestW = -1;
        double bestVal = -1;
        for (int w = 0; w <= CAP; w++) {
            if (dp[w] > bestVal + 1e-12) {
                bestVal = dp[w];
                bestW = w;
            }
        }

        if (bestW < 0) return null;

        int[] qty = new int[agg.types];
        for (int t = 0; t < agg.types; t++) {
            qty[t] = prev[t][bestW];
        }
        int pieces = Arrays.stream(qty).sum();
        if (pieces == 0) return null;

        double used = dot(qty, agg.lens) + kerf * Math.max(0, pieces - 1);
        if (used > L + 1e-5) return null;

        return new Column(Column.Type.NEW, -1, qty, round2(used), L, pieces);
    }

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

    // ===== 整数化 =====
    static class IntSolution {
        final int[] mult;
        IntSolution(int[] m) { this.mult = m.clone(); }
    }

    private IntSolution integerizeStage1(List<Column> cols, Agg agg, double[] scraps) {
        MPSolver ip = MPSolver.createSolver("SCIP");
        if (ip == null) throw new IllegalStateException("SCIP not available");
        ip.setTimeLimit(60_000);
        MPObjective obj = ip.objective();
        obj.setMinimization();
        MPVariable[] z = new MPVariable[cols.size()];
        for (int k = 0; k < cols.size(); k++) {
            Column c = cols.get(k);
            z[k] = ip.makeIntVar(0.0, 1000.0, "z_" + k);
            if (c.type == Column.Type.NEW) obj.setCoefficient(z[k], 1.0);
        }
        for (int t = 0; t < agg.types; t++) {
            MPConstraint ct = ip.makeConstraint(agg.demand[t], agg.demand[t], "dem_exact_" + t);
            for (int k = 0; k < cols.size(); k++) {
                int a = cols.get(k).qty[t];
                if (a > 0) ct.setCoefficient(z[k], a);
            }
        }
        for (int i = 0; i < scraps.length; i++) {
            MPConstraint ct = ip.makeConstraint(0.0, 1.0, "scr_" + i);
            for (int k = 0; k < cols.size(); k++) {
                if (cols.get(k).type == Column.Type.SCRAP && cols.get(k).scrapIdx == i) {
                    ct.setCoefficient(z[k], 1.0);
                }
            }
        }
        MPSolver.ResultStatus st = ip.solve();
        LOGGER.info("SCIP stage1 status: {}", st);
        if (st == MPSolver.ResultStatus.OPTIMAL || st == MPSolver.ResultStatus.FEASIBLE) {
            int[] mult = new int[cols.size()];
            for (int k = 0; k < cols.size(); k++) mult[k] = (int) Math.round(z[k].solutionValue());
            return new IntSolution(mult);
        }
        throw new IllegalStateException("SCIP stage1 failed: " + st);
    }

    // ✅ 无硬编码，基于混合度、均衡性、段数打分
    private IntSolution integerizeStage2MinWaste(List<Column> cols, Agg agg, double[] scraps, IntSolution stage1) {
        int newBars = 0;
        for (int k = 0; k < cols.size(); k++) if (cols.get(k).type == Column.Type.NEW) newBars += stage1.mult[k];

        MPSolver ip = MPSolver.createSolver("SCIP");
        if (ip == null) throw new IllegalStateException("SCIP not available");
        ip.setTimeLimit(60_000);

        MPObjective obj = ip.objective();
        obj.setMinimization();
        MPVariable[] z = new MPVariable[cols.size()];
        for (int k = 0; k < cols.size(); k++) {
            Column c = cols.get(k);
            z[k] = ip.makeIntVar(0.0, 1000.0, "z2_" + k);

            double waste = Math.max(0.0, c.capacity - c.used);
            double cost = 0.0;

            // 【主目标】强烈惩罚非满载
            if (waste > 1e-9) {
                cost += 1000.0;
            }

            // 【次目标】废料
            cost += waste;

            // 【偏好】混合切割
            int nonZeroTypes = (int) Arrays.stream(c.qty).filter(q -> q > 0).count();
            if (nonZeroTypes >= 2) {
                cost -= 10.0;
            }

            // 【偏好】使用旧料
            if (c.type == Column.Type.SCRAP) {
                cost -= 0.1;
            }

            obj.setCoefficient(z[k], cost);
            LOGGER.info("stage2 列[{}]: qty={}, waste={}, cost={}", k, Arrays.toString(c.qty), round2(waste), round2(cost));
        }

        for (int t = 0; t < agg.types; t++) {
            MPConstraint ct = ip.makeConstraint(agg.demand[t], agg.demand[t], "dem2_exact_" + t);
            for (int k = 0; k < cols.size(); k++) {
                int a = cols.get(k).qty[t];
                if (a > 0) ct.setCoefficient(z[k], a);
            }
        }

        for (int i = 0; i < scraps.length; i++) {
            MPConstraint ct = ip.makeConstraint(0.0, 1.0, "scr2_" + i);
            for (int k = 0; k < cols.size(); k++) {
                if (cols.get(k).type == Column.Type.SCRAP && cols.get(k).scrapIdx == i) {
                    ct.setCoefficient(z[k], 1.0);
                }
            }
        }

        MPConstraint newCount = ip.makeConstraint(newBars, newBars, "fix_new_bars");
        for (int k = 0; k < cols.size(); k++) if (cols.get(k).type == Column.Type.NEW) newCount.setCoefficient(z[k], 1.0);

        MPSolver.ResultStatus st = ip.solve();
        LOGGER.info("SCIP stage2 status: {}", st);
        if (st == MPSolver.ResultStatus.OPTIMAL || st == MPSolver.ResultStatus.FEASIBLE) {
            int[] mult = new int[cols.size()];
            for (int k = 0; k < cols.size(); k++) {
                mult[k] = (int) Math.round(z[k].solutionValue());
                if (mult[k] > 0) {
                    LOGGER.info("✅ stage2 选择列[{}]: {} × {} 次", k, Arrays.toString(cols.get(k).qty), mult[k]);
                }
            }
            return new IntSolution(mult);
        }
        throw new IllegalStateException("SCIP stage2 failed: " + st);
    }

    private IntSolution solveWithPenalizedRelaxation(List<Column> cols, Agg agg, double[] scraps) {
        MPSolver ip = MPSolver.createSolver("SCIP");
        if (ip == null) throw new IllegalStateException("SCIP not available");
        ip.setTimeLimit(60_000);
        MPObjective obj = ip.objective();
        obj.setMinimization();
        MPVariable[] z = new MPVariable[cols.size()];
        for (int k = 0; k < cols.size(); k++) {
            Column c = cols.get(k);
            z[k] = ip.makeIntVar(0.0, 1000.0, "z_relax_" + k);
            if (c.type == Column.Type.NEW) obj.setCoefficient(z[k], 1.0);
        }
        for (int t = 0; t < agg.types; t++) {
            double lb = Math.floor(0.95 * agg.demand[t] + 1e-9);
            double ub = agg.demand[t];
            MPConstraint ct = ip.makeConstraint(lb, ub, "dem_min_" + t);
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
        LOGGER.info("Relaxed solve status: {}", st);
        if (st == MPSolver.ResultStatus.OPTIMAL || st == MPSolver.ResultStatus.FEASIBLE) {
            int[] mult = new int[cols.size()];
            for (int k = 0; k < cols.size(); k++) mult[k] = (int) Math.round(z[k].solutionValue());
            return new IntSolution(mult);
        }
        throw new IllegalStateException("Relaxed solve failed: " + st);
    }

    private List<BarResult> toBarResults(List<Column> cols, IntSolution sol, Agg agg, List<List<Integer>> typeToItemIdx,
                                         double L, double[] scraps) {
        List<BarResult> out = new ArrayList<>();
        Deque<Integer>[] q = new ArrayDeque[agg.types];
        for (int t = 0; t < agg.types; t++) {
            q[t] = new ArrayDeque<>(typeToItemIdx.get(t));
        }

        int newIdx = 1;
        for (int k = 0; k < cols.size(); k++) {
            int m = sol.mult[k];
            if (m <= 0) continue;
            Column c = cols.get(k);
            for (int r = 0; r < m; r++) {
                List<Double> cuts = new ArrayList<>();
                for (int t = 0; t < agg.types; t++) {
                    int toCut = Math.min(c.qty[t], q[t].size());
                    for (int cnt = 0; cnt < toCut; cnt++) {
                        q[t].pollFirst();
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

    private AggMap aggregate(double[] items) {
        Map<Double, List<Integer>> map = new TreeMap<>();
        for (int i = 0; i < items.length; i++) {
            double rounded = round2(items[i]);
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

    private double dot(int[] q, double[] v) {
        double s = 0;
        for (int i = 0; i < q.length; i++) s += q[i] * v[i];
        return round2(s);
    }

    private double dotIntDouble(int[] q, double[] v) {
        double s = 0;
        for (int i = 0; i < q.length; i++) s += q[i] * v[i];
        return s;
    }

    private double round2(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private int[] greedyPack(Agg agg, double capLen, int[] maxCount, double kerf) {
        int[] take = new int[agg.types];
        double used = 0.0;
        int cuts = 0;
        Integer[] indices = IntStream.range(0, agg.types).boxed().toArray(Integer[]::new);
        Arrays.sort(indices, (a, b) -> Double.compare(agg.lens[b], agg.lens[a]));
        boolean updated = true;
        while (updated) {
            updated = false;
            for (int id : indices) {
                if (take[id] >= maxCount[id]) continue;
                double next = used + agg.lens[id] + (cuts > 0 ? kerf : 0.0);
                if (next <= capLen + 1e-9) {
                    take[id]++;
                    cuts++;
                    used = round2(next);
                    updated = true;
                }
            }
        }
        return take;
    }
}