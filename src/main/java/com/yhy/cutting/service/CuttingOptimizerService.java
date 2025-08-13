package com.yhy.cutting.service;

import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import com.yhy.cutting.vo.BinResult;
import com.yhy.cutting.vo.Item;
import com.yhy.cutting.vo.Piece;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.IntStream;

@Service
public class CuttingOptimizerService {

    private static final double BIN_WIDTH = 2.0;
    private static final double BIN_HEIGHT = 2.0;
    private static final int MAX_BINS = 20;
    private static final int SCALE = 1000; // 提高精度到毫米级

    public List<BinResult> optimize(List<Item> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }

        Loader.loadNativeLibraries();
        CpModel model = new CpModel();
        int n = items.size();

        System.out.println("优化项目数量: " + n);

        // ========== 变量定义 ==========
        IntVar[] binVars = new IntVar[n];
        IntVar[] xVars = new IntVar[n];
        IntVar[] yVars = new IntVar[n];
        IntVar[] wVars = new IntVar[n];
        IntVar[] hVars = new IntVar[n];
        BoolVar[] rotated = new BoolVar[n];

        for (int i = 0; i < n; i++) {
            double w = items.get(i).getWidth();
            double h = items.get(i).getHeight();
            int wi = (int)(w * SCALE);
            int hi = (int)(h * SCALE);

            binVars[i] = model.newIntVar(0, MAX_BINS - 1, "bin_" + i);
            xVars[i] = model.newIntVar(0, (int)(BIN_WIDTH * SCALE), "x_" + i);
            yVars[i] = model.newIntVar(0, (int)(BIN_HEIGHT * SCALE), "y_" + i);
            rotated[i] = model.newBoolVar("rot_" + i);

            wVars[i] = model.newIntVar(0, (int)(BIN_WIDTH * SCALE), "w_" + i);
            hVars[i] = model.newIntVar(0, (int)(BIN_HEIGHT * SCALE), "h_" + i);

            // 宽高根据旋转选择
            model.addEquality(wVars[i], wi).onlyEnforceIf(rotated[i].not());
            model.addEquality(hVars[i], hi).onlyEnforceIf(rotated[i].not());
            model.addEquality(wVars[i], hi).onlyEnforceIf(rotated[i]);
            model.addEquality(hVars[i], wi).onlyEnforceIf(rotated[i]);
        }

        // ========== 使用 NoOverlap2D 约束 ==========
        // 为每个 bin 创建 NoOverlap2D 约束
        for (int b = 0; b < MAX_BINS; b++) {
            NoOverlap2dConstraint noOverlap2D = model.addNoOverlap2D();

            // 为当前 bin 收集所有可能的间隔变量
            for (int i = 0; i < n; i++) {
                BoolVar isInBin = model.newBoolVar("is_in_bin_" + i + "_" + b);
                model.addEquality(binVars[i], b).onlyEnforceIf(isInBin);
                model.addDifferent(binVars[i], b).onlyEnforceIf(isInBin.not());

                // 创建结束变量
                IntVar endX = model.newIntVar(0, (int)(BIN_WIDTH * SCALE), "end_x_" + i + "_" + b);
                IntVar endY = model.newIntVar(0, (int)(BIN_HEIGHT * SCALE), "end_y_" + i + "_" + b);

                // 约束结束位置 = 起始位置 + 尺寸
                model.addEquality(endX, LinearExpr.newBuilder().add(xVars[i]).add(wVars[i]).build());
                model.addEquality(endY, LinearExpr.newBuilder().add(yVars[i]).add(hVars[i]).build());

                // 创建可选间隔变量并添加到 NoOverlap2D 约束中
                IntervalVar xInterval = model.newOptionalIntervalVar(xVars[i], wVars[i], endX, isInBin, "x_interval_" + i + "_" + b);
                IntervalVar yInterval = model.newOptionalIntervalVar(yVars[i], hVars[i], endY, isInBin, "y_interval_" + i + "_" + b);

                noOverlap2D.addRectangle(xInterval, yInterval);
            }
        }

        // ========== 边界约束 ==========
        for (int i = 0; i < n; i++) {
            model.addLessOrEqual(
                    LinearExpr.newBuilder().add(xVars[i]).add(wVars[i]).build(),
                    (int)(BIN_WIDTH * SCALE)
            );
            model.addLessOrEqual(
                    LinearExpr.newBuilder().add(yVars[i]).add(hVars[i]).build(),
                    (int)(BIN_HEIGHT * SCALE)
            );
        }

        // ========== 对称性破缺约束 ==========
        // 强制bin按使用顺序编号
        for (int b = 1; b < MAX_BINS; b++) {
            BoolVar usedBefore = model.newBoolVar("used_before_" + b);
            BoolVar usedCurrent = model.newBoolVar("used_current_" + b);

            // 检查前面是否有物品分配到bin b-1
            List<BoolVar> assignedToPrev = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                BoolVar assigned = model.newBoolVar("assigned_" + i + "_to_" + (b-1));
                model.addEquality(binVars[i], b-1).onlyEnforceIf(assigned);
                assignedToPrev.add(assigned);
            }
            if (!assignedToPrev.isEmpty()) {
                model.addGreaterOrEqual(LinearExpr.sum(assignedToPrev.toArray(new BoolVar[0])), 1)
                        .onlyEnforceIf(usedBefore);
                model.addEquality(LinearExpr.sum(assignedToPrev.toArray(new BoolVar[0])), 0)
                        .onlyEnforceIf(usedBefore.not());
            }

            // 检查当前bin是否被使用
            List<BoolVar> assignedToCurrent = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                BoolVar assigned = model.newBoolVar("assigned_" + i + "_to_" + b);
                model.addEquality(binVars[i], b).onlyEnforceIf(assigned);
                assignedToCurrent.add(assigned);
            }
            if (!assignedToCurrent.isEmpty()) {
                model.addGreaterOrEqual(LinearExpr.sum(assignedToCurrent.toArray(new BoolVar[0])), 1)
                        .onlyEnforceIf(usedCurrent);
                model.addEquality(LinearExpr.sum(assignedToCurrent.toArray(new BoolVar[0])), 0)
                        .onlyEnforceIf(usedCurrent.not());
            }

            // 如果当前bin被使用，前面的bin必须也被使用
            model.addImplication(usedCurrent, usedBefore);
        }

        // ========== 目标：最小化使用的 bin 数量 ==========
        BoolVar[] usedBins = new BoolVar[MAX_BINS];
        for (int b = 0; b < MAX_BINS; b++) {
            usedBins[b] = model.newBoolVar("used_" + b);
            BoolVar[] isAssigned = new BoolVar[n];
            for (int i = 0; i < n; i++) {
                isAssigned[i] = model.newBoolVar("assign_" + i + "_" + b);
                model.addEquality(binVars[i], b).onlyEnforceIf(isAssigned[i]);
                model.addDifferent(binVars[i], b).onlyEnforceIf(isAssigned[i].not());
            }

            LinearExpr numAssigned = LinearExpr.sum(isAssigned);
            model.addGreaterOrEqual(numAssigned, 1).onlyEnforceIf(usedBins[b]);
            model.addEquality(numAssigned, 0).onlyEnforceIf(usedBins[b].not());
        }

        IntVar numUsed = model.newIntVar(0, MAX_BINS, "num_used");
        model.addEquality(numUsed, LinearExpr.sum(usedBins));
        model.minimize(numUsed);

        // ========== 添加决策策略 ==========
        // 按物品面积降序排列，优先处理大物品
        List<Integer> itemIndices = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            itemIndices.add(i);
        }
        itemIndices.sort((i, j) -> {
            double areaI = items.get(i).getWidth() * items.get(i).getHeight();
            double areaJ = items.get(j).getWidth() * items.get(j).getHeight();
            return Double.compare(areaJ, areaI); // 降序
        });

        // 为大物品添加决策策略
        List<LinearArgument> decisionVars = new ArrayList<>();
        for (int i : itemIndices) {
            decisionVars.add(binVars[i]);
            decisionVars.add(xVars[i]);
            decisionVars.add(yVars[i]);
        }

        if (!decisionVars.isEmpty()) {
            model.addDecisionStrategy(
                    decisionVars.toArray(new LinearArgument[0]),
                    DecisionStrategyProto.VariableSelectionStrategy.CHOOSE_LOWEST_MIN,
                    DecisionStrategyProto.DomainReductionStrategy.SELECT_MIN_VALUE
            );
        }

        // ========== 求解 ==========
        CpSolver solver = new CpSolver();

        // 优化求解器参数
        SatParameters.Builder parameters = solver.getParameters();
        parameters.setMaxTimeInSeconds(120); // 增加到120秒
        parameters.setNumWorkers(1); // 使用单线程确保确定性
   //     parameters.setLogSearchProgress(false); // 减少日志输出
        parameters.setCpModelProbingLevel(2); // 增强探测
        parameters.setCpModelPresolve(true);
        parameters.setUseOptionalVariables(true);
        parameters.setSearchBranching(SatParameters.SearchBranching.FIXED_SEARCH); // 固定搜索策略

        solver.getParameters().mergeFrom(parameters.build());

        System.out.println("开始求解...");
        CpSolverStatus status = solver.solve(model);

        List<BinResult> results = new ArrayList<>();
        if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
            System.out.println("求解完成: " + status);
            Map<Integer, BinResult> map = new HashMap<>();
            double totalArea = BIN_WIDTH * BIN_HEIGHT;

            for (int i = 0; i < n; i++) {
                int binId = (int) solver.value(binVars[i]);
                map.computeIfAbsent(binId, k -> {
                    BinResult br = new BinResult();
                    br.setBinId(k);
                    br.setPieces(new ArrayList<>());
                    return br;
                });

                Piece p = new Piece();
                p.setLabel(items.get(i).getLabel());
                p.setX(Math.round(solver.value(xVars[i]) / (double)SCALE * 1000.0) / 1000.0); // 保留3位小数
                p.setY(Math.round(solver.value(yVars[i]) / (double)SCALE * 1000.0) / 1000.0);
                p.setW(Math.round(solver.value(wVars[i]) / (double)SCALE * 1000.0) / 1000.0);
                p.setH(Math.round(solver.value(hVars[i]) / (double)SCALE * 1000.0) / 1000.0);
                p.setRotated(solver.value(rotated[i]) > 0.5);

                map.get(binId).getPieces().add(p);
            }

            for (BinResult br : map.values()) {
                double usedArea = br.getPieces().stream().mapToDouble(p -> p.getW() * p.getH()).sum();
                double utilization = (usedArea / totalArea) * 100;
                br.setUtilization(Math.round(utilization * 100.0) / 100.0); // 保留两位小数
            }

            results.addAll(map.values());
            results.sort(Comparator.comparingInt(BinResult::getBinId));

            System.out.println("使用bin数量: " + results.size());
            for (BinResult br : results) {
                System.out.println("Bin " + br.getBinId() + ": " + br.getPieces().size() + " 个项目, 利用率: " + br.getUtilization() + "%");
            }
        } else {
            System.out.println("求解失败: " + status);
        }

        return results;
    }
}