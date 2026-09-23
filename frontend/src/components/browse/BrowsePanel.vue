<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { Delete, Position, Promotion, VideoPlay } from '@element-plus/icons-vue'
import { useEditorStore } from '@/stores/editor'
import { useRobotStore } from '@/stores/robot'
import { useTasksStore } from '@/stores/tasks'
import { fmtTime, PATH_STATUS_TAG, POINT_TYPE_LABEL } from '@/utils/format'
import { radToDeg } from '@/utils/coords'

const emit = defineEmits<{
  (e: 'locate-point', xy: { x: number; y: number }): void
  (e: 'locate-bounds', pts: { x: number; y: number }[]): void
}>()

const editor = useEditorStore()
const robot = useRobotStore()
const tasks = useTasksStore()

const colorOf = (t: string): string => (t === 'CHARGER' ? '#e6a23c' : '#95d475')

async function navigateToPoint() {
  const p = editor.selectedPoint
  if (!p) return
  if (!robot.status?.pose_initialized) return ElMessage.warning('机器人位姿未初始化，无法导航')
  if (tasks.moveTaskInFlight) return ElMessage.warning('已有执行中的移动任务，请先取消')
  await tasks.navigateToPoint(p.id)
}

async function followPath() {
  const p = editor.selectedPath
  if (!p) return
  if (p.status !== 'DEPLOYED') return ElMessage.warning('只有已部署（DEPLOYED）的路线才能循线')
  if (!robot.status?.pose_initialized) return ElMessage.warning('机器人位姿未初始化，无法循线')
  if (tasks.moveTaskInFlight) return ElMessage.warning('已有执行中的移动任务，请先取消')
  await tasks.followPath(p.id)
}

function locatePath() {
  const detail = editor.selectedPath ? editor.pathDetails[editor.selectedPath.id] : null
  if (!detail) return
  const pts: { x: number; y: number }[] = []
  for (const e of detail) {
    const s = editor.pointsById.get(e.source_point_id)
    const t = editor.pointsById.get(e.target_point_id)
    if (s) pts.push({ x: s.x, y: s.y })
    if (t) pts.push({ x: t.x, y: t.y })
  }
  if (pts.length) emit('locate-bounds', pts)
}
</script>

<template>
  <div class="browse-panel">
    <el-scrollbar class="panel-scroll">
      <!-- 选中点位 -->
      <template v-if="editor.selectedPoint">
        <div class="sec-title">点位信息</div>
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="编码">
            <span class="dot" :style="{ background: colorOf(editor.selectedPoint.point_type) }"></span>
            {{ editor.selectedPoint.point_code }}
          </el-descriptions-item>
          <el-descriptions-item label="类型">{{ POINT_TYPE_LABEL[editor.selectedPoint.point_type] ?? editor.selectedPoint.point_type }}</el-descriptions-item>
          <el-descriptions-item label="坐标">({{ editor.selectedPoint.x.toFixed(2) }}, {{ editor.selectedPoint.y.toFixed(2) }}) m</el-descriptions-item>
          <el-descriptions-item label="朝向">{{ radToDeg(editor.selectedPoint.yaw) }}°</el-descriptions-item>
          <el-descriptions-item v-if="editor.selectedPoint.remark" label="备注">{{ editor.selectedPoint.remark }}</el-descriptions-item>
        </el-descriptions>
        <div class="btns">
          <el-button type="primary" :icon="Promotion" :disabled="tasks.moveTaskInFlight || !robot.status?.pose_initialized" @click="navigateToPoint">移动到点</el-button>
          <el-button :icon="Position" @click="emit('locate-point', { x: editor.selectedPoint!.x, y: editor.selectedPoint!.y })">定位</el-button>
        </div>
      </template>

      <!-- 选中路线 -->
      <template v-else-if="editor.selectedPath">
        <div class="sec-title">路线信息</div>
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="编码">{{ editor.selectedPath.path_code }}</el-descriptions-item>
          <el-descriptions-item label="名称">{{ editor.selectedPath.path_name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="PATH_STATUS_TAG[editor.selectedPath.status].type">{{ PATH_STATUS_TAG[editor.selectedPath.status].label }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="边数">{{ editor.pathDetails[editor.selectedPath.id]?.length ?? '-' }}</el-descriptions-item>
        </el-descriptions>
        <div class="btns">
          <el-button type="primary" :icon="VideoPlay" :disabled="editor.selectedPath.status !== 'DEPLOYED'" @click="followPath">循线</el-button>
          <el-button :icon="Position" @click="locatePath">定位</el-button>
        </div>
      </template>

      <!-- 地图信息 -->
      <template v-else>
        <div class="sec-title">地图信息</div>
        <el-descriptions v-if="editor.mapInfo" :column="1" size="small" border>
          <el-descriptions-item label="名称">{{ editor.mapInfo.map_name }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ editor.mapInfo.status === 'ACTIVE' ? '部署中' : editor.mapInfo.status }}</el-descriptions-item>
          <el-descriptions-item label="分辨率">{{ editor.mapInfo.resolution }} m/格</el-descriptions-item>
          <el-descriptions-item label="尺寸">{{ editor.mapInfo.width }} × {{ editor.mapInfo.height }}</el-descriptions-item>
          <el-descriptions-item v-if="editor.mapInfo.robot_map_name" label="机器人地图">{{ editor.mapInfo.robot_map_name }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ fmtTime(editor.mapInfo.updated_at) }}</el-descriptions-item>
        </el-descriptions>
        <el-empty v-else description="尚未加载地图" :image-size="60" />

        <el-divider />
        <div class="sec-title">图层</div>
        <div class="layers">
          <el-checkbox v-model="editor.layers.map" label="地图" />
          <el-checkbox v-model="editor.layers.points" label="点位" />
          <el-checkbox v-model="editor.layers.paths" label="路线" />
          <el-checkbox v-model="editor.layers.robot" label="机器人" />
          <el-checkbox v-model="editor.layers.trail" label="轨迹" />
        </div>
        <div class="btns">
          <el-button :icon="Delete" @click="robot.clearTrail()">清空轨迹</el-button>
        </div>
        <div class="tip">提示：点击地图上的点位或路线可在此查看详情并下发任务。</div>
      </template>
    </el-scrollbar>
  </div>
</template>

<style scoped>
.browse-panel {
  height: 100%;
}

.panel-scroll {
  height: 100%;
}

.panel-scroll :deep(.el-scrollbar__wrap) {
  padding: 12px;
}

.sec-title {
  font-weight: 600;
  font-size: 14px;
  margin: 12px 0 10px;
}

.sec-title:first-child {
  margin-top: 0;
}

.dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-right: 4px;
  vertical-align: middle;
}

.btns {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}

.layers {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.tip {
  margin-top: 14px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
</style>
