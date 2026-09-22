package com.agv.navdeployer.common;

public record ApiResponse<T>(int status, String msg, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> error(int status, String msg) {
        return new ApiResponse<>(status, msg, null);
    }
}
