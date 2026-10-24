# V2 地图包兼容旧调度拓扑

V2 导出与 RMS `mapping/get` 上传共用 `MapPackageService.exportZip`，包内保留
`map_info.json`、`global_map.pgm`、`global_map.png`、`global_map.yaml`，增加
`global_map.yaml.lxmap`。文件名与包内 YAML 对齐。

旧调度点位类型映射：

| V2 或旧类型 | lxmap 类型 |
| --- | --- |
| path / NORMAL | 0 普通点 |
| workstation / WORK | 1 作业点 |
| charge / CHARGER | 2 充电点 |
| parking / HOME | 3 待命点 |
| 其他类型、空类型 | 0 普通点 |

映射仅作用于兼容文件，`map_info.json` 仍保留 V2 原始类型。
每个路线段生成一条有向 Route；双向路线额外生成反向 Route，同向点对去重。
限速优先使用段限速，其次路线限速、楼层默认限速，最后使用 0.6 m/s。
倒车标记使用段的 `reverse`，点位引用不存在时拒绝导出。

旧格式仅输出段端点拓扑，曲线控制点、区域、货架和楼层间连接等 V2 信息保留在
`map_info.json`，不会转换为旧格式扩展。
