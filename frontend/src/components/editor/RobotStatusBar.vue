<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRobotStore } from '@/stores/robot'
import { useTasksStore } from '@/stores/tasks'
import { MOVE_TASK_STATUS_TAG, MOVE_TASK_TYPE_LABEL } from '@/utils/format'
import { radToDeg } from '@/utils/coords'
import { armHomed } from '@/utils/armJoints'
import { getOperational } from '@/api/robot'
import type { MapAlignStatus } from '@/api/robot'

const robot = useRobotStore()
const tasks = useTasksStore()

// 停用状态（急停联动）
const decommissioned = ref(false)
const decommissionReason = ref('')
// 地图一致性（机器人当前地图 vs 上位机导航基准图；MISMATCH 时任务会被拒绝）
const mapAlign = ref<MapAlignStatus | null>(null)

async function refreshOperational() {
  try {
    const s = await getOperational()
    decommissioned.value = s.decommissioned
    decommissionReason.value = s.reason ?? ''
    mapAlign.value = s.map_align ?? null
  } catch { /* 忽略 */ }
}

onMounted(() => {
  void refreshOperational()
  window.setInterval(refreshOperational, 3000)
})

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

/** /odom twist（车体系）：全向底盘合成线速 = √(vx²+vy²)，横移藏在 linear_y */
const speedText = computed(() => {
  const v = robot.velocity
  if (!v) return null
  const linear = Math.hypot(v.linear_x, v.linear_y)
  return {
    linear,
    angular: v.angular_z,
    tip: `车体系分量：vx ${v.linear_x.toFixed(2)} m/s · vy ${v.linear_y.toFixed(2)} m/s · ω ${v.angular_z.toFixed(2)} rad/s`,
  }
})

/** 电量显示（/agv/status 电量接入字段：battery_simulator / 真机 BMS 同格式） */
const batteryView = computed(() => {
  const st = robot.status
  if (!st) return null
  // battery_data_valid 仅在显式 false 时视为无效（缺字段 = 旧版机器人，保持原展示）
  const invalid = st.battery_data_valid === false
  const pct = typeof st.battery === 'number' && !Number.isNaN(st.battery) ? st.battery : null
  const charging = st.charging === true
  if (invalid || pct == null) {
    return {
      text: '-',
      color: '#909399',
      charging: false,
      tip: invalid
        ? '机器人上报电量数据无效（battery_data_valid=false，BMS/电池节点未就绪）'
        : '机器人未上报电量（旧版 bridge 无电量字段）',
    }
  }
  const color = charging
    ? '#67c23a'
    : pct < 20 ? '#f56c6c'
    : pct < 40 ? '#e6a23c'
    : ''
  const tipParts = [`电量 ${Math.round(pct)}%`]
  if (st.battery_state) tipParts.push(`电池状态：${st.battery_state}`)
  if (st.charging != null) tipParts.push(st.charging ? '充电中' : '未充电')
  if (st.charge_connected != null) tipParts.push(st.charge_connected ? '充电桩已连接' : '充电桩未连接')
  tipParts.push(st.battery_data_valid == null ? '电量数据有效性：未知（旧版）' : `数据${st.battery_data_valid ? '有效' : '无效'}`)
  // 充电任务在途：把阶段与目标并入 tooltip（右侧不再单列充电 tag，省状态栏宽度）
  const ct = tasks.chargeTask
  if (ct && tasks.chargeTaskInFlight) {
    const phase = ct.status === 'MOVING' ? '去充电中' : ct.status === 'STARTING' ? '对接充电桩' : '充电中'
    tipParts.push(`充电任务：${phase} · 目标 ${Math.round(ct.target_battery_pct)}%`)
  }
  return { text: `${Math.round(pct)}%`, color, charging, tip: tipParts.join(' · ') }
})

/** 未定位冷启动警示：机器人无建图无定位，画面仅为存档地图浏览 */
const notLocalized = computed(() =>
  !!robot.status && !robot.status.pose_initialized
  && robot.status.mode !== 'MAPPING' && robot.status.mode !== 'RELOCALIZING')

/** 机械臂归位联锁状态（角色映射 + 单臂 profile 回退，与后端 ArmStateMonitor 同参数；闸门以后端判定为准） */
const armState = computed<{ label: string; color: string } | null>(() => {
  const homed = armHomed(robot.joints, 'left') ?? armHomed(robot.joints, 'right')
  if (homed === null) return null
  return homed
    ? { label: '归位', color: '#67c23a' }
    : { label: '展开', color: '#e6a23c' }
})

/** 雷达简化展示：点云已上图绘制，状态栏只留健康指示（✓/✗），明细放悬停提示 */
const scanView = computed(() => {
  const s1 = robot.snapshot?.scan1
  const s2 = robot.snapshot?.scan2
  const p1 = robot.scan1Cloud
  const p2 = robot.scan2Cloud
  const fmt = (s: typeof s1, cloud: typeof p1) =>
    s ? `${s.range_count ?? '-'}点（${s.min_range ?? '-'}~${s.max_range ?? '-'}m${cloud?.points?.length ? '，点云✓' : '，无点云'}）` : '无数据'
  const mark = (s: typeof s1) => (s ? '✓' : '✗')
  return {
    text: `雷达 ${mark(s1)}${mark(s2)}`,
    healthy: s1 != null && s2 != null,
    tip: `雷达1: ${fmt(s1, p1)} | 雷达2: ${fmt(s2, p2)}`,
  }
})

/** 地图不一致警示：机器人加载的地图与上位机导航基准图不同（同名点位坐标对不上，任务一律拒绝防乱撞） */
const mapMismatch = computed(() => mapAlign.value?.state === 'MISMATCH')

async function onMapMismatch() {
  const align = mapAlign.value
  const detail = `机器人地图：${align?.robot_map_name || '未知'}\n基准地图：${align?.ref_map_name || '未知'}（${align?.ref_source || '—'}）`
  await ElMessageBox.alert(
    `${align?.message || '机器人当前加载的地图与上位机导航基准图不一致，任务已被拒绝执行。'}\n\n${detail}\n\n请先把机器人切换到基准地图（主页地图管理/切图），或更新上位机基准图后再下发任务。`,
    '地图不一致',
    { type: 'warning', confirmButtonText: '知道了' },
  )
}

async function onControl(action: 'start' | 'stop' | 'reset') {
  const label = action === 'start' ? '启动' : action === 'stop' ? '急停' : '复位'
  await ElMessageBox.confirm(`确认对机器人执行「${label}」？`, '机器人控制', { type: 'warning' })
  const r = await robot.control(action)
  if (r.success) {
    ElMessage.success(`已${label}：${r.message}`)
    // 急停/启动联动停用标志，立即刷新
    await refreshOperational()
  } else {
    ElMessage.error(r.message)
  }
}

/** 充电任务在途：状态栏只留取消按钮（充电中/电量看左侧电量区 ⚡，避免重复占位） */
const chargeTaskActive = computed(() => tasks.chargeTaskInFlight)

/** 低电量死锁救援：电量 ≤10% 且未在充电（机器人将拒绝移动指令，无法自主到桩） */
const needRescue = computed(() => {
  const st = robot.status
  const pct = st?.battery
  return st != null && typeof pct === 'number' && pct <= 10
    && st.charging !== true && !tasks.chargeTaskInFlight
})

async function onRescueCharge() {
  await ElMessageBox.confirm(
    '远程开始充电（无需机器人到桩）？仅用于电量耗尽无法移动的救援场景；机器人回桩后的正常充电请用地图右键「去充电」。',
    '救援充电',
    { type: 'warning', confirmButtonText: '开始救援充电' },
  )
  await tasks.rescueChargeTask()
}

async function onCancelCharge() {
  await ElMessageBox.confirm('确认取消充电任务？（在桩上将停止充电）', '取消充电', { type: 'warning' })
  await tasks.cancelChargeTask()
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
        <template v-if="notLocalized">
          <el-divider direction="vertical" />
          <span style="color: #e6a23c" title="机器人既无建图也无定位，当前画面仅为上位机存档地图浏览；回到主页可选图启动定位或开始建图">
            ⚠ 机器人未定位（仅为地图浏览）
          </span>
        </template>
        <el-divider direction="vertical" />
        <span v-if="batteryView" :title="batteryView.tip">电量：<b :style="{ color: batteryView.color }">{{ batteryView.text }}</b><span
          v-if="batteryView.charging"
          class="charging-badge"
          title="机器人上报充电中（charging=true）"
        >⚡充电中</span></span>
        <span v-else>电量：<b>-</b></span>
        <el-divider direction="vertical" />
        <span v-if="speedText" title="线速度 |v|=√(vx²+vy²)（/odom 车体系）"
          >速度：<b>{{ speedText.linear.toFixed(2) }} m/s</b> · <b>{{ speedText.angular.toFixed(2) }} rad/s</b></span>
        <span v-else class="muted" title="未启用 /odom 订阅或未收到里程计">速度：-</span>
        <template v-if="armState">
          <el-divider direction="vertical" />
          <span title="机械臂归位联锁：展开状态下移动作业会被后端拒绝（以后端判定为准）">
            机械臂：<b :style="{ color: armState.color }">{{ armState.label }}</b>
          </span>
        </template>
        <el-divider direction="vertical" />
        <span :style="{ color: robot.status.pose_initialized ? '' : '#f56c6c' }">
          {{ poseText }}
        </span>
        <template v-if="robot.pose?.topo_name">
          <el-divider direction="vertical" />
          <span title="机器人上报的当前所在点位（/agv/pose topo_name；在点位上时携带点位名）">
            点位：<b>{{ robot.pose.topo_name }}</b>
          </span>
        </template>
        <el-divider direction="vertical" />
        <span
          class="muted"
          :style="scanView.healthy ? '' : 'color: #e6a23c'"
          :title="scanView.tip"
        >{{ scanView.text }}</span>
      </template>
      <template v-else>
        <span class="muted">等待机器人遥测…</span>
      </template>
    </div>

    <div class="status-right">
      <el-tag v-if="decommissioned" size="small" type="danger" effect="dark" class="decommission-tag">
        ⛔ 停用（{{ decommissionReason }}）
      </el-tag>
      <el-tag
        v-if="mapMismatch"
        size="small"
        type="danger"
        effect="dark"
        class="decommission-tag"
        style="cursor: pointer"
        @click="onMapMismatch"
      >⚠ 地图不一致：{{ mapAlign?.robot_map_name }} ≠ {{ mapAlign?.ref_map_name }}</el-tag>
      <!-- 充电任务在途：不再单列充电 tag（左侧电量区 ⚡ 已表达充电中，目标看电量 tooltip），只留取消按钮 -->
      <el-button
        v-if="chargeTaskActive"
        size="small"
        type="danger"
        plain
        :title="tasks.chargeTask?.message ?? ''"
        @click="onCancelCharge"
      >取消充电</el-button>
      <el-button
        v-else-if="needRescue"
        size="small"
        type="danger"
        @click="onRescueCharge"
      >⚠ 救援充电</el-button>
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
      <el-button size="small" :type="decommissioned ? 'success' : 'default'" @click="onControl('start')">启动</el-button>
      <el-button size="small" :type="decommissioned ? 'default' : 'danger'" :disabled="decommissioned" @click="onControl('stop')">急停</el-button>
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
  gap: 8px;
  padding: 0 8px;
  font-size: 12px;
  background: #fff;
  border-top: 1px solid #e4e7ed;
}

/* 状态栏空间紧张：收紧竖分隔线与按钮内边距 */
.statusbar :deep(.el-divider--vertical) {
  margin: 0 3px;
}

.statusbar :deep(.el-button--small) {
  padding: 5px 8px;
}

.status-left {
  display: flex;
  align-items: center;
  gap: 5px;
  overflow: hidden;
  white-space: nowrap;
}

.status-left b {
  font-weight: 600;
}

.conn {
  display: flex;
  align-items: center;
  gap: 4px;
  font-weight: 600;
}

.conn-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
  flex-shrink: 0;
}

.muted {
  color: #909399;
}

.charging-badge {
  margin-left: 2px;
  color: #67c23a;
  font-weight: 600;
  animation: charging-pulse 1.6s infinite;
}

@keyframes charging-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.45; }
}

.status-right {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.decommission-tag {
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}
</style>
