package com.skill.loader;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import com.skill.model.SkillDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 负责读取并解析 SKILL.md。
 */
@Component
public class SkillLoader {

    /**
     * 使用安全模式解析 YAML，避免 YAML 创建任意 Java 对象。
     */
    private final Yaml yaml =
            new Yaml(new SafeConstructor(new LoaderOptions()));

    /**
     * 从 resources 目录加载 Skill。
     *
     * @param location 例如 skills/dialect-assistant/SKILL.md
     */
    public SkillDefinition loadFromClasspath(String location) {
        String normalizedLocation = location.startsWith("/")
                ? location.substring(1)
                : location;

        return load(new ClassPathResource(normalizedLocation));
    }

    /**
     * 读取并解析 Skill 文件。
     */
    public SkillDefinition load(Resource resource) {
        try {
            String content = readContent(resource)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');

            if (!content.startsWith("---\n")) {
                throw new IllegalArgumentException(
                        "Skill文件缺少开头的YAML分隔符"
                );
            }

            int metadataEnd = content.indexOf("\n---\n", 4);

            if (metadataEnd < 0) {
                throw new IllegalArgumentException(
                        "Skill文件缺少结束的YAML分隔符"
                );
            }

            // 读取两个 --- 之间的 YAML 元数据
            String metadataText = content.substring(4, metadataEnd);

            // 读取第二个 --- 后面的 Markdown 执行说明
            String instructions = content
                    .substring(metadataEnd + 5)
                    .trim();

            Map<String, Object> metadata = yaml.load(metadataText);

            if (metadata == null) {
                throw new IllegalArgumentException(
                        "Skill元数据不能为空"
                );
            }

            return new SkillDefinition(
                    text(metadata, "name"),
                    text(metadata, "description"),
                    text(metadata, "version"),
                    booleanValue(metadata, "enabled", true),
                    stringList(metadata, "tools"),
                    instructions
            );
        } catch (IOException e) {
            throw new IllegalStateException(
                    "读取Skill文件失败：" + resource.getDescription(),
                    e
            );
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "解析Skill文件失败："
                            + resource.getDescription()
                            + "，原因："
                            + e.getMessage(),
                    e
            );
        }
    }

    private String readContent(Resource resource) throws IOException {
        try (InputStream inputStream = resource.getInputStream()) {
            return new String(
                    inputStream.readAllBytes(),
                    StandardCharsets.UTF_8
            );
        }
    }

    private String text(
            Map<String, Object> metadata,
            String key) {

        Object value = metadata.get(key);

        return value == null
                ? null
                : String.valueOf(value);
    }

    private boolean booleanValue(
            Map<String, Object> metadata,
            String key,
            boolean defaultValue) {

        Object value = metadata.get(key);

        return value == null
                ? defaultValue
                : Boolean.parseBoolean(String.valueOf(value));
    }

    private List<String> stringList(
            Map<String, Object> metadata,
            String key) {

        Object value = metadata.get(key);

        if (!(value instanceof List<?> values)) {
            return List.of();
        }

        return values.stream()
                .map(String::valueOf)
                .toList();
    }
}
//读取 SKILL.md
//→ 找到两个 --- 分隔符
//→ 前半部分用 SnakeYAML 解析
//→ 后半部分作为 Skill 执行说明
//→ 组装成 SkillDefinition