package com.yhy.cutting.service;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import com.google.ortools.sat.*;
import com.yhy.cutting.vo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CuttingOptimizerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CuttingOptimizerService.class);
    private static final int SCALE = 1000;

    public List<BinResult> plane(List<Item> items, List<MaterialType> availableMaterials) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }

        List<MaterialType> sortedMaterials = availableMaterials != null
                ? availableMaterials.stream()
                .sorted(Comparator.comparingDouble(m -> m.getWidth() * m.getHeight()))
                .collect(Collectors.toList())
                : new ArrayList<>();

        try {
            Loader.loadNativeLibraries();

            List<MaterialInstance> materialInstances = prepareMaterialInstances(sortedMaterials);

            for (int i = 0; i < 100; i++) {
                materialInstances.add(new MaterialInstance("新2x2板材", 2.0, 2.0));
            }

            int numBins = materialInstances.size();

            int n = items.size();
            System.out.println("优化项目数量: " + n + ", 可用材料实例数量: " + numBins);

            CpModel model = new CpModel();

            BoolVar[][] inBin = new BoolVar[n][numBins];
            BoolVar[] binUsed = new BoolVar[numBins];
            BoolVar[][] placeNR = new BoolVar[n][numBins];
            BoolVar[][] placeR = new BoolVar[n][numBins];

            IntVar[][] xNR = new IntVar[n][numBins];
            IntVar[][] yNR = new IntVar[n][numBins];
            IntVar[][] xEndNR = new IntVar[n][numBins];
            IntVar[][] yEndNR = new IntVar[n][numBins];
            IntervalVar[][] xItvNR = new IntervalVar[n][numBins];
            IntervalVar[][] yItvNR = new IntervalVar[n][numBins];

            IntVar[][] xR = new IntVar[n][numBins];
            IntVar[][] yR = new IntVar[n][numBins];
            IntVar[][] xEndR = new IntVar[n][numBins];
            IntVar[][] yEndR = new IntVar[n][numBins];
            IntervalVar[][] xItvR = new IntervalVar[n][numBins];
            IntervalVar[][] yItvR = new IntervalVar[n][numBins];

            int[] wNRi = new int[n];
            int[] hNRi = new int[n];
            int[] wRi = new int[n];
            int[] hRi = new int[n];
            for (int i = 0; i < n; i++) {
                double w = items.get(i).getWidth();
                double h = items.get(i).getHeight();
                wNRi[i] = scale(w);
                hNRi[i] = scale(h);
                wRi[i] = scale(h);
                hRi[i] = scale(w);
            }

            for (int b = 0; b < numBins; b++) {
                binUsed[b] = model.newBoolVar("binUsed_" + b);
                MaterialInstance m = materialInstances.get(b);
                int BW = scale(m.getWidth());
                int BH = scale(m.getHeight());

                NoOverlap2dConstraint noOverlap2d = model.addNoOverlap2D();

                for (int i = 0; i < n; i++) {
                    inBin[i][b] = model.newBoolVar("inBin_" + i + "_" + b);
                    placeNR[i][b] = model.newBoolVar("placeNR_" + i + "_" + b);
                    placeR[i][b] = model.newBoolVar("placeR_" + i + "_" + b);

                    model.addEquality(
                            LinearExpr.sum(new BoolVar[]{placeNR[i][b], placeR[i][b]}),
                            inBin[i][b]
                    );

                    xNR[i][b] = model.newIntVar(0L, (long) Math.max(0, BW - wNRi[i]), "xNR_" + i + "_" + b);
                    yNR[i][b] = model.newIntVar(0L, (long) Math.max(0, BH - hNRi[i]), "yNR_" + i + "_" + b);
                    xEndNR[i][b] = model.newIntVar(0L, (long) BW, "xEndNR_" + i + "_" + b);
                    yEndNR[i][b] = model.newIntVar(0L, (long) BH, "yEndNR_" + i + "_" + b);

                    xItvNR[i][b] = model.newOptionalIntervalVar(xNR[i][b], LinearExpr.constant(wNRi[i]), xEndNR[i][b], placeNR[i][b], "xItvNR_" + i + "_" + b);
                    yItvNR[i][b] = model.newOptionalIntervalVar(yNR[i][b], LinearExpr.constant(hNRi[i]), yEndNR[i][b], placeNR[i][b], "yItvNR_" + i + "_" + b);

                    xR[i][b] = model.newIntVar(0L, (long) Math.max(0, BW - wRi[i]), "xR_" + i + "_" + b);
                    yR[i][b] = model.newIntVar(0L, (long) Math.max(0, BH - hRi[i]), "yR_" + i + "_" + b);
                    xEndR[i][b] = model.newIntVar(0L, (long) BW, "xEndR_" + i + "_" + b);
                    yEndR[i][b] = model.newIntVar(0L, (long) BH, "yEndR_" + i + "_" + b);

                    xItvR[i][b] = model.newOptionalIntervalVar(xR[i][b], LinearExpr.constant(wRi[i]), xEndR[i][b], placeR[i][b], "xItvR_" + i + "_" + b);
                    yItvR[i][b] = model.newOptionalIntervalVar(yR[i][b], LinearExpr.constant(hRi[i]), yEndR[i][b], placeR[i][b], "yItvR_" + i + "_" + b);

                    noOverlap2d.addRectangle(xItvNR[i][b], yItvNR[i][b]);
                    noOverlap2d.addRectangle(xItvR[i][b], yItvR[i][b]);
                }

                BoolVar[] inThisBin = new BoolVar[n];
                for (int i = 0; i < n; i++) inThisBin[i] = inBin[i][b];
                model.addMaxEquality(binUsed[b], inThisBin);
            }

            for (int i = 0; i < n; i++) {
                BoolVar[] row = new BoolVar[numBins];
                for (int b = 0; b < numBins; b++) row[b] = inBin[i][b];
                model.addEquality(LinearExpr.sum(row), 1);
            }

            model.minimize(LinearExpr.sum(binUsed));

            CpSolver solver = new CpSolver();
            SatParameters.Builder params = solver.getParameters();
            params.setMaxTimeInSeconds(120);
            params.setNumSearchWorkers(8);
            params.setUseOptionalVariables(true);
            params.setLogSearchProgress(true);
            params.setCpModelProbingLevel(3);
            //params.setSearchBranching(SatParameters.SearchBranching.FIXED_SEARCH);
            solver.getParameters().mergeFrom(params.build());

            CpSolverStatus status = solver.solve(model);

            if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
                System.out.println("OR-Tools求解成功: " + status);
                return buildResultsFromSolver(solver, items, materialInstances, numBins, inBin, placeNR, placeR, xNR, yNR, xR, yR);
            } else {
                System.out.println("未找到可行解: " + status);
                return Collections.emptyList();
            }

        } catch (Exception e) {
            System.err.println("OR-Tools求解异常: " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    private List<BinResult> buildResultsFromSolver(CpSolver solver, List<Item> items, List<MaterialInstance> materialInstances, int numBins, BoolVar[][] inBin, BoolVar[][] placeNR, BoolVar[][] placeR, IntVar[][] xNR, IntVar[][] yNR, IntVar[][] xR, IntVar[][] yR) {
        int n = items.size();

        Map<Integer, List<Piece>> binPieces = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int assignedBin = -1;
            boolean rotated = false;
            double x = 0, y = 0;
            double w = items.get(i).getWidth();
            double h = items.get(i).getHeight();

            for (int b = 0; b < numBins; b++) {
                if (solver.booleanValue(inBin[i][b])) {
                    assignedBin = b;
                    boolean nr = solver.booleanValue(placeNR[i][b]);
                    boolean rr = solver.booleanValue(placeR[i][b]);
                    if (nr) {
                        rotated = false;
                        x = unscale(solver.value(xNR[i][b]));
                        y = unscale(solver.value(yNR[i][b]));
                    } else if (rr) {
                        rotated = true;
                        x = unscale(solver.value(xR[i][b]));
                        y = unscale(solver.value(yR[i][b]));
                        double tmp = w;
                        w = h;
                        h = tmp;
                    }
                    break;
                }
            }

            if (assignedBin >= 0) {
                Piece p = new Piece();
                p.setLabel(items.get(i).getLabel());
                p.setX(round3(x));
                p.setY(round3(y));
                p.setW(w);
                p.setH(h);
                p.setRotated(rotated);
                binPieces.computeIfAbsent(assignedBin, k -> new ArrayList<>()).add(p);
            }
        }

        List<BinResult> results = new ArrayList<>();
        int resultBinId = 0;
        for (Map.Entry<Integer, List<Piece>> e : binPieces.entrySet()) {
            int b = e.getKey();
            MaterialInstance mi = materialInstances.get(b);
            List<Piece> pieces = e.getValue();

            BinResult br = new BinResult();
            br.setBinId(resultBinId++);
            br.setMaterialType(mi.getOriginalName());
            br.setMaterialWidth(mi.getWidth());
            br.setMaterialHeight(mi.getHeight());
            br.setPieces(pieces);

            double materialArea = mi.getWidth() * mi.getHeight();
            double usedArea = pieces.stream().mapToDouble(p -> p.getW() * p.getH()).sum();
            double utilization = materialArea > 0 ? (usedArea / materialArea) * 100.0 : 0.0;
            br.setUtilization(Math.round(utilization * 100.0) / 100.0);

            results.add(br);
        }
        results.sort(Comparator.comparingInt(BinResult::getBinId));
        return results;
    }

    private List<MaterialInstance> prepareMaterialInstances(List<MaterialType> availableMaterials) {
        List<MaterialInstance> instances = new ArrayList<>();
        if (availableMaterials != null) {
            for (MaterialType material : availableMaterials) {
                int cnt = Math.max(0, material.getAvailableCount());
                for (int i = 0; i < cnt; i++) {
                    instances.add(new MaterialInstance(material.getName(), material.getWidth(), material.getHeight()));
                }
            }
        }
        return instances;
    }

    private static class MaterialInstance {
        private final String originalName;
        private final double width;
        private final double height;

        public MaterialInstance(String originalName, double width, double height) {
            this.originalName = originalName;
            this.width = width;
            this.height = height;
        }

        public String getOriginalName() {
            return originalName;
        }

        public double getWidth() {
            return width;
        }

        public double getHeight() {
            return height;
        }
    }

    private static int scale(double v) {
        return (int) Math.round(v * SCALE);
    }

    private static double unscale(long v) {
        return ((double) v) / SCALE;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }


    public List<BarResult> bar(BarRequest request) {
        List<BarResult> results = new ArrayList<>();
        BigDecimal newMaterialLengthCM = request.getNewMaterialLength();
        double newMaterialLength = newMaterialLengthCM.doubleValue();

        List<BigDecimal> needsBD = request.getItems();
        double[] needs = needsBD.stream().mapToDouble(BigDecimal::doubleValue).toArray();
        int n = needs.length;

        List<BigDecimal> scrapLengthsBD = request.getMaterials();
        double[] scrapLengths = scrapLengthsBD.stream().mapToDouble(BigDecimal::doubleValue).toArray();
        int numScrap = scrapLengths.length;

        int maxNewBins = n;

        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            LOGGER.error("Could not create solver SCIP");
            return results;
        }

        // ========================
        // 变量定义（不变）
        // ========================
        MPVariable[][] yOld = new MPVariable[numScrap][n];
        for (int i = 0; i < numScrap; i++) {
            for (int j = 0; j < n; j++) {
                yOld[i][j] = solver.makeBoolVar("y_old[" + i + "][" + j + "]");
            }
        }

        MPVariable[] xNew = new MPVariable[maxNewBins];
        MPVariable[][] yNew = new MPVariable[maxNewBins][n];
        for (int i = 0; i < maxNewBins; i++) {
            xNew[i] = solver.makeBoolVar("x_new[" + i + "]");
            for (int j = 0; j < n; j++) {
                yNew[i][j] = solver.makeBoolVar("y_new[" + i + "][" + j + "]");
            }
        }

        // ========================
        // 约束（1~6）保持不变
        // ========================

        // 约束1: 每个需求只能分配一次
        for (int j = 0; j < n; j++) {
            MPConstraint ct = solver.makeConstraint(1.0, 1.0);
            for (int i = 0; i < numScrap; i++) ct.setCoefficient(yOld[i][j], 1.0);
            for (int i = 0; i < maxNewBins; i++) ct.setCoefficient(yNew[i][j], 1.0);
        }

        // 约束2: 旧余料容量
        for (int i = 0; i < numScrap; i++) {
            MPConstraint ct = solver.makeConstraint(0.0, scrapLengths[i]);
            for (int j = 0; j < n; j++) {
                ct.setCoefficient(yOld[i][j], needs[j]);
            }
        }

        // 约束3: 新材料容量
        for (int i = 0; i < maxNewBins; i++) {
            MPConstraint ct = solver.makeConstraint(-Double.MAX_VALUE, 0.0);
            ct.setCoefficient(xNew[i], -newMaterialLength);
            for (int j = 0; j < n; j++) {
                ct.setCoefficient(yNew[i][j], needs[j]);
            }
        }

        // 对称性破除
        for (int i = 1; i < maxNewBins; i++) {
            MPConstraint ct = solver.makeConstraint(-Double.MAX_VALUE, 0.0);
            ct.setCoefficient(xNew[i], 1);
            ct.setCoefficient(xNew[i - 1], -1);
        }

        // 新材料余料限制：剩余 ≤50cm 或 ≥100cm
        double USAGE_MIN_HIGH = newMaterialLength - 50.0;   // used >= 595
        double USAGE_MAX_LOW = newMaterialLength - 100.0;  // used <= 500
        double M1 = newMaterialLength - USAGE_MAX_LOW;
        double M2 = USAGE_MIN_HIGH;

        MPVariable[] z1 = new MPVariable[maxNewBins];
        MPVariable[] z2 = new MPVariable[maxNewBins];
        MPVariable[] usedNew = new MPVariable[maxNewBins];

        for (int i = 0; i < maxNewBins; i++) {
            usedNew[i] = solver.makeNumVar(0.0, newMaterialLength, "used_new[" + i + "]");
            z1[i] = solver.makeBoolVar("z1[" + i + "]");
            z2[i] = solver.makeBoolVar("z2[" + i + "]");

            MPConstraint ctUsed = solver.makeConstraint(0.0, 0.0);
            ctUsed.setCoefficient(usedNew[i], -1.0);
            for (int j = 0; j < n; j++) {
                ctUsed.setCoefficient(yNew[i][j], needs[j]);
            }

            // z1[i]=1 ⇒ used >= 595
            MPConstraint ct1 = solver.makeConstraint(USAGE_MIN_HIGH - M2, Double.MAX_VALUE);
            ct1.setCoefficient(usedNew[i], 1.0);
            ct1.setCoefficient(z1[i], -M2);

            // z2[i]=1 ⇒ used <= 500
            MPConstraint ct2 = solver.makeConstraint(-Double.MAX_VALUE, USAGE_MAX_LOW + M1);
            ct2.setCoefficient(usedNew[i], 1.0);
            ct2.setCoefficient(z2[i], M1);

            // z1 + z2 >= xNew[i]
            MPConstraint ct3 = solver.makeConstraint(0.0, Double.MAX_VALUE);
            ct3.setCoefficient(z1[i], 1.0);
            ct3.setCoefficient(z2[i], 1.0);
            ct3.setCoefficient(xNew[i], -1.0);
        }

        // ========================
        // 🌟 第一阶段：最小化新材料数量
        // ========================
        MPObjective objective = solver.objective();
        objective.setMinimization();
        for (int i = 0; i < maxNewBins; i++) {
            objective.setCoefficient(xNew[i], 1.0);
        }

        MPSolver.ResultStatus status1 = solver.solve();
        if (status1 != MPSolver.ResultStatus.OPTIMAL && status1 != MPSolver.ResultStatus.FEASIBLE) {
            LOGGER.error("❌ 第一阶段未找到可行解。状态: {}", status1);
            return results;
        }

        double minNewBars = objective.value();

        // ========================
        // 🌟 第二阶段：固定新材料数量，最大化余料使用
        // ========================
        //  solver.clear();
        MPObjective obj2 = solver.objective();
        obj2.setMaximization();

        // 目标：尽可能多地使用余料（按长度加权）
        for (int i = 0; i < numScrap; i++) {
            for (int j = 0; j < n; j++) {
                obj2.setCoefficient(yOld[i][j], needs[j]); // 每使用 1cm 余料 +1 分
            }
        }

        // 添加约束：新材料数量不能超过第一阶段结果
        MPConstraint limitNew = solver.makeConstraint(-Double.MAX_VALUE, minNewBars);
        for (MPVariable var : xNew) {
            limitNew.setCoefficient(var, 1.0);
        }

        // 重新求解
        MPSolver.ResultStatus resultStatus = solver.solve();
        if (resultStatus != MPSolver.ResultStatus.OPTIMAL && resultStatus != MPSolver.ResultStatus.FEASIBLE) {
            LOGGER.error("❌ 未找到可行解。状态: {}", resultStatus);
            return results;
        }

        LOGGER.info("✅ 找到可行解！");
        int usedNewCount = (int) Arrays.stream(xNew).filter(var -> var.solutionValue() > 0.5).count();
        LOGGER.info("使用旧余料: {} 根", numScrap);
        LOGGER.info("启用新材料: {} 根", usedNewCount);

        // --- 旧余料使用情况 ---
        for (int i = 0; i < numScrap; i++) {
            List<Double> cuts = new ArrayList<>();
            BigDecimal sum = BigDecimal.ZERO;
            for (int j = 0; j < n; j++) {
                if (yOld[i][j].solutionValue() > 0.5) {
                    double len = needsBD.get(j).doubleValue();
                    cuts.add(len);
                    sum = sum.add(BigDecimal.valueOf(len));
                }
            }
            if (!cuts.isEmpty()) {
                double original = scrapLengthsBD.get(i).doubleValue();
                double used = sum.doubleValue();
                double remaining = new BigDecimal(original).subtract(new BigDecimal(used)).setScale(2, RoundingMode.HALF_UP).doubleValue();
                results.add(BarResult.builder()
                        .index(i + 1)
                        .totalLength(original)
                        .used(used)
                        .remaining(remaining)
                        .cuts(cuts)
                        .build());
            }
        }

        // --- 新材料使用情况 ---
        int newBinIndex = 1;
        for (int i = 0; i < maxNewBins; i++) {
            if (xNew[i].solutionValue() > 0.5) {
                List<Double> cuts = new ArrayList<>();
                BigDecimal sum = BigDecimal.ZERO;
                for (int j = 0; j < n; j++) {
                    if (yNew[i][j].solutionValue() > 0.5) {
                        double len = needsBD.get(j).doubleValue();
                        cuts.add(len);
                        sum = sum.add(BigDecimal.valueOf(len));
                    }
                }
                double used = sum.doubleValue();
                double remaining = new BigDecimal(newMaterialLengthCM.doubleValue()).subtract(new BigDecimal(used)).setScale(2, RoundingMode.HALF_UP).doubleValue();

                results.add(BarResult.builder()
                        .index(newBinIndex++)
                        .totalLength(newMaterialLength)
                        .used(used)
                        .remaining(remaining)
                        .cuts(cuts)
                        .build());
            }
        }

        return results;
    }


    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        Loader.loadNativeLibraries();
    }
}
