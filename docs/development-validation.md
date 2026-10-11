# 第一阶段开发验证记录

交付记录日期：2026-10-11（Asia/Shanghai）。

## 构建与安装

- Debug APK 构建通过；包名 `com.example.terrariatoolbox`，版本 `1.0` / `1`。
- 应用名称“泰拉瑞亚工具箱”；`minSdk 34`，`compileSdk/targetSdk 37`。
- Debug 签名验证通过；APK 包含 arm64-v8a、armeabi-v7a、x86、x86_64 原生依赖。
- 已在 `emulator-5554` 安装并冷启动，启动结果 `Status: ok`；检查首次页面文字、布局及系统边距，无截断。
- 模拟器：Google sdk_gphone64_x86_64，Android 17 / API 37，安全补丁 2026-05-05。
- APK 权限检查：没有网络、存储管理、Root 或 Shizuku 权限；仅包含 AndroidX 自定义的未导出接收器签名权限。

## 自动化结果

| 检查 | 结果 |
| --- | --- |
| `:app:assembleDebug` | 通过 |
| `:app:testDebugUnitTest` | 27 项通过，0 失败 |
| `:app:connectedDebugAndroidTest` | 11 项通过，0 失败 |
| `:app:lintDebug` | 通过，0 错误；22 项非阻断警告，主要为可更新依赖及模板资源 |
| APK 签名检查 | 通过，Android Debug 签名 |
| 模拟器安装与冷启动 | 通过 |

配置测试使用真实样本的只读测试资源，在内存中创建英文副本。覆盖未知字段、精确数字、数组、重复键（含转义同名键）、严格 JSON 语法、UTF-8、语言类型及未知编号。

文件操作测试覆盖备份失败和损坏、源文件变化、权限撤销、短写入、写入中断、回读失败、未完成记录重建、损坏目标恢复、来源不匹配及禁止覆盖源文档别名。

仪器测试使用**仅存在于测试 APK 的受控文档提供方**：提供方所有者按标准接口授予受控 URI 权限，使用实际 ContentResolver 进行读写、截断、备份、恢复和导出。测试协调器时明确覆盖测试来源标识；生产应用不提供该覆盖入口。测试夹具和文件提供方均未包含在交付 APK 中。

Compose 测试覆盖目录／文件授权取消、游戏退出确认、已为中文和未完成操作时禁用写入、同名副本保持只读、原始备份导出及错误提示不泄露配置标识。系统选择器结果通过 Espresso Intents 注入。

## 交付文件

- APK：`artifacts/phase1/TerrariaToolbox-validation-debug.apk`
- 首次页面截图：`artifacts/phase1/validation-preview.png`
- 操作说明：`docs/phase1-validation.md`
- 真机验收模板：`docs/device-validation-template.md`

APK SHA-256：

```text
E77E1FF8790AD4389F0A56967C122273956C944C2AAB3DCF4E0B3AF887F1EC77
```

真实样本 `docs/config.json` SHA-256：

```text
B3AFC39F85016ECEB011D95494617132FC96A13CB36C47F1CE60F97C10CE2625
```

## 待真机完成

当前没有连接 Android 14 真机，也没有安装目标游戏。上述结果不能证明 Android/data 中的游戏原配置可被授权或写回。

尚待完成：本应用独立选中游戏原配置、原文件备份与写回、应用和设备重启后授权复用、游戏启动显示中文及退出重启后的中文保持、原文件完整恢复和 MT 手动恢复。完整验收前，支持结论为“未验证”，不能声明支持 Android 14 一键切换。
