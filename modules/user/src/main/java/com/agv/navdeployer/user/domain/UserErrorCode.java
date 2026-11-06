package com.agv.navdeployer.user.domain;

import com.agv.navdeployer.common.error.ErrorCode;

public enum UserErrorCode implements ErrorCode {

    USER_NOT_FOUND(404, "用户不存在"),
    AVATAR_NOT_SET(404, "用户尚未设置头像"),
    AVATAR_FILE_EMPTY(400, "头像文件不能为空"),
    AVATAR_TYPE_UNRECOGNIZED(400, "无法识别头像文件类型"),
    AVATAR_TYPE_UNSUPPORTED(400, "不支持的图片类型"),
    AVATAR_STORE_FAILED(500, "头像存取失败");

    private final int status;
    private final String message;

    UserErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String message() {
        return message;
    }
}
