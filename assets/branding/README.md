# 项目 Logo

2026-10-11 选用 A1 蓝色史莱姆。`terraria-toolbox-logo.png` 是 1536 × 1536 的原始 PNG，保持生成结果原样，供文档和其他品牌展示使用。

图片通过 imgrelay 的 `pqh` 供应商生成，请求模型为 `gpt-image-2.5-flare`；上游未单独报告底层模型。原始批次位于 `output/imagegen/terraria-logo-20261011-141750/`。

Android 资源由 `tools/generate_launcher_icons.py` 导出，需要 Python、Pillow 和 NumPy：

```powershell
python tools/generate_launcher_icons.py --preview output/logo-adoption/launcher-preview.png
```

普通图标和圆形图标包含五档屏幕密度。自适应图标将原图放入 108 dp 图层中央的 72 dp 可见区域，外边界延展供启动器动画使用；单色图层保留史莱姆轮廓与眼睛、嘴的镂空，支持 Android 系统主题图标。
