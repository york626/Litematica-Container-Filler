v1.23.0-Ray：P2 资源簇地图点（Xaero 第三方 waypoint）

- 新功能「**资源簇地图点**」（SCAN 页，默认开，跟随「野外材料感知」总开关）：
  把附近发现的**缺料原料簇**标到 Xaero 世界地图/小地图——红色点，名字 `原料×数量`（如 `白桦原木×172`）。
- 发现：沿用 32 格扫描、走过累积；按 16³ section 记同种原料数量，**相邻 section（6 邻接）同种合并**，
  合计 **≥64** 算一簇；点位置取“数量最多的那个 section 里第一个命中方块”。
- 更新：再次扫到同一片时重算——数量变 → 改名字；**<64（采空）→ 删点**；同位置多资源各自一个点。
- 上限：每维度最多 **256** 个点（按离玩家由近到远取）。
- 持久化：`config/litematica_container_filler/material_clusters.json`，**按维度**存 section 计数；
  进世界/切维度把当前维度的点重加给 Xaero，退出/切换时保存。
- 软依赖：Xaero 未装静默跳过；反射调 `ThirdPartyWaypointManager`（不打包 Xaero 代码），
  origin = `litematica_container_filler:material_clusters`。
- 判据日志：`[material-scan] 资源簇：N 个点（维度 X，候选 M 簇）`；失败有 warn。
- 已知边界：只统计当前清单缺料的原料；`exposedOnly` 开着时埋住的方块不计入（可能数不到 64）；
  不受 Litematica 选区过滤（地图点看的是探索范围）。
