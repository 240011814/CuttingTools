package com.yhy.cutting.user.vo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Date;
import java.util.List;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "user")
public class User implements UserDetails {

    @Id
    private String id;

    @Column(unique = true, nullable = false)
    private String userName;

    @Column()
    private String password;

    @Column()
    private String email;

    @Column()
    private String phone;

    @Column()
    private String address;


    @Column(unique = true)
    private String refreshToken;

    @Column()
    private Date createTime;

    @Column()
    private Date updateTime;

    @Column()
    private boolean enabled = true;

    @Column()
    private boolean superAdmin ;

    @Column()
    private String nickName;

    public User(UserRequest userRequest) {
        this.userName = userRequest.getPhone();
        this.email = userRequest.getEmail();
        this.phone = userRequest.getPhone();
        this.address = userRequest.getAddress();
        this.updateTime = new Date();
        this.createTime = new Date();
        this.enabled = true;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getUsername() {
        return userName;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }


}
