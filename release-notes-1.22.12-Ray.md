v1.22.12-Ray：离线材料列表（远处打开材料列表不再等区块加载）

- 现象：远离投影时打开 GUI 材料列表，会等投影所在区块加载（野外卡顿 / 列表迟迟出不来）。
- 根因：Litematica 的 `TaskCountBlocksPlacement` 每处理一个区块前都检查 **`clientWorld`** 周围区块是否已加载
  （只检查、不主动加载）；远处任务一直等。客户端区块只会在**真实玩家**附近加载 ⇒
  「在投影处挂假人」也没用（假人只影响服务端，客户端收不到那里的区块）。
- 修法：新增「**离线材料列表**」开关（默认开，「过滤」配置页）：
  - 打开材料列表时，如果放置区域客户端区块没加载 ⇒ **不调度统计任务**，直接用投影文件数据构建列表；
  - **总数**从 `LitematicaBlockStateContainer` 数出来，口径和原版 `MaterialListUtils.getMaterialList` 一致
    （含水方块算水桶、多物品状态展开）；
  - **missing / mismatch** 用最近一次在附近正常统计时的缓存
    （`config/litematica_container_filler/offline_material_cache.json`，正常统计完成时自动写）；
    没缓存的行按 total 显示（日志提示）；
  - available 按原版 `getInventoryItemCounts` 实时算。
- 效果：任何地方打开材料列表都立即出数、**不触发区块加载**；去投影附近打开一次即可建立/更新缓存。
- 判据日志：`[material-list] 离线材料列表：N 种（缓存覆盖=是/否，放置 …）`
  ／`该放置没有缓存 ⇒ missing 按 total 显示`。
