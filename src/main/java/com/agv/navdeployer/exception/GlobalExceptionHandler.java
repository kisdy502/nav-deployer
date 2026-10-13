package com.agv.navdeployer.exception;

import com.agv.navdeployer.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(RuntimeException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 客户端断开连接（SSE/长连接的正常生命周期：浏览器刷新、关页、断网）。
     * 此时响应已提交（text/event-stream），无法也无需再写错误响应体；
     * 返回 null 视为已处理，避免 HttpMessageNotWritableException 与 Tomcat ERROR 刷屏。
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiResponse<Void>> handleClientAbort(IOException e, HttpServletResponse response) {
        if (response.isCommitted() || isClientAbort(e)) {
            log.debug("client aborted connection: {}", e.getMessage());
            return null;
        }
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "IO 错误: " + e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e, HttpServletResponse response) {
        if (response.isCommitted()) {
            // SSE 等已提交响应的 async dispatch：写任何错误体都会失败，静默结束
            log.debug("exception on committed response: {}", e.getMessage());
            return null;
        }
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误: " + e.getMessage());
    }

    private boolean isClientAbort(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("中止了一个已建立的连接")   // Windows
                || message.contains("Broken pipe")        // Linux
                || message.contains("Connection reset")
                || message.contains("connection was aborted");
    }

    private ResponseEntity<ApiResponse<Void>> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.error(status.value(), message));
    }
}
