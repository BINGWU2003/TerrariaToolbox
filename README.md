# 泰拉瑞亚工具箱

<img src="app/src/main/res/drawable-nodpi/terraria_toolbox_logo.png" width="96" alt="泰拉瑞亚工具箱 Logo" />

管理 Android 泰拉瑞亚角色与世界存档，支持国际服与心动国服；国际服还可切换中文／英文。首个公开版本为 **1.0.0**。

- 导入手机文件、ZIP 或内置存档，同名自动编号，保留已有存档。
- 搜索、排序和多选导出角色／世界；多个文件导出为带来源信息与校验摘要的 ZIP。
- 按国服／国际服隔离资源与目标，未知来源需要确认所属服。
- 语言切换保留音量、按键等设置，写入异常时提供检查与恢复入口。

## 安装与使用

从 [GitHub Releases](https://github.com/BINGWU2003/TerrariaToolbox/releases) 下载 `TerrariaToolbox-v1.0.0.apk`。同页提供 `SHA256SUMS.txt`，用于核对下载文件。

需要 **Android 14 或更新系统**，以及通过无线调试启动的 [Shizuku](https://shizuku.rikka.app/zh-hans/guide/setup/)。先打开一次对应游戏，完全退出后，在工具箱首页连接 Shizuku 并允许授权。手机重启后需要重新启动 Shizuku。

完整流程和问题排查见 [使用说明](docs/usage.md)。首页也提供项目与作者主页入口：

- [GitHub 项目与反馈](https://github.com/BINGWU2003/TerrariaToolbox)
- [作者哔哩哔哩主页](https://space.bilibili.com/355910465)

公开版采用新包名 `io.github.bingwu2003.terrariatoolbox` 和独立正式签名，与旧测试版分别安装。旧测试版内部备份、授权和未完成操作记录不会迁移；如旧版有异常恢复提示，先完成恢复。以后公开版使用相同包名与签名覆盖更新。

## 支持范围

游戏目录通过 Shizuku 访问；系统文件选择器用于选择手机文件和保存导出文件。仅支持手机主用户、Shizuku API 13+、无线调试 shell UID 2000。

国际服支持 `com.and.games505.TerrariaPaid`；心动国服支持 `com.xd.terraria` 下的数字账号目录。语言工具仅用于国际服。路径与内置资源对应关系见 [存档路径说明](Masters%20Of%20Terraria/path.md)。

只复制 `.plr`／`.wld` 主文件，不包含地图探索和 `.bak`，不转换或解密存档格式。单文件最多 64 MB、每批 256 MB、最多 500 个存档。来源识别可减少选错所属服，但不保证目标游戏版本能加载任意存档。

## 开发与构建

使用 Kotlin、Jetpack Compose，JDK 25、Gradle Wrapper 9.6.0、AGP 9.4.1、Android SDK `platforms;android-37.0` 与 Build Tools 36.0.0。`minSdk = 34`，`compileSdk = targetSdk = 37`；`versionCode = 8`。Windows 使用 `gradlew.bat`，本机 SDK 路径写入未提交的 `local.properties`。

```bash
bash gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

正式构建先配置长期签名。默认读取 `~/.android/terraria-toolbox/signing.properties`，也可用环境变量 `TERRARIA_SIGNING_PROPERTIES` 指定其他文件。文件内容格式如下，实际密码和私钥保存在仓库外：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=terrariatoolbox
keyPassword=YOUR_KEY_PASSWORD
```

```bash
bash gradlew :app:prepareRelease
python tools/verify_release.py --build-tools "$ANDROID_HOME/build-tools/36.0.0" --expected-tag v1.0.0
```

正式产物位于 `app/build/outputs/distribution/`：APK、SHA-256、签名报告与验证摘要。缺少签名配置时，正式构建会停止。备份私钥及密码；后续更新必须继续使用同一签名。

## GitHub Actions

[Android CI and release](.github/workflows/android.yml) 在 PR、推送 `main` 和版本标签时执行单元测试、lint 与设备测试代码编译；可手动运行检查。标签发布还会在 API 34 模拟器实际安装正式 APK，检查启动和首页两个链接入口。

发布任务从仓库的 [Actions Secrets](https://github.com/BINGWU2003/TerrariaToolbox/settings/secrets/actions) 读取：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 正式 keystore 文件的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名别名 |
| `ANDROID_KEY_PASSWORD` | 私钥密码 |

代码与工作流提交、推送后，推送 `v1.0.0` 标签触发发布。检查通过后构建正式 APK，校验包名、不可调试状态、签名、标签版本与内置存档原字节，通过正式 APK 启动检查后生成 GitHub Release 并上传 APK 与 `SHA256SUMS.txt`。标签必须与 APK 的 `versionName` 一致；后续发版同时递增 `versionCode`。

测试结果和验证范围见 [验证记录](docs/validation.md)，图标维护见 [品牌说明](docs/branding.md)。
