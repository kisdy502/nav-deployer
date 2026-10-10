package com.agv.navdeployer.rms.inspection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 生成与真机一致的质检 result_report 结构（仿真数据）。 */
public final class RmsInspectionResultFactory {

    private RmsInspectionResultFactory() {
    }

    public static Map<String, Object> create(String taskId,
                                             String configuredResult,
                                             Map<String, Object> parameters) {
        String result = "NG".equalsIgnoreCase(configuredResult) ? "NG" : "OK";
        String errorReason = "NG".equals(result) ? "QR/DM code not decoded" : "";
        String barcode = parameterText(parameters, "wireharness_barcode", "");
        String harnessType = parameterText(parameters, "wireharness_type", "SIM-WIRE-HARNESS");

        Map<String, Object> lengthItem = new LinkedHashMap<>();
        lengthItem.put("error_reason", "");
        lengthItem.put("result", "OK");
        lengthItem.put("result_details", List.of(
                Map.of("key", "measured_length_mm", "value", "157.7"),
                Map.of("key", "error_threshold_mm", "value", List.of(155.0, 158.0))));
        lengthItem.put("seg_id", "SEG_001");
        lengthItem.put("seg_name", "simulation line M001");
        lengthItem.put("type", "HarnessMeasurment");

        Map<String, Object> qrItem = new LinkedHashMap<>();
        qrItem.put("error_reason", errorReason);
        qrItem.put("result", result);
        qrItem.put("result_details", List.of(Map.of(
                "key", "QR", "value", "OK".equals(result) ? "SIM-QR-CODE" : "")));
        qrItem.put("type", "QrDetection");

        String safeTaskId = taskId == null || taskId.isBlank() ? "inspection" : taskId;
        Map<String, Object> leftPoint = point(
                1, "left_3D", safeTaskId + "_left_3D.jpg", "OK", "", List.of(lengthItem));
        Map<String, Object> rightPoint = point(
                2, "right_3D", safeTaskId + "_right_3D.jpg", result, errorReason, List.of(qrItem));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("inspection_result", result);
        report.put("points", List.of(leftPoint, rightPoint));
        report.put("task_id", safeTaskId);
        report.put("wireharness_barcode", barcode);
        report.put("wireharness_type", harnessType);
        return report;
    }

    private static Map<String, Object> point(int pointId,
                                             String cameraId,
                                             String imageName,
                                             String result,
                                             String errorReason,
                                             List<Map<String, Object>> items) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("camera_id", cameraId);
        point.put("camera_type", "3D");
        point.put("error_reason", errorReason);
        point.put("image_id", pointId - 1);
        point.put("image_name", imageName);
        point.put("items", items);
        point.put("point_id", pointId);
        point.put("result", result);
        return point;
    }

    private static String parameterText(Map<String, Object> parameters, String key, String defaultValue) {
        Object value = parameters == null ? null : parameters.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return String.valueOf(value).trim();
    }
}
