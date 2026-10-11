# 项目 Logo

2026-10-11 选用 A1 蓝色史莱姆。唯一的 Logo 图片为：

`app/src/main/res/drawable-nodpi/terraria_toolbox_logo.png`

这是 1536 × 1536 的原始 PNG，保持生成结果原样。README 与 Android 图标共用这张图片。

## Android 配置

Manifest 的 `android:icon` 和 `android:roundIcon` 都引用 `@mipmap/ic_launcher`。`mipmap-anydpi/ic_launcher.xml` 直接引用原始 PNG，并将其放入自适应图标中央的 72 dp 可见区域；启动器按自己的圆形或其他形状应用遮罩。项目最低支持 Android 14，使用这一份自适应资源即可。

采用完整彩色图片显示，系统主题图标使用启动器的回退行为。

## 更换 Logo

1. 用新的正方形 PNG 替换 `app/src/main/res/drawable-nodpi/terraria_toolbox_logo.png`，保留文件名。
2. 构建 APK：`./gradlew :app:assembleDebug`；Windows 使用 `./gradlew.bat :app:assembleDebug`。

Logo 配置不需要 Python、Pillow、NumPy 或导出命令。图片由 Android 资源系统缩放与打包。

## 生成来源

图片通过 imgrelay 的 `pqh` 供应商生成，请求模型为 `gpt-image-2.5-flare`；上游未单独报告底层模型。原始批次位于 `output/imagegen/terraria-logo-20261011-141750/`。
