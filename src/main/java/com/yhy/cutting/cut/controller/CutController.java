package com.yhy.cutting.cut.controller;

import com.yhy.cutting.cut.service.*;
import com.yhy.cutting.cut.vo.*;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController()
@RequestMapping(value = "api/cut")
public class CutController {

    private final CuttingBarService barService;
    private final List<IPlaneService> planeServices;
    private final MaxRectsCuttingService maxRectsCuttingService;

    public CutController(CuttingBarService barService,
                         List<IPlaneService> planeServices,
                         MaxRectsCuttingService maxRectsCuttingService
    ) {
        this.barService = barService;
        this.planeServices = planeServices;
        this.maxRectsCuttingService = maxRectsCuttingService;
    }

    @PostMapping(value = "plane")
    public R<List<BinResult>> optimizeWithMaterials(@RequestBody BinRequest request) {
        return R.ok(planeServices.stream().filter(x -> x.getName().equals(request.getStrategy())).findFirst().orElse(maxRectsCuttingService).optimize(request));
    }


    @PostMapping(value = "bar")
    public R<List<BarResult>> bar(@RequestBody BarRequest request) {
        return R.ok(barService.bar(request));
    }


}
