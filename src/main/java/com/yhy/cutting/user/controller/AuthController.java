package com.yhy.cutting.user.controller;

import cn.hutool.core.util.IdUtil;
import com.yhy.cutting.cut.vo.R;
import com.yhy.cutting.user.config.JwtUtil;
import com.yhy.cutting.user.repository.UserRepository;
import com.yhy.cutting.user.service.RefreshTokenService;
import com.yhy.cutting.user.service.UserService;
import com.yhy.cutting.user.vo.*;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping(value = "api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;

    private final UserService userDetailsService;

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtUtil jwtUtil;

    public AuthController(AuthenticationManager authenticationManager,
                          UserService userDetailsService,
                          UserRepository userRepository,
                          RefreshTokenService refreshTokenService,
                          JwtUtil jwtUtil) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/login")
    public R<AuthResponse> createAuthenticationToken(@RequestBody AuthRequest authRequest) {
        try {
            Authentication authenticate = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(authRequest.getUserName(), authRequest.getPassword())
            );
            if (authenticate.isAuthenticated()) {
                final UserDetails userDetails = userDetailsService.loadUserByUsername(authRequest.getUserName());
                final String accessToken = jwtUtil.generateToken(userDetails.getUsername());
                final String refreshToken = refreshTokenService.createRefreshToken(userDetails.getUsername());

                return R.ok(new AuthResponse(accessToken, refreshToken));
            } else {
                return R.failed("用户名或密码错误");
            }
        } catch (BadCredentialsException e) {
            return R.failed("用户名或密码错误");
        }

    }

    // 可选：注册接口
    @PostMapping("/register")
    public R<UserInfo> register(@RequestBody UserRequest request) {
        if(!request.getPassword().equals(request.getConfirmPassword())) {
            return R.failed("密码不一致");
        }
        User user = new User(request);
        user.setId(IdUtil.getSnowflakeNextIdStr());
        user.setPassword(new BCryptPasswordEncoder().encode(request.getPassword()));
        userRepository.save(user);
        user.setPassword("");
        return R.ok(new UserInfo(user));
    }

    @PostMapping("/refresh-token")
    public R<?> refreshToken(@RequestBody Map request) {
        String refreshToken = request.get("refreshToken").toString();

        Optional<User> temp = refreshTokenService.getUsernameByRefreshToken(refreshToken);
        if (temp.isPresent()) {
            User user = temp.get();
            String newAccessToken = jwtUtil.generateToken(user.getUserName());
            String newRefreshToken = refreshTokenService.createRefreshToken(user.getUserName());
            return R.ok(new AuthResponse(newAccessToken, newRefreshToken));
        } else {
            return R.failed("无效或过期的刷新Token");
        }

    }

    @PostMapping("/logout")
    public R<String> logout(@RequestHeader("Authorization") String authHeader) {
        String refreshToken = authHeader;
        if (refreshToken != null) {
            refreshTokenService.deleteRefreshToken(refreshToken);
        }
        return R.ok("登出成功");
    }

    @GetMapping("/getUserInfo")
    public R<UserInfo> userInfo() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Optional<User> byUserName = userRepository.findByUserName(authentication.getName());
        if (byUserName.isPresent()) {
            return R.ok(new UserInfo(byUserName.get()));
        }else {
            return R.failed("用户信息不存在");
        }
    }
}
