# GitHub 同类工具基准（公开资料）

检索日期：2026-08-31。以下按“Android root/性能/应用管理/ZRAM/FPS”关键词搜索后，选取相关度最高的 10 个公开仓库；Star 只是公开热度指标，不代表产品质量排名。只借鉴公开的产品思路，不复制代码、资源或旧应用结构。

| 仓库 | Stars（检索时） | 可借鉴点 | 帧域的取舍 |
| --- | ---: | --- | --- |
| [Hamza417/Inure](https://github.com/Hamza417/Inure) | 1,898 | Root/Shizuku 应用管理、分析、定制主题 | M2 增加后端状态和应用健康摘要；不引入内置终端和内容商店 |
| [Androidacy/MagiskModuleManager](https://github.com/Androidacy/MagiskModuleManager) | 1,232 | 模块发现、安装前信息确认 | 第一版排除在线模块商店，只保留本地能力契约 |
| [HmnDev-Tech/shevery](https://github.com/HmnDev-Tech/shevery) | 914 | Compose/Material 3、兼容性导向 | 采用自定义状态卡和自适应布局，不复刻视觉 |
| [Khh-vu/wifi-password-manager](https://github.com/Khh-vu/wifi-password-manager) | 291 | Shizuku/root 后端降级 | C0 采用最小权限和明确后端切换状态 |
| [VR-25/zram-swap-manager](https://github.com/VR-25/zram-swap-manager) | 264 | ZRAM + 动态 swappiness | M5 增加变更预览、快照和恢复；不直接移植脚本 |
| [ZUANVFX01/ZKM](https://github.com/ZUANVFX01/ZKM) | 98 | Kernel 调节、Material 3 Expressive | M4 采用能力矩阵和安全预设，避免盲写 |
| [android/tuningfork](https://github.com/android/tuningfork) | 62 | 按阶段记录 frame time 和性能指标 | M8 增加阶段标签、P95 和 jank，而非只显示平均 FPS |
| [Mathias-Boulay/Android-Game-Booster](https://github.com/Mathias-Boulay/Android-Game-Booster) | 61 | 低端设备的一键场景思路 | M3 规划“保守/均衡/性能”意图预设，默认保守 |
| [mayusi/Calibrate-SoC](https://github.com/mayusi/Calibrate-SoC) | 9 | 调节、监控、benchmark 合一 | 保留 session/对比思路，第一版不加入复杂 benchmark |
| [OutrageousStorm/android-system-tuner](https://github.com/OutrageousStorm/android-system-tuner) | 0 | sysfs 低层调节覆盖面 | 只借鉴能力分层；所有写入仍需 C0 白名单和回滚 |

## 已转化为实施要求

1. 每个危险操作必须先显示能力、影响范围和恢复方式。
2. M2/M3 以“应用健康与场景关系”为主线，不做旧式脚本菜单堆叠。
3. M4/M5 采用预设 + 高级参数双层交互，默认只显示设备支持项。
4. M8 默认展示 P95 frame time、jank 和阶段对比，平均 FPS 只是摘要。
5. C0/C4 统一给出后端、任务、结果和日志，避免用户猜测是否成功。
