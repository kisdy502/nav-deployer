package com.zioneer.robotmanager.robot.ota.infrastructure.client;

import com.zioneer.robotmanager.common.core.error.BusinessException;
import com.zioneer.robotmanager.robot.error.RobotManagementErrorCode;
import com.zioneer.robotmanager.robot.ota.domain.config.OtaStorageProperties;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageSource;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.storage.JfrogArtifactFetch;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.storage.JfrogArtifactFetcher;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.storage.JfrogEndpoint;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class HttpJfrogArtifactFetcher implements JfrogArtifactFetcher {

    private final OtaStorageProperties properties;
    private final HttpClient httpClient;

    public HttpJfrogArtifactFetcher(OtaStorageProperties properties) {
        this.properties = properties;
        this.httpClient = defaultHttpClient();
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public JfrogArtifactFetch fetch(OtaPackageSource source, String rangeHeader) {
        JfrogEndpoint endpoint = JfrogEndpoint.of(properties.getJfrog());
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        endpoint.uri(UriUtils.encodePath(source.path(), StandardCharsets.UTF_8)))
                // timeout 只限制响应头到达，制品流的慢速读取不受限
                .timeout(Duration.ofSeconds(30))
                .header(HttpHeaders.AUTHORIZATION, endpoint.basicAuthorization())
                .GET();
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            request.header(HttpHeaders.RANGE, rangeHeader);
        }
        try {
            HttpResponse<InputStream> response = httpClient.send(request.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200 && response.statusCode() != 206) {
                response.body().close();
                throw new BusinessException(RobotManagementErrorCode.OTA_STORAGE_ARTIFACT_FETCH_FAILED);
            }
            return new JfrogArtifactFetch(response.statusCode(),
                    response.headers().firstValue(HttpHeaders.CONTENT_LENGTH).map(Long::parseLong).orElse(null),
                    response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(null),
                    response.headers().firstValue(HttpHeaders.CONTENT_RANGE).orElse(null),
                    response.body());
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new BusinessException(RobotManagementErrorCode.OTA_STORAGE_ARTIFACT_FETCH_FAILED);
        }
    }
}
