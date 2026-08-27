package com.skill.registry;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import com.skill.loader.SkillLoader;
import com.skill.model.SkillDefinition;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill注册中心。
 *
 * 负责在项目启动时加载全部SKILL.md，
 * 并提供查询Skill的方法。
 */
@Slf4j
@Component
public class SkillRegistry {

    /**
     * 扫描所有skills目录下的SKILL.md。
     */
    private static final String SKILL_LOCATION_PATTERN =
            "classpath*:skills/*/SKILL.md";

    private final SkillLoader skillLoader;

    /**
     * key是Skill名称，value是完整的Skill定义。
     */
    private final Map<String, SkillDefinition> skills =
            new ConcurrentHashMap<>();

    public SkillRegistry(SkillLoader skillLoader) {
        this.skillLoader = skillLoader;
    }

    /**
     * Spring创建该Bean后自动执行。
     */
    @PostConstruct
    public void initialize() {
        loadAll();
    }

    /**
     * 扫描并加载所有Skill。
     */
    public void loadAll() {
        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver();

        try {
            Resource[] resources = resolver.getResources(
                    SKILL_LOCATION_PATTERN
            );

            skills.clear();

            for (Resource resource : resources) {
                SkillDefinition skill = skillLoader.load(resource);
                register(skill);
            }

            log.info(
                    "[SkillRegistry] Skill加载完成: count={}, names={}",
                    skills.size(),
                    skills.keySet()
            );
        } catch (IOException e) {
            throw new IllegalStateException(
                    "扫描Skill文件失败",
                    e
            );
        }
    }

    /**
     * 注册一个Skill。
     */
    private void register(SkillDefinition skill) {
        SkillDefinition existing = skills.putIfAbsent(
                skill.name(),
                skill
        );

        if (existing != null) {
            throw new IllegalStateException(
                    "发现重复的Skill名称：" + skill.name()
            );
        }

        log.info(
                "[SkillRegistry] 已注册Skill: name={}, enabled={}, tools={}",
                skill.name(),
                skill.enabled(),
                skill.tools().size()
        );
    }

    /**
     * 根据名称查询已启用的Skill。
     */
    public Optional<SkillDefinition> findEnabledByName(
            String skillName) {

        if (skillName == null || skillName.isBlank()) {
            return Optional.empty();
        }

        return Optional.ofNullable(skills.get(skillName.trim()))
                .filter(SkillDefinition::enabled);
    }

    /**
     * 查询所有已启用的Skill。
     */
    public List<SkillDefinition> findAllEnabled() {
        return skills.values()
                .stream()
                .filter(SkillDefinition::enabled)
                .sorted(Comparator.comparing(
                        SkillDefinition::name
                ))
                .toList();
    }

    /**
     * 查询当前加载的Skill数量。
     */
    public int size() {
        return skills.size();
    }
}