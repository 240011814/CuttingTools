package com.yhy.cutting.user.repository;

import com.yhy.cutting.user.vo.User;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByUserName(String username);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE User u SET u.refreshToken = :refreshToken, u.updateTime = :updateTime WHERE u.userName = :userName")
    int updateRefreshTokenAndUpdateTimeByUserName(@Param("userName") String userName , @Param("updateTime") LocalDateTime updateTime, @Param("refreshToken") String refreshToken );

    Optional<User> findByRefreshToken(String refreshToken);
}
