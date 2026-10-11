# Shizuku 验证版开发记录

日期：2026-10-11（Asia/Shanghai）。版本 `1.1 / 2`，包名 `com.example.terrariatoolbox`，`minSdk 34`、`compileSdk/targetSdk 37`。

## 环境和范围

| 环境 | 配置 | 用途 |
| --- | --- | --- |
| API 37 模拟器 | `Medium_Phone_API_37.0`；Android 17；补丁 2026-05-05 | 页面、SAF、文件描述符及错误处理回归 |
| 独立 API 34 模拟器 | `TerrariaToolbox_API_34_Shizuku`；Google sdk_gphone64_x86_64；Android 14；补丁 2023-09-05；Google APIs x86_64 r14 | 相同回归，以及真实 Shizuku 服务的受控夹具验证 |
| Shizuku | 官方 `13.6.0.r1086.2650830c`；API 13；服务 UID 2000 | 真实 shell UserService 和 Binder 文件描述符传输 |
| 客户端依赖 | `dev.rikka.shizuku:api`、`provider` 固定 `13.1.5` | 本次 APK 的实际依赖 |

API 34 模拟器是新建的独立测试环境，未安装 Terraria。先核对游戏包和配置不存在，再通过开发测试连接部署受控配置。Shizuku 按其界面显示的官方启动命令运行原生启动器；工具箱通过正常 Shizuku 授权弹窗获得授权。开发连接用于测试部署，用户使用流程仍是手机无线调试配对和启动。

官方 Shizuku APK 签名 CN 为 Rikka，证书 SHA-256 为 `268b5590e868fb08bae7e0ac413564cd1ff88f5ccff74af9dbd0dc918e30db30`。没有将 Shizuku APK 打包到工具箱中。

## 构建与自动化

| 检查 | 结果 |
| --- | --- |
| Debug APK 构建 | 通过 |
| JVM 单测 | 39 项通过，0 失败 |
| API 34 普通仪器回归 | 19 项通过，0 失败 |
| API 37 普通仪器回归 | 19 项通过，0 失败 |
| API 34 真实 Shizuku 文件及页面流程 | 2 项通过 |
| 真实 v1.0 → v1.1 旧会话及备份兼容检查 | 1 项通过 |
| lint | 0 错误、23 项非阻断警告；主要为依赖更新建议、模板资源和 KTX 建议 |
| Debug 签名一致性 | 新旧均为 Android Debug，证书 SHA-256 `18a59e7e4c1588bead8abee87893660cf90a38f5e197858de411da0b5a7d9e63` |
| APK 权限 | 仅 Shizuku API 和 AndroidX 自定义接收器权限；没有网络、管理所有文件权限 |

JVM 测试保留真实配置只读资源及其内存英文副本，验证严格 JSON、重复键、整数语言、未知字段、精确数字、数组顺序、原始备份和恢复。新增后端路由、旧记录缺失访问方式按 SAF 读取、授权拒绝、10 秒绑定超时及迟到回调、服务死亡、API/UID/主用户检查、同来源恢复及跨后端拒绝、源文件别名导出保护。

仪器测试使用测试 APK 内的普通文档提供方和应用私有文件，验证实际 FD 截断及读写、缺失/目录/符号链接/超限文件拒绝、非 shell 身份拒绝、约 2 MiB FD 传输、同步失败、关闭失败、回读失败与恢复。失败均保留原始备份及未完成记录。页面覆盖取消选择、普通同名文件只读、游戏退出确认、中文配置不重复写入和连接断开后禁止写入。

需要真实目标路径或历史数据的三项测试使用 `DedicatedFixture` 注解，默认普通设备回归通过 `notAnnotation` 排除；手动启动时还要求显式夹具参数。早期 UTP XML 把 `AssumptionViolatedException` 记为 failure，而 Gradle 正确将其视为跳过。保留早期原始报告供排查，最终报告使用注解排除这些用例，实际服务验证单独运行并记录，未将跳过计为通过。

## 真实 Shizuku 夹具

受控夹具明确包含 `FixtureMarker: controlled-shizuku-fixture`、`Language: 1`、大整数和约 2 MiB 字符串。原始大小 **2,097,262 字节**，SHA-256：

```text
205712f173aa5985e1e1fd7781ff54b5efdbfd6a3d1e65f2390ceadb1af50f86
```

固定目标缺失时，真实服务返回“先运行游戏生成配置”，未创建文件。部署夹具后，真实服务验证固定路径、UID 2000、FD 读取、原始备份、英文切换、候选回读、中文不重复写入、完整原始字节恢复、错误预期哈希拒绝和未完成记录重新检查。页面测试通过主连接按钮、来源确认、游戏退出确认、切换和恢复。恢复后夹具哈希与原始一致。

相关测试显式命令（仅限上述专用夹具设备，先正常授权 Shizuku）：

```powershell
adb -s emulator-5556 shell am instrument -w -e shizukuFixture true -e class 'com.example.terrariatoolbox.ShizukuIntegrationTest,com.example.terrariatoolbox.ShizukuUiIntegrationTest' com.example.terrariatoolbox.test/androidx.test.runner.AndroidJUnitRunner
```

禁止将这条命令用于有真实游戏数据的手机。默认回归没有远程写入游戏路径的能力；生产服务只有固定路径接口，没有夹具覆盖参数。

## 覆盖安装和历史数据

在独立模拟器使用原第一阶段 APK，通过原页面生成 SAF 原始备份并导出；随后直接覆盖安装 1.1。旧 `source.json` 和备份元数据没有 `accessMethod` 字段。新版实际加载为 SAF，读取原始字节并通过系统文档接口导出，导出内容一致。

升级前后对内部备份目录的 6 个文件（3 组数据/元数据，其中 1 组由旧版生成）逐一 SHA-256 比较，全部相同。未使用卸载或清除数据完成升级。Gradle 的普通仪器测试会在结束后卸载测试目标，这是测试工具的清理行为，与覆盖安装检查分开记录。

## 服务断开和进程重启

在真实 Shizuku 夹具测试之后强制结束工具箱进程再启动，应用重新连接服务、读取固定配置，未重复写入。停止模拟器上的真实 Shizuku server 后，页面显示服务断开，“验证并切换中文”的可点击父节点为 `enabled=false`；重新启动服务和工具箱后恢复连接，UID 仍为 2000，夹具哈希不变。

为验证启动恢复提示，在这个专用夹具环境中注入一份合法的未完成记录（预期为中文哈希，目标仍是原始英文），然后结束并重启应用。页面检测到未完成写入并提示“暂勿启动游戏”；点击重新校验显示预期不一致，记录仍保留。选择对应英文原始备份，确认游戏已退出后完成恢复，原始哈希一致，未完成记录被安全标记为完成。此项是可重复的中断状态注入，并非对真实游戏文件做断电测试。

原始页面 XML、注入记录和恢复结果在 `test-results/` 中保留。手机或模拟器整机重启后的行为未在此项中测试，真机模板仍标为未测。

## 交付及证据

- APK：`artifacts/shizuku-validation/TerrariaToolbox-shizuku-validation-debug.apk`
- APK SHA-256：`artifacts/shizuku-validation/SHA256SUMS.txt`
- API 34/37 原始回归 XML、构建/单测/lint 日志、真实服务及升级日志：`artifacts/shizuku-validation/test-results/`
- 页面截图：`artifacts/shizuku-validation/validation-preview.png`
- 来源与恢复页面：`artifacts/shizuku-validation/source-preview.png`
- 操作说明：[shizuku-validation.md](shizuku-validation.md)
- 真机记录：[shizuku-device-validation-template.md](shizuku-device-validation-template.md)

原 `docs/config.json` 未修改，SHA-256 仍为 `B3AFC39F85016ECEB011D95494617132FC96A13CB36C47F1CE60F97C10CE2625`。原第一阶段 APK 未修改，SHA-256 仍为 `E77E1FF8790AD4389F0A56967C122273956C944C2AAB3DCF4E0B3AF887F1EC77`。第一阶段说明和测试记录保持历史内容。

## 待真机验收

尚无 Android 14 真机或游戏生效结果。模拟器的旧安全补丁、受控文件权限及无游戏进程环境不能代表用户手机。

仍须验证：手机实际配置访问、英文原配置切换及中文显示、游戏完整恢复、应用重启、手机重启后重新启动 Shizuku 和授权复用。只有对应设备、系统补丁、游戏及 Shizuku 版本的完整验收通过，才声明支持直接切换；访问失败时记录限制，并使用已能访问目录的工具手动恢复或进入后续导入导出流程。

## 后续用户反馈（2026-10-11）

上述“待真机验收”描述交付时的状态。用户随后确认本机调试、中文切换、游戏内中文显示和完整恢复正常，结果已另存为 [用户实测记录](shizuku-user-validation-2026-10-11.md)。设备环境和重启检查未单独提供；保留此前开发验证的原始范围和证据。
