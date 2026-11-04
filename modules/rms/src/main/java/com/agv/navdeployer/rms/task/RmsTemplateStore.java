package com.agv.navdeployer.rms.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * RMS 任务模板存储（对齐真机机制：模板由上层经 task_template/add 下发并持久化，
 * 任务下发时引用的模板必须已存在，否则提示"类型 x 的任务模板 x 不存在"）。
 *
 * <p>持久化：每个模板一个 JSON 文件（{@code <template_dir>/<code>.json}），
 * 元数据 + 模板内容（行为树 XML 等原文，仿真侧不解析、只存档备查）。
 * 启动时加载目录恢复内存索引；baseDir 为 null 时退化为纯内存（单测用）。
 *
 * <p>存储路径为上位机（机器人侧）自行约定，上层无需感知：
 * 默认 {@code data/rms-templates}，经 {@code rms.behavior.template-dir} 可改。
 */
public final class RmsTemplateStore {

    private static final Logger log = LoggerFactory.getLogger(RmsTemplateStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 模板元数据 + 内容（content 为下发原文，仿真侧不解析）。 */
    public record Template(String code, String templateId, String type,
                           String description, String content, String addedAt) {
    }

    private final Path baseDir;
    private final ConcurrentHashMap<String, Template> templates = new ConcurrentHashMap<>();

    public RmsTemplateStore(Path baseDir) {
        this.baseDir = baseDir;
        loadAll();
    }

    /** 纯内存模式（单测/无持久化环境）。 */
    public static RmsTemplateStore inMemory() {
        return new RmsTemplateStore(null);
    }

    private void loadAll() {
        if (baseDir == null || !Files.isDirectory(baseDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(baseDir)) {
            files.filter(path -> !path.getFileName().toString().startsWith("."))
                    .forEach(this::loadOne);
        } catch (IOException exception) {
            log.warn("rms template dir load failed: {} msg={}", baseDir, exception.getMessage());
        }
        log.info("rms templates loaded: dir={} count={}", baseDir, templates.size());
    }

    /**
     * 单文件加载，两种形态（业务：模板以文件形式存在，任务引用只带名字，后缀不参与匹配）：
     * 1) 本机 JSON 包装（task_template/add 落盘）：按内容 template_code 注册；
     * 2) 原始模板文件（行为树 XML 等，直接放入目录）：按<b>文件名去扩展名</b>注册，原文存档。
     */
    private void loadOne(Path file) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            Map<?, ?> parsed = null;
            try {
                parsed = MAPPER.readValue(raw, Map.class);
            } catch (Exception ignored) {
                // 非 JSON（原始 XML 等）：按文件名注册
            }
            if (parsed != null) {
                String code = normalize(text(parsed, "template_code"));
                if (code != null) {
                    templates.put(code, new Template(code, text(parsed, "template_id"),
                            text(parsed, "template_type"), text(parsed, "template_description"),
                            text(parsed, "template_content"), text(parsed, "template_add_time")));
                    return;
                }
                // JSON 但不是本机包装（无 template_code 字段）：同样按文件名注册
            }
            registerByFileName(file, raw);
        } catch (Exception exception) {
            log.warn("rms template file load failed: {} msg={}", file, exception.getMessage());
        }
    }

    /** 原始模板文件注册：文件名去最后一个扩展名作为 code（LAD-28-A-not-lift.xml → LAD-28-A-not-lift），内容原文存档备查。 */
    private void registerByFileName(Path file, String raw) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String code = normalize(dot > 0 ? name.substring(0, dot) : name);
        if (code == null) {
            return;
        }
        templates.put(code, new Template(code, code, "", "file: " + name, raw,
                OffsetDateTime.now().toString()));
        log.info("rms template registered by file name: {} -> {}", name, code);
    }

    /** 保存/覆盖模板（同 code 覆盖；code 去首尾空格——报文多余空格不影响按名匹配），持久化失败不阻断（内存为准，WARN 留痕）。 */
    public Template save(String code, String templateId, String type,
                         String description, String content) {
        code = normalize(code);
        Template template = new Template(code,
                templateId == null || templateId.isBlank() ? code : templateId,
                type == null ? "" : type,
                description == null ? "" : description,
                content,
                OffsetDateTime.now().toString());
        templates.put(code, template);
        if (baseDir != null) {
            try {
                Files.createDirectories(baseDir);
                Path file = baseDir.resolve(fileNameOf(code));
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), Map.of(
                        "template_code", template.code(),
                        "template_id", template.templateId(),
                        "template_type", template.type(),
                        "template_description", template.description(),
                        "template_content", template.content() == null ? "" : template.content(),
                        "template_add_time", template.addedAt()));
            } catch (Exception exception) {
                log.warn("rms template {} persist failed: {}", code, exception.getMessage());
            }
        }
        log.info("rms template saved: code={} type={} contentLen={}",
                code, template.type(), content == null ? 0 : content.length());
        return template;
    }

    /** 删除模板；不存在返回 false。 */
    public boolean delete(String code) {
        Template removed = templates.remove(normalize(code));
        if (removed == null) {
            return false;
        }
        if (baseDir != null) {
            try {
                Files.deleteIfExists(baseDir.resolve(fileNameOf(code)));
            } catch (IOException exception) {
                log.warn("rms template {} delete file failed: {}", code, exception.getMessage());
            }
        }
        return true;
    }

    /** 按名查找：精确匹配（大小写敏感），后缀/文件名不参与——任务引用与下发使用同一 code 字符串。 */
    public Optional<Template> find(String code) {
        code = normalize(code);
        return code == null
                ? Optional.empty() : Optional.ofNullable(templates.get(code));
    }

    public List<Template> list() {
        return new ArrayList<>(templates.values());
    }

    /** 文件名净化：只留安全字符，防路径穿越（template_code 是外部输入）。 */
    private static String fileNameOf(String code) {
        String safe = code.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
        return safe + ".json";
    }

    /** code 归一：去首尾空格（空白视为 null）——报文冗余空白不影响按名匹配。 */
    private static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
