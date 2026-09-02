# 栖境配置调度真机验收：Xiaomi 2410DPN6CC（2026-09-02）

## 结论

真实配置包已在目标设备完成只读导入与硬件绑定，当前只能判定为“可继续 Root 只读预检”，不能判定为“正式可写”。本轮未调用 `su`，未启动自动化、Root worker、limiter 或 Gesture watcher，也未写入任何系统节点。

## 证据绑定

- 设备：Xiaomi `2410DPN6CC`，代号 `haotian`
- SoC / 平台：`SM8750` / `sun`，`ro.hardware=qcom`
- 系统：Android 17 / API 37，`OS4.0.0.6.XOBCNXM`
- 构建指纹：`Xiaomi/haotian/haotian:17/CP2A.260605.016/OS4.0.0.6.XOBCNXM:user/release-keys`
- 内核：`6.6.118-android15-8-gb9cc6ec16bc8-abogki536571621-4k`
- 配置 ZIP SHA-256：`2533A27BE0C61318679B36D3A3944563AF1A04E89729A03B516380229A4E2D24`
- 应用内配置指纹：`b33649b8ec66cebda55171e1f2236565a483dba89575db13685f6e8f9165c358`
- 已选变体：`Config/6+2/8E/流畅/官调降频版本`

## CPU 与变体匹配

| 策略域 | 核心 | 硬件范围 | 当前限制 | Governor 候选 |
|---|---|---:|---:|---|
| policy0 | 0–5 | 384–3532.8 MHz | 556.8–2745.6 MHz | walt、schedutil、performance、powersave、conservative |
| policy6 | 6–7 | 1017.6–4320 MHz | 1017.6–2649.6 MHz | walt、schedutil、performance、powersave、conservative |

设备核心集合为 `0-7`，策略域拓扑为 `6+2`。导入后 24 套配置中有 12 套 `8E / sun` 变体通过 SoC、平台、核心集合与拓扑校验；12 套 `8E5 / canoe` 变体保持不可选择。

## 目标节点只读审计

普通 ADB 已读到 32 个目标节点，包括两组 CPUFreq 最低/最高频率与 Governor、WALT target_loads / hispeed_freq / rate limit、两簇 core_ctl、CPU6/7 在线状态及四组 cpuset。

以下节点存在，但普通 ADB 因权限无法读取，必须在下一阶段用 Root **只读**确认，不能据此生成可写结论：

- `/sys/class/kgsl/kgsl-3d0/devfreq/{min_freq,max_freq,mod_percent}`
- `/sys/module/perfmgr/parameters/perfmgr_enable`
- `/proc/sys/walt/sched_boost`
- `/proc/sys/walt/input_boost/sched_boost_on_input`

以下节点在当前内核普通路径下明确不存在：

- `/proc/game_opt/disable_cpufreq_limit`
- `/sys/module/migt/parameters/glk_disable`
- `/sys/module/migt/parameters/glk_freq_limit_walt`
- `/sys/devices/system/cpu/cpufreq/boost`

缺失节点并不自动代表所有路由不可用：例如后三组主要出现在游戏或省电 call 路由中。但执行前必须按实际“模式 × 应用/游戏 × active/inactive”展开命令，逐项阻断缺失节点，不能静默忽略。

## 本轮发现并修复

1. 变体选择 Sheet 原先不可滚动，匹配项可能完全无法访问；现改为可滚动列表并优先显示兼容项。
2. 应用原先使用 `Build.HARDWARE=qcom` 作为平台，错误拒绝要求 `sun` 的变体；现优先使用 `Build.BOARD=sun`，同时保留 hardware、board、device 作为 SoC 辅助标识。
3. WALT `hispeed_freq` 被错误要求命中 CPU OPP 表。真机当前值 `1344000 / 2380800` 本身不在 OPP 列表但由内核正常上报；现仅对合法范围与最终读回负责，CPU policy 上下限仍严格按 OPP 表约束。

## 下一阶段门槛

1. 设备重新连接后覆盖安装 SHA-256 为 `6538BE2D6BA21E95FC9880D995AF7875BD1A1BD507FCD7D8A17007CA5937FBE1` 的 Debug APK。
2. 经用户单独确认后，只用 Root 读取上述 6 个权限节点，并检查所选变体每条实际路由的目标节点可读、可写属性；不改变值。
3. 输出第一批真实写入清单：精确原值、目标值、执行顺序、验证方式、恢复命令与风险，再等待第二次确认。
4. 只有真实写入、读回、离场恢复、进程中断恢复和恢复不完整故障验收全部通过后，才可签名正式版；结论仅适用于本报告绑定的设备、系统、内核和配置包哈希。
