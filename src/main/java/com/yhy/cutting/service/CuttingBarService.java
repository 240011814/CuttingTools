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
        final int SCALE = 2;      // 0.5cm -> 1 个刻度
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
        enum Type {
            NEW, SCRAP
        }

        final Type type;
        final int scrapIdx;    // SCRAP 时有效；NEW 为 -1
        final int[] qty;       // 每种类型切几件
        final double used;     // 实际使用长度（cm）
        final double capacity; // NEW=L，SCRAP=对应余料长度

        Column(Type type, int scrapIdx, int[] qty, double used, double capacity) {
            this.type = type;
            this.scrapIdx = scrapIdx;
            this.qty = qty.clone();
            this.used = used;
            this.capacity = capacity;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Column column = (Column) o;
            return type == column.type &&
                    scrapIdx == column.scrapIdx &&
                    Arrays.equals(qty, column.qty) &&
                    Math.abs(used - column.used) < 1e-5 &&
                    Math.abs(capacity - column.capacity) < 1e-5;
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(type, scrapIdx, used, capacity);
            result = 31 * result + Arrays.hashCode(qty);
            return result;
        }

        boolean isEmpty() {
            for (int q : qty) {
                if (q > 0) return false;
            }
            return true;
        }
    }

    // ===== 公共入口 =====
    public List<BarResult> bar(BarRequest request) {
        Loader.loadNativeLibraries();

        double L = request.getNewMaterialLength().doubleValue();
        List<BigDecimal> itemsBD = request.getItems();
        List<BigDecimal> scrapsBD = request.getMaterials();

        double[] items = itemsBD.stream().mapToDouble(BigDecimal::doubleValue).toArray();
        double[] scraps = (scrapsBD == null || scrapsBD.isEmpty()) ? new double[0] :
                scrapsBD.stream().mapToDouble(BigDecimal::doubleValue).toArray();

        AggMap map = aggregate(items);
        Agg agg = map.agg;

        final int CAP = (int) Math.round(L * agg.SCALE + 1e-9);

        // 1) 初始列
        List<Column> columns = new ArrayList<>();
        Set<String> colSeen = new HashSet<>();
        seedColumns(agg, L, scraps, columns, colSeen);
        LOGGER.info("Initial columns: {}", columns.size());

        // 2) 主问题 LP（GLOP）
        MPSolver master = MPSolver.createSolver("GLOP");
        if (master == null) {
            throw new IllegalStateException("GLOP not available");
        }
        MPObjective obj = master.objective();
        obj.setMinimization();

        // 需求约束
        MPConstraint[] dem = new MPConstraint[agg.types];
        for (int t = 0; t < agg.types; t++) {
            dem[t] = master.makeConstraint(agg.demand[t], Double.POSITIVE_INFINITY, "dem_" + t);
        }

        // 旧料使用约束
        MPConstraint[] suse = new MPConstraint[scraps.length];
        for (int i = 0; i < scraps.length; i++) {
            suse[i] = master.makeConstraint(Double.NEGATIVE_INFINITY, 1.0, "scr_" + i);
        }

        List<MPVariable> x = new ArrayList<>();
        for (Column c : columns) {
            addColumnToMaster(master, obj, dem, suse, x, c);
        }

        // 3) 列生成
        final int MAX_ITER = 300;
        for (int it = 0; it < MAX_ITER; it++) {
            MPSolver.ResultStatus st = master.solve();
            if (st != MPSolver.ResultStatus.OPTIMAL) {
                LOGGER.error("Master not optimal at iter {}: {}", it, st);
                break;
            }

            double[] dualDem = new double[agg.types];
            for (int t = 0; t < agg.types; t++) {
                dualDem[t] = dem[t].dualValue();
            }

            double[] dualScr = new double[scraps.length];
            for (int i = 0; i < scraps.length; i++) {
                dualScr[i] = suse[i].dualValue();
            }

            // 计算剩余需求
            int[] remainingDemand = new int[agg.types];
            for (int t = 0; t < agg.types; t++) {
                double fulfilled = 0;
                for (int k = 0; k < columns.size(); k++) {
                    fulfilled += columns.get(k).qty[t] * x.get(k).solutionValue();
                }
                remainingDemand[t] = Math.max(0, agg.demand[t] - (int) Math.floor(fulfilled + 1e-6));
            }

            // 统计每种长度可用的旧料数量（用于新料定价抑制）
            int[] scrapCount = new int[agg.types];
            Arrays.fill(scrapCount, 0);
            Map<Double, Integer> scrapLenMap = new HashMap<>();
            for (double s : scraps) {
                double rounded = Math.round(s * 2.0) / 2.0;
                scrapLenMap.merge(rounded, 1, Integer::sum);
            }
            for (int t = 0; t < agg.types; t++) {
                scrapCount[t] = scrapLenMap.getOrDefault(agg.lens[t], 0);
            }

            List<Column> newCols = new ArrayList<>();
            Column newCol = priceNew(agg, L, dualDem, remainingDemand, 0, CAP, scraps, scrapCount);
            if (newCol != null && reducedCostNew(newCol, dualDem) < -1e-9) {
                String key = colKey(newCol);
                if (!colSeen.contains(key)) {
                    colSeen.add(key);
                    newCols.add(newCol);
                }
            }

            for (int i = 0; i < scraps.length; i++) {
                Column sc = priceScrap(agg, scraps[i], i, dualDem, remainingDemand);
                if (sc != null && reducedCostScrap(sc, dualDem, dualScr[i]) < -1e-9) {
                    String key = colKey(sc);
                    if (!colSeen.contains(key)) {
                        colSeen.add(key);
                        newCols.add(sc);
                    }
                }
            }

            if (newCols.isEmpty()) {
                LOGGER.info("Column generation converged at iter {}", it);
                break;
            }

            for (Column c : newCols) {
                columns.add(c);
                addColumnToMaster(master, obj, dem, suse, x, c);
            }
        }

        // 4) 整数化（SCIP）
        IntSolution sol;
        try {
            sol = integerize(columns, agg, scraps, L);
        } catch (Exception e) {
            LOGGER.warn("SCIP failed: " + e.getMessage() + ", falling back to penalized relaxed.");
            sol = solveWithPenalizedRelaxation(columns, agg, scraps, L);
        }

        // 5) 展开为具体 BarResult
        return toBarResults(columns, sol, agg, map.typeToItemIdx, L, scraps);
    }

    private String colKey(Column c) {
        return c.type + "_" + c.scrapIdx + "_" + Arrays.toString(c.qty);
    }

    // ===== 初始列 =====
    private void seedColumns(Agg agg, double L, double[] scraps, List<Column> out, Set<String> seen) {
        final int CAP = (int) Math.round(L * agg.SCALE + 1e-9);

        // 1. 单一类型
        for (int t = 0; t < agg.types; t++) {
            int maxRep = (int) Math.floor(L / agg.lens[t] + 1e-9);
            if (maxRep <= 0) continue;
            int take = Math.min(maxRep, agg.demand[t]);
            int[] qty = new int[agg.types];
            qty[t] = take;
            double used = round2(take * agg.lens[t]);
            if (used > 0 && used <= L) {
                Column col = new Column(Column.Type.NEW, -1, qty, used, L);
                String key = colKey(col);
                if (!seen.contains(key)) {
                    seen.add(key);
                    out.add(col);
                }
            }
        }

        // 2. 贪心混合（升序）
        int[] q1 = new int[agg.types];
        double used1 = 0.0;
        greedyPackInto(agg, L, agg.demand, q1, 1);
        if (used1 > 0) {
            Column col = new Column(Column.Type.NEW, -1, q1, used1, L);
            String key = colKey(col);
            if (!seen.contains(key)) {
                seen.add(key);
                out.add(col);
            }
        }

        // 3. 贪心混合（降序）
        int[] q2 = new int[agg.types];
        double used2 = 0.0;
        greedyPackInto(agg, L, agg.demand, q2, -1);
        if (used2 > 0) {
            Column col = new Column(Column.Type.NEW, -1, q2, used2, L);
            String key = colKey(col);
            if (!seen.contains(key)) {
                seen.add(key);
                out.add(col);
            }
        }

        // 4. 强制加入 140*2 + 160*2 = 600 的完美模式
        int t140 = findType(agg, 140.0);
        int t160 = findType(agg, 160.0);
        if (t140 != -1 && t160 != -1) {
            int[] qty = new int[agg.types];
            qty[t140] = 2;
            qty[t160] = 2;
            double used = 140 * 2 + 160 * 2;
            if (Math.abs(used - L) < 1e-3) {
                Column col = new Column(Column.Type.NEW, -1, qty, used, L);
                String key = colKey(col);
                if (!seen.contains(key)) {
                    seen.add(key);
                    out.add(col);
                    LOGGER.info("Added perfect pattern: 140*2 + 160*2");
                }
            }
        }

        // 5. SCRAP 贪心
        for (int i = 0; i < scraps.length; i++) {
            if (scraps[i] <= 0) continue;
            int cap = (int) Math.round(scraps[i] * agg.SCALE + 1e-9);
            int[] qs = greedyPack(agg, cap, agg.demand);
            double u = round2(dot(qs, agg.lens));
            if (u > 0 && u <= scraps[i]) {
                Column col = new Column(Column.Type.SCRAP, i, qs, u, scraps[i]);
                String key = colKey(col);
                if (!seen.contains(key)) {
                    seen.add(key);
                    out.add(col);
                }
            }
        }
    }

    // 贪心填充（order: 1=升序, -1=降序）
    private void greedyPackInto(Agg agg, double L, int[] maxCount, int[] output, int order) {
        double used = 0.0;
        Arrays.fill(output, 0);
        Integer[] idx = IntStream.range(0, agg.types)
                .boxed()
                .toArray(Integer[]::new);
        Arrays.sort(idx, (a, b) -> order * Double.compare(agg.lens[a], agg.lens[b]));

        for (int id : idx) {
            int remaining = maxCount[id];
            if (remaining <= 0) continue;
            int can = (int) Math.floor((L - used) / agg.lens[id] + 1e-9);
            int take = Math.min(remaining, can);
            if (take > 0) {
                output[id] += take;
                used = round2(used + take * agg.lens[id]);
            }
        }
    }

    private int findType(Agg agg, double len) {
        for (int i = 0; i < agg.lens.length; i++) {
            if (Math.abs(agg.lens[i] - len) < 1e-3) return i; // 放宽精度
        }
        return -1;
    }

    private int[] greedyPack(Agg agg, int cap, int[] maxCount) {
        int[] take = new int[agg.types];
        int load = 0;
        Integer[] idx = IntStream.range(0, agg.types)
                .boxed()
                .toArray(Integer[]::new);
        Arrays.sort(idx, (a, b) -> Double.compare(agg.lens[b], agg.lens[a]));

        for (int id : idx) {
            int w = agg.w[id];
            if (w <= 0 || load >= cap) continue;
            int can = Math.min(maxCount[id], (cap - load) / w);
            if (can > 0) {
                take[id] += can;
                load += can * w;
            }
        }
        return take;
    }

    // ===== 主问题添加列 =====
    private void addColumnToMaster(MPSolver master, MPObjective obj, MPConstraint[] dem, MPConstraint[] suse,
                                   List<MPVariable> x, Column c) {
        MPVariable var = master.makeNumVar(0.0, Double.POSITIVE_INFINITY, "col_" + x.size() + "_" + UUID.randomUUID());
        x.add(var);
        if (c.type == Column.Type.NEW) {
            obj.setCoefficient(var, 1.0);
        }
        for (int t = 0; t < dem.length; t++) {
            if (c.qty[t] > 0) {
                dem[t].setCoefficient(var, c.qty[t]);
            }
        }
        if (c.type == Column.Type.SCRAP && c.scrapIdx >= 0 && c.scrapIdx < suse.length) {
            suse[c.scrapIdx].setCoefficient(var, 1.0);
        }
    }

    // ===== 定价：NEW（带旧料优先抑制）=====
    private Column priceNew(Agg agg, double L, double[] dualDem, int[] remainingDemand, int low, int high,
                            double[] scraps, int[] scrapCount) {
        final int CAP = (int) Math.round(L * agg.SCALE + 1e-9);
        low = Math.max(0, low);
        high = Math.min(high, CAP);
        if (low > high) return null;

        double[] adjustedDual = dualDem.clone();
        for (int t = 0; t < agg.types; t++) {
            if (scrapCount[t] > 0 && remainingDemand[t] > 0) {
                adjustedDual[t] *= 0.3;
            }
        }

        double[] dp = new double[CAP + 1];
        int[][] prev = new int[agg.types][CAP + 1];

        for (int w = 0; w <= CAP; w++) dp[w] = 0;
        for (int[] row : prev) Arrays.fill(row, 0);

        for (int t = 0; t < agg.types; t++) {
            int wt = agg.w[t];
            if (wt <= 0 || remainingDemand[t] <= 0) continue;
            int maxRep = Math.min(remainingDemand[t], CAP / wt);
            if (maxRep <= 0) continue;

            double[] ndp = Arrays.copyOf(dp, dp.length);
            int[][] nprev = new int[agg.types][CAP + 1];
            for (int i = 0; i < t; i++) {
                System.arraycopy(prev[i], 0, nprev[i], 0, CAP + 1);
            }

            for (int w = wt; w <= CAP; w++) {
                int bestR = 0;
                double bestVal = ndp[w];
                int can = Math.min(maxRep, w / wt);
                for (int r = 1; r <= can; r++) {
                    double cand = dp[w - r * wt] + r * adjustedDual[t];
                    if (cand > bestVal + 1e-12) {
                        bestVal = cand;
                        bestR = r;
                    }
                }
                if (bestR > 0) {
                    ndp[w] = bestVal;
                    for (int i = 0; i < t; i++) {
                        nprev[i][w] = prev[i][w - bestR * wt];
                    }
                    nprev[t][w] = bestR;
                } else {
                    for (int i = 0; i < t; i++) {
                        nprev[i][w] = prev[i][w];
                    }
                }
            }
            dp = ndp;
            prev = nprev;
        }

        int bestW = -1;
        double best = -1e100;
        for (int w = low; w <= high; w++) {
            if (dp[w] > best + 1e-12) {
                best = dp[w];
                bestW = w;
            }
        }
        if (bestW < 0) return null;

        int[] qty = new int[agg.types];
        for (int t = 0; t < agg.types; t++) {
            qty[t] = prev[t][bestW];
        }
        double used = round2(bestW / (double) agg.SCALE);
        if (sum(qty) == 0 || used > L + 1e-5) return null;

        return new Column(Column.Type.NEW, -1, qty, used, L);
    }

    // ===== 定价：SCRAP =====
    private Column priceScrap(Agg agg, double scrapLen, int scrapIdx, double[] dualDem, int[] remainingDemand) {
        int CAP = (int) Math.round(scrapLen * agg.SCALE + 1e-9);
        if (CAP <= 0) return null;

        double[] dp = new double[CAP + 1];
        int[][] prev = new int[agg.types][CAP + 1];
        for (int w = 0; w <= CAP; w++) dp[w] = 0;
        for (int[] row : prev) Arrays.fill(row, 0);

        for (int t = 0; t < agg.types; t++) {
            int wt = agg.w[t];
            if (wt <= 0 || remainingDemand[t] <= 0) continue;
            int maxRep = Math.min(remainingDemand[t], CAP / wt);
            if (maxRep <= 0) continue;

            double[] ndp = Arrays.copyOf(dp, dp.length);
            int[][] nprev = new int[agg.types][CAP + 1];
            for (int i = 0; i < t; i++) {
                System.arraycopy(prev[i], 0, nprev[i], 0, CAP + 1);
            }

            for (int w = wt; w <= CAP; w++) {
                int bestR = 0;
                double bestVal = ndp[w];
                int can = Math.min(maxRep, w / wt);
                for (int r = 1; r <= can; r++) {
                    double cand = dp[w - r * wt] + r * dualDem[t];
                    if (cand > bestVal + 1e-12) {
                        bestVal = cand;
                        bestR = r;
                    }
                }
                if (bestR > 0) {
                    ndp[w] = bestVal;
                    for (int i = 0; i < t; i++) {
                        nprev[i][w] = prev[i][w - bestR * wt];
                    }
                    nprev[t][w] = bestR;
                } else {
                    for (int i = 0; i < t; i++) {
                        nprev[i][w] = prev[i][w];
                    }
                }
            }
            dp = ndp;
            prev = nprev;
        }

        int bestW = -1;
        double best = -1e100;
        for (int w = 0; w <= CAP; w++) {
            if (dp[w] > best + 1e-12) {
                best = dp[w];
                bestW = w;
            }
        }
        if (bestW < 0) return null;

        int[] qty = new int[agg.types];
        for (int t = 0; t < agg.types; t++) {
            qty[t] = prev[t][bestW];
        }
        double used = round2(bestW / (double) agg.SCALE);
        if (sum(qty) == 0 || used > scrapLen + 1e-5) return null;

        return new Column(Column.Type.SCRAP, scrapIdx, qty, used, scrapLen);
    }

    private double reducedCostNew(Column c, double[] dualDem) {
        return 1.0 - dot(c.qty, dualDem);
    }

    private double reducedCostScrap(Column c, double[] dualDem, double dualScr) {
        return dualScr - dot(c.qty, dualDem);
    }

    // ===== 整数化 =====
    static class IntSolution {
        final int[] mult;

        IntSolution(int[] m) {
            this.mult = m.clone();
        }
    }

    private IntSolution integerize(List<Column> cols, Agg agg, double[] scraps, double L) {
        MPSolver ip = MPSolver.createSolver("SCIP");
        if (ip == null) throw new IllegalStateException("SCIP not available");
        ip.setTimeLimit(60);

        MPObjective obj = ip.objective();
        obj.setMinimization();
        MPVariable[] z = new MPVariable[cols.size()];
        for (int k = 0; k < cols.size(); k++) {
            Column c = cols.get(k);
            if (c.type == Column.Type.NEW) {
                z[k] = ip.makeIntVar(0.0, 1000, "z_" + k);
                obj.setCoefficient(z[k], 1.0);
            } else {
                z[k] = ip.makeIntVar(0.0, 1.0, "z_" + k);
            }
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
        if (st == MPSolver.ResultStatus.OPTIMAL || st == MPSolver.ResultStatus.FEASIBLE) {
            int[] mult = new int[cols.size()];
            for (int k = 0; k < cols.size(); k++) {
                mult[k] = (int) Math.round(z[k].solutionValue());
            }
            return new IntSolution(mult);
        }

        throw new IllegalStateException("SCIP failed: " + st);
    }

    private IntSolution solveWithPenalizedRelaxation(List<Column> cols, Agg agg, double[] scraps, double L) {
        MPSolver ip = MPSolver.createSolver("SCIP");
        if (ip == null) throw new IllegalStateException("SCIP not available");

        MPObjective obj = ip.objective();
        obj.setMinimization();

        MPVariable[] z = new MPVariable[cols.size()];
        MPVariable[] excess = new MPVariable[agg.types];
        double PENALTY = 100.0;

        for (int k = 0; k < cols.size(); k++) {
            Column c = cols.get(k);
            if (c.type == Column.Type.NEW) {
                z[k] = ip.makeIntVar(0.0, 1000, "z_relax_" + k);
                obj.setCoefficient(z[k], 1.0);
            } else {
                z[k] = ip.makeIntVar(0.0, 1.0, "z_relax_" + k);
            }
        }

        for (int t = 0; t < agg.types; t++) {
            excess[t] = ip.makeIntVar(0.0, 100, "excess_" + t);
            obj.setCoefficient(excess[t], PENALTY);

            double lb = 0.95 * agg.demand[t];
            MPConstraint ct = ip.makeConstraint(lb, Double.POSITIVE_INFINITY, "dem_min_" + t);
            for (int k = 0; k < cols.size(); k++) {
                int a = cols.get(k).qty[t];
                if (a > 0) ct.setCoefficient(z[k], a);
            }

            MPConstraint ub = ip.makeConstraint(-Double.POSITIVE_INFINITY, agg.demand[t], "dem_ub_" + t);
            for (int k = 0; k < cols.size(); k++) {
                int a = cols.get(k).qty[t];
                if (a > 0) ub.setCoefficient(z[k], a);
            }
            ub.setCoefficient(excess[t], -1);
        }

        for (int i = 0; i < scraps.length; i++) {
            MPConstraint ct = ip.makeConstraint(0.0, 1.0, "scr_relax_" + i);
            for (int k = 0; k < cols.size(); k++) {
                if (cols.get(k).type == Column.Type.SCRAP && cols.get(k).scrapIdx == i) {
                    ct.setCoefficient(z[k], 1.0);
                }
            }
        }

        MPSolver.ResultStatus st = ip.solve();
        if (st == MPSolver.ResultStatus.OPTIMAL || st == MPSolver.ResultStatus.FEASIBLE) {
            int[] mult = new int[cols.size()];
            for (int k = 0; k < cols.size(); k++) {
                mult[k] = (int) Math.round(z[k].solutionValue());
            }
            return new IntSolution(mult);
        }

        throw new IllegalStateException("Penalized relaxation also failed: " + st);
    }

    // ===== 结果展开 =====
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
                    for (int cnt = 0; cnt < c.qty[t]; cnt++) {
                        if (q[t].isEmpty()) {
                            LOGGER.error("No more demand for length {}, but trying to produce", agg.lens[t]);
                            continue;
                        }
                        q[t].pollFirst();
                        cuts.add(agg.lens[t]);
                    }
                }
                double used = round2(cuts.stream().mapToDouble(Double::doubleValue).sum());
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

        for (int t = 0; t < agg.types; t++) {
            if (!q[t].isEmpty()) {
                LOGGER.warn("Unfulfilled demand for length {}: {} items left", agg.lens[t], q[t].size());
            }
        }

        return out;
    }

    // ===== 辅助 =====
    private AggMap aggregate(double[] items) {
        Map<Double, List<Integer>> map = new TreeMap<>();
        for (int i = 0; i < items.length; i++) {
            double rounded = Math.round(items[i] * 2.0) / 2.0;
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
        for (int i = 0; i < q.length; i++) {
            s += q[i] * v[i];
        }
        return round2(s);
    }

    private int sum(int[] a) {
        return Arrays.stream(a).sum();
    }

    private double round2(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}