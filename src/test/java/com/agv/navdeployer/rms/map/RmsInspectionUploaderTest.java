package com.agv.navdeployer.rms.map;

import com.agv.navdeployer.rms.config.RmsProperties;
import com.agv.navdeployer.rms.protocol.dto.command.TaskCommandRequest;
import com.agv.navdeployer.rms.task.RmsTask;
import com.agv.navdeployer.rms.task.RmsTaskRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class RmsInspectionUploaderTest {

    @Test
    void zipUsesSameNgResultAndContainsReferencedImages() throws Exception {
        RmsProperties properties = new RmsProperties();
        properties.getBehavior().setInspectionResult("NG");
        ObjectMapper mapper = new ObjectMapper();
        RmsTaskRegistry registry = new RmsTaskRegistry();
        registry.put(new RmsTask(new TaskCommandRequest(
                "task-1", "action-1", null, "action", "quality_inspection",
                null, null, Map.of(
                "wireharness_type", "LAD-52010021",
                "wireharness_barcode", "BC-001"))));
        RmsInspectionUploader uploader = new RmsInspectionUploader(mapper, properties, registry);

        Map<String, byte[]> entries = unzip(uploader.buildInspectionZip("task-1"));
        assertThat(entries).containsKeys(
                "report.json", "summary.txt", "task-1_left_3D.jpg", "task-1_right_3D.jpg");
        JsonNode report = mapper.readTree(entries.get("report.json"));
        assertThat(report.path("inspection_result").asText()).isEqualTo("NG");
        assertThat(report.path("wireharness_type").asText()).isEqualTo("LAD-52010021");
        assertThat(report.path("wireharness_barcode").asText()).isEqualTo("BC-001");
        assertThat(report.path("points").get(0).path("result").asText()).isEqualTo("OK");
        assertThat(report.path("points").get(1).path("result").asText()).isEqualTo("NG");
        assertThat(report.path("points").get(1).path("items").get(0).path("result").asText()).isEqualTo("NG");
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
