package com.yhy.cutting.user.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserInfo implements Serializable {

    @NotNull(message = "id不能为空")
    private String id;

    private String userName;

    private boolean enabled ;

    private String email;

    private String phone;

    private String address;

    private String nickName;

    private List<String> roles = new ArrayList<>();

    private List<String> buttons = new ArrayList<>();

    public UserInfo(User user) {
        this.id = user.getId();
        this.userName = user.getUserName();
        this.enabled = user.isEnabled();
        this.email = Optional.ofNullable(user.getEmail()).orElse("");
        this.phone = Optional.ofNullable(user.getPhone()).orElse("");
        this.address =Optional.ofNullable(user.getAddress()).orElse("");
        this.nickName = Optional.ofNullable(user.getNickName()).orElse("");
        this.roles = new ArrayList<>();
        this.buttons = new ArrayList<>();
        if(user.isSuperAdmin()){
            roles.add("admin");
        }
    }
}
