package com.yhy.cutting.user.repository;

import com.yhy.cutting.user.vo.User;
import com.yhy.cutting.user.vo.UserInfo;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, String>, JpaSpecificationExecutor<User> {
    Optional<User> findByUserName(String username);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE User u SET u.refreshToken = :refreshToken, u.updateTime = :updateTime WHERE u.userName = :userName")
    int updateRefreshTokenAndUpdateTimeByUserName(@Param("userName") String userName , @Param("updateTime") LocalDateTime updateTime, @Param("refreshToken") String refreshToken );

    Optional<User> findByRefreshToken(String refreshToken);


    @Modifying
    @Transactional
    @Query("UPDATE User u SET " +
            "u.email = COALESCE(:#{#user.email}, u.email), " +
            "u.phone = COALESCE(:#{#user.phone}, u.phone), " +
            "u.address = COALESCE(:#{#user.address}, u.address), " +
            "u.nickName = COALESCE(:#{#user.nickName}, u.nickName), " +
            "u.enabled = COALESCE(:#{#user.enabled}, u.enabled) " +
            "WHERE u.id = :#{#user.id}")
    int updateUserInfo(@Param("user") UserInfo user);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE User u SET u.password = :password WHERE u.id = :id")
    int updatePasswordById(@Param("password") String password , @Param("id") String id);

}
