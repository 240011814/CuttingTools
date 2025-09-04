package com.yhy.cutting.user.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


import javax.validation.constraints.NotNull;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserRequest {

    private String userName;

    @NotNull(message = "密码不能为空")
    private String password;

    @NotNull(message = "密码不能为空")
    private String confirmPassword;

    private String email;

    @NotNull(message = "手机号不能为空")
    private String phone;

    private String address;

}
