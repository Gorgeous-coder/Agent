package com;

import com.llm.service.LlmService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 手动测试入口：验证 memo 技能能否被模型识别并调用。
 *
 * <p>启动完整 Spring 上下文，复用生产链路（deepseekClient + LlmService），
 * 依次测试"记录备忘"和"查看备忘"两个场景。</p>
 */
public class SkillTestMain {

    public static void main(String[] args) {
        try (ConfigurableApplicationContext ctx = SpringApplication.run(YkdApplication.class, args)) {
            LlmService llmService = ctx.getBean(LlmService.class);
            ChatClient deepseekClient = ctx.getBean("deepseekClient", ChatClient.class);

            String userId = "test-user";

            // 场景1：记录备忘
            String recordReply = llmService.chat("记一下明天下午三点开会", null, deepseekClient, userId);
            System.out.println("【记录】模型回复: " + recordReply);

            // 场景2：查看备忘
            String viewReply = llmService.chat("我的备忘", null, deepseekClient, userId);
            System.out.println("【查看】模型回复: " + viewReply);
        }
    }
}