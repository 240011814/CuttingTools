package com.yhy.cutting.cut.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BinRequest {
    private List<Item> items;
    private List<MaterialType> materials;
}

