# 游戏存档与内置资源路径

工具箱通过 Shizuku 访问手机主用户下的以下目录，系统文件选择器用于手机文件导入和导出保存。

| 所属服 | 游戏包名 | 存档根目录 |
| --- | --- | --- |
| 国际服 | `com.and.games505.TerrariaPaid` | `/storage/emulated/0/Android/data/com.and.games505.TerrariaPaid/` |
| 心动国服 | `com.xd.terraria` | `/storage/emulated/0/Android/data/com.xd.terraria/<数字账号ID>/` |

根目录下的 `Players/` 存放 `.plr` 角色，`Worlds/` 存放 `.wld` 世界。国服需先登录账号并完全退出游戏，工具箱再读取实际数字账号目录；单账号自动选择，多账号手动选择。

国际服语言配置固定为 `/storage/emulated/0/Android/data/com.and.games505.TerrariaPaid/config.json`，国服不使用语言工具。

本目录作为应用内置存档资源目录，映射如下：

| 资源子目录 | 所属服与类型 | 当前数量 |
| --- | --- | --- |
| `players/` | 国际服角色 | 4 |
| `worlds/` | 国际服世界 | 2 |
| `xd-players/` | 国服角色（当前未提供） | 0 |
| `xd-worlds/` | 国服世界 | 1 |

资源绑定同服目标，保持文件原字节；仅复制主存档，不转换格式，不包含地图探索及自动备份。本说明文件被构建配置排除出 APK。