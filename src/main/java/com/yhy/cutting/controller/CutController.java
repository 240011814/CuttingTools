package com.yhy.cutting.controller;

import com.yhy.cutting.service.CuttingBarService;
import com.yhy.cutting.service.CuttingOptimizerService;
import com.yhy.cutting.service.MaxRectsCuttingService;
import com.yhy.cutting.vo.*;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController()
@RequestMapping(value = "api/cut")
public class CutController {

    private final CuttingOptimizerService optimizerService;
    private final CuttingBarService barService;
    private final MaxRectsCuttingService maxRectsCuttingService;

    public CutController(CuttingOptimizerService optimizerService,
                         CuttingBarService barService,
                         MaxRectsCuttingService maxRectsCuttingService) {
        this.optimizerService = optimizerService;
        this.barService = barService;
        this.maxRectsCuttingService = maxRectsCuttingService;
    }

    @PostMapping(value = "plane")
    public R<List<BinResult>> optimizeWithMaterials(@RequestBody BinRequest request) {
        return R.ok(maxRectsCuttingService.optimize(request.getItems(), request.getMaterials()));
    }


    @PostMapping(value = "bar")
    public R<List<BarResult>> bar(@RequestBody BarRequest request) {
        return R.ok(barService.bar(request));
    }


}
