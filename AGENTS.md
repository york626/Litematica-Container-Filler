# AGENTS.md —— filler-私人版（投影容器填充机，我方 fork）

> 上游：`MimicEnzymes/Litematica-Container-Filler`（作者 MimicEnzymes，LGPL-3.0）
> 我方仓库：`york626/Litematica-Container-Filler`（fork）
> **只维护 `1.21.1` 分支** —— 上游默认分支是 `1.21.10`，别动、别合并。

## 硬约定

1. **只改 `1.21.1` 分支**；上游其它分支一律不管。
2. 本地改动的远程是 `origin`（york626）；`upstream` 指向原作者，只用来参考/取补丁。
3. **构建必须能离线跑**（Ray 的机器访问 maven.fabricmc.net 常超时）：
   - `settings.gradle` 里把 `fabric-loom` 按**模块坐标**解析（缓存里没有插件标记）；
   - 工具链版本对齐本机缓存：Gradle 9.5.1 / Loom 1.17.21 / yarn `1.21.1+build.3` / loader `0.19.3` / fabric-api `0.116.8`；
   - 命令：`./gradlew.bat build --offline`（首次如缺依赖，去掉 `--offline` 并用 aliyun 镜像补一次）。
4. `libs/` 里放的是**编译期依赖**（Litematica / MaLiLib / ModMenu / QuickShulker / 精妙背包+核心+energy+transfer），
   运行期由玩家 `mods\` 提供，**不要打包进 jar**。
5. 部署走主项目的 `versions\tools\deploy_mod.py`（只看目标环境自己的日志锁），部署名以 `【投影容器填充机】…` 开头，
   旧 jar 改名 `.old` 保留、**不得删除**。

## 我方相对上游的改动（截至 2026-11-03）

| 改动 | 说明 |
|---|---|
| 构建工具链对齐本机缓存 | 见上（commit `938b4dd`） |
| **快捷精妙背包提取**（`enableSbpExtraction`，默认开） | 缺料时从精妙背包取货并自动重提交任务，用户只按一次填充键。实现：`compat/sbp/*`；接线在 `AutoFillerStateMachine` 的 `addTask` / `addManualTask` / `markMissingMaterials`（commit `562848f`） |
| **材料列表计入背包内潜影盒**（`enableSbpShulkerCount`，默认开） | `mixin/SbpMaterialListUtilsMixin`（反射读精妙存储，展开背包内潜影盒/收纳袋内容） |
| 标题栏显示版本号 | `Reference.MOD_VERSION` + `GuiConfigs` 标题拼接 |
| 0.10.5-Ray 修启动崩溃 | ⚠️ **mixins.json 里的条目必须相对 `package`**：写成 `compat.sbp.mixin.X` 会被再拼一次包前缀 ⇒ ClassNotFound ⇒ PREPARE 失败（连带把 carpet-ams 的 preLaunch 拖崩）。类放 `…litematicafiller.mixin` 包、json 只写类名 |

## 版本状态（Ray 实测）

| 版本 | 内容 | 状态 |
|---|---|---|
| **1.12.3-Ray** | 填满补两处边界：①投影里"本来就是空的容器"按空模板清空（不再当无数据跳过）②槽位能不能放物品改问真实方块实体的 `Inventory.isValid`（熔炉输出/燃料槽这类限制不再漏判） | ⏳ 待 Ray 实测 |
| **1.12.2-Ray** | 批量工具补「**填满容器**」：按每格自己的投影模板（多退少补，能精确到不满一组）；点击计划按每 tick 64 个包分批发；缺料 / 投影里没数据正常跳过 | ⏳ 待 Ray 实测 |
| **1.12.1-Ray** | **「批量工具」独立页 + 数据通道**（不建界面/不挂菜单）：准星单容器 / 投影选区队列，清空容器（丢出/进背包/进背包+丢出）；远距离开箱+远距离点击（服务端只放行容器）；`批量工具触发/取消` 两个热键 | ⏳ 待 Ray 实测 |
| 1.12.0-Ray | 回退到 1.8.6 干净基线（1.9~1.11 批量实验全部作废），批量另立「批量工具」页重做 | 基线 |
| **1.8.5-Ray** | 补给泵：取货量按「排队容器需求合计 − 现有散装」；被去重挡下的需求排队补发；判「材料耗尽」前先看补给是否在途；容器已满足 / 玩家开着界面时停止重提交 | ✅ Ray 2026-11-05「测试好像没啥问题，正常」 |
| 1.8.2 → 1.8.4 | 搬盒后立刻判耗尽 → 填一次就报缺材料 → 自动重提交改补给泵（1.8.5 收尾） | 中间态，**别再回退到这几版** |
| 1.5.0 / 1.5.1 | 盒装材料「整盒搬出」+ 增强潜影盒大容量 | ✅ 实测通过 |

## 快捷精妙背包提取（设计要点）

- 触发：`markMissingMaterials(pos, missingTypes)`（所有"缺料判定"的唯一收敛点）→ 按物品发 C2S 请求（10 tick 去重、无货退避 200 tick）。
- 取货量：**所有排队容器对该物品的需求合计 − 背包里现有散装**（工具类最大堆叠=1，按堆叠算会一次只搬 1 个）。被「在途 / 去重 / 无货」挡下的需求压进 `demand` 队列、冷却后自动补发（**不丢单**）。
- 自动重提交（补给泵）：`pendingTasks` 保留 30s、每 500ms 最多提交一次；**提交前先问 `stillNeedsFill()`**（已满足就停手），玩家开着任意界面时也让路。
- 服务端：`SbpServerExtract` 用精妙背包 API 抽货（先模拟后真取；背包满先回腾位置；绝不掉地）。
- 回包成功：若该容器缺的物品都已进玩家背包 → `clearFailedContainer(pos)` + 重新 `addManualTask`/`addTask`
  （填充机第一次判缺料时会把容器记进 `failedContainers`，不清理就会被 `addTask` 开头直接拒绝）。
- 软依赖：没装精妙背包时整条链路不注册（`SbpSupport.available()`）。

## 已知边界

- 只认**身上佩戴/手持**的精妙背包；不管跨维度/远程背包、箱子。
- 「容器工具」模式（`ContainerToolStateMachine`）暂未接。
- 取货是"一个满堆叠"，多出来的留在玩家背包。

## 上游同步

```powershell
git fetch upstream
git log --oneline upstream/1.21.1 -5    # 看上游 1.21.1 有没有新提交
git cherry-pick <sha>                   # 或按需手工挑
```
