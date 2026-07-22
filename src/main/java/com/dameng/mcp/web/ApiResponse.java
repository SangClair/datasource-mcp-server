package com.dameng.mcp.web;

import lombok.Data;

/**
 * 统一 REST API 响应结构。
 *
 * @param <T> 数据载荷类型
 */
@Data
public class ApiResponse<T> {

    /**
     * 操作是否成功
     */
    private boolean success;

    /**
     * 提示信息
     */
    private String message;

    /**
     * 数据载荷
     */
    private T data;

    public ApiResponse() {
    }

    public ApiResponse(boolean success, String message, T data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    public static <T> ApiResponse<T> ok(String message) {
        return new ApiResponse<>(true, message, null);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, message, null);
    }
}
