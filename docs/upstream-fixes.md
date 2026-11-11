# 上游问题修复适配

本轮以第 24 版 `29b32e0` 为基线，保留淘宝闪购茶饮配送识别修正，以及第 17 版聊天气泡与滚动实现。
仅移植下述问题的适用修复，不整体同步上游主题或界面改动，也不修改插件工具审批策略。

## 新任务保留弹窗配色

参考 Emyth 上游 [f6a24deb](https://github.com/Xiaoyun0629/emyth/commit/f6a24deb63b2f6eb4710effa2af70cc16316e5d0)。
之前发布任务会新建 `TaskSetting`，使背景、按钮和倒计时颜色恢复默认。
现在从当前设置生成新任务，保留配色，同时更新任务内容、类型、截止时间和惩罚，清空旧提交并回到待完成状态。
旧配置没有颜色字段时继续使用默认外观。

## 历史图片编码缓存

参考 RikkaHub [ae599e8](https://github.com/rikkahub/rikkahub/commit/ae599e8d71ccb8c00a3efa524b567498bf95c6e7)
和 [问题 #2292](https://github.com/rikkahub/rikkahub/issues/2292)。
之前每轮请求以及工具调用后的请求准备都会重新解码和压缩历史图片。
新增应用缓存目录内的 JPEG 编码缓存；键包含原图路径、修改时间、长度、编码参数与格式版本。
缓存总量上限为 256 MiB，按最近使用时间淘汰。读取或写入失败时继续从原图编码，不修改原图或聊天附件。
GIF 继续保留原有处理路径。

## Claude 思考块与签名回传

参考 RikkaHub [e166716](https://github.com/rikkahub/rikkahub/commit/e1667163cab112eb1da99cf3e0c8c4a1776be6d4)
和 [问题 #2308](https://github.com/rikkahub/rikkahub/issues/2308)。
适配本 fork 的旧消息与流式处理架构：分别保存 Claude 的思考块，只在同一个流式块内合并文字和签名分片，
并保留、恢复 `redacted_thinking` 的加密内容。其他供应商的普通思考流继续沿用原有合并行为。
消息分组只隐藏没有可见文字的加密思考协议块，避免产生空白卡片；原有思考卡片、气泡材质和滚动逻辑保持原样。
已有历史中已经丢弃的加密内容或签名无法从现有文字重新生成，本次不伪造或自动改写这类历史。

## 验证范围

构建流程显式运行 `:ai:testDebugUnitTest`、`:app:testDebugUnitTest` 和 `:app:assemblePerformance`，
同时上传两个模块的测试报告，并检查测试包名、非 debuggable 与 R8 优化输出。
回归测试分别覆盖任务配色和旧配置、图片缓存的命中和失效及失败处理，以及 Claude 的分块保存和回放。

JVM 测试和优化 APK 构建不能代替手机上的图片请求耗时对比或真实 Claude 接口验证；这两项仍需实际使用观察。
