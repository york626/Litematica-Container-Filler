v1.22.0-Ray：设置箱子追踪库存（选区容器一键写进箱子追踪）

- 移植自二改（zhaixianyu/litematica-printer）的「打印机库存」，按 Ray 的要求改名为 **「设置箱子追踪库存」**，放在 **SCAN 页**（野外材料感知，挨着「箱子外框」那组配置）。
- 用法：用 Litematica **选区**框住容器区域 → 按热键 → 自动把选区里所有容器的内容写进**箱子追踪记忆**——**不弹界面、不闪屏**；之后「箱子外框」搜索就能找到它们，不用一个个手动开箱。
- 实现：
  - 走 CF 已有的**服务端直连通道**（新增只读 `read_contents` 动作 + 内容回包），服务端直接读 `Inventory`（双箱合并成 54 格）；
  - 客户端用箱子追踪 API 写记忆（`MemoryBuilder.create(items).inContainer(block).otherPositions(另一半).build()` → `MemoryBank.addMemory`），持久化；双箱两半都会亮；
  - 每 tick 最多 8 个在途请求，单个 60 tick 超时跳过；actionbar 显示进度，完成给汇总。
- 限制：单人/集成服务端可用（CF 是客户端模组，纯客户端连别人的服会提示不支持）；只扫**已加载区块**；容器类型跟批量工具同一套过滤（`CONTAINER_FILTER_LIST`）；空箱子跳过。
- 判据日志：`[chest-inventory] 开始：N 个容器（选区 M 个盒）`、`完成：写入 X，空 Y，失败 Z（共 N）`。
- 依赖：`libs/chesttracker-2.6.7+1.21.1.jar`（compileOnly，不打包；运行期由【箱子追踪】提供，软依赖）。
