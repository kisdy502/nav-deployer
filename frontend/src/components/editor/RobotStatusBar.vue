<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRobotStore } from '@/stores/robot'
import { useTasksStore } from '@/stores/tasks'
import { MOVE_TASK_STATUS_TAG, MOVE_TASK_TYPE_LABEL } from '@/utils/format'
import { radToDeg } from '@/utils/coords'

const robot = useRobotStore()
const tasks = useTasksStore()

const conn = computed(() => {
  switch (robot.connStatus) {
    case 'open':
      return { color: '#67c23a', label: '实时连接' }
    case 'connecting':
      return { color: '#e6a23c', label: '连接中' }
    default:
      return { color: '#f56c6c', label: '已断开' }
  }
})

const poseText = computed(() => {
  const p = robot.pose
  return p ? `x ${p.x.toFixed(2)}  y ${p.y.toFixed(2)}  θ ${radToDeg(p.yaw)}°` : '位姿未知'
})

const scanText = computed(() => {
  const s1 = robot.snapshot?.scan1
  const s2 = robot.snapshot?.scan2
  const p1 = robot.scan1Cloud
  const p2 = robot.scan2Cloud
  const fmt = (s: typeof s1, cloud: typeof p1) =>
    s ? `${s.range_count ?? '-'}点 (${s.min_range ?? '-'}~${s.max_range ?? '-'}m${cloud?.points?.length ? ', 点云✓' : ', 无点云'})` : '-'
  return `雷达1: ${fmt(s1, p1)} | 雷达2: ${fmt(s2, p2)}`
})

async function onControl(action: 'start' | 'stop' | 'reset') {
  const label = action === 'start' ? '启动' : action === 'stop' ? '急停' : '复位'
  await ElMessageBox.confirm(`确认对机器人执行「${label}」？`, '机器人控制', { type: 'warning' })
  const r = await robot.control(action)
  r.success ? ElMessage.success(`已${label}：${r.message}`) : ElMessage.error(r.message)
}

async function onCancelTask() {
  await ElMessageBox.confirm('确认取消当前移动任务？', '取消任务', { type: 'warning' })
  await tasks.cancelActive()
}
</script>

<template>
  <div class="statusbar">
    <div class="status-left">
      <span class="conn" :style="{ color: conn.color }">
        <span class="conn-dot" :style="{ background: conn.color }"></span>
        {{ conn.label }}
      </span>
      <el-divider direction="vertical" />
      <template v-if="robot.status">
        <span>状态：<b>{{ robot.status.state ?? '-' }}</b></span>
        <el-divider direction="vertical" />
        <span>模式：<b>{{ robot.status.mode ?? '-' }}</b></span>
        <el-divider direction="vertical" />
        <span>电量：<b>{{ robot.battery ?? '-' }}%</b></span>
        <el-divider direction="vertical" />
        <span>地图：<b>{{ robot.status.map_name ?? '-' }}</b></span>
        <el-divider direction="vertical" />
        <span :style="{ color: robot.status.pose_initialized ? '' : '#f56c6c' }">
          {{ poseText }}
        </span>
        <el-divider direction="vertical" />
        <span class="muted">{{ scanText }}</span>
      </template>
      <template v-else>
        <span class="muted">等待机器人遥测…</span>
      </template>
    </div>

    <div class="status-right">
      <template v-if="tasks.activeMoveTask">
        <el-tag
          size="small"
          :type="MOVE_TASK_STATUS_TAG[tasks.activeMoveTask.status]?.type ?? 'info'"
          effect="dark"
        >
          {{ MOVE_TASK_TYPE_LABEL[tasks.activeMoveTask.task_type] }}
          {{ MOVE_TASK_STATUS_TAG[tasks.activeMoveTask.status]?.label ?? tasks.activeMoveTask.status }}
          <template v-if="tasks.activeMoveTask.task_type === 'FOLLOW_PATH' && tasks.activeMoveTask.segment_total">
            （段 {{ tasks.activeMoveTask.segment_seq ?? '-' }}/{{ tasks.activeMoveTask.segment_total }}）
          </template>
        </el-tag>
        <el-button v-if="tasks.moveTaskInFlight" size="small" type="danger" plain @click="onCancelTask">取消任务</el-button>
        <el-divider direction="vertical" />
      </template>
      <el-button size="small" @click="onControl('start')">启动</el-button>
      <el-button size="small" type="danger" @click="onControl('stop')">急停</el-button>
      <el-button size="small" @click="onControl('reset')">复位</el-button>
    </div>
  </div>
</template>

<style scoped>
.statusbar {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 12px;
  font-size: 12.5px;
  background: #fff;
  border-top: 1px solid #e4e7ed;
}

.status-left {
  display: flex;
  align-items: center;
  gap: 10px;
  overflow: hidden;
  white-space: nowrap;
}

.status-left b {
  font-weight: 600;
}

.conn {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}

.conn-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.muted {
  color: #909399;
}

.status-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
</style>
