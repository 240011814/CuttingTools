--liquibase formatted sql logicalFilePath:changelog-v1.0.0.sql

--changeset hou yong:1001  runOnChange:true
--comment: add  user table
CREATE TABLE IF NOT EXISTS user (
                      id VARCHAR(64) PRIMARY KEY COMMENT '用户ID，主键',

                      user_name VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名，唯一且非空',

                      password VARCHAR(100) DEFAULT NULL COMMENT '密码（BCrypt加密存储）',

                      email VARCHAR(100) DEFAULT NULL COMMENT '邮箱',

                      phone VARCHAR(20) DEFAULT NULL COMMENT '手机号',

                      address VARCHAR(255) DEFAULT NULL COMMENT '地址',

                      refresh_token VARCHAR(200) UNIQUE COMMENT '刷新Token，唯一（可为空）',

                      create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

                      update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

                      enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用：1=是，0=否'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';



--changeset hou yong:1002  runOnChange:true
--comment: add  user
INSERT INTO opt_omc.`user` (id,user_name,password,email,phone,address,refresh_token,create_time,update_time,enabled) VALUES
    ('1960951924276264960','admin','$2a$10$LfZvc1jgyBfL.4LMyfI8QeWkSU.LdBVSTtfPlQ/lL1nrAaFILCNim',NULL,NULL,NULL,NULL,'2025-08-28 14:25:46','2025-08-28 14:25:46',1);
