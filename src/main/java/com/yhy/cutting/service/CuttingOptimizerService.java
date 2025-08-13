package com.yhy.cutting.service;

import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import com.yhy.cutting.vo.BinResult;
import com.yhy.cutting.vo.Item;
import com.yhy.cutting.vo.Piece;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CuttingOptimizerService {

    private static final double BIN_WIDTH = 2.0;
    private static final double BIN_HEIGHT = 2.0;
    private static final int MAX_BINS = 10;
    private static final int SCALE = 100; // 单位：厘米

    public List<BinResult> optimize(List<Item> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }

        Loader.loadNativeLibraries();
        CpModel model = new CpModel();
        int n = items.size();

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

        // ========== 手动添加不重叠约束（仅适用于小规模）==========
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                BoolVar binSame = model.newBoolVar("same_bin_" + i + "_" + j);
                model.addEquality(binVars[i], binVars[j]).onlyEnforceIf(binSame);
                model.addDifferent(binVars[i], binVars[j]).onlyEnforceIf(binSame.not());

                // 四种不重叠情况：i 在 j 左、右、上、下
                BoolVar left = model.newBoolVar("left_" + i + "_" + j);
                BoolVar right = model.newBoolVar("right_" + i + "_" + j);
                BoolVar below = model.newBoolVar("below_" + i + "_" + j);
                BoolVar above = model.newBoolVar("above_" + i + "_" + j);

                // i 在 j 左边: x_i + w_i <= x_j
                model.addLessOrEqual(
                        LinearExpr.newBuilder().add(xVars[i]).add(wVars[i]).build(),
                        xVars[j]
                ).onlyEnforceIf(left);

                // i 在 j 右边: x_j + w_j <= x_i
                model.addLessOrEqual(
                        LinearExpr.newBuilder().add(xVars[j]).add(wVars[j]).build(),
                        xVars[i]
                ).onlyEnforceIf(right);

                // i 在 j 下面: y_i + h_i <= y_j
                model.addLessOrEqual(
                        LinearExpr.newBuilder().add(yVars[i]).add(hVars[i]).build(),
                        yVars[j]
                ).onlyEnforceIf(below);

                // i 在 j 上面: y_j + h_j <= y_i
                model.addLessOrEqual(
                        LinearExpr.newBuilder().add(yVars[j]).add(hVars[j]).build(),
                        yVars[i]
                ).onlyEnforceIf(above);

                // 必须满足至少一个方向不重叠（当在同一 bin）
                model.addBoolOr(new BoolVar[]{left, right, below, above}).onlyEnforceIf(binSame);
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

        // ========== 目标：最小化使用的 bin 数量 ==========
        BoolVar[] usedBins = new BoolVar[MAX_BINS];
        for (int b = 0; b < MAX_BINS; b++) {
            usedBins[b] = model.newBoolVar("used_" + b);
            BoolVar[] isAssigned = new BoolVar[n];
            for (int i = 0; i < n; i++) {
                isAssigned[i] = model.newBoolVar("assign_" + i + "_" + b);
                model.addEquality(binVars[i], b).onlyEnforceIf(isAssigned[i]);
            }

            // 使用 LinearExpr.sum(BoolVar[]) 求和
            LinearExpr numAssigned = LinearExpr.sum(isAssigned);

            // 如果至少一个 item 分配到 bin b，则 usedBins[b] = 1
            model.addGreaterOrEqual(numAssigned, 1).onlyEnforceIf(usedBins[b]);
            // 如果没有 item 分配到 bin b，则 usedBins[b] = 0
            model.addEquality(numAssigned, 0).onlyEnforceIf(usedBins[b].not());
        }

// 总共使用的 bin 数量
        IntVar numUsed = model.newIntVar(0, MAX_BINS, "num_used");
        model.addEquality(numUsed, LinearExpr.sum(usedBins));
        model.minimize(numUsed);

        // ========== 求解 ==========
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(30);
        CpSolverStatus status = solver.solve(model);

        List<BinResult> results = new ArrayList<>();
        if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
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
                p.setX(solver.value(xVars[i]) / (double)SCALE);
                p.setY(solver.value(yVars[i]) / (double)SCALE);
                p.setW(solver.value(wVars[i]) / (double)SCALE);
                p.setH(solver.value(hVars[i]) / (double)SCALE);
                p.setRotated(solver.value(rotated[i]) > 0.5);

                map.get(binId).getPieces().add(p);
            }

            for (BinResult br : map.values()) {
                double usedArea = br.getPieces().stream().mapToDouble(p -> p.getW() * p.getH()).sum();
                double utilization = (usedArea / totalArea) * 100;
                br.setUtilization(Math.round(utilization * 10.0) / 10.0);
            }

            results.addAll(map.values());
            results.sort(Comparator.comparingInt(BinResult::getBinId));
        }

        return results;
    }
}