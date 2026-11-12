# 主动消息上下文与 Operit 对照

本轮先完成参考研究和设置页整理，核心请求管线沿用构建 28。
不据界面速度或费用感受推断服务端缓存命中，也不承诺参考其他项目后必然降低账单。

## 参考范围

- Emyth 基线：`e23296a089682390cdcf135e21bc4d3f59301d53`（构建 28）。
- Operit 官方仓库：[AAswordman/Operit](https://github.com/AAswordman/Operit)。
- 固定参考提交：`16cffb98f52a11dc77bd0a789ffa0b01ed398ee4`。
- 重点跟踪其工作流调用 `send_message_to_ai` 的聊天路径；不把结论扩展到所有模型调用。
- 只参考设计，未复制该项目实现，也未访问用户的自建模型网关。

## 可以借鉴的实现

### 自动触发复用聊天处理

Operit 的计划工作流通过工具执行 `send_message_to_ai`，最终进入
`ChatServiceCore.sendUserMessage` 和 `MessageCoordinationDelegate.sendUserMessage`。
普通聊天界面也使用这条委托链，继续进入消息处理、模型上下文和 API 服务。
角色卡、模型和记忆空间根据目标聊天解析，而不是为自动任务另外维护简化版人设和记忆。

代码证据：

- [工作流调用工具](https://github.com/AAswordman/Operit/blob/16cffb98f52a11dc77bd0a789ffa0b01ed398ee4/app/src/main/java/com/ai/assistance/operit/core/workflow/WorkflowExecutor.kt#L1260)
- [工具进入聊天服务](https://github.com/AAswordman/Operit/blob/16cffb98f52a11dc77bd0a789ffa0b01ed398ee4/app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/standard/StandardChatManagerTool.kt#L1900)
- [普通界面入口](https://github.com/AAswordman/Operit/blob/16cffb98f52a11dc77bd0a789ffa0b01ed398ee4/app/src/main/java/com/ai/assistance/operit/ui/features/chat/viewmodel/ChatViewModel.kt#L1461)
- [目标聊天配置](https://github.com/AAswordman/Operit/blob/16cffb98f52a11dc77bd0a789ffa0b01ed398ee4/app/src/main/java/com/ai/assistance/operit/services/core/MessageCoordinationDelegate.kt#L601)

### 显示与持久化分开

`ChatTurnOptions` 分别控制 `persistTurn`、`hideUserMessage` 和 `notifyReply`。
隐藏且持久化的输入会标记为 `HIDDEN_PLACEHOLDER` 并写进历史；读取模型历史时不按显示标记过滤。
这提供了保留后台事件、同时不把控制指令堆到聊天界面的实现思路。

代码证据：

- [发送选项](https://github.com/AAswordman/Operit/blob/16cffb98f52a11dc77bd0a789ffa0b01ed398ee4/app/src/main/java/com/ai/assistance/operit/data/model/ChatTurnOptions.kt#L3)
- [隐藏并保存](https://github.com/AAswordman/Operit/blob/16cffb98f52a11dc77bd0a789ffa0b01ed398ee4/app/src/main/java/com/ai/assistance/operit/services/core/MessageProcessingDelegate.kt#L878)
- [读取模型历史](https://github.com/AAswordman/Operit/blob/16cffb98f52a11dc77bd0a789ffa0b01ed398ee4/app/src/main/java/com/ai/assistance/operit/core/chat/AIMessageManager.kt#L1361)

所查代码没有与 Emyth `[PASS]` 完全等价的主动跳过协议，不能声称其已解决跳过记忆。
Emyth 若采用这个思路，应保留真实时间、跳过或保存结果，以及“未向用户发送”状态；
没有明确简短理由时就保留原因未知，不从思考过程或后续推测补写原因。

### 缓存统计保留未知值

Operit 的 `ProviderUsageSnapshot` 用可空字段区分供应商未上报与实际为零，分别保存命中、
未命中和缓存写入用量。其本地公共前缀估算不能当作服务端 KV 缓存命中证据。

- [用量契约](https://github.com/AAswordman/Operit/blob/16cffb98f52a11dc77bd0a789ffa0b01ed398ee4/app/src/main/java/com/ai/assistance/operit/data/stats/ProviderUsageSnapshot.kt#L6)
- [真实请求用量记录](https://github.com/AAswordman/Operit/blob/16cffb98f52a11dc77bd0a789ffa0b01ed398ee4/app/src/main/java/com/ai/assistance/operit/api/chat/llmprovider/TokenTrackingAIService.kt#L270)

## 不直接照搬的部分

Operit 在部分发送流程里通过目标聊天是否为当前窗口判断后台发送。自动任务恰好指向当前窗口时，
仍可能进入读取输入框附件的分支。Emyth 应显式区分真实用户输入、定时唤醒和工具触发，
目标会话与发送来源分别传递，避免后台任务携带用户尚未发送的草稿或附件。

共享请求管线也不表示取消后台工具审批、并发互斥、WakeLock、取消保护或原有工具轮次边界。
这些执行策略仍需保留，并与上下文组装分别验证。

## Emyth 的建议实施顺序

1. 补齐真实缓存观测：主动流保存最终 usage；兼容服务端实际返回的缓存字段；
   区分未上报与零。记录请求模式和用量，不全局保存完整模型回复或请求头。
2. 从普通聊天抽取共用的人设、记忆、召回、工具及历史准备，先验证普通聊天输出不变，
   再让主动消息沿用目标对话的人设和助手配置。
3. 为唤醒与跳过增加按助手和会话隔离的有限事件历史。界面显示与模型读取分别处理，
   不把后台指令说成用户亲口发言，不把跳过算成已联系，不重放已执行工具。
4. 调整输入布局，把稳定内容放前，本轮变化的时间、设备状态和触发信息放后，
   再用实际供应商统计做同一会话的普通/主动对照。

第 4 步需要逐供应商适配：当前 Claude、Google 适配器只读取首条系统消息，后续系统消息会被过滤，
不能直接追加末尾系统消息便认定事件已传到模型。模板中的当前时间、世界书注入、历史窗口与工具
集合也可能改变前缀；即使内容接近，缓存是否命中仍取决于服务端和网关实际行为。

最低验证范围包括：普通与主动的人设/记忆一致性，跨助手隔离，跳过后的事件连续性，
各供应商不丢触发信息，后台审批与取消不变，以及流式 usage、原生缓存字段和未上报字段。

## 本轮设置页整理

主动消息设置只显示最新结果摘要；完整记录移到弹出列表，点按单条展开详情与对应聊天入口。
原有最多八条本地执行记录和可见时轮询保持不变，查看记录不会触发新的模型请求或重排计划。
唤醒提示词、淘宝闪购茶饮提醒、内置提示词入口分别使用闹钟、通知和编辑图标。
聊天气泡、材质、滚动以及模型请求内容均沿用构建 28。
