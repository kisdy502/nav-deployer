package com.agv.navdeployer.controller;

import com.agv.navdeployer.sim.SimAgvProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class Map3dControllerTest {
    @Test
    void proxiesStatusAndStreamsBinary() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] ply = new byte[]{'p', 'l', 'y', 0, 1, 2};
        server.createContext("/maps/test/status", exchange -> {
            byte[] json = "{\"status\":\"ready\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, json.length);
            try (var stream = exchange.getResponseBody()) { stream.write(json); }
        });
        server.createContext("/maps/test/cloud.ply", exchange -> {
            exchange.sendResponseHeaders(200, ply.length);
            try (var stream = exchange.getResponseBody()) { stream.write(ply); }
        });
        server.start();
        try {
            var controller = new Map3dController(new SimAgvProperties(),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            assertEquals("{\"status\":\"ready\"}", new String(controller.status("test").getBody(), StandardCharsets.UTF_8));
            var output = new ByteArrayOutputStream();
            controller.cloud("test").getBody().writeTo(output);
            assertArrayEquals(ply, output.toByteArray());
            assertThrows(ResponseStatusException.class, () -> controller.status("../test"));
        } finally {
            server.stop(0);
        }
    }
}
