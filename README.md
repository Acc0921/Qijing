# 栖境（Qijing）

这是「栖境（Qijing）」的全新第一版工程。产品只实现已确认的核心能力：特权执行、场景调度、设备能力探测、新数据层、日志任务、全新 UI，以及设备总览、应用列表、应用场景、CPU 调节、内存/ZRAM、FPS 监控。实现不依赖任何旧版数据、页面或脚本格式。

> 默认后端仍为 dry-run。Root 与 Shizuku 必须由用户明确选择；仅 CPU governor、频率和 swappiness 进入固定白名单，快照不完整或读回不一致时拒绝执行。

## 工程约定

- Android/Kotlin，UI 使用 Jetpack Compose + Material 3，并通过自定义色彩、卡片和信息层级区别于旧版。
- C0-C5 是平台层；M1/M2/M3/M4/M5/M8 是第一版产品模块。
- C3 是全新数据模型，不提供历史数据兼容层。
- 每次可审阅的改动都更新 `CHANGELOG.md`，提交信息保持简短。

## 目录

- `docs/v1-scope.md`：第一版冻结范围与验收标准。
- `docs/implementation-coverage.md`：第一版能力到代码接口的实现映射。
- `app/src/main/java/com/qijing/`：核心代码与 UI 骨架。
- `CHANGELOG.md`：更新记录。

## 构建

在安装 Android SDK、JDK 17 和 Gradle 后执行：

```text
./gradlew :app:assembleDebug
```

当前开发环境使用 JDK 17、Android SDK 35 与 Gradle 8.11.1；提交前运行 JVM 测试、AndroidTest 编译、lint 和 debug APK 构建。真实 Root/Shizuku 写入仍以真机验收记录为发布门槛。
