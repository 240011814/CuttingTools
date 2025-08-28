package com.yhy.cutting.cut.vo;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

import java.io.Serializable;
@Data
@AllArgsConstructor
@NoArgsConstructor
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private int code;

    private String msg;

    private T data;

    public static <T> R<T> ok() {
        return restResult(null, HttpStatus.OK.value(), null);
    }

    public static <T> R<T> ok(T data) {
        return restResult(data, HttpStatus.OK.value(), null);
    }

    public static <T> R<T> ok(T data, String msg) {
        return restResult(data, HttpStatus.OK.value(), msg);
    }

    public static <T> R<T> failed() {
        return restResult(null, HttpStatus.INTERNAL_SERVER_ERROR.value(), null);
    }

    public static <T> R<T> failed(String msg) {
        return restResult(null, HttpStatus.INTERNAL_SERVER_ERROR.value(), msg);
    }

    public static <T> R<T> failed(T data) {
        return restResult(data, HttpStatus.INTERNAL_SERVER_ERROR.value(), null);
    }

    public static <T> R<T> failed(T data, String msg) {
        return restResult(data, HttpStatus.INTERNAL_SERVER_ERROR.value(), msg);
    }

    private static <T> R<T> restResult(T data, int code, String msg) {
        R<T> result = new R<>();
        result.setCode(code);
        result.setData(data);
        result.setMsg(msg);
        return result;
    }

}