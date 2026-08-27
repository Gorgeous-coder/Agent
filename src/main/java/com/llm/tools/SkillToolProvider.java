package com.llm.tools;

import org.springaicommunity.agent.tools.SkillsTool.Skill;
import org.springaicommunity.agent.utils.Skills;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.core.io.ResourceLoader;

import java.util.function.Function;

/**
 * 技能工具提供者：把每个 SKILL.md 注册成一个独立的 ToolCallback。
 *
 * <p>工具名 = 技能名（SKILL.md frontmatter 的 name），工具描述 = 技能描述。
 * 模型只看到"技能名 + 一句话描述"；当模型调用该工具时，才返回完整 SKILL.md
 * 内容（渐进式披露），再按其中的步骤与文件约定执行。</p>
 */
public class SkillToolProvider implements ToolCallbackProvider {

    /** 技能调用无参数 */
    public record NoArgs() {
    }

    private final ToolCallback[] skillCallbacks;

    /**
     * 将资源目录下所有skill，转化成ToolCallback[]（数组）
     *
     * @param resourceLoader
     */
    public SkillToolProvider(ResourceLoader resourceLoader) {
        this.skillCallbacks = Skills.loadResource(resourceLoader.getResource("classpath:skills"))//Skills.loadResource(...) 扫描 classpath:skills 目录，返回 List<Skill>（一个 SKILL.md = 一个 Skill 对象）。
                .stream()//.stream() 把它变成流（Stream），可以逐个处理。
                .map(SkillToolProvider::toToolCallback)//等价于.map(skill -> SkillToolProvider.toToolCallback(skill))，Stream<Skill> → Stream<ToolCallback>
                .toArray(ToolCallback[]::new);//ToolCallback[]::new等价于size -> new ToolCallback[size]，因为 getToolCallbacks() 接口要求返回 ToolCallback[]（数组类型），所以必须转数组而不是 List。
    }

    private static ToolCallback toToolCallback(Skill skill) {
        //fn 是工具被调用时的执行逻辑：当模型调用 memo 工具时，框架执行 fn.apply(new NoArgs()) → 返回 SKILL.md 内容字符串 → 作为 Observation 喂回给模型。
        Function<NoArgs, String> fn = noArgs -> {//Function<T, R> 是 Java 自带的函数式接口，代表"接收一个 T 类型参数，返回一个 R 类型结果"的函数,NoArgs（无参数的空 record）
            String basePath = skill.basePath() == null ? "" : skill.basePath();
            return "Base directory for this skill: %s%n%n%s".formatted(basePath, skill.content());
        };
        return FunctionToolCallback.builder(skill.name(), fn)
                .description(String.valueOf(skill.frontMatter().get("description"))
                        + " 调用后必须严格按返回的技能说明立即完成文件读写操作，不得反问用户或仅作文字说明")//在SKILL.md的description末尾添加提示，这个直接写在SKILL.md中，是因为这是所有技能的通用提示，无需在每个SKILL.md中重复写。
                .inputType(NoArgs.class)
                .build();
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return skillCallbacks;
    }
}