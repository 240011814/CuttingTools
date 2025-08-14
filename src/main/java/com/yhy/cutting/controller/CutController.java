package com.yhy.cutting.controller;

import com.google.ortools.Loader;
import com.google.ortools.init.OrToolsVersion;
import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.yhy.cutting.service.CuttingOptimizerService;
import com.yhy.cutting.service.MaxRectsCuttingService;
import com.yhy.cutting.vo.BinResult;
import com.yhy.cutting.vo.Item;
import com.yhy.cutting.vo.MaterialType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class CutController {

    private final CuttingOptimizerService optimizerService;
    private final MaxRectsCuttingService maxRectsCuttingService;

    public CutController(CuttingOptimizerService optimizerService,
                         MaxRectsCuttingService maxRectsCuttingService) {
        this.optimizerService = optimizerService;
        this.maxRectsCuttingService = maxRectsCuttingService;
    }

    // 在你的Controller中添加新端点
    @PostMapping("/api/optimize-with-materials")
    public List<BinResult> optimizeWithMaterials(@RequestBody Map<String, Object> request) {
        List<Map<String, Object>> itemMaps = (List<Map<String, Object>>) request.get("items");
        List<Map<String, Object>> materialMaps = (List<Map<String, Object>>) request.get("materials");

        List<Item> items = itemMaps.stream()
                .map(map -> new Item(
                        (String) map.get("label"),
                        ((Number) map.get("width")).doubleValue(),
                        ((Number) map.get("height")).doubleValue()
                )).collect(Collectors.toList());

        List<MaterialType> materials = materialMaps.stream()
                .map(map -> new MaterialType(
                        (String) map.get("name"),
                        ((Number) map.get("width")).doubleValue(),
                        ((Number) map.get("height")).doubleValue(),
                        ((Number) map.get("availableCount")).intValue()
                )).collect(Collectors.toList());

        return maxRectsCuttingService.optimize(items, materials);
    }


    @RequestMapping(value = "test")

    public String test(){
        // 原材料标准长度
        double newMaterialLength = 6.0;

        // 需求列表（单位：米）
        double[] needs = {2.5, 1.7, 3.2, 4.0, 0.8, 1.5, 2.2, 1.1, 0.9};
        int n = needs.length;

        // 已有的剩余材料（可利用的余料）
        double[] scrapLengths = {4.2, 3.8, 5.0, 2.5}; // 示例：4 根余料
        int numScrap = scrapLengths.length;

        // 最多可能需要 n 根新材料（最坏情况）
        int maxNewBins = n;

        // 加载 OR-Tools
        Loader.loadNativeLibraries();
        System.out.println("Google OR-Tools version: " + OrToolsVersion.getVersionString());

        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            System.out.println("Could not create solver SCIP");
            return "Solver creation failed";
        }

        // ========================
        // 变量定义
        // ========================

        // 1. y_old[i][j]: 第 j 个需求是否分配到第 i 根旧余料上 (i in [0, numScrap))
        MPVariable[][] yOld = new MPVariable[numScrap][n];
        for (int i = 0; i < numScrap; i++) {
            for (int j = 0; j < n; j++) {
                yOld[i][j] = solver.makeBoolVar("y_old[" + i + "][" + j + "]");
            }
        }

        // 2. x_new[i]: 第 i 根新材料是否被使用
        MPVariable[] xNew = new MPVariable[maxNewBins];
        // 3. y_new[i][j]: 第 j 个需求是否分配到第 i 根新材料上
        MPVariable[][] yNew = new MPVariable[maxNewBins][n];

        for (int i = 0; i < maxNewBins; i++) {
            xNew[i] = solver.makeBoolVar("x_new[" + i + "]");
            for (int j = 0; j < n; j++) {
                yNew[i][j] = solver.makeBoolVar("y_new[" + i + "][" + j + "]");
            }
        }

        // ========================
        // 约束 1: 每个需求只能分配到一个位置（旧料 or 新料）
        // ========================
        for (int j = 0; j < n; j++) {
            MPConstraint ct = solver.makeConstraint(1.0, 1.0, "assign_" + j);

            // 来自旧余料
            for (int i = 0; i < numScrap; i++) {
                ct.setCoefficient(yOld[i][j], 1.0);
            }
            // 来自新材料
            for (int i = 0; i < maxNewBins; i++) {
                ct.setCoefficient(yNew[i][j], 1.0);
            }
        }

        // ========================
        // 约束 2: 旧余料容量限制
        // ========================
        for (int i = 0; i < numScrap; i++) {
            MPConstraint ct = solver.makeConstraint(0.0, scrapLengths[i], "scrap_capacity_" + i);
            for (int j = 0; j < n; j++) {
                ct.setCoefficient(yOld[i][j], needs[j]);
            }
        }

        // ========================
        // 约束 3: 新材料容量限制（仅当 xNew[i]=1 时可用）
        // sum <= 6.0 * xNew[i]
        // ========================
        for (int i = 0; i < maxNewBins; i++) {
            MPConstraint ct = solver.makeConstraint(-Double.MAX_VALUE, 0.0, "new_capacity_" + i);
            ct.setCoefficient(xNew[i], -newMaterialLength);
            for (int j = 0; j < n; j++) {
                ct.setCoefficient(yNew[i][j], needs[j]);
            }
        }

        // ========================
        // 新增：对称性破除（新材料之间）
        // ========================
        for (int i = 1; i < maxNewBins; i++) {
            MPConstraint ct = solver.makeConstraint(-Double.MAX_VALUE, 0.0, "sym_new_" + i);
            ct.setCoefficient(xNew[i], 1);
            ct.setCoefficient(xNew[i - 1], -1); // xNew[i] <= xNew[i-1]
        }

        // ========================
        // 新增：新材料上的余料必须满足：≤0.5 或 ≥1.0
        // ========================
        MPVariable[] z1 = new MPVariable[maxNewBins]; // used >= 5.5
        MPVariable[] z2 = new MPVariable[maxNewBins]; // used <= 5.0
        MPVariable[] usedNew = new MPVariable[maxNewBins];

        for (int i = 0; i < maxNewBins; i++) {
            usedNew[i] = solver.makeNumVar(0.0, newMaterialLength, "used_new[" + i + "]");
            z1[i] = solver.makeBoolVar("z1[" + i + "]");
            z2[i] = solver.makeBoolVar("z2[" + i + "]");

            // 定义 usedNew[i] = sum_j needs[j] * yNew[i][j]
            MPConstraint ctUsed = solver.makeConstraint(0.0, 0.0, "used_def_new_" + i);
            ctUsed.setCoefficient(usedNew[i], -1.0);
            for (int j = 0; j < n; j++) {
                ctUsed.setCoefficient(yNew[i][j], needs[j]);
            }

            // z1[i]=1 ⇒ usedNew[i] >= 5.5
            MPConstraint ct1 = solver.makeConstraint(0.0, Double.MAX_VALUE);
            ct1.setCoefficient(usedNew[i], 1.0);
            ct1.setCoefficient(z1[i], -5.5);

            // z2[i]=1 ⇒ usedNew[i] <= 5.0
            MPConstraint ct2 = solver.makeConstraint(-Double.MAX_VALUE, 5.0);
            ct2.setCoefficient(usedNew[i], 1.0);
            ct2.setCoefficient(z2[i], -1.0);

            // 必须满足 z1[i] 或 z2[i]（如果 xNew[i] == 1）
            MPConstraint ct3 = solver.makeConstraint(0.0, Double.MAX_VALUE);
            ct3.setCoefficient(z1[i], 1.0);
            ct3.setCoefficient(z2[i], 1.0);
            ct3.setCoefficient(xNew[i], -1.0); // z1 + z2 >= xNew[i]
        }

        // ========================
        // 目标：最小化新启用的材料数量
        // ========================
        MPObjective objective = solver.objective();
        for (int i = 0; i < maxNewBins; i++) {
            objective.setCoefficient(xNew[i], 1.0);
        }
        objective.setMinimization();

        // ========================
        // 求解
        // ========================
        MPSolver.ResultStatus resultStatus = solver.solve();

        if (resultStatus == MPSolver.ResultStatus.OPTIMAL ||
                resultStatus == MPSolver.ResultStatus.FEASIBLE) {

            System.out.println("\n✅ 找到可行解！");
            System.out.printf("使用旧余料: %d 根\n", numScrap);
            System.out.printf("启用新材料: %d 根\n", (int) Math.ceil(objective.value()));

            // 输出旧余料使用情况
            System.out.println("\n--- 旧余料使用情况 ---");
            for (int i = 0; i < numScrap; i++) {
                List<Double> cuts = new ArrayList<>();
                double sum = 0.0;
                for (int j = 0; j < n; j++) {
                    if (yOld[i][j].solutionValue() > 0.5) {
                        cuts.add(needs[j]);
                        sum += needs[j];
                    }
                }
                if (!cuts.isEmpty()) {
                    double remaining = scrapLengths[i] - sum;
                    System.out.printf("余料%d (%.1fm): 切割 %s, 用 %.2fm, 剩 %.2fm\n",
                            i + 1, scrapLengths[i], cuts, sum, remaining);
                }
            }

            // 输出新材料使用情况
            System.out.println("\n--- 新材料使用情况 ---");
            int newBinIndex = 1;
            for (int i = 0; i < maxNewBins; i++) {
                if (xNew[i].solutionValue() > 0.5) {
                    List<Double> cuts = new ArrayList<>();
                    double sum = 0.0;
                    for (int j = 0; j < n; j++) {
                        if (yNew[i][j].solutionValue() > 0.5) {
                            cuts.add(needs[j]);
                            sum += needs[j];
                        }
                    }
                    double remaining = newMaterialLength - sum;
                    String status = (remaining <= 0.5 || remaining >= 1.0) ? "✓" : "✗";
                    System.out.printf("新材料%d: 切割 %s, 用 %.2fm, 剩 %.2fm (%s)\n",
                            newBinIndex++, cuts, sum, remaining, status);
                }
            }

            // 验证所有需求是否被分配
            boolean[] assigned = new boolean[n];
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < numScrap; i++) {
                    if (yOld[i][j].solutionValue() > 0.5) assigned[j] = true;
                }
                for (int i = 0; i < maxNewBins; i++) {
                    if (yNew[i][j].solutionValue() > 0.5) assigned[j] = true;
                }
                if (!assigned[j]) {
                    System.out.printf("⚠️ 需求 %.1f 未被分配！\n", needs[j]);
                }
            }

        } else {
            System.out.println("❌ 未找到可行解。状态: " + resultStatus);
            return "No solution found";
        }

        return "Optimal solution found";
    }



}
