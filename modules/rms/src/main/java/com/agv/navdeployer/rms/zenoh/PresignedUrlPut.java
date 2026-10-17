package com.agv.navdeployer.rms.zenoh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 预签名 URL HTTP PUT（质检结果 zip / teach 采集 zip 共用）。
 *
 * <p>云端预签名把 content-type 计入了签名（X-Amz-SignedHeaders=content-type;host），
 * PUT 的 Content-Type 必须与云端签名时的值完全一致，否则 MinIO 返回 403 SignatureDoesNotMatch。
 * 指令未告知签名用的 content-type，按常见约定依次尝试；上次成功的值优先（不同云端版本签名约定不同，
 * 记住后免于每次重试两个 403），仅在 403 SignatureDoesNotMatch 时换下一个候选重试（PUT 幂等，重复上传无副作用）。
 */
public final class PresignedUrlPut {

    private static final Logger log = LoggerFactory.getLogger(PresignedUrlPut.class);
    private static final long HTTP_TIMEOUT_SEC = 30;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final List<String> CONTENT_TYPES = List.of(
            "video/mp4", "application/zip", "application/octet-stream", "binary/octet-stream");

    /** 上次成功的 content-type（云端版本固定后签名值不变，优先复用避免 403 重试） */
    private static volatile String lastSuccessfulContentType;

    private PresignedUrlPut() {
    }

    public static void put(String url, byte[] body) throws Exception {
        String preferred = lastSuccessfulContentType;
        List<String> candidates = new ArrayList<>(CONTENT_TYPES.size());
        if (preferred != null) {
            candidates.add(preferred);
        }
        for (String contentType : CONTENT_TYPES) {
            if (!candidates.contains(contentType)) {
                candidates.add(contentType);
            }
        }
        IllegalStateException lastFailure = null;
        for (String contentType : candidates) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", contentType)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SEC))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                lastSuccessfulContentType = contentType;
                log.info("预签名上传成功, content-type={}（后续上传沿用此值）", contentType);
                return;
            }
            String bodyText = response.body() == null ? "" : response.body();
            lastFailure = new IllegalStateException("HTTP " + response.statusCode() + ": " + bodyText);
            boolean signatureMismatch = response.statusCode() == 403 && bodyText.contains("SignatureDoesNotMatch");
            log.warn("预签名上传失败（content-type={}）: HTTP {}", contentType, response.statusCode());
            // 只有"签名不匹配"才值得换 content-type 重试；403 过期/拒绝访问等其他错误直接失败
            if (!signatureMismatch) {
                throw lastFailure;
            }
        }
        throw lastFailure;
    }
}
