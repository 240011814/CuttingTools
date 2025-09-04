package com.yhy.cutting.user.config;

import com.yhy.cutting.user.vo.User;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import java.util.Date;

@Component
public class JwtUtil {

    private final String SECRET_KEY = "MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBAL1Ue4NVi28LzHeVPcv6g/62N17hmWL7lo1Sh0yE5nQpAgwvJ0bH3CtiOSshSzj2LR6exL9wZWLBlPRm1XDRz1kCAwEAAQ=="; // 改成环境变量
    private final long EXPIRATION_TIME = 86400000; // 24小时

    // 生成 Token
    public String generateToken(String username) {
        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                .signWith(SignatureAlgorithm.HS512, SECRET_KEY)
                .compact();
    }

    // 解析用户名
    public String extractUsername(String token) {
        JwtParser parser = Jwts.parser().setSigningKey(SECRET_KEY).build();
        return parser
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }

    // 验证 Token
    public Boolean validateToken(String token, UserDetails user) {
        return user.getUsername().equals(extractUsername(token)) && !isTokenExpired(token) && user.isEnabled();
    }

    private Boolean isTokenExpired(String token) {
        JwtParser parser = Jwts.parser().setSigningKey(SECRET_KEY).build();
        Date expiration = parser
                .parseClaimsJws(token)
                .getBody()
                .getExpiration();
        return expiration.before(new Date());
    }
}
