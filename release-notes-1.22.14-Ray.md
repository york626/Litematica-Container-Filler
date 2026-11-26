v1.22.14-Ray：移除“设置箱子追踪库存”

- 删除 SCAN 页的“设置箱子追踪库存”热键和配置入口。
- 删除 `ChestTrackerInventoryScanner`、`ChestTrackerMemoryWriter`。
- 删除批量直连通道里的 `ACTION_READ_CONTENTS`、`batch_contents` 和服务端只读读取分支。
- 功能迁到投影打印机：
  - “缓存投影选区容器”负责逐箱远程打开并写入箱子追踪记忆。
  - “打印机库存”负责单独保存打印机自有缓存，供打印缺料取物。
- 填充机现有的野外材料感知、箱子外框、批量工具和精妙背包取料不受影响。
