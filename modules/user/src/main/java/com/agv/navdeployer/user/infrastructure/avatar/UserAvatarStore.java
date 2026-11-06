package com.agv.navdeployer.user.infrastructure.avatar;

import com.agv.navdeployer.common.error.BusinessException;
import com.agv.navdeployer.user.domain.UserErrorCode;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class UserAvatarStore {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");

    private final MinioClient minioClient;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final String region;

    public UserAvatarStore(MinioClient minioClient,
                           @Value("${minio.bucket}") String bucket,
                           @Value("${minio.access-key}") String accessKey,
                           @Value("${minio.secret-key}") String secretKey,
                           @Value("${minio.region:us-east-1}") String region) {
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
    }

    public String put(MultipartFile file) {
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
            throw new BusinessException(UserErrorCode.AVATAR_STORE_FAILED, "头像上传到 MinIO 失败: " + e.getMessage());
        }
    }

    public AvatarObject get(String objectKey) {
        try (GetObjectResponse response = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            String contentType = response.headers().get("Content-Type");
            return new AvatarObject(response.readAllBytes(), contentType);
        } catch (Exception e) {
            throw new BusinessException(UserErrorCode.AVATAR_STORE_FAILED, "从 MinIO 读取头像失败: " + e.getMessage());
        }
    }

    public String presignGetUrl(String objectKey, String externalEndpoint, long ttlSeconds) {
        try {
            MinioClient presignClient = MinioClient.builder()
                    .endpoint(externalEndpoint)
                    .credentials(accessKey, secretKey)
                    .region(region)
                    .build();
            return presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry((int) ttlSeconds, TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new BusinessException(UserErrorCode.AVATAR_STORE_FAILED, "生成头像访问链接失败: " + e.getMessage());
        }
    }

    public void removeQuietly(String objectKey) {
        if (objectKey == null) {
            return;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception ignored) {
        }
    }

    private String resolveExtension(String filename) {
        if (filename == null || filename.lastIndexOf('.') < 0) {
            throw new BusinessException(UserErrorCode.AVATAR_TYPE_UNRECOGNIZED);
        }
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException(UserErrorCode.AVATAR_TYPE_UNSUPPORTED, "不支持的图片类型: " + ext);
        }
        return ext;
    }

    public record AvatarObject(byte[] data, String contentType) {
    }
}
