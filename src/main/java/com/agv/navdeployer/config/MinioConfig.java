package com.agv.navdeployer.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Bean
    public MinioClient minioClient(@Value("${minio.endpoint}") String endpoint,
                                   @Value("${minio.access-key}") String accessKey,
                                   @Value("${minio.secret-key}") String secretKey) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    @Bean
    public ApplicationRunner minioBucketInitializer(MinioClient minioClient,
                                                    @Value("${minio.bucket}") String bucket) {
        return args -> {
            if (minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                return;
            }
            try {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            } catch (Exception e) {
                // 多副本同时启动时可能都检测到桶不存在而重复创建，确认桶存在则忽略
                if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    throw e;
                }
            }
        };
    }
}
