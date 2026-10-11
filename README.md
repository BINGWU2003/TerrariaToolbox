# 泰拉瑞亚工具箱

<img src="assets/branding/terraria-toolbox-logo.png" width="96" alt="泰拉瑞亚工具箱 Logo" />

Kotlin + Jetpack Compose 原生 Android 应用。当前为 **1.4 / versionCode 5**，通过 Shizuku 管理国服、国际服的角色与世界存档，并将国际服游戏语言切换为中文或英文。语言切换保留音量、按键等设置，并在每次切换前自动备份。

首页提供 Shizuku 连接状态和安装、启动说明，以及「存档管理」「游戏语言」两个工具入口。「更多操作」包含设置备份和问题排查。页面跟随系统浅色、深色和字体大小，日常说明面向普通玩家。

## 使用与安装

查看 [1.4 使用说明](docs/v1.4-usage.md)。可安装 APK：`artifacts/v1.4/TerrariaToolbox-v1.4-debug.apk`，摘要在同目录 `SHA256SUMS.txt`。使用原 Debug 签名，可覆盖安装旧版本并保留设置备份与未完成操作记录。

游戏目录只通过 Shizuku 访问。系统文件选择器用于选择玩家自己的存档及保存导出文件；不需要授予游戏目录的 SAF 权限。旧版配置副本流程继续停用，旧设置备份仍可查看和导出。

存档支持内置 4 个角色与 1 个世界、手机 .plr/.wld 文件和可再次导入的 ZIP。国服单账号自动选择、多账号手动选择。同名在目标目录内忽略大小写，自动生成 `Vor (1).plr` 等名称，保留已有文件和存档内容；游戏里的名称可能仍相同。角色、世界列表显示文件名、大小和修改时间。单选导出原文件，多选导出按 Players/Worlds 分组的 ZIP；不处理 .bak 或地图探索等附属文件。

恢复备份会完整还原语言、音量、按键等设置；单纯切回英文应使用「切换到英文」。切换和恢复前均需要确认游戏已完全退出。

## 构建与验证

- Android SDK Platform 37，Gradle Wrapper 9.6.0、AGP 9.4.1，项目 Gradle JVM 为 JDK 25。
- `minSdk = 34`，`compileSdk = targetSdk = 37`；包名 `com.example.terrariatoolbox`。
- 保持 `local.properties` 中 SDK 路径与本机一致，不提交该文件。

```powershell
$env:JAVA_HOME = 'D:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# 普通模拟器测试；默认排除需要专用游戏夹具的用例。
.\gradlew.bat :app:connectedDebugAndroidTest
```

构建产物：`app/build/outputs/apk/debug/app-debug.apk`。本次验证记录见 [1.4 开发验证记录](docs/v1.4-development-validation.md)。

## 数据与支持范围

语言工具固定访问 `/storage/emulated/0/Android/data/com.and.games505.TerrariaPaid/config.json`。存档工具按用户提供的 `Masters Of Terraria/path.md`，仅访问国际服包目录或国服包目录下数字账号的 Players/Worlds。连接后自动校验服务身份和路径，无需玩家核对长路径。仅支持手机主用户、Shizuku API 13+、无线调试 shell UID 2000；不申请管理所有文件权限。手机重启后需要重新启动 Shizuku。没有国际服配置文件时，仍能使用已连接的存档工具。

语言写入前保存并检查原字节备份，确认源文件未变化，写入后重新读取检查。中断记录会阻止再次切换，直到检查或恢复完成。备份和操作记录位于应用内部 `noBackupFilesDir`，不随系统云备份上传。存档导入先在应用缓存中检查原文件或 ZIP，再经受限服务独占创建新文件、同步及 SHA-256 回读检查；不覆盖已有存档。导入和导出只复制主文件，不进行跨版本格式转换。

## 代码职责

- `domain`：严格 JSON 检查、中英语言修改、备份、存档命名与 ZIP 暂存。
- `data`：Shizuku UserService、文件描述符访问、备份与会话存储；保留旧数据读取和 SAF 导出兼容。
- `feature/chinese`：首页、游戏语言、备份和问题排查页面及 ViewModel。文件操作在 IO 线程串行执行，返回首页不会取消操作。
- `feature/saves`：版本与账号选择、内置与手机文件导入、列表、多选导出及逐项结果。

## 历史资料

[1.2 使用说明](docs/v1.2-usage.md)、[1.2 开发验证](docs/v1.2-development-validation.md) 和早期 SAF/副本流程仅用于历史版本。2026-10-11 用户确认旧版 Shizuku 中文切换、游戏内中文显示和完整恢复正常，见 [用户实测记录](docs/shizuku-user-validation-2026-10-11.md)；此记录不等同于 1.3 真机英文切换验收。
