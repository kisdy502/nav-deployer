package com.agv.navdeployer.service;

import com.agv.navdeployer.dto.UserCreateDTO;
import com.agv.navdeployer.dto.UserQuery;
import com.agv.navdeployer.dto.UserUpdateDTO;
import com.agv.navdeployer.entity.User;
import com.agv.navdeployer.exception.NotFoundException;
import com.agv.navdeployer.mapper.UserMapper;
import com.agv.navdeployer.vo.AvatarUrlVO;
import com.agv.navdeployer.vo.UserVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class UserService {

    private static final Set<String> ALLOWED_AVATAR_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final Duration AVATAR_URL_TTL = Duration.ofHours(24);

    private final UserMapper userMapper;
    private final MinioClient minioClient;
    private final TransactionTemplate transactionTemplate;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final String region;

    public UserService(UserMapper userMapper,
                       MinioClient minioClient,
                       TransactionTemplate transactionTemplate,
                       @Value("${minio.bucket}") String bucket,
                       @Value("${minio.access-key}") String accessKey,
                       @Value("${minio.secret-key}") String secretKey,
                       @Value("${minio.region:us-east-1}") String region) {
        this.userMapper = userMapper;
        this.minioClient = minioClient;
        this.transactionTemplate = transactionTemplate;
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
    }

    @CachePut(value = "users", key = "#result.id")
    @Transactional(rollbackFor = Exception.class)
    public UserVO create(UserCreateDTO dto) {
        User user = new User();
        user.setName(dto.name());
        user.setAge(dto.age());
        userMapper.insert(user);
        return UserVO.from(user);
    }

    @Cacheable(value = "users", key = "#id")
    @Transactional(readOnly = true)
    public UserVO getById(Long id) {
        return UserVO.from(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<UserVO> list(UserQuery query) {
        LambdaQueryWrapper<User> wrapper = Wrappers.lambdaQuery(User.class)
                .like(StringUtils.hasText(query.name()), User::getName, query.name())
                .orderByDesc(User::getId);
        return userMapper.selectList(wrapper).stream().map(UserVO::from).toList();
    }

    @CachePut(value = "users", key = "#id")
    @Transactional(rollbackFor = Exception.class)
    public UserVO update(Long id, UserUpdateDTO dto) {
        User user = findOrThrow(id);
        user.setName(dto.name());
        user.setAge(dto.age());
        user.setUpdatedAt(null);
        userMapper.updateById(user);
        return UserVO.from(user);
    }

    @CacheEvict(value = "users", key = "#id")
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        User user = findOrThrow(id);
        userMapper.deleteById(user.getId());
        afterCommitOrNow(() -> removeAvatarQuietly(user.getAvatarKey()));
    }

    @CachePut(value = "users", key = "#id")
    public UserVO uploadAvatar(Long id, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("头像文件不能为空");
        }
        String objectKey = putAvatarObject(file);
        User updated = transactionTemplate.execute(status -> {
            User user = findOrThrow(id);
            String oldKey = user.getAvatarKey();
            user.setAvatarKey(objectKey);
            user.setUpdatedAt(null);
            userMapper.updateById(user);
            afterCommitOrNow(() -> removeAvatarQuietly(oldKey));
            return user;
        });
        return UserVO.from(updated);
    }

    @Transactional(readOnly = true)
    public AvatarData getAvatar(Long id) {
        User user = findOrThrow(id);
        if (user.getAvatarKey() == null) {
            throw new NotFoundException("用户 " + id + " 尚未设置头像");
        }
        try (GetObjectResponse response = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(user.getAvatarKey()).build())) {
            String contentType = response.headers().get("Content-Type");
            return new AvatarData(response.readAllBytes(), contentType);
        } catch (Exception e) {
            throw new IllegalStateException("从 MinIO 读取头像失败: " + e.getMessage(), e);
        }
    }

    /**
     * externalEndpoint 必须是浏览器可达的地址（由 Controller 依据请求 Host 推导），
     * 预签名与 host 绑定，签名后不可再改写。显式指定 region，签名纯本地计算。
     */
    @Transactional(readOnly = true)
    public AvatarUrlVO getAvatarUrl(Long id, String externalEndpoint) {
        User user = findOrThrow(id);
        if (user.getAvatarKey() == null) {
            throw new NotFoundException("用户 " + id + " 尚未设置头像");
        }
        try {
            MinioClient presignClient = MinioClient.builder()
                    .endpoint(externalEndpoint)
                    .credentials(accessKey, secretKey)
                    .region(region)
                    .build();
            String url = presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(user.getAvatarKey())
                    .expiry((int) AVATAR_URL_TTL.toSeconds(), TimeUnit.SECONDS)
                    .build());
            return new AvatarUrlVO(url, AVATAR_URL_TTL.toSeconds());
        } catch (Exception e) {
            throw new IllegalStateException("生成头像访问链接失败: " + e.getMessage(), e);
        }
    }

    private User findOrThrow(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new NotFoundException("用户 " + id + " 不存在");
        }
        return user;
    }

    private String putAvatarObject(MultipartFile file) {
        String extension = resolveExtension(file.getOriginalFilename());
        String objectKey = "avatars/" + UUID.randomUUID() + "." + extension;
        try (InputStream in = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
            return objectKey;
        } catch (Exception e) {
            throw new IllegalStateException("头像上传到 MinIO 失败: " + e.getMessage(), e);
        }
    }

    private String resolveExtension(String filename) {
        if (filename == null || filename.lastIndexOf('.') < 0) {
            throw new IllegalArgumentException("无法识别头像文件类型");
        }
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_AVATAR_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("不支持的图片类型: " + ext);
        }
        return ext;
    }

    private void removeAvatarQuietly(String key) {
        if (key == null) {
            return;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception ignored) {
        }
    }

    private void afterCommitOrNow(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    public record AvatarData(byte[] data, String contentType) {
    }
}
