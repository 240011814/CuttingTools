package com.yhy.cutting.cut.service;

import com.yhy.cutting.cut.vo.BinRequest;
import com.yhy.cutting.cut.vo.BinResult;

import java.util.List;

public interface IPlaneService {
    List<BinResult> optimize(BinRequest request);

    String getName();
}
