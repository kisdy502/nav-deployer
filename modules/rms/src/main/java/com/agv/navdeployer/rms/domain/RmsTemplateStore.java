package com.agv.navdeployer.rms.domain;

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

public final class RmsTemplateStore {

    private static final Logger log = LoggerFactory.getLogger(RmsTemplateStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Template(String code, String templateId, String type,
                           String description, String content, String addedAt) {
    }

    private final Path baseDir;
    private final ConcurrentHashMap<String, Template> templates = new ConcurrentHashMap<>();

    public RmsTemplateStore(Path baseDir) {
        this.baseDir = baseDir;
        loadAll();
    }

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

    private void loadOne(Path file) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            Map<?, ?> parsed = null;
            try {
                parsed = MAPPER.readValue(raw, Map.class);
            } catch (Exception ignored) {
            }
            if (parsed != null) {
                String code = normalize(text(parsed, "template_code"));
                if (code != null) {
                    templates.put(code, new Template(code, text(parsed, "template_id"),
                            text(parsed, "template_type"), text(parsed, "template_description"),
                            text(parsed, "template_content"), text(parsed, "template_add_time")));
                    return;
                }
            }
            registerByFileName(file, raw);
        } catch (Exception exception) {
            log.warn("rms template file load failed: {} msg={}", file, exception.getMessage());
        }
    }

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

    public Optional<Template> find(String code) {
        code = normalize(code);
        return code == null
                ? Optional.empty() : Optional.ofNullable(templates.get(code));
    }

    public List<Template> list() {
        return new ArrayList<>(templates.values());
    }

    private static String fileNameOf(String code) {
        String safe = code.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
        return safe + ".json";
    }

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
