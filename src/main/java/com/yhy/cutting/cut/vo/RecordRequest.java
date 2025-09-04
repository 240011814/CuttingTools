package com.yhy.cutting.cut.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RecordRequest {

    @NotNull(message = "type不能为空")
    private String type;

    @NotNull(message = "request不能为空")
    private String request;

    @NotNull(message = "response不能为空")
    private String response;

    @NotNull(message = "name不能为空")
    private String name;
}
