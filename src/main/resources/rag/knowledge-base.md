# 优课达 Agent 项目知识库

## 项目简介
优课达 Agent 是一个基于微信的智能助手机器人，通过 wechat-ilink-sdk 接入微信，能够自动回复用户消息。

## 核心功能
1. 天气查询：通过高德地图 API 获取实时天气信息
2. 图片分析：支持发送图片让 AI 分析内容
3. 语音识别：支持语音消息转文字
4. 翻译：支持中英文互译
5. 备忘录：支持记录和查询备忘事项
6. 倒计时：支持设置倒计时提醒

## 技术架构
- 后端框架：Spring Boot 4.1.0 + Spring AI 2.0.0
- 对话模型：通过 SiliconFlow 调用 Qwen3.5-35B-A3B
- 向量模型：BAAI/bge-m3，输出 1024 维向量
- 向量数据库：Redis Stack（RediSearch 模块）
- 微信接入：wechat-ilink-sdk 2.3.3

## 消息处理流程
1. 用户发送消息到微信
2. WeixinBotService 轮询接收消息
3. MessageProcessor 解析消息内容
4. 依次尝试：翻译拦截 → Skill 关键词匹配 → RAG 知识库检索 → LLM 兜底回答



17日工作总结

今日工作：

  

1. 微信 SDK 接入与消息收发 ：在 Spring Boot 项目中引入 wechat-ilink-sdk，通过 Builder 模式创建客户端，用 ZXing 将登录二维码内容渲染为图片完成扫码登录；采用 onMessage 监听器 + 心跳轮询机制实现消息的持续收发与自动回复，解决了"只回一次"的问题。

2. 大模型文本回复 ：申请百炼 API，使用 OpenAI 兼容接口接入 qwen-plus，实现"收到消息 → 调用模型 → 回复微信"的完整链路。API Key 通过环境变量管理，不写入代码，确保信息安全。

3. 图片能力打通 ：完成两条图片链路——图片理解（qwen-vl-plus 识别用户发送的图片内容并以文字回复）和文生图（wanx 根据"画xxx"指令异步生成图片并发送回微信）。

遇到的关键问题及解决思路：

  

1. JPA 自动配置导致启动失败 ：项目中引入了 JPA 但未配置数据源。通过排查启动日志定位到是 DataSourceAutoConfiguration 缺少数据库 URL，配置 MySQL 数据源后解决。这让我理解了 Spring Boot 自动配置的触发条件。

2. 接口返回异常引发 NPE ：调用视觉模型时 choices 节点取不到值导致空指针。通过打印原始响应定位到是 API Key 未正确读取（IDEA 进程未继承环境变量），在 Run Configuration 中配置后解决。教训是：解析接口响应前先打印原始报文，避免盲目排查。

3. 监听器回调的异常处理 ：SDK 的消息监听器接口不允许抛出受检异常，但内部调用的模型方法会抛 IOException。通过在监听器中统一 try-catch 包裹处理解决，同时注意到 catch 块内调用 sendText 仍需内层 try-catch——这让我理解了 try-catch 的作用域边界。

4. 异步任务的链路设计 ：文生图接口采用异步任务模式（提交获取 task_id → 轮询状态 → 下载结果），与传统同步接口思路不同，通过状态机轮询 + 超时兜底实现。

明日工作计划

语音能力接入 ：语音转文字（paraformer 模型）+ 语音合成回复（cosyvoice 模型）。核心难点是微信 SILK 语音格式与模型 wav 格式之间的转码，需要先安装配置 ffmpeg 并验证转码链路。



18日工作总结

今日工作：

  

1. 语音识别（ASR） ：完成"微信语音 → 文字"链路。摸清微信语音是 SILK v3 格式（ 0x02 头 + #!SILK_V3 ），搭建解码工具链（去头 → rust-silk 转 16k wav），接入百炼 qwen3-asr-flash 实现语音转文字。实测能听懂用户语音内容并回复。

2. 语音合成（TTS） ：实现"文字 → 语音文件回复"。用 cosyvoice-v2 生成 wav，以文件消息形式发送，微信端可点开播放。完成了语音对话完整闭环（收语音 → 识别 → LLM → 合成 → 发语音）。

3. 意图识别重构 ：按老师要求重构回复逻辑——引入 replyMode 状态（text/voice），支持 /语音 、 /文本 显式切换指令，文字/图片/语音三类消息统一按当前模式输出，做到"输出形式听指令、不跟输入走"。

4. 天气能力 ：注册和风天气 API，解决 Key 绑定专属 Host 的鉴权问题，新建 Weather 类（36 个城市 ID 映射 + 实时天气查询），已接通"XX天气"意图。

遇到的关键问题及解决：

  

1. 语音气泡可行性判定 （重点）：按老师要求先研究"能不能气泡"。用三种变体逐一验证（tencent 头 silk、标准 silk、 原样转发收到的语音 ）——均出现"服务端 errcode=0 但微信端完全不投递"，判定 iLink 协议层不接受语音消息，气泡不可行。据此改用 语音文件 形式实现语音回复，没有用文字糊弄。

2. 语音格式转码 ：ffmpeg 不认微信语音的 0x02 前缀头，排查后确定需去头再解码；且 ffmpeg 不支持 SILK 编码，选用 rust-silk 工具链（支持微信腾讯前缀）解决双向格式转换。

3. 模型接入报错排查 ：qwen-audio 系列不支持 OpenAI 兼容协议（换 qwen3-asr-flash，注意 input_audio 字段格式）；TTS 报 418（format/sample_rate 必须在 input 而非 parameters 、音色须带 _v2 后缀、响应为 JSON 含音频 URL）。两次都是查官方文档定位，而非瞎试。

4. 天气 API Invalid Host ：Key 绑定专属 API Host，通用域名 403；GeoAPI 在专属 Host 下不可用，改用城市 ID 映射表绕开。  

明日工作计划

意图识别补强 ：结合记忆/上下文（如"接下来都用语音回复"的持久模式）进一步优化。


19日工作总结

一、今日工作

  

1. 理解并实践 Function Calling / Tool Use 完整闭环 ：梳理了"模型返回 tool_calls → 程序执行工具 → 结果以 role=tool 回传 → 二次请求生成最终回复"的整个流程，并弄清了第一轮响应体（ content=null 、 finish_reason=tool_calls ）与第二轮请求体（ messages 追加 tool_call_id ）的结构差异。

2. 掌握用 JSON Schema 描述函数签名 ：对照 get_current_time 工具，搞清了 name / description / properties / required / additionalProperties 各字段的作用，并明确了"必填参数放 required，可选参数留空"的写法。

3. 新增天气工具 get_weather ：在 WeatherTool 中实现了 JSON Schema 声明（入参 city 必填），并在 WxLink.buildTools() 挂载、 executeTool() 增加分发分支，至此微信 Bot 已能通过 Function Calling 调用时间、天气两个工具并输出执行结果。

4. 代码重构 ：将 WxLink 移入 agent 包，工具类按 tools/time 、 tools/weather 分包，并把工具的 JSON Schema 从 WxLink 下沉到各工具类自身的 buildTool() 方法，初步形成"每个工具自描述+自实现"的结构。

二、遇到的难点与解决

  

1. 工具结果回传的数据结构混淆 ：一开始分不清"模型看到的请求"和"模型返回的响应"，导致不知道工具结果该放哪。解决：画出两次请求的完整 JSON，确认第二次请求只是 messages 追加了 role=assistant(tool_calls) 和 role=tool(result) 两条，其他字段不变。

2. 天气分流与工具调用互相截胡 ：现有代码里 text.endsWith("天气") 会先拦截消息直接查天气，导致工具 get_weather 收不到典型请求。解决：认知到该冲突，明确后续测试需用"杭州现在多少度"这类不触发关键词分流的说法，并保留该问题待设计统一入口时处理。

3. 重构引发的 import 连锁改动 ：目录调整后工具类的包名全变。解决：按 agent / tools 分包后同步更新所有 import，编译诊断确认无报错。  

明日工作计划

1.解决天气分流与工具调用冲突 —— 设计统一的指令入口，让关键词分发和 Function Calling 不打架。  

2.补充一个纯内容型工具（如计算器/翻译），进一步验证 JSON Schema 参数校验（number 类型、必填）的用法。


20日工作总结

今日工作

 1. 项目架构分析与骨架搭建

完整阅读了组长项目的所有代码包，最终理清六大模块关系：

  

com.llm 是 Spring AI 核心模块，负责 ChatClient 配置、Advisor 日志和工具层

com.weather 是天气查询业务模块，使用 wttr.in 免费 API 获取天气数据

com.location 是高德地图服务模块，提供地理编码、周边搜索和路线规划功能

com.processor 是消息编排模块，包含 MessageProcessor 和 UserContext 实现

com.wxbot 是微信机器人模块，负责登录、轮询和消息收发

核心数据流为：微信消息经过 MessageProcessor 进入 LlmService，通过 ChatClient 调用 @Tool 工具，最终结果返回微信

  

在壳子项目中实现了核心骨架代码，共 8 个文件：

  

ChatClientConfig 配置 ChatClient Bean，通过方法参数自动注入 OpenAiChatModel

LlmService 定义聊天接口

LlmServiceImpl 实现核心聊天逻辑，将 WeatherTools 注册到 ChatClient 中

WeatherTools 是一个典型的 @Tool 工具，AI 通过它查询天气

WeatherService 定义天气业务接口

WeatherServiceImpl 调用 wttr.in 的 API 查询实时天气

WeatherResponse 是天气数据的 DTO

ChatController 提供 REST 接口 /chat 测试对话

  

 2. 遇到的难题与解决

第一个难题是理解 @Bean 方法参数注入机制。ChatClientConfig 中 @Bean 方法参数 OpenAiChatModel 没有加 @Autowired 注解，但能自动注入。原因是 Spring 框架对 @Bean 方法有内置规则，方法由 Spring 调用，参数自动从容器中查找注入，这和构造器注入是同一机制的不同形式。

  

第二个难题是编译报错不支持发行版本 21。mvn compile 时报错提示不支持 Java 21，排查后发现 JAVA_HOME 指向了 JDK 11，但 pom.xml 要求 Java 21。系统已安装 JDK 21，在 IDEA 中通过项目结构中的 SDK 设置切换即可解决。

  

第三个难题是代码同步问题，部分文件通过 AI 工具创建后未持久化，最终由用户手动补写完成。  
  

明日工作计划
继续学习其他 @Tool 工具（LocationTools、ImageTools、VoiceTools）的写法，选择第二个典型工具手动实现，加深对工具层架构的理解。


本周周报（8.17-8.21）

一、本周完成了哪些功能

1. 微信机器人接入与消息收发 ：引入 wechat-ilink-sdk，通过 Builder 模式创建客户端并用二维码扫码登录；实现心跳轮询持续收发消息并自动回复，解决了收发一次后不再回复的问题。

2. 大模型文本回复 ：申请 API Key，使用 OpenAI 兼容接口接入大模型，打通收到消息、调用模型、回复微信的完整链路。API Key 通过环境变量管理，不写入代码。

3. 图片能力 ：完成两条链路，图片理解（识别用户发送的图片并以文字回复）和文生图（根据画图指令异步生成图片回传微信）。

4. 语音能力 ：实现语音识别（微信 SILK 格式转 wav 后转文字）和语音合成（文字生成语音文件回复），形成收语音、识别、LLM、合成、发语音的完整闭环。

5. 意图识别 ：引入 replyMode 状态（text/voice），支持切换指令，文字图片语音三类消息统一按当前模式输出。

6. 天气能力 ：注册天气 API，解决 Key 绑定 Host 的鉴权问题，新建 Weather 类实现实时天气查询。

7. Function Calling / Tool Use ：理解并实践了模型返回 tool_calls、程序执行工具、结果回传、二次请求生成最终回复的完整闭环，掌握了用 JSON Schema 描述函数签名，并实现了时间、天气两个工具。

8. 项目架构学习与骨架搭建 ：完整阅读组长项目，理清 llm、weather、location、processor、wxbot 六大模块关系，并在壳子项目里动手实现了 ChatClientConfig、LlmService、WeatherTools 等核心骨架，补充实现了微信接入的 MessageProcessor、UserContext、WeixinBotService 与图片生成 ImageTools。

二、本周遇到的最大困难是什么，怎么解决的

最大的困难是打通微信端和模型能力的端到端链路时，问题大多出在数据格式和协议差异，静态看代码看不出原因。

语音气泡不可行那次，先用十种变体逐一验证均出现服务端返回成功但微信端不投递，最终判定协议层不接受语音消息，据此改用语音文件形式实现，没有用文字糊弄。

另一大类是格式与鉴权问题，如 ffmpeg 不认微信语音的 0x02 头、ASR 模型不支持 OpenAI 协议、天气 Key 绑定专属 Host 导致 403 等。解决思路都是先打印原始响应、查官方文档确认字段要求，而不是盲目试错。这也让我养成了解析接口响应前先看原始报文的习惯。

三、本周的收获是什么

1. 对 Spring AI 工具层有了结构化的认识，理解了模型决定工具调用、程序执行工具、结果回传这一套机制，并亲手搭建了能跑的骨架。

2. 深入理解了 Spring 的依赖注入细节，包括 @Bean 方法参数注入、构造器注入无需 @Autowired、同类型多 Bean 时用 @Qualifier 或 @Primary 区分。

3. 掌握了网络与并发处理，包括线程池并行收发消息、volatile 跨线程开关、节流控制发送频率，以及多个工具同时注册进 ChatClient 的用法。

4. 认识到排查问题的正确姿势，先打印原始响应、查官方文档、一步步定位根因，比盲目修改代码高效得多。