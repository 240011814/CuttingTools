package com.yhy.cutting.cut.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class BarResult {
    public int index;
    public double totalLength;
    public List<Double> cuts = new ArrayList<Double>();
    public double used;
    public double remaining;
    public String source;
}
