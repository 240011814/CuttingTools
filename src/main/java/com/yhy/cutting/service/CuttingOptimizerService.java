package com.yhy.cutting.service;

import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import com.yhy.cutting.vo.BinResult;
import com.yhy.cutting.vo.Item;
import com.yhy.cutting.vo.MaterialType;
import com.yhy.cutting.vo.Piece;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class CuttingOptimizerService {

    private static final int SCALE = 1000;

    public List<BinResult> optimize(List<Item> items, List<MaterialType> availableMaterials) {
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
            int[] wRi  = new int[n];
            int[] hRi  = new int[n];
            for (int i = 0; i < n; i++) {
                double w = items.get(i).getWidth();
                double h = items.get(i).getHeight();
                wNRi[i] = scale(w);
                hNRi[i] = scale(h);
                wRi[i]  = scale(h);
                hRi[i]  = scale(w);
            }

            for (int b = 0; b < numBins; b++) {
                binUsed[b] = model.newBoolVar("binUsed_" + b);
                MaterialInstance m = materialInstances.get(b);
                int BW = scale(m.getWidth());
                int BH = scale(m.getHeight());

                NoOverlap2dConstraint noOverlap2d = model.addNoOverlap2D();

                for (int i = 0; i < n; i++) {
                    inBin[i][b]   = model.newBoolVar("inBin_" + i + "_" + b);
                    placeNR[i][b] = model.newBoolVar("placeNR_" + i + "_" + b);
                    placeR[i][b]  = model.newBoolVar("placeR_" + i + "_" + b);

                    model.addEquality(
                            LinearExpr.sum(new BoolVar[]{placeNR[i][b], placeR[i][b]}),
                            inBin[i][b]
                    );

                    xNR[i][b]    = model.newIntVar(0L, (long) Math.max(0, BW - wNRi[i]), "xNR_" + i + "_" + b);
                    yNR[i][b]    = model.newIntVar(0L, (long) Math.max(0, BH - hNRi[i]), "yNR_" + i + "_" + b);
                    xEndNR[i][b] = model.newIntVar(0L, (long) BW, "xEndNR_" + i + "_" + b);
                    yEndNR[i][b] = model.newIntVar(0L, (long) BH, "yEndNR_" + i + "_" + b);

                    xItvNR[i][b] = model.newOptionalIntervalVar(xNR[i][b], LinearExpr.constant(wNRi[i]), xEndNR[i][b], placeNR[i][b], "xItvNR_" + i + "_" + b);
                    yItvNR[i][b] = model.newOptionalIntervalVar(yNR[i][b], LinearExpr.constant(hNRi[i]), yEndNR[i][b], placeNR[i][b], "yItvNR_" + i + "_" + b);

                    xR[i][b]    = model.newIntVar(0L, (long) Math.max(0, BW - wRi[i]), "xR_" + i + "_" + b);
                    yR[i][b]    = model.newIntVar(0L, (long) Math.max(0, BH - hRi[i]), "yR_" + i + "_" + b);
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

    private List<BinResult> buildResultsFromSolver(CpSolver solver, List<Item> items, List<MaterialInstance> materialInstances, int numBins, BoolVar[][] inBin, BoolVar[][] placeNR, BoolVar[][] placeR, IntVar[][] xNR, IntVar[][] yNR, IntVar[][] xR,  IntVar[][] yR) {
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
                        double tmp = w; w = h; h = tmp;
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
        public String getOriginalName() { return originalName; }
        public double getWidth() { return width; }
        public double getHeight() { return height; }
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
}
