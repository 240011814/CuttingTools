package com.yhy.cutting.cut.vo;
import cn.hutool.core.util.IdUtil;
import com.yhy.cutting.user.vo.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "cut_record")
public class CutRecord {
    @Id
    @Column(name = "id", length = 100, nullable = false)
    private String id;

    @Column(name = "type", length = 20)
    private String type;

    @Lob
    @Column(name = "request")
    private String request;

    @Lob
    @Column(name = "response")
    private String response;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "user_id", length = 100, nullable = false)
    private String userId;

    @Column(name = "code", length = 100)
    private String code;

    @Column(name = "name", length = 100)
    private String name;

    public CutRecord(RecordRequest recordRequest) {
        this.request = recordRequest.getRequest();
        this.response = recordRequest.getResponse();
        this.createTime = LocalDateTime.now();
        this.type = recordRequest.getType();
        this.id = IdUtil.simpleUUID();
        this.name = recordRequest.getName();
        this.userId = ((User) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getId();
        this.code = (this.type.equals("1")?"B":"P") + LocalDateTime.now().format(DateTimeFormatter.ofPattern("YYYYMMdd")) +
                IdUtil.getSnowflakeNextIdStr().substring(11,19);
    }
}
