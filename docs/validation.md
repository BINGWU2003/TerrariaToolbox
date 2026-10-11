# 首发验证记录

日期：2026-10-11（Asia/Shanghai）。公开版 **1.0.0 / versionCode 8**，包名 `io.github.bingwu2003.terrariatoolbox`。

## 发布改动

- Kotlin、Java、AIDL、Shizuku 服务与测试引用统一迁移新包名。
- 首页提供 GitHub 项目与哔哩哔哩主页入口，使用系统网页打开方式，失败时保留现有错误提示。
- 正式构建使用独立长期签名，关闭可调试状态；产物命名为 `TerrariaToolbox-v1.0.0.apk`。
- GitHub Actions 对 PR／提交运行检查，对版本标签构建、验证并发布签名 APK 和 SHA-256。
- 当前使用说明合并为一份，删除过期流程、重复开发日志和空验收模板。测试夹具移入 `app/src/test/resources/config.json`。

## 当前验证

- `prepareRelease`、`testDebugUnitTest`、`lintDebug`、`assembleDebugAndroidTest` 与 `connectedDebugAndroidTest` 成功，Gradle 配置缓存正常保存。
- 72 项 JVM 测试、56 项普通 API 37 模拟器测试通过，0 failures／errors。新增首页链接用例检查两次实际 ACTION_VIEW 请求的完整 URL。
- lint：0 errors、24 warnings。另在不读取本机签名配置的环境运行与 CI 相同的测试、lint 和设备测试编译任务，全部成功。
- 正式 APK 实际安装时发现旧分包优化配置导致 Kotlin 集合类 IllegalAccessError；改用完整 R8 优化与标准 Android 优化规则后，API 34 与 API 37 的正式 APK 安装、启动和首页链接显示检查均通过。发布工作流加入正式 APK 的 API 34 启动检查。
- GitHub Actions 工作流通过 actionlint 1.7.12 静态校验；所有 Action 固定到已核实的 commit。四项签名 Secrets 已配置，工作流尚未推送，未实际触发 GitHub 发布。
- 正式 APK 为 5,074,031 字节，包名与版本匹配、不可调试、正式签名校验通过，七份内置存档逐字节一致，未打包 Markdown 文档。

APK：`artifacts/v1.0.0/TerrariaToolbox-v1.0.0.apk`。

SHA-256：`5ff22eb91c88e5f4fddbad671d0bea8de4d1731b992a108bd92696c96f1be32f`。

签名证书 SHA-256：`8ab8bf9a5cd93f125924512942a3b8ee2615dd336ada2de79e8ec382a134ff68`。

签名与资源报告由 `tools/verify_release.py` 生成；交付副本、测试 XML、lint 报告和正式首页截图位于 `artifacts/v1.0.0/`，该本地目录不提交 Git。

## 已有验证依据

内部开发版 1.6 在改名首发前已有 72 项 JVM 测试通过、54 项普通 API 37 模拟器测试通过；最后存档 UI 定向复测 9 项通过，与普通套件合计覆盖 55 个不同设备用例。浅色与深色大字体下实际应用页面检查通过，七份内置资源在 APK 中逐字节一致。上述结果属于原测试包，不代替本轮首发检查。

更早的 1.1 用户实体手机反馈确认中文切换、游戏内中文显示和完整设置恢复正常；未采集具体设备、版本或哈希证据，不能据此声明公开版已完成真机全量验收。

## 验证范围

普通设备测试默认排除 `DedicatedFixture`，只使用隔离夹具、测试文档提供器和应用缓存，不写真实游戏目录。常规 CI 编译设备测试；标签发布在 API 34 模拟器安装并启动正式 APK，检查首页链接入口，不进行真实游戏写入。

当前未完成公开版实体手机 Shizuku 文件访问及两种游戏实际加载七份存档的复测。所属服绑定与来源检查可阻止选错目标，不证明任意发行版本可以读取存档；文件来源声明和 ZIP 清单不提供签名认证。

旧包名的覆盖升级专用测试不能用来证明公开版可以继承旧测试版数据，新包名按独立应用安装。
