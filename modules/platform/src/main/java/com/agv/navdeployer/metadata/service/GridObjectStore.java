package com.agv.navdeployer.metadata.service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.io.ByteArrayInputStream;
import java.util.zip.GZIPOutputStream;

/** v2 地图栅格对象存取（gzip JSON → MinIO，沿用 nav-maps 桶，key 前缀 v2-maps/）。 */
@Component
public class GridObjectStore {

    private final MinioClient minioClient;
    private final String bucket;

    public GridObjectStore(MinioClient minioClient,
                           @Value("${minio.maps-bucket:nav-maps}") String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    public void put(String objectKey, byte[] compressed) {
        try {
            ensureBucket();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(compressed), compressed.length, -1)
                    .contentType("application/gzip")
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("地图数据写入 MinIO 失败: " + e.getMessage(), e);
        }
    }

    public byte[] get(String objectKey) {
        try (GetObjectResponse response = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            return response.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("从 MinIO 读取地图数据失败: " + e.getMessage(), e);
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

    private synchronized void ensureBucket() throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    public static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(raw.length / 2);
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(raw);
        }
        return buffer.toByteArray();
    }

    public static byte[] ungzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream buffer = new ByteArrayOutputStream(compressed.length * 4)) {
            gzip.transferTo(buffer);
            return buffer.toByteArray();
        }
    }
}
