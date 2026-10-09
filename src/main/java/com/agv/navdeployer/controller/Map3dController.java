package com.agv.navdeployer.controller;

import com.agv.navdeployer.sim.SimAgvProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Proxy map assets through the application's origin; control websocket stays small. */
@RestController
@RequestMapping("/api/v1/map-3d")
public class Map3dController {
    private final String baseUrl;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public Map3dController(SimAgvProperties properties,
                           @Value("${map3d.base-url:}") String configuredUrl) {
        URI websocket = URI.create(properties.getWsUrl());
        this.baseUrl = configuredUrl.isBlank()
                ? "http://" + (websocket.getHost().contains(":") ? "[" + websocket.getHost() + "]" : websocket.getHost()) + ":8089"
                : configuredUrl.replaceAll("/+$", "");
    }

    private HttpResponse<InputStream> request(String name, String asset) throws Exception {
        if (name.isBlank() || name.length() > 128 || name.contains("..")
                || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid map name");
        }
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/maps/" + encoded + "/" + asset))
                .timeout(Duration.ofSeconds(60)).GET().build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.io.IOException exc) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Robot 3D map service is unavailable", exc);
        }
    }

    @GetMapping(value = "/{name}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> status(@PathVariable String name) throws Exception {
        var response = request(name, "status");
        try (InputStream stream = response.body()) {
            return ResponseEntity.status(response.statusCode()).contentType(MediaType.APPLICATION_JSON)
                    .header("Cache-Control", "no-store").body(stream.readNBytes(16384));
        }
    }

    @GetMapping("/{name}/cloud.ply")
    public ResponseEntity<StreamingResponseBody> cloud(@PathVariable String name) throws Exception {
        var response = request(name, "cloud.ply");
        if (response.statusCode() != 200) {
            response.body().close();
            throw new ResponseStatusException(HttpStatus.valueOf(response.statusCode()), "3D map is not ready");
        }
        StreamingResponseBody body = output -> {
            try (InputStream stream = response.body()) {
                stream.transferTo(output);
            }
        };
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Cache-Control", "no-cache").body(body);
    }
}
