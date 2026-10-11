# 泰拉瑞亚工具箱

Kotlin + Jetpack Compose 原生 Android 应用。当前为 1.1 “Shizuku 优先授权与安全写回验证版”，保留第一阶段 SAF 入口；完整工具箱和副本编辑流程属于后续阶段。

## 构建

- Android Studio，Android SDK Platform 37，项目 Gradle Wrapper 9.6.0、AGP 9.4.1。
- 使用项目的 Gradle JVM 配置（JDK 25）；Android Studio 自带 JBR 可启动 Wrapper。
- `minSdk = 34`，`compileSdk = targetSdk = 37`；包名 `com.example.terrariatoolbox`。
- 保持 `local.properties` 中 SDK 路径与本机一致，不提交该文件。

```powershell
# 本机 Android Studio 路径；在其他电脑上替换为自己的 JBR 路径。
$env:JAVA_HOME = 'D:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# 连接开发测试设备或启动模拟器后运行：
.\gradlew.bat :app:connectedDebugAndroidTest
```

构建 APK：`app/build/outputs/apk/debug/app-debug.apk`。新版交付副本：`artifacts/shizuku-validation/TerrariaToolbox-shizuku-validation-debug.apk`。第一阶段 APK 原样保留。使用原 Debug 签名，可覆盖安装并保留内部备份；工具箱不上传配置。

## 验证与支持范围

使用说明见 [第一阶段操作说明](docs/phase1-validation.md)，设备记录见 [真机验收模板](docs/device-validation-template.md)，本次开发测试结果见 [开发验证记录](docs/development-validation.md)。

Android 14 标准 SAF 通常限制访问其他应用的 `Android/data`。按本轮要求新增用户主动启用的 Shizuku 无线调试 shell 后端；不申请管理所有文件权限。Shizuku 需安装、启动并独立授权工具箱；手机重启后需重新启动服务。本版仅验证主用户、API 13+、UID 2000 和固定配置。

新版资料：[Shizuku 操作说明](docs/shizuku-validation.md)、[真机模板](docs/shizuku-device-validation-template.md)、[开发验证记录](docs/shizuku-development-validation.md)。第一阶段记录保留为历史记录。普通文档或模拟器夹具成功不代表真机游戏目录访问及中文生效验收通过。

2026-10-11 用户确认本机调试、中文切换、游戏内中文显示和完整恢复均正常，已保存 [用户实测记录](docs/shizuku-user-validation-2026-10-11.md)。具体设备环境与重启验收尚未单独提供。

SAF 入口只识别外部存储系统提供方给出的精确目标文档 ID `primary:Android/data/com.and.games505.TerrariaPaid/config.json`，并在用户确认来源后允许尝试写回。其他提供方和同名副本可读取、保存原始备份、导出备份，不能直接写回。Shizuku 入口固定访问上述游戏配置路径。可尝试写入仍须经过实际写入和回读才能记录验证通过。

## 代码职责

- `domain`：严格 JSON 检查、语言修改、内容校验及写入/恢复/导出协调。
- `data`：SAF、Shizuku UserService 与文件描述符访问、内部安全落盘、原始备份、授权信息及未完成操作记录。
- `feature/chinese`：验证页面和 ViewModel；所有文件工作在 IO 线程串行执行。

备份和操作记录位于应用内部 `noBackupFilesDir`，不随系统云备份上传。备份逐字节保存且不会自动删除。外部 SAF 提供方没有通用原子替换保证，写入失败或应用被中断时须先检查或恢复备份。
