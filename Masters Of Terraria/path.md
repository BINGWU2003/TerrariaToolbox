### 国际服泰拉瑞亚（固定路径）

国际服采用固定包名存放数据：

- **人物存档路径（Players）**：
  `/storage/emulated/0/Android/data/com.and.games505.TerrariaPaid/Players/`
- **地图存档路径（Worlds）**：
  `/storage/emulated/0/Android/data/com.and.games505.TerrariaPaid/Worlds/`

---

### 国服泰拉瑞亚（心动网络版，动态 ID）

国服包名为 `com.xd.terraria`，存档路径会根据当前登录的玩家数字账号 ID（如截图中的 `53399`）自动分流为子文件夹：

- **人物存档路径（Players）**：
  `/storage/emulated/0/Android/data/com.xd.terraria/<玩家ID>/Players/`

- _示例（以 ID 53399 为例）_：
  `/storage/emulated/0/Android/data/com.xd.terraria/53399/Players/`

- **地图存档路径（Worlds）**：
  `/storage/emulated/0/Android/data/com.xd.terraria/<玩家ID>/Worlds/`

- _示例（以 ID 53399 为例）_：
  `/storage/emulated/0/Android/data/com.xd.terraria/53399/Worlds/`

---

### 结构对比速览

| 版本       | 包名                            | 路径层级结构                                                          |
| ---------- | ------------------------------- | --------------------------------------------------------------------- |
| **国际服** | `com.and.games505.TerrariaPaid` | `.../Android/data/com.and.games505.TerrariaPaid/{Players 或 Worlds}/` |
| **国服**   | `com.xd.terraria`<br>           | `.../Android/data/com.xd.terraria/<UID>/{Players 或 Worlds}/`<br>     |

> **提示**：国服跨号迁移或备份存档时，只需进入 `com.xd.terraria` 对应数字 ID 文件夹下的 `Players` 与 `Worlds` 进行文件复制即可。安卓高版本如果无法直接访问 `Android/data` 目录，建议借助具备 Shizuku 授权或系统原生 DocumentsUI 权限的文件管理器（如 MT 管理器、ZArchiver 等）进行操作。
