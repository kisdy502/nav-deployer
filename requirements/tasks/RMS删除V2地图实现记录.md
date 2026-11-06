# RMS 删除 V2 地图实现记录

所属故事: ../stories/实现RMS删除V2地图.md

## 改动清单

- RmsMapService.deleteMap：新增 V2 优先分支——findByUploadName（robot_map_name 优先、map_name 兜底）命中则走 MapMetaService.delete（PUBLISHED 拒删返回 400、子表级联、MinIO v2-maps/ 对象清理）；未命中回落 V1 链路（共存期保留）

## 验证情况

- `mvnw compile` 通过
- 待云端联调冒烟：AC1（删除 V2 图成功且 result-report 成功）、AC2（删 PUBLISHED 基准图被拒）、AC3（列表与 MinIO 清理核对）
