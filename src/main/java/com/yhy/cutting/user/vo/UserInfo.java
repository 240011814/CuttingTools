package com.yhy.cutting.user.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserInfo {

    private String userId;

    private String userName;

    private List<String> roles;

    private List<String> buttons;

    public UserInfo(User user) {
        this.userId = user.getId();
        this.userName = user.getUserName();
        this.roles = new ArrayList<>();
        this.buttons = new ArrayList<>();
    }
}
