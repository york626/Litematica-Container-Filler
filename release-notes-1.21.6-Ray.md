v1.21.6-Ray：HUD 双状态跳修（同 tick 复用）+ 提前行日志

- 修 HUD 在「原始列表 / 提前列表」间来回跳：同一 tick 内强制复用同一个合并列表（Litematica 每帧会问两次，两次的 filtered 列表可能不同）。
- 新增 `[material-scan] HUD 提前 N 行：…` 日志；下次按日志核对“白桦 ×8374”这类来源/数量。
