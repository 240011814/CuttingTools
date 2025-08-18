package com.yhy.cutting.service;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.*;
import com.yhy.cutting.vo.BarRequest;
import com.yhy.cutting.vo.BarResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class CuttingBarService {

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
                this.w[i] = (int) Math.round(lens[i] * SCALE);
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
            this.qty = qty;
            this.used = used;
            this.capacity = capacity;
        }

        boolean isEmpty() {
            for (int q : qty) {
                if (q > 0) {
                    return false;
                }
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
        double[] scraps = (scrapsBD == null) ? new double[0] :
                scrapsBD.stream().mapToDouble(BigDecimal::doubleValue).toArray();

        AggMap map = aggregate(items);
        Agg agg = map.agg;

        // 容量刻度
        final int CAP = (int) Math.round(L * agg.SCALE);
        final int LOW1 = (int) Math.round((L - 50.0) * agg.SCALE);
        final int HIGH1 = CAP;
        final int LOW2 = 0;
        final int HIGH2 = (int) Math.round((L - 100.0) * agg.SCALE);

        // 1) 初始列：简单贪心产生若干 NEW 与 SCRAP 图样
        List<Column> columns = new ArrayList<>();
        seedColumns(agg, L, scraps, columns);

        // 2) 主问题 LP（GLOP）
        MPSolver master = MPSolver.createSolver("GLOP");
        if (master == null) {
            throw new IllegalStateException("GLOP not available");
        }
        MPObjective obj = master.objective();
        obj.setMinimization();

        // 需求约束：Σ_k a_{t,k} x_k >= demand[t]
        MPConstraint[] dem = new MPConstraint[agg.types];
        for (int t = 0; t < agg.types; t++) {
            dem[t] = master.makeConstraint(agg.demand[t], Double.POSITIVE_INFINITY, "dem_" + t);
        }

        // 旧料使用：每根 SCRAP 最多使用一次
        MPConstraint[] suse = new MPConstraint[scraps.length];
        for (int i = 0; i < scraps.length; i++) {
            suse[i] = master.makeConstraint(Double.NEGATIVE_INFINITY, 1.0, "scr_" + i);
        }

        List<MPVariable> x = new ArrayList<>();
        for (Column c : columns) {
            addColumnToMaster(master, obj, dem, suse, x, c);
        }

        // 3) 列生成：子问题定价（两个 NEW 窗口 + 所有 SCRAP）
        final int MAX_ITER = 300;
        for (int it = 0; it < MAX_ITER; it++) {
            MPSolver.ResultStatus st = master.solve();
            if (st != MPSolver.ResultStatus.OPTIMAL) {
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

            List<Column> newCols = new ArrayList<>();
            Column newHigh = priceNew(agg, L, dualDem, LOW1, HIGH1);
            if (newHigh != null && reducedCostNew(newHigh, dualDem) < -1e-9) {
                newCols.add(newHigh);
            }

            Column newLow = priceNew(agg, L, dualDem, LOW2, HIGH2);
            if (newLow != null && reducedCostNew(newLow, dualDem) < -1e-9) {
                newCols.add(newLow);
            }

            for (int i = 0; i < scraps.length; i++) {
                Column sc = priceScrap(agg, scraps[i], i, dualDem);
                if (sc != null && reducedCostScrap(sc, dualDem, dualScr[i]) < -1e-9) {
                    newCols.add(sc);
                }
            }

            if (newCols.isEmpty()) {
                break; // 无可用新列 -> 最优
            }

            for (Column c : newCols) {
                columns.add(c);
                addColumnToMaster(master, obj, dem, suse, x, c);
            }
        }

        // 4) 整数化（SCIP）：NEW 为 IntVar>=0，SCRAP 为 0/1
        IntSolution sol = integerize(columns, agg, scraps, L);

        // 5) 展开为具体 BarResult（把类型数量分配到具体 item）
        return toBarResults(columns, sol, agg, map.typeToItemIdx, L, scraps);
    }

    // ===== 初始列 =====
    private void seedColumns(Agg agg, double L, double[] scraps, List<Column> out) {
        // NEW: 单一类型
        for (int t = 0; t < agg.types; t++) {
            int maxRep = Math.max(1, (int) Math.floor(L / agg.lens[t] + 1e-9));
            if (maxRep <= 0) {
                continue;
            }
            int[] qty = new int[agg.types];
            qty[t] = Math.min(maxRep, agg.demand[t]);
            double used = qty[t] * agg.lens[t];
            if (used > 0) {
                out.add(new Column(Column.Type.NEW, -1, qty, used, L));
            }
        }

        // NEW: 简单混合（降序贪心）
        int[] q = new int[agg.types];
        double used = 0;
        Integer[] idx = new Integer[agg.types];
        for (int i = 0; i < agg.types; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> Double.compare(agg.lens[b], agg.lens[a]));
        for (int id : idx) {
            int can = Math.min(agg.demand[id], (int) Math.floor((L - used) / agg.lens[id] + 1e-9));
            if (can > 0) {
                q[id] += can;
                used += can * agg.lens[id];
            }
        }
        if (used > 0) {
            out.add(new Column(Column.Type.NEW, -1, q.clone(), used, L));
        }

        // SCRAP: 贪心
        for (int i = 0; i < scraps.length; i++) {
            int cap = (int) Math.round(scraps[i] * agg.SCALE);
            int[] qs = greedyPack(agg, cap);
            double u = dot(qs, agg.lens);
            if (u > 0) {
                out.add(new Column(Column.Type.SCRAP, i, qs, u, scraps[i]));
            }
        }
    }

    private int[] greedyPack(Agg agg, int cap) {
        int[] take = new int[agg.types];
        int load = 0;
        Integer[] idx = new Integer[agg.types];
        for (int i = 0; i < agg.types; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> Double.compare(agg.lens[b], agg.lens[a]));
        for (int id : idx) {
            int w = agg.w[id];
            if (w <= 0) {
                continue;
            }
            int can = Math.min(agg.demand[id], (cap - load) / w);
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
        MPVariable var = master.makeNumVar(0.0, Double.POSITIVE_INFINITY, "col_" + x.size());
        x.add(var);
        if (c.type == Column.Type.NEW) {
            obj.setCoefficient(var, 1.0); // NEW 成本=1
        }
        for (int t = 0; t < dem.length; t++) {
            if (c.qty[t] > 0) {
                dem[t].setCoefficient(var, c.qty[t]);
            }
        }
        if (c.type == Column.Type.SCRAP) {
            suse[c.scrapIdx].setCoefficient(var, 1.0);
        }
    }

    // ===== 定价：NEW（窗口） =====
    private Column priceNew(Agg agg, double L, double[] dualDem, int low, int high) {
        if (high < 0) {
            return null;
        }
        high = Math.min(high, (int) Math.round(L * agg.SCALE));
        low = Math.max(0, Math.min(low, high));
        int CAP = (int) Math.round(L * agg.SCALE);

        // 有界背包：dp[w] = 最大对偶和；记录每种类型的选择数量
        double[] dp = new double[CAP + 1];
        int[][] prev = new int[agg.types][CAP + 1];
        for (int w = 0; w <= CAP; w++) {
            dp[w] = 0;
        }
        for (int t = 0; t < agg.types; t++) {
            Arrays.fill(prev[t], 0);
        }

        for (int t = 0; t < agg.types; t++) {
            int wt = agg.w[t];
            if (wt <= 0) {
                continue;
            }
            int maxRep = Math.min(agg.demand[t], CAP / wt);
            if (maxRep <= 0) {
                continue;
            }
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
        for (int w = low; w <= high; w++) {
            if (dp[w] > best + 1e-12) {
                best = dp[w];
                bestW = w;
            }
        }
        if (bestW < 0) {
            return null;
        }
        int[] qty = new int[agg.types];
        for (int t = 0; t < agg.types; t++) {
            qty[t] = prev[t][bestW];
        }
        double used = bestW / (double) agg.SCALE;
        if (sum(qty) == 0) {
            return null;
        }
        return new Column(Column.Type.NEW, -1, qty, used, L);
    }

    // ===== 定价：SCRAP =====
    private Column priceScrap(Agg agg, double scrapLen, int scrapIdx, double[] dualDem) {
        int CAP = (int) Math.round(scrapLen * agg.SCALE);
        if (CAP <= 0) {
            return null;
        }
        double[] dp = new double[CAP + 1];
        int[][] prev = new int[agg.types][CAP + 1];
        for (int t = 0; t < agg.types; t++) {
            Arrays.fill(prev[t], 0);
        }
        for (int w = 0; w <= CAP; w++) {
            dp[w] = 0;
        }

        for (int t = 0; t < agg.types; t++) {
            int wt = agg.w[t];
            if (wt <= 0) {
                continue;
            }
            int maxRep = Math.min(agg.demand[t], CAP / wt);
            if (maxRep <= 0) {
                continue;
            }
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

        int bestW = 0;
        double best = -1e100;
        for (int w = 0; w <= CAP; w++) {
            if (dp[w] > best + 1e-12) {
                best = dp[w];
                bestW = w;
            }
        }
        if (best <= 1e-9) {
            return null; // 没贡献
        }
        int[] qty = new int[agg.types];
        for (int t = 0; t < agg.types; t++) {
            qty[t] = prev[t][bestW];
        }
        double used = bestW / (double) agg.SCALE;
        if (sum(qty) == 0) {
            return null;
        }
        return new Column(Column.Type.SCRAP, scrapIdx, qty, used, scrapLen);
    }

    private double reducedCostNew(Column c, double[] dualDem) {
        return 1.0 - dot(c.qty, dualDem);
    }

    private double reducedCostScrap(Column c, double[] dualDem, double dualScr) {
        return dualScr - dot(c.qty, dualDem); // cost=0
    }

    // ===== 整数化：SCIP =====
    static class IntSolution {
        final int[] mult;

        IntSolution(int[] m) {
            this.mult = m;
        }
    }

    private IntSolution integerize(List<Column> cols, Agg agg, double[] scraps, double L) {
        MPSolver ip = MPSolver.createSolver("SCIP");
        if (ip == null) {
            throw new IllegalStateException("SCIP not available");
        }
        
        // 增加求解时间，避免过早终止
        ip.setTimeLimit(60000);
        
        MPObjective obj = ip.objective();
        obj.setMinimization();
        MPVariable[] z = new MPVariable[cols.size()];
        for (int k = 0; k < cols.size(); k++) {
            Column c = cols.get(k);
            if (c.type == Column.Type.NEW) {
                z[k] = ip.makeIntVar(0.0, Double.POSITIVE_INFINITY, "z_" + k);
                obj.setCoefficient(z[k], 1.0);
            } else {
                z[k] = ip.makeIntVar(0.0, 1.0, "z_" + k);
            }
        }

        // 需求约束：允许少量误差
        for (int t = 0; t < agg.types; t++) {
            double lb = agg.demand[t] - 1e-6;  // 允许小误差
            double ub = agg.demand[t] + 1e-6;
            MPConstraint ct = ip.makeConstraint(lb, ub, "dem_" + t);
            for (int k = 0; k < cols.size(); k++) {
                int a = cols.get(k).qty[t];
                if (a > 0) {
                    ct.setCoefficient(z[k], a);
                }
            }
        }

        // SCRAP 约束保持不变
        for (int i = 0; i < scraps.length; i++) {
            MPConstraint ct = ip.makeConstraint(Double.NEGATIVE_INFINITY, 1.0, "scr_" + i);
            for (int k = 0; k < cols.size(); k++) {
                if (cols.get(k).type == Column.Type.SCRAP && cols.get(k).scrapIdx == i) {
                    ct.setCoefficient(z[k], 1.0);
                }
            }
        }

        // 增加求解重试
        MPSolver.ResultStatus st = ip.solve();
        int attempts = 0;
        while (st != MPSolver.ResultStatus.OPTIMAL && st != MPSolver.ResultStatus.FEASIBLE && attempts < 3) {
            attempts++;
            st = ip.solve();
        }
        
        if (st != MPSolver.ResultStatus.OPTIMAL && st != MPSolver.ResultStatus.FEASIBLE) {
            throw new IllegalStateException("Failed to find integer solution: " + st);
        }

        int[] mult = new int[cols.size()];
        for (int k = 0; k < cols.size(); k++) {
            mult[k] = (int) Math.round(z[k].solutionValue());
        }
        return new IntSolution(mult);
    }

    // 添加新的辅助方法处理不可行情况
    private IntSolution solveWithRelaxedConstraints(MPSolver ip, List<Column> cols, Agg agg, MPVariable[] z) {
        // 清除所有约束
        ip.clear();
        
        MPObjective obj = ip.objective();
        obj.setMinimization();
        
        // 重新添加变量
        for (int k = 0; k < cols.size(); k++) {
            Column c = cols.get(k);
            if (c.type == Column.Type.NEW) {
                obj.setCoefficient(z[k], 1.0);
            }
        }
        
        // 添加松弛后的需求约束
        for (int t = 0; t < agg.types; t++) {
            MPConstraint ct = ip.makeConstraint(0.95 * agg.demand[t], Double.POSITIVE_INFINITY, "demRelax_" + t);
            for (int k = 0; k < cols.size(); k++) {
                int a = cols.get(k).qty[t];
                if (a > 0) {
                    ct.setCoefficient(z[k], a);
                }
            }
        }
        
        MPSolver.ResultStatus st = ip.solve();
        if (st != MPSolver.ResultStatus.OPTIMAL && st != MPSolver.ResultStatus.FEASIBLE) {
            throw new IllegalStateException("Relaxed problem still infeasible: " + st);
        }
        
        int[] mult = new int[cols.size()];
        for (int k = 0; k < cols.size(); k++) {
            mult[k] = (int) Math.round(z[k].solutionValue());
        }
        return new IntSolution(mult);
    }

    // ===== 结果展开 =====
    private List<BarResult> toBarResults(List<Column> cols, IntSolution sol, Agg agg, List<List<Integer>> typeToItemIdx,
                                         double L, double[] scraps) {
        List<BarResult> out = new ArrayList<>();
        // 为每个类型维护一个队列（原始 item 下标），按需弹出
        Deque<Integer>[] q = new ArrayDeque[agg.types];
        for (int t = 0; t < agg.types; t++) {
            q[t] = new ArrayDeque<>(typeToItemIdx.get(t));
        }

        int newIdx = 1;
        for (int k = 0; k < cols.size(); k++) {
            int m = sol.mult[k];
            if (m <= 0) {
                continue;
            }
            Column c = cols.get(k);
            for (int r = 0; r < m; r++) {
                List<Double> cuts = new ArrayList<>();
                for (int t = 0; t < agg.types; t++) {
                    for (int cnt = 0; cnt < c.qty[t]; cnt++) {
                        Integer id = q[t].pollFirst();
                        // id 只是用于占位消费；输出长度即可
                        cuts.add(agg.lens[t]);
                    }
                }
                double used = round2(cuts.stream().mapToDouble(Double::doubleValue).sum());
                double cap = c.capacity;
                double rem = round2(cap - used);
                if (c.type == Column.Type.NEW) {
                    out.add(BarResult.builder()
                            .index(newIdx++)
                            .totalLength(L)
                            .cuts(cuts)
                            .used(used)
                            .remaining(rem)
                            .build());
                } else {
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

    private static double dot(int[] q, double[] v) {
        double s = 0;
        for (int i = 0; i < q.length; i++) {
            s += q[i] * v[i];
        }
        return s;
    }

    private static int sum(int[] a) {
        int s = 0;
        for (int v : a) {
            s += v;
        }
        return s;
    }

    private static double round2(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}