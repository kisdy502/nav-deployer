package com.agv.navdeployer.controller;

import com.agv.navdeployer.common.ApiResponse;
import com.agv.navdeployer.dto.UserCreateDTO;
import com.agv.navdeployer.dto.UserQuery;
import com.agv.navdeployer.dto.UserUpdateDTO;
import com.agv.navdeployer.service.UserService;
import com.agv.navdeployer.vo.AvatarUrlVO;
import com.agv.navdeployer.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.StringUtils;

import java.util.List;

@Tag(name = "用户管理", description = "用户的增删改查与头像管理")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final String minioExternalPort;
    private final String minioExternalEndpoint;

    public UserController(UserService userService,
                          @Value("${minio.external-port:9000}") String minioExternalPort,
                          @Value("${minio.external-endpoint:}") String minioExternalEndpoint) {
        this.userService = userService;
        this.minioExternalPort = minioExternalPort;
        this.minioExternalEndpoint = minioExternalEndpoint;
    }

    @Operation(summary = "创建用户")
    @PostMapping
    public ResponseEntity<ApiResponse<UserVO>> create(@Valid @RequestBody UserCreateDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(userService.create(dto)));
    }

    @Operation(summary = "查询单个用户", description = "优先命中 Redis 缓存")
    @GetMapping("/{id}")
    public ApiResponse<UserVO> getById(@PathVariable Long id) {
        return ApiResponse.ok(userService.getById(id));
    }

    @Operation(summary = "查询用户列表", description = "支持 name 模糊匹配")
    @GetMapping
    public ApiResponse<List<UserVO>> list(UserQuery query) {
        return ApiResponse.ok(userService.list(query));
    }

    @Operation(summary = "更新用户")
    @PutMapping("/{id}")
    public ApiResponse<UserVO> update(@PathVariable Long id, @Valid @RequestBody UserUpdateDTO dto) {
        return ApiResponse.ok(userService.update(id, dto));
    }

    @Operation(summary = "删除用户", description = "同时清理 Redis 缓存与 MinIO 头像对象")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return ApiResponse.ok();
    }

    @Operation(summary = "上传头像", description = "multipart 上传至 MinIO，用户记录保存 objectKey")
    @PostMapping("/{id}/avatar")
    public ApiResponse<UserVO> uploadAvatar(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(userService.uploadAvatar(id, file));
    }

    @Operation(summary = "获取头像预签名 URL", description = "24 小时有效，按浏览器访问的 Host 动态签名；配置 minio.external-endpoint 后固定用它签名")
    @GetMapping("/{id}/avatar-url")
    public ApiResponse<AvatarUrlVO> getAvatarUrl(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(userService.getAvatarUrl(id, buildMinioExternalEndpoint(request)));
    }

    /**
     * 计算预签名用的 MinIO 外部地址：
     * 1. 配置了 minio.external-endpoint（如 https://minio.example.com）则固定使用（生产域名场景）
     * 2. 否则按浏览器请求的 Host 动态推导（本机 IP/域名/localhost 访问自动适配）
     */
    private String buildMinioExternalEndpoint(HttpServletRequest request) {
        if (StringUtils.hasText(minioExternalEndpoint)) {
            return minioExternalEndpoint;
        }
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (!StringUtils.hasText(scheme)) {
            scheme = request.getScheme();
        }
        String host = request.getHeader("Host");
        if (StringUtils.hasText(host)) {
            if (host.startsWith("[")) {
                host = host.substring(0, host.indexOf(']') + 1);
            } else if (host.contains(":")) {
                host = host.substring(0, host.indexOf(':'));
            }
        } else {
            host = request.getServerName();
        }
        return scheme + "://" + host + ":" + minioExternalPort;
    }

    @Operation(summary = "获取头像图片字节", description = "应用代理转发，备用方案")
    @GetMapping("/{id}/avatar")
    public ResponseEntity<byte[]> getAvatar(@PathVariable Long id) {
        UserService.AvatarData avatar = userService.getAvatar(id);
        HttpHeaders headers = new HttpHeaders();
        String contentType = avatar.contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : avatar.contentType();
        headers.setContentType(MediaType.parseMediaType(contentType));
        return new ResponseEntity<>(avatar.data(), headers, HttpStatus.OK);
    }
}
