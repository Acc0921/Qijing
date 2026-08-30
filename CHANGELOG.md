# 更新记录

## 2026-08-31

- 增强场景执行为 CommandPlan 事务，失败时按已执行顺序逆向回滚并记录结果。
- 增加 SharedPreferences 新数据层实现，持久化设备、应用、场景和 FPS 遥测。
- 增加 M1 设备总览、M2 应用筛选、M3 场景草稿与校验状态层。
- 接入本机 Android SDK/Gradle 构建配置，指定已安装 Build Tools 版本。
- 修正 AndroidX 构建开关，准备生成首个 debug APK。
- 增加可访问的 Maven 镜像源，改善受限网络下的构建稳定性。
- 调整 Gradle/Kotlin 编译内存，避免本机低堆配置触发 GC thrashing。
- 首次完成 `assembleDebug` 构建，生成可安装 debug APK。
- 建立第一版重构工程与模块边界。
- 固化 C0-C5、M1/M2/M3/M4/M5/M8 范围。
- 增加 APK 静态解析功能映射文档。
- 增加特权执行、场景引擎、全新数据层、CPU/内存调节和 FPS 监控接口骨架。
- 增加 Compose 首页 UI 骨架，默认使用 dry-run 执行策略。
- 公开工程改为 clean-room 设计表述，移除旧产品标识和旧数据结构名称。
- 清理公开源码中的历史结构关键词，保持产品文案与实现独立。
