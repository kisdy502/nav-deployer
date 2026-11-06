package com.zioneer.robotmanager.robot.ota.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zioneer.robotmanager.common.core.error.BusinessException;
import com.zioneer.robotmanager.robot.error.RobotManagementErrorCode;
import com.zioneer.robotmanager.robot.ota.domain.config.OtaStorageProperties;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.storage.JfrogCredential;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.storage.JfrogCredentialIssuer;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.storage.JfrogEndpoint;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;

@Component
public class HttpJfrogCredentialIssuer implements JfrogCredentialIssuer {

    private static final long DEFAULT_TTL_SECONDS = 3600;

    private final RestClient restClient;

    public HttpJfrogCredentialIssuer() {
        this(defaultRestClient());
    }

    HttpJfrogCredentialIssuer(RestClient restClient) {
        this.restClient = restClient;
    }

    private static RestClient defaultRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(10000);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public JfrogCredential issue(OtaStorageProperties.Jfrog jfrog) {
        JfrogEndpoint endpoint = JfrogEndpoint.of(jfrog);
        long ttlSeconds = ttlOf(jfrog);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", jfrog.getUsername());
        form.add("expires_in", String.valueOf(ttlSeconds));
        form.add("scope", "applied-permissions/user");
        Instant issuedAt = Instant.now();
        try {
            TokenResponse response = restClient.post()
                    .uri(endpoint.uri("api/security/token"))
                    .headers(headers -> headers.setBasicAuth(endpoint.username(), endpoint.password()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                throw new BusinessException(RobotManagementErrorCode.OTA_STORAGE_CREDENTIAL_ISSUE_FAILED);
            }
            return new JfrogCredential(jfrog.getUsername(), response.accessToken(), issuedAt.plusSeconds(ttlSeconds));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(RobotManagementErrorCode.OTA_STORAGE_CREDENTIAL_ISSUE_FAILED);
        }
    }

    // JFrog 的 expires_in=0 表示永不过期；配置非正数时钳回默认，防止误配出永久凭据
    private long ttlOf(OtaStorageProperties.Jfrog jfrog) {
        long configured = jfrog.getCredentialTtlSeconds();
        return configured > 0 ? configured : DEFAULT_TTL_SECONDS;
    }

    private record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }
}
