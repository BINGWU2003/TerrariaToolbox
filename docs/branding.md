# 项目品牌与图标

应用名称为「泰拉瑞亚工具箱」，APK 使用 `TerrariaToolbox-v<版本>.apk` 命名，Android 包名为 `io.github.bingwu2003.terrariatoolbox`。

蓝色史莱姆 Logo 的唯一图片为 `app/src/main/res/drawable-nodpi/terraria_toolbox_logo.png`，尺寸 1536 × 1536。README 与 Android 图标共用原始 PNG。

Manifest 的 `android:icon` 和 `android:roundIcon` 都引用 `@mipmap/ic_launcher`。`mipmap-anydpi/ic_launcher.xml` 将原始 PNG 放入自适应图标中央的 72 dp 可见区域，启动器按自身形状应用遮罩。图标使用完整彩色图片，系统主题图标采用启动器回退行为。

更换时用正方形 PNG 替换上述图片，保留文件名，然后按 README 配置签名并运行 `:app:prepareRelease`。图片由 Android 资源系统缩放与打包。