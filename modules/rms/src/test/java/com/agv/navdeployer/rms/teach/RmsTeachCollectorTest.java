package com.agv.navdeployer.rms.teach;

import com.agv.navdeployer.rms.config.RmsProperties;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * teach/capture 指令校验与 zip 打包测试（不触 zenoh/HTTP）。
 */
class RmsTeachCollectorTest {

    private RmsTeachCollector collector(RmsProperties properties) {
        return new RmsTeachCollector(new com.fasterxml.jackson.databind.ObjectMapper(), properties);
    }

    @Test
    void rejectsMissingRequiredParams() {
        RmsTeachCollector collector = collector(new RmsProperties());
        ObjectNode reply = collector.handleCapture(
                "{\"msg_id\":42,\"template\":\"t-1\",\"node\":\"n-1\"}", null, null);
        assertThat(reply.path("msg_id").asLong()).isEqualTo(42);
        assertThat(reply.path("status").asInt()).isEqualTo(400);
        assertThat(reply.path("msg").asText()).contains("必填");
    }

    @Test
    void rejectsInvalidCamera() {
        RmsTeachCollector collector = collector(new RmsProperties());
        ObjectNode reply = collector.handleCapture(
                "{\"msg_id\":7,\"template\":\"t\",\"node\":\"n\",\"camera\":\"top-1\",\"upload_url\":\"http://x\"}",
                null, null);
        assertThat(reply.path("status").asInt()).isEqualTo(400);
        assertThat(reply.path("msg").asText()).contains("camera");
    }

    @Test
    void zipContainsMetaAndSampleFiles() throws Exception {
        Path dir = Files.createTempDirectory("teach-samples");
        Files.writeString(dir.resolve("a.ply"), "ply-content");
        Files.writeString(dir.resolve("b.jpg"), "jpg-content");
        Files.writeString(dir.resolve("c.png"), "png-content");
        RmsProperties properties = new RmsProperties();
        properties.getBehavior().setTeachSampleDir(dir.toString());

        RmsTeachCollector.Command command = new RmsTeachCollector.Command(
                99, "tpl-1", "node-1", "left-3", "http://upload");
        Map<String, byte[]> entries = unzip(collector(properties).buildZip(command));

        assertThat(entries).containsKeys("teach_capture.json", "a.ply", "b.jpg", "c.png");
        com.fasterxml.jackson.databind.JsonNode meta =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(entries.get("teach_capture.json"));
        assertThat(meta.path("template").asText()).isEqualTo("tpl-1");
        assertThat(meta.path("camera").asText()).isEqualTo("left-3");
        assertThat(meta.path("msg_id").asLong()).isEqualTo(99);
    }

    @Test
    void zipFallsBackWhenSampleDirMissing() throws Exception {
        RmsProperties properties = new RmsProperties();
        properties.getBehavior().setTeachSampleDir("Z:/definitely/not/exist");
        RmsTeachCollector.Command command = new RmsTeachCollector.Command(
                1, "tpl", "node", "head", "http://upload");
        Map<String, byte[]> entries = unzip(collector(properties).buildZip(command));
        assertThat(entries).containsKeys("teach_capture.json", "runtime-info.txt");
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }
}
