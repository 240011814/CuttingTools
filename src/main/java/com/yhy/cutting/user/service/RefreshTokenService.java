package com.yhy.cutting.user.service;

import com.yhy.cutting.user.repository.UserRepository;
import com.yhy.cutting.user.vo.User;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private final UserRepository userRepository;

    public RefreshTokenService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public String createRefreshToken(String  userName) {
        String token = UUID.randomUUID().toString();
        int i = userRepository.updateRefreshTokenAndUpdateTimeByUserName(userName, LocalDateTime.now(), token);
        if (i > 0) {
            return token;
        }else {
            throw new RuntimeException("update refresh token failed");
        }
    }


    public Optional<User> getUsernameByRefreshToken(String token) {
        return userRepository.findByRefreshToken(token);
    }

    public void deleteRefreshToken(String token) {

    }
}
