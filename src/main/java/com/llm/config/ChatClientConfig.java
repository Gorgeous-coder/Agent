package com.llm.config;

import com.processor.BoundedResultQueue;
import com.processor.ProcessResult;
import com.llm.tools.SkillToolProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springaicommunity.agent.tools.FileSystemTools;
import com.llm.advisor.ReActLoggingAdvisor;
import com.storage.StorageProperties;

import java.util.Queue;

@Configuration
public class ChatClientConfig {

    @Bean
    Queue<ProcessResult> voiceQueue() {
        return new BoundedResultQueue(20);
    }

    /**
     * 0. 技能工具：每个 SKILL.md 注册为一个独立工具（工具名 = 技能名），模型直接按技能名调用
     */
    @Bean
    public SkillToolProvider skillToolProvider(ResourceLoader resourceLoader) {//逐一把每个`@Bean`拆解，区分：**入参（容器自动给）、自己 new/build、返回注册为 Bean**
        return new SkillToolProvider(resourceLoader);
    }

    /**
     * 0.1 文件系统工具：限制在备忘目录内，供技能读写备忘文件
     * 
     * FileSystemTools：Read、Write、ListDir、Delete
     */
    @Bean
    public FileSystemTools fileSystemTool(StorageProperties storageProperties) {
        return FileSystemTools.builder()
                .allowedDirectory(storageProperties.memoDir())
                .build();
    }

    /**
     * 1. 主对话客户端：直接使用默认模型
     */
    @Bean
    public ChatClient deepseekClient(OpenAiChatModel openAiChatModel, SkillToolProvider skillToolProvider, FileSystemTools fileSystemTool) {
        return ChatClient.builder(openAiChatModel)
                .defaultTools(skillToolProvider, fileSystemTool)
                //defaultTools：default = 默认常驻，客户端级
                //.tools(...)：本次请求临时指定，请求级
                .defaultSystem("""
                       你是人工智能助手。根据工具描述选择合适的工具，不要调用无关工具
                       识别图片时，没有明确要求生成图片或者用语音回答不准随便调用工具生成，必须只能出现纯文本
                        重要规则：
                        1. 工具返回的图片URL必须原样输出，不得省略、改写、用文字替代
                        2.位置类工具若提示尚未设置位置，直接提醒用户发送”我在XX”设置位置
                        3. 语音播报无明确性别要求时，gender 默认传 “female”
                       """)
                .defaultAdvisors(new ReActLoggingAdvisor())
                .build();
    }

    /**
     * 2. 识图/多模态客户端：共用底层 openAiChatModel，通过 Builder 动态指定视觉模型
     */
    @Bean
    public ChatClient agnesClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel)
                .defaultOptions(
                        OpenAiChatOptions.builder()
                                .model("Qwen/Qwen2.5-VL-72B-Instruct")
                )
                .defaultSystem("""
                       你是能识别图片的ai助手
                       当识别图片时，没有明确要求生成图片或者用语音回答不准随便调用工具生成，必须只能出现纯文本
                        重要规则：
                        1. 用户明确要求生成图片时，必须调用 generateImage 工具
                        2. 工具返回的图片URL必须原样输出，不得省略、不得改写、不得用文字描述替代
                        3. 回复格式："https://+平台返回的完整图片URL"
                        4.当用户明确告知‘我在某地’、‘把位置设置为某地’或提供具体地址时，必须调用setCurrentLocation工具
                        5.当用户询问本地天气且未说城市时，必须调用getLocalWeather工具
                       """)
                .build();
    }
}