package com.yhy.cutting.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BarRequest {
    private List<BigDecimal> items;
    private List<BigDecimal> materials;
    BigDecimal newMaterialLength;
    BigDecimal loss;
}
