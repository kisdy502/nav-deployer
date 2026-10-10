package com.agv.navdeployer.exchange;

import com.agv.navdeployer.vo.MapGridVO;
import com.agv.navdeployer.vo.NavPointVO;
import com.agv.navdeployer.vo.NavPathVO;
import com.agv.navdeployer.vo.PathEdgeVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 调度系统地图适配器（蓝心底盘 zip 格式，样例 map_0728.zip）。
 *
 * <p>包结构（六件套，全部以地图名 <name> 为前缀）：
 * <pre>
 *   <name>.pgm          栅格（P5，254=空/0=占据/205=未知）
 *   <name>.png          同尺寸灰度预览
 *   <name>.yaml         元数据（OpenCV %YAML:1.0 风格 + map_scan_plane 专有键）
 *   <name>.yaml.lxmap   拓扑：Topology-Map 头 + Cairn Goal(点位)/Route(路线) 行
 *   <name>.feature.json 特征容器模板（反光板/禁区/虚拟墙，固定空模板）
 *   <name>.json         点位/路线属性 schema 模板（调度工具链定义，与地图无关）
 * </pre>
 *
 * <p>lxmap 的 Cairn 行是空格分隔的自定义文本（非 JSON/标准 YAML）。固定字段及
 * 行尾属性严格按调度系统样例生成；尤其 Goal 的 function_ 数值是服务端识别点位语义的
 * 唯一依据，不能只把类型写进自定义 JSON。导入解析读取固定字段并宽松容错。
 */
@Component
public class ScheduleMapAdapter {

    private static final Logger log = LoggerFactory.getLogger(ScheduleMapAdapter.class);
    private static final String SCHEMA_RESOURCE = "schedule/schedule-map-schema.json";
    private static final String FEATURES_RESOURCE = "schedule/schedule-map-features.json";
    /** Route 行第 10 字段（样例值 0.6，推断为限速 m/s）缺省值 */
    private static final double DEFAULT_ROUTE_SPEED = 0.6;
    private static final int GOAL_TYPE_PATH_MARKER = 0;
    private static final int GOAL_TYPE_WORKSTATION = 1;
    private static final int GOAL_TYPE_CHARGING = 2;
    private static final int GOAL_TYPE_REST = 3;
    private static final String DEFAULT_GOAL_ATTRIBUTES =
            "{\"quadrant_divide_angle\":\"0,90,180,270\"}";
    private static final String DEFAULT_ROUTE_ATTRIBUTES =
            "{\"shelf_posture\":0,\"path_width\":100,\"cost\":1,\"empty_cost\":1,\"full_cost\":1}";

    // ==================== 导出：我方数据 → 调度 zip ====================

    public byte[] exportZip(String mapName,
                            MapGridVO grid,
                            List<NavPointVO> points,
                            List<NavPathVO> paths,
                            Map<Long, List<PathEdgeVO>> edgesByPath) throws IOException {
        String name = sanitize(mapName);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            putEntry(zip, name + ".pgm", buildPgm(grid));
            putEntry(zip, name + ".png", buildPng(grid));
            putEntry(zip, name + ".yaml", buildYaml(name, grid));
            putEntry(zip, name + ".yaml.lxmap", buildLxmap(grid, points, paths, edgesByPath));
            putEntry(zip, name + ".feature.json", readResource(FEATURES_RESOURCE));
            putEntry(zip, name + ".json", readResource(SCHEMA_RESOURCE));
        }
        log.info("schedule zip exported: name={}, points={}, paths={}", name, points.size(), paths.size());
        return buffer.toByteArray();
    }

    /** P5 灰度：占据(>65)→0 黑，空闲(<20)→254 白，未知(-1)→205，中间按概率线性取反。 */
    private byte[] buildPgm(MapGridVO grid) {
        int width = grid.getWidth();
        int height = grid.getHeight();
        int[] data = grid.getData();
        byte[] pixels = new byte[width * height];
        // pgm 第 0 行是世界 y 最大一侧（map_server 约定），data 第 0 行是 y 最小一侧 → 行翻转
        for (int row = 0; row < height; row++) {
            int srcRow = height - 1 - row;
            for (int col = 0; col < width; col++) {
                pixels[row * width + col] = (byte) pgmValue(data[srcRow * width + col]);
            }
        }
        String header = "P5\n" + width + " " + height + "\n255\n";
        byte[] head = header.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[head.length + pixels.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(pixels, 0, out, head.length, pixels.length);
        return out;
    }

    private static int pgmValue(int occupancy) {
        if (occupancy == -1) {
            return 205;
        }
        if (occupancy > 65) {
            return 0;
        }
        if (occupancy < 20) {
            return 254;
        }
        return 255 - Math.round(occupancy * 255f / 100f);
    }

    /** PNG 预览：与 pgm 相同的灰度映射（调度 UI 展示用）。 */
    private byte[] buildPng(MapGridVO grid) throws IOException {
        int width = grid.getWidth();
        int height = grid.getHeight();
        int[] data = grid.getData();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < height; y++) {
            int srcRow = height - 1 - y; // 与 pgm 同步行翻转
            for (int x = 0; x < width; x++) {
                int grey = pgmValue(data[srcRow * width + x]) & 0xFF;
                image.setRGB(x, y, grey << 16 | grey << 8 | grey);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** OpenCV 风格 yaml（样例为 %YAML:1.0，标准 yaml 解析器读不了，必须按样例手写）。 */
    private byte[] buildYaml(String name, MapGridVO grid) {
        String yaml = "%YAML:1.0\n"
                + "image: \"" + name + ".pgm\"\n"
                + "resolution: " + grid.getResolution() + "\n"
                + "origin: [" + grid.getOriginX() + ", " + grid.getOriginY() + ", 0]\n"
                + "negate: 0\n"
                + "occupied_thresh: 0.65\n"
                + "free_thresh: 0.196\n"
                + "map_scan_plane: 0\n";
        return yaml.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * lxmap 拓扑：
     * <pre>
     * Topology-Map
     * MapId: 0
     * Rect: <origin_x> <origin_y> <width> <height>      （宽高 = 栅格数 × 分辨率，样例验证 1099×0.05=54.95）
     * Version:
     * Description: default description
     * Cairn: Goal <id> <code> "" "" <x> <y> <theta> <type> 0 7 0 "" "0,0,0" "0,0,0" "0" "1" 0 0 0 0 0 0 {扩展}
     * Cairn: Route <id> <src> <dst> <sx> <sy> <dx> <dy> <back> <speed> 0 1 0 0 0 "" "0" "1" "" "0" 0 0 0 {扩展}
     * </pre>
     * Goal 的 type/function_：0=路径标点，1=作业/质检点，2=充电点，3=休息/待命点。
     * 分别对应我方 NORMAL、WORK、CHARGER、HOME。
     */
    private byte[] buildLxmap(MapGridVO grid,
                              List<NavPointVO> points,
                              List<NavPathVO> paths,
                              Map<Long, List<PathEdgeVO>> edgesByPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("Topology-Map\n");
        sb.append("MapId: 0\n");
        sb.append("Rect: ").append(grid.getOriginX()).append(' ').append(grid.getOriginY())
                .append(' ').append(grid.getWidth() * grid.getResolution())
                .append(' ').append(grid.getHeight() * grid.getResolution()).append('\n');
        sb.append("Version: \n");
        sb.append("Description: default description\n");

        // 点位 id 映射（lxmap 用数字 id，我方用数据库自增）
        Map<Long, Integer> pointIds = new LinkedHashMap<>();
        int nextGoalId = 1;
        for (NavPointVO point : points) {
            pointIds.put(point.getId(), nextGoalId);
            sb.append("Cairn: Goal ").append(nextGoalId++)
                    .append(' ').append(point.getPointCode())
                    .append(" \"\" \"\" ")
                    .append(point.getX()).append(' ').append(point.getY()).append(' ').append(point.getYaw())
                    .append(' ').append(scheduleGoalType(point.getPointType()))
                    .append(" 0 7 0 \"\" \"0,0,0\" \"0,0,0\" \"0\" \"1\" 0 0 0 0 0 0 ")
                    .append(DEFAULT_GOAL_ATTRIBUTES).append('\n');
        }
        // 调度服务器把每行 Route 当作单向边；每条通道必须输出 A→B 和 B→A 才会识别为双向。
        // 按无向点对去重，避免已经同时存在正反边的导入地图再导出时变成四条 Route。
        int nextRouteId = 1;
        Set<String> emittedConnections = new LinkedHashSet<>();
        for (NavPathVO path : paths) {
            List<PathEdgeVO> edges = edgesByPath.getOrDefault(path.getId(), List.of());
            for (PathEdgeVO edge : edges) {
                Integer src = pointIds.get(edge.getSourcePointId());
                Integer dst = pointIds.get(edge.getTargetPointId());
                NavPointVO source = findPoint(points, edge.getSourcePointId());
                NavPointVO target = findPoint(points, edge.getTargetPointId());
                if (src == null || dst == null || source == null || target == null) {
                    throw new IllegalArgumentException("路线边引用了当前地图不存在的点位，拒绝生成残缺地图: path="
                            + path.getId() + ", edge=" + edge.getId() + ", source="
                            + edge.getSourcePointId() + ", target=" + edge.getTargetPointId());
                }
                String connectionKey = Math.min(src, dst) + ":" + Math.max(src, dst);
                if (!emittedConnections.add(connectionKey)) {
                    continue;
                }
                double speed = edge.getMaxSpeed() != null ? edge.getMaxSpeed() : DEFAULT_ROUTE_SPEED;
                boolean reverseDriving = Boolean.TRUE.equals(edge.getReverse());
                nextRouteId = appendRoute(sb, nextRouteId, src, dst, source, target, speed, reverseDriving);
                if (!src.equals(dst)) {
                    nextRouteId = appendRoute(sb, nextRouteId, dst, src, target, source, speed, reverseDriving);
                }
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static int appendRoute(StringBuilder sb,
                                   int routeId,
                                   int src,
                                   int dst,
                                   NavPointVO source,
                                   NavPointVO target,
                                   double speed,
                                   boolean reverseDriving) {
        sb.append("Cairn: Route ").append(routeId)
                .append(' ').append(src).append(' ').append(dst)
                .append(' ').append(source.getX()).append(' ').append(source.getY())
                .append(' ').append(target.getX()).append(' ').append(target.getY())
                .append(' ').append(reverseDriving ? 1 : 0)
                .append(' ').append(speed)
                .append(" 0 1 0 0 0 \"\" \"0\" \"1\" \"\" \"0\" 0 0 0 ")
                .append(DEFAULT_ROUTE_ATTRIBUTES).append('\n');
        return routeId + 1;
    }

    private static int scheduleGoalType(String pointType) {
        if (pointType == null || pointType.isBlank() || "NORMAL".equalsIgnoreCase(pointType)) {
            return GOAL_TYPE_PATH_MARKER;
        }
        if ("CHARGER".equalsIgnoreCase(pointType)) {
            return GOAL_TYPE_CHARGING;
        }
        if ("WORK".equalsIgnoreCase(pointType)) {
            return GOAL_TYPE_WORKSTATION;
        }
        if ("HOME".equalsIgnoreCase(pointType)) {
            return GOAL_TYPE_REST;
        }
        throw new IllegalArgumentException("不支持的点位类型，无法生成调度地图: " + pointType);
    }

    private static String navPointType(int scheduleGoalType) {
        return switch (scheduleGoalType) {
            case GOAL_TYPE_WORKSTATION -> "WORK";
            case GOAL_TYPE_CHARGING -> "CHARGER";
            case GOAL_TYPE_REST -> "HOME";
            default -> "NORMAL";
        };
    }

    private static NavPointVO findPoint(List<NavPointVO> points, Long id) {
        return points.stream().filter(p -> p.getId().equals(id)).findFirst().orElse(null);
    }

    // ==================== 导入：调度 zip → 解析结果 ====================

    /** 导入解析结果：栅格 + 点位 + 路线边（坐标/类型已还原）。 */
    public record ScheduleMapImport(
            double resolution,
            double originX,
            double originY,
            int width,
            int height,
            int[] data,
            List<ImportedPoint> points,
            List<ImportedRoute> routes) {
    }

    public record ImportedPoint(String code, double x, double y, double yaw, String pointType) {
    }

    public record ImportedRoute(String sourceCode, String targetCode,
                                double sourceX, double sourceY,
                                double targetX, double targetY,
                                Double maxSpeed, boolean reverse, String edgeType) {
    }

    public ScheduleMapImport importZip(byte[] zipBytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
            }
        }
        byte[] yamlBytes = findEntry(entries, ".yaml", ".lxmap");
        byte[] pgmBytes = findEntry(entries, ".pgm");
        byte[] lxmapBytes = findEntry(entries, ".lxmap");
        if (yamlBytes == null || pgmBytes == null || lxmapBytes == null) {
            throw new IllegalArgumentException("zip 缺少必需文件（yaml/pgm/lxmap），现有条目: " + entries.keySet());
        }

        PgmMeta pgm = parsePgm(pgmBytes);
        YamlMeta meta = parseYaml(new String(yamlBytes, StandardCharsets.UTF_8));
        // 与 ROS map_server 一致：pgm 第 0 行是世界 y 最大一侧，data 第 0 行是 y 最小一侧 → 行翻转
        int[] data = new int[pgm.width() * pgm.height()];
        for (int row = 0; row < pgm.height(); row++) {
            int outRow = pgm.height() - 1 - row;
            for (int col = 0; col < pgm.width(); col++) {
                data[outRow * pgm.width() + col] = occupancyFromPgm(pgm.pixels()[row * pgm.width() + col] & 0xFF);
            }
        }

        List<ImportedPoint> points = new ArrayList<>();
        List<ImportedRoute> routes = new ArrayList<>();
        parseLxmap(new String(lxmapBytes, StandardCharsets.UTF_8), points, routes);
        log.info("schedule zip imported: {}x{}@{}, points={}, routes={}",
                pgm.width(), pgm.height(), meta.resolution(), points.size(), routes.size());
        return new ScheduleMapImport(meta.resolution(), meta.originX(), meta.originY(),
                pgm.width(), pgm.height(), data, points, routes);
    }

    private static int occupancyFromPgm(int grey) {
        if (grey == 205) {
            return -1;
        }
        if (grey > 254 - 60) {   // 空闲阈值附近
            return 0;
        }
        if (grey < 65) {         // 占据阈值附近
            return 100;
        }
        return Math.round((255 - grey) * 100f / 255f);
    }

    private record PgmMeta(int width, int height, byte[] pixels) {
    }

    private PgmMeta parsePgm(byte[] bytes) throws IOException {
        if (bytes[0] != 'P' || bytes[1] != '5') {
            throw new IllegalArgumentException("pgm 不是 P5 格式");
        }
        int[] idx = {2};
        int width = readPgmToken(bytes, idx);
        int height = readPgmToken(bytes, idx);
        int maxval = readPgmToken(bytes, idx);
        if (maxval > 255) {
            throw new IllegalArgumentException("pgm maxval > 255 不支持");
        }
        idx[0]++; // P5 规范：maxval 后恰好一个空白符，readPgmToken 停在它身上，必须跳过
        byte[] pixels = new byte[width * height];
        System.arraycopy(bytes, idx[0], pixels, 0, pixels.length);
        return new PgmMeta(width, height, pixels);
    }

    private static int readPgmToken(byte[] bytes, int[] idx) {
        while (idx[0] < bytes.length && Character.isWhitespace(bytes[idx[0]])) {
            idx[0]++;
        }
        int start = idx[0];
        while (idx[0] < bytes.length && !Character.isWhitespace(bytes[idx[0]])) {
            idx[0]++;
        }
        return Integer.parseInt(new String(bytes, start, idx[0] - start, StandardCharsets.US_ASCII));
    }

    private record YamlMeta(double resolution, double originX, double originY) {
    }

    private YamlMeta parseYaml(String yaml) {
        double resolution = 0.05;
        double originX = 0;
        double originY = 0;
        for (String line : yaml.split("\\R")) {
            if (line.startsWith("resolution:")) {
                resolution = Double.parseDouble(line.substring("resolution:".length()).trim());
            } else if (line.startsWith("origin:")) {
                String body = line.substring(line.indexOf('[') + 1, line.lastIndexOf(']'));
                String[] parts = body.split(",");
                originX = Double.parseDouble(parts[0].trim());
                originY = Double.parseDouble(parts[1].trim());
            }
        }
        return new YamlMeta(resolution, originX, originY);
    }

    /** 宽松解析 lxmap：Goal 取前 8 个已知字段，Route 取前 10 个；扩展段还原我方专有数据。 */
    private void parseLxmap(String lxmap, List<ImportedPoint> points, List<ImportedRoute> routes) {
        Map<Integer, String> goalIdToCode = new LinkedHashMap<>();
        Map<Integer, double[]> goalIdToPose = new LinkedHashMap<>();
        Map<Integer, String> goalIdToType = new LinkedHashMap<>();
        List<String[]> routeRows = new ArrayList<>();
        for (String line : lxmap.split("\\R")) {
            if (line.startsWith("Cairn: Goal ")) {
                String[] f = line.substring("Cairn: Goal ".length()).split("\\s+");
                if (f.length < 8) {
                    continue;
                }
                int id = Integer.parseInt(f[0]);
                goalIdToCode.put(id, f[1]);
                // 字段序：id code "" "" x y theta → f[4]/f[5]/f[6]（空引号占 f[2]/f[3]）
                goalIdToPose.put(id, new double[]{
                        Double.parseDouble(f[4]), Double.parseDouble(f[5]), Double.parseDouble(f[6])});
                goalIdToType.put(id, navPointType(Integer.parseInt(f[7])));
            } else if (line.startsWith("Cairn: Route ")) {
                routeRows.add(line.substring("Cairn: Route ".length()).split("\\s+"));
            }
        }
        // 点位类型来自调度格式固定的 function_ 字段。
        for (var entry : goalIdToCode.entrySet()) {
            double[] pose = goalIdToPose.get(entry.getKey());
            points.add(new ImportedPoint(entry.getValue(), pose[0], pose[1], pose[2],
                    goalIdToType.get(entry.getKey())));
        }
        for (String[] f : routeRows) {
            if (f.length < 10) {
                continue;
            }
            int src = Integer.parseInt(f[1]);
            int dst = Integer.parseInt(f[2]);
            double[] sp = goalIdToPose.get(src);
            double[] dp = goalIdToPose.get(dst);
            if (sp == null || dp == null) {
                continue;
            }
            boolean reverse = "1".equals(f[7]);
            Double speed = null;
            try {
                double v = Double.parseDouble(f[8]);
                if (v > 0) {
                    speed = v;
                }
            } catch (NumberFormatException ignored) {
                // 容错：非数值限速字段
            }
            routes.add(new ImportedRoute(goalIdToCode.get(src), goalIdToCode.get(dst),
                    sp[0], sp[1], dp[0], dp[1], speed, reverse, null));
        }
    }

    // ==================== 通用工具 ====================

    private static byte[] findEntry(Map<String, byte[]> entries, String... suffixes) {
        for (String suffix : suffixes) {
            for (var entry : entries.entrySet()) {
                if (entry.getKey().endsWith(suffix)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static String sanitize(String name) {
        return name == null ? "map" : name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private byte[] readResource(String path) throws IOException {
        try (InputStream in = ScheduleMapAdapter.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("classpath 缺少 " + path);
            }
            return in.readAllBytes();
        }
    }
}
