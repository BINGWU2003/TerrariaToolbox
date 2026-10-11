# 泰拉瑞亚工具箱

<img src="app/src/main/res/drawable-nodpi/terraria_toolbox_logo.png" width="96" alt="泰拉瑞亚工具箱 Logo" />

Kotlin + Jetpack Compose 原生 Android 应用。当前为 **1.6 / versionCode 7**，通过 Shizuku 管理国服、国际服的角色与世界存档，并将国际服游戏语言切换为中文或英文。存档资源、导入来源与目标按服隔离。语言切换保留音量、按键等设置，内部自动备份仅用于异常恢复。

底部导航为「首页 / 存档 / 工具」。首页提供连接状态、导入与导出快捷任务、当前语言和实际操作记录。存档页提供角色／世界顶部分类、搜索、排序与跨分类多选；导入采用先选文件、再确认目标的五步引导。顶部「帮助」统一连接说明与问题排查。日常设置备份、历史与恢复入口已移除，仅写入异常时显示恢复入口。页面跟随系统浅色、深色和字体大小。

## 使用与安装

查看 [1.6 使用说明](docs/v1.6-usage.md)。可安装 APK：`artifacts/v1.6/TerrariaToolbox-v1.6-debug.apk`，摘要在同目录 `SHA256SUMS.txt`。使用原 Debug 签名，可覆盖安装旧版本并保留内部备份与未完成操作记录。

游戏目录只通过 Shizuku 访问。系统文件选择器用于选择玩家自己的存档及保存导出文件；不需要授予游戏目录的 SAF 权限。旧版配置副本流程继续停用，旧设置备份数据保留，日常列表与导出入口移除。

内置资源按「国际服 / 国服」和「角色 / 世界」分类：国际服 4 个角色、2 个世界，国服 1 个世界、暂无内置角色。选择后绑定同服目标，国服单账号自动选择、多账号手动选择。手机文件先识别世界的 `relogic` / `xindong` 标记或工具箱 ZIP 来源信息；未知来源必须明确声明所属服，跨服混合或信息冲突整批拒绝。同名自动生成 `Vor (1).plr` 等名称，保留已有文件和存档内容。单选导出原文件，多选导出按 Players/Worlds 分组并附来源服、大小和 SHA-256 的 ZIP；不处理 .bak 或地图探索等附属文件。

异常恢复会还原语言、音量、按键等设置；日常切回英文使用「切换到英文」。切换和异常恢复前均需要确认游戏已完全退出。

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

构建产物：`app/build/outputs/apk/debug/app-debug.apk`。本次验证记录见 [1.6 开发验证记录](docs/v1.6-development-validation.md)。

## 数据与支持范围

语言工具固定访问 `/storage/emulated/0/Android/data/com.and.games505.TerrariaPaid/config.json`。存档工具按用户提供的 `Masters Of Terraria/path.md`，仅访问国际服包目录或国服包目录下数字账号的 Players/Worlds。连接后自动校验服务身份和路径，无需玩家核对长路径。仅支持手机主用户、Shizuku API 13+、无线调试 shell UID 2000；不申请管理所有文件权限。手机重启后需要重新启动 Shizuku。没有国际服配置文件时，仍能使用已连接的存档工具。

语言写入前保存并检查原字节备份，确认源文件未变化，写入后重新读取检查。中断记录会阻止再次切换，直到检查或恢复完成。备份和操作记录位于应用内部 `noBackupFilesDir`，不随系统云备份上传。存档导入先在应用缓存中检查原文件或 ZIP，再经受限服务独占创建新文件、同步及 SHA-256 回读检查；不覆盖已有存档。导入和导出只复制主文件，不进行跨版本格式转换。

## 代码职责

- `domain`：严格 JSON 检查、中英语言修改、备份、存档命名、所属服约束与 ZIP 暂存／来源信息校验。
- `data`：Shizuku UserService、文件描述符访问、备份与会话存储；保留旧数据读取和 SAF 导出兼容。
- `feature/chinese`：底部导航、首页、语言、统一帮助与异常恢复页面及 ViewModel。文件操作在 IO 线程串行执行，返回首页不会取消语言操作。
- `feature/saves`：分服内置目录、目标记忆与重新核实、五步导入、未知来源声明、搜索／排序、同服跨分类多选导出、进度及逐项结果。页面往返保留分类、搜索、选择和当前导入步骤。

## 历史资料

[1.2 使用说明](docs/v1.2-usage.md)、[1.2 开发验证](docs/v1.2-development-validation.md) 和早期 SAF/副本流程仅用于历史版本。2026-10-11 用户确认旧版 Shizuku 中文切换、游戏内中文显示和完整恢复正常，见 [用户实测记录](docs/shizuku-user-validation-2026-10-11.md)；此记录不等同于 1.3 真机英文切换验收。
