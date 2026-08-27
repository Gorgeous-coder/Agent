package com.llm.skill.impl;

import com.llm.skill.BaseSkill;
import org.springframework.stereotype.Component;

@Component
public class HelpSkill extends BaseSkill {

    @Override
    public String getName() {
        return "帮助";
    }

    @Override
    public String[] getKeywords() {
        return new String[]{"帮助", "功能", "能做什么", "有什么功能", "怎么用"};
    }

    @Override
    protected String doExecute(String userMessage, String userId) {
        return """
                🤖 我可以帮你做这些事情：

                1. 🌤️ 天气查询：输入 "北京天气" / "上海热不热"
                2. 🎨 生成图片：输入 "画一只猫" / "生成风景图"
                3. 🔤 翻译文本：输入 "翻译你好" / "hello怎么说"
                4. ⏰ 当前时间：输入 "现在几点" / "北京时间"
                5. 📹 视频摘要：输入 "总结视频 + B站链接"
                6. 📋 政策查询：输入 "年假政策" / "考勤规则"

                试试告诉我你想做什么吧！
                """;
    }
}