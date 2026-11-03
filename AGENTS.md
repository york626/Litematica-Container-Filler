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

## 快捷精妙背包提取（设计要点）

- 触发：`markMissingMaterials(pos, missingTypes)`（所有"缺料判定"的唯一收敛点）→ 按物品发 C2S 请求（40 tick 去重、无货退避 200 tick）。
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
