<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Position, Promotion, VideoPlay } from '@element-plus/icons-vue'
import { DEFAULT_MAX_SPEED, useEditorStore } from '@/stores/editor'
import { useRobotStore } from '@/stores/robot'
import { useTasksStore } from '@/stores/tasks'
import { fmtTime, PATH_STATUS_TAG } from '@/utils/format'
import { degToRad, radToDeg } from '@/utils/coords'
import type { PointType } from '@/types/api'

const emit = defineEmits<{
  (e: 'locate', xy: { x: number; y: number }): void
}>()

const editor = useEditorStore()
const robot = useRobotStore()
const tasks = useTasksStore()

// ---------- 点位表单 ----------
const pointForm = reactive({
  point_code: '',
  point_type: 'NORMAL' as PointType,
  x: 0,
  y: 0,
  yawDeg: 0,
  remark: '',
})
const pointSaving = ref(false)

watch(
  () => editor.selectedPoint,
  (p) => {
    if (!p) return
    pointForm.point_code = p.point_code
    pointForm.point_type = p.point_type
    pointForm.x = p.x
    pointForm.y = p.y
    pointForm.yawDeg = radToDeg(p.yaw)
    pointForm.remark = p.remark ?? ''
  },
  { immediate: true },
)

async function savePoint() {
  const p = editor.selectedPoint
  if (!p) return
  pointSaving.value = true
  try {
    await editor.updatePoint(p.id, {
      point_code: pointForm.point_code.trim(),
      point_type: pointForm.point_type,
      x: pointForm.x,
      y: pointForm.y,
      yaw: degToRad(pointForm.yawDeg),
      remark: pointForm.remark.trim() || undefined,
    })
    ElMessage.success('点位已保存')
  } catch {
    /* http 层已提示 */
  } finally {
    pointSaving.value = false
  }
}

async function deletePoint() {
  const p = editor.selectedPoint
  if (!p) return
  await ElMessageBox.confirm(`确认删除点位「${p.point_code}」？`, '删除点位', { type: 'warning' })
  await editor.deletePoint(p.id)
}

// ---------- 路线编辑 ----------
const edgeForm = reactive({ max_speed: DEFAULT_MAX_SPEED, back_up: false, reverse: false })

watch(
  () => editor.selectedEdgeIdx,
  (idx) => {
    const e = idx == null ? null : editor.pathEdit?.edges[idx]
    if (!e) return
    edgeForm.max_speed = e.max_speed ?? DEFAULT_MAX_SPEED
    edgeForm.back_up = e.back_up
    edgeForm.reverse = e.reverse
  },
)

watch(edgeForm, () => {
  const idx = editor.selectedEdgeIdx
  const e = idx == null ? null : editor.pathEdit?.edges[idx]
  if (!e) return
  e.max_speed = edgeForm.max_speed
  e.back_up = edgeForm.back_up
  e.reverse = edgeForm.reverse
})

const pathSaving = ref(false)

async function savePathEdges() {
  pathSaving.value = true
  try {
    await editor.savePathEdit()
    ElMessage.success('路线图形已保存（状态已重置为草稿）')
  } catch (err) {
    if (err instanceof Error && err.message) ElMessage.warning(err.message)
  } finally {
    pathSaving.value = false
  }
}

async function deployFromPanel() {
  const p = editor.selectedPath
  if (!p) return
  await editor.deployPath(p.id)
  ElMessage.success('路线已部署')
}

async function followFromPanel() {
  const p = editor.selectedPath
  if (!p) return
  if (p.status !== 'DEPLOYED') {
    ElMessage.warning('只有已部署（DEPLOYED）的路线才能循线')
    return
  }
  if (tasks.moveTaskInFlight) {
    ElMessage.warning('已有执行中的移动任务，请先取消')
    return
  }
  await tasks.followPath(p.id)
}

const pointCodeOf = (id: number) => editor.pointsById.get(id)?.point_code ?? `#${id}`
</script>

<template>
  <div class="prop">
    <el-scrollbar class="prop-scroll">
      <!-- 路线图形编辑中 -->
      <template v-if="editor.pathEdit">
        <div class="prop-section">
          <div class="prop-title">
            编辑路线：{{ editor.editingPath?.path_code }}
            <el-tag v-if="editor.editingPath" size="small" :type="PATH_STATUS_TAG[editor.editingPath.status].type">
              {{ PATH_STATUS_TAG[editor.editingPath.status].label }}
            </el-tag>
          </div>
          <div class="prop-tip">
            依次点击地图上的点位连接路线；拖动橙色控制点调整曲线。保存后状态重置为草稿，需重新部署。
          </div>
          <div class="edit-actions">
            <el-button type="primary" :loading="pathSaving" @click="savePathEdges">保存图形</el-button>
            <el-button @click="editor.cancelPathEdit()">取消</el-button>
            <el-button text type="danger" @click="editor.removeLastEdge()">撤销上一条边</el-button>
            <el-button text type="danger" @click="editor.clearEdgesDraft()">清空</el-button>
          </div>

          <div class="edge-list">
            <div
              v-for="(e, i) in editor.pathEdit.edges"
              :key="i"
              class="edge-item"
              :class="{ selected: editor.selectedEdgeIdx === i }"
              @click="editor.selectedEdgeIdx = i"
            >
              <span class="edge-seq">{{ i + 1 }}</span>
              <span class="edge-name">{{ pointCodeOf(e.source_point_id) }} → {{ pointCodeOf(e.target_point_id) }}</span>
              <el-tag size="small" :type="e.edge_type === 'CURVE' ? 'warning' : 'info'" effect="plain">
                {{ e.edge_type === 'CURVE' ? '曲线' : '直线' }}
              </el-tag>
            </div>
            <el-empty v-if="editor.pathEdit.edges.length === 0" description="点击地图上的点位开始绘制" :image-size="48" />
          </div>

          <!-- 选中边属性 -->
          <template v-if="editor.selectedEdgeIdx != null && editor.pathEdit.edges[editor.selectedEdgeIdx]">
            <el-divider />
            <div class="prop-sub">边属性（第 {{ editor.selectedEdgeIdx! + 1 }} 条）</div>
            <el-form label-width="80px" size="small" @submit.prevent>
              <el-form-item label="类型">
                <el-button-group>
                  <el-button size="small" @click="editor.edgeToggleType(editor.selectedEdgeIdx!)">切换直线/曲线</el-button>
                  <el-button size="small" :disabled="editor.pathEdit!.edges[editor.selectedEdgeIdx!].edge_type !== 'CURVE' || editor.pathEdit!.edges[editor.selectedEdgeIdx!].control_points.length >= 2" @click="editor.edgeAddControlPoint(editor.selectedEdgeIdx!)">
                    加控制点
                  </el-button>
                  <el-button
                    size="small"
                    :disabled="editor.pathEdit!.edges[editor.selectedEdgeIdx!].edge_type !== 'CURVE'"
                    @click="editor.edgeRemoveControlPoint(editor.selectedEdgeIdx!, editor.pathEdit!.edges[editor.selectedEdgeIdx!].control_points.length - 1)"
                  >
                    删控制点
                  </el-button>
                </el-button-group>
              </el-form-item>
              <el-form-item label="限速(m/s)">
                <el-slider v-model="edgeForm.max_speed" :min="0.05" :max="2" :step="0.05" show-input :show-input-controls="false" />
              </el-form-item>
              <el-form-item label="倒退行驶">
                <el-switch v-model="edgeForm.back_up" />
              </el-form-item>
              <el-form-item label="反向">
                <el-switch v-model="edgeForm.reverse" />
              </el-form-item>
            </el-form>
          </template>
        </div>
      </template>

      <!-- 选中点位 -->
      <template v-else-if="editor.selectedPoint">
        <div class="prop-section">
          <div class="prop-title">点位属性</div>
          <el-form label-width="80px" size="small" @submit.prevent>
            <el-form-item label="编码">
              <el-input v-model="pointForm.point_code" maxlength="64" />
            </el-form-item>
            <el-form-item label="类型">
              <el-radio-group v-model="pointForm.point_type">
                <el-radio-button value="NORMAL">普通</el-radio-button>
                <el-radio-button value="CHARGER">充电</el-radio-button>
                <el-radio-button value="HOME">待命</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="X (m)">
              <el-input-number v-model="pointForm.x" :step="0.05" style="width: 100%" />
            </el-form-item>
            <el-form-item label="Y (m)">
              <el-input-number v-model="pointForm.y" :step="0.05" style="width: 100%" />
            </el-form-item>
            <el-form-item label="朝向(°)">
              <el-input-number v-model="pointForm.yawDeg" :min="-180" :max="180" :step="5" style="width: 100%" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="pointForm.remark" maxlength="255" />
            </el-form-item>
            <el-form-item label="ID/时间">
              <span class="prop-muted">#{{ editor.selectedPoint.id }} · {{ fmtTime(editor.selectedPoint.updated_at) }}</span>
            </el-form-item>
          </el-form>
          <div class="edit-actions">
            <el-button type="primary" :loading="pointSaving" @click="savePoint">保存</el-button>
            <el-button :icon="Position" @click="emit('locate', { x: editor.selectedPoint!.x, y: editor.selectedPoint!.y })">定位</el-button>
            <el-button
              type="primary"
              plain
              :icon="Promotion"
              :disabled="tasks.moveTaskInFlight || !robot.status?.pose_initialized"
              @click="tasks.navigateToPoint(editor.selectedPoint!.id)"
            >导航</el-button>
            <el-button type="danger" plain :icon="Delete" @click="deletePoint">删除</el-button>
          </div>
        </div>
      </template>

      <!-- 选中路线 -->
      <template v-else-if="editor.selectedPath">
        <div class="prop-section">
          <div class="prop-title">路线信息</div>
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="编码">{{ editor.selectedPath.path_code }}</el-descriptions-item>
            <el-descriptions-item label="名称">{{ editor.selectedPath.path_name || '-' }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag size="small" :type="PATH_STATUS_TAG[editor.selectedPath.status].type">
                {{ PATH_STATUS_TAG[editor.selectedPath.status].label }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="边数">{{ editor.pathDetails[editor.selectedPath.id]?.length ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="更新时间">{{ fmtTime(editor.selectedPath.updated_at) }}</el-descriptions-item>
          </el-descriptions>
          <div class="edit-actions">
            <el-button type="primary" @click="editor.startPathEdit(editor.selectedPath!.id)">编辑图形</el-button>
            <el-button v-if="editor.selectedPath.status !== 'DEPLOYED'" type="success" :icon="VideoPlay" @click="deployFromPanel">部署</el-button>
            <el-button type="primary" plain @click="followFromPanel">循线</el-button>
          </div>
        </div>
      </template>

      <!-- 地图信息 + 图层 -->
      <template v-else>
        <div class="prop-section">
          <div class="prop-title">地图信息</div>
          <el-descriptions v-if="editor.mapInfo" :column="1" size="small" border>
            <el-descriptions-item label="名称">{{ editor.mapInfo.map_name }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ editor.mapInfo.status }}</el-descriptions-item>
            <el-descriptions-item label="分辨率">{{ editor.mapInfo.resolution }} m/格</el-descriptions-item>
            <el-descriptions-item label="尺寸">{{ editor.mapInfo.width }} × {{ editor.mapInfo.height }}</el-descriptions-item>
            <el-descriptions-item label="原点">({{ editor.mapInfo.origin_x }}, {{ editor.mapInfo.origin_y }}) 朝向 {{ editor.mapInfo.origin_yaw }} rad</el-descriptions-item>
            <el-descriptions-item label="机器人地图">{{ editor.mapInfo.robot_map_name || '-' }}</el-descriptions-item>
          </el-descriptions>

          <el-divider />
          <div class="prop-title">图层</div>
          <div class="layer-list">
            <el-checkbox v-model="editor.layers.map" label="地图" />
            <el-checkbox v-model="editor.layers.points" label="点位" />
            <el-checkbox v-model="editor.layers.paths" label="路线" />
            <el-checkbox v-model="editor.layers.robot" label="机器人" />
            <el-checkbox v-model="editor.layers.trail" label="轨迹" />
            <el-checkbox v-model="editor.layers.scan" label="雷达点云" />
          </div>

          <el-divider />
          <div class="prop-title">操作</div>
          <div class="edit-actions">
            <el-button @click="robot.clearTrail()">清空轨迹</el-button>
          </div>
        </div>
      </template>

      <div class="prop-spacer"></div>
    </el-scrollbar>
  </div>
</template>

<style scoped>
.prop {
  height: 100%;
}

.prop-scroll {
  height: 100%;
}

.prop-section {
  padding: 12px;
}

.prop-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.prop-sub {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 8px;
}

.prop-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  margin-bottom: 10px;
}

.prop-muted {
  font-size: 12px;
  color: #909399;
}

.prop-spacer {
  height: 24px;
}

.edit-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.edge-list {
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  max-height: 220px;
  overflow: auto;
}

.edge-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  cursor: pointer;
  font-size: 12.5px;
}

.edge-item:hover {
  background: #f5f7fa;
}

.edge-item.selected {
  background: #ecf5ff;
}

.edge-seq {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #409eff;
  color: #fff;
  font-size: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.edge-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.layer-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
</style>
