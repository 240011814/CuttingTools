package com.yhy.cutting.controller;

import com.yhy.cutting.service.CuttingOptimizerService;
import com.yhy.cutting.service.MaxRectsCuttingService;
import com.yhy.cutting.vo.BarRequest;
import com.yhy.cutting.vo.BarResult;
import com.yhy.cutting.vo.BinRequest;
import com.yhy.cutting.vo.BinResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;


@RestController()
@RequestMapping(value = "cut")
public class CutController {

    private final CuttingOptimizerService optimizerService;
    private final MaxRectsCuttingService maxRectsCuttingService;

    public CutController(CuttingOptimizerService optimizerService,
                         MaxRectsCuttingService maxRectsCuttingService) {
        this.optimizerService = optimizerService;
        this.maxRectsCuttingService = maxRectsCuttingService;
    }

    @PostMapping(value = "plane")
    public List<BinResult> optimizeWithMaterials(@RequestBody BinRequest request) {
        return maxRectsCuttingService.optimize(request.getItems(), request.getMaterials());
    }


    @PostMapping(value = "bar")
    public List<BarResult> bar(@RequestBody BarRequest request){
        return optimizerService.bar(request);
    }



}
