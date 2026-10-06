<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, computed } from 'vue'
import { useRobotStore } from '@/stores/robot'

const emit = defineEmits<{ (e: 'close'): void }>()
const robot = useRobotStore()

const linearSpeed = ref(0.7)
const angularSpeed = ref(0.8)
const active = ref('')
let repeatTimer: number | undefined
let stopRetryTimers: number[] = []
let commandToken = 0

interface Motion {
  key: string
  label: string
  hint: string
  x: number
  y: number
  z: number
  danger?: boolean
}

const motions = computed<Motion[]>(() => {
  const v = linearSpeed.value
  const w = angularSpeed.value
  return [
    { key: 'curve-left', label: '↖', hint: '左转弯', x: v, y: 0, z: w },
    { key: 'forward', label: '↑', hint: '前进', x: v, y: 0, z: 0 },
    { key: 'curve-right', label: '↗', hint: '右转弯', x: v, y: 0, z: -w },
    { key: 'strafe-left', label: '←', hint: '左平移', x: 0, y: v, z: 0 },
    { key: 'stop', label: '■', hint: '停车', x: 0, y: 0, z: 0, danger: true },
    { key: 'strafe-right', label: '→', hint: '右平移', x: 0, y: -v, z: 0 },
    { key: 'spin-left', label: '↶', hint: '原地左转', x: 0, y: 0, z: w },
    { key: 'backward', label: '↓', hint: '后退', x: -v, y: 0, z: 0 },
    { key: 'spin-right', label: '↷', hint: '原地右转', x: 0, y: 0, z: -w },
  ]
})

function send(m: Motion) {
  const token = commandToken
  void robot.teleop(m.x, m.y, m.z).catch(() => {
    if (token === commandToken) stop()
  })
}

function start(m: Motion, ev: PointerEvent) {
  ev.currentTarget && (ev.currentTarget as HTMLElement).setPointerCapture(ev.pointerId)
  stop(false)
  // 清掉上一次松手排队的停车重试，避免迟到的零速指令杀掉新一轮运动
  stopRetryTimers.forEach(t => window.clearTimeout(t))
  stopRetryTimers = []
  commandToken++
  active.value = m.key
  send(m)
  if (m.key !== 'stop') repeatTimer = window.setInterval(() => send(m), 150)
}

function stop(sendZero = true) {
  commandToken++
  if (repeatTimer) window.clearInterval(repeatTimer)
  repeatTimer = undefined
  active.value = ''
  if (sendZero) {
    // 停车指令连发 3 次（0/120/300ms）：单条可能撞上 rosbridge 传输窗口被延迟或丢失，
    // 底盘 0.5s 指令超时会兜底，但连发能把"松手迟停"压到最低
    sendZeroOnce()
    stopRetryTimers.push(window.setTimeout(sendZeroOnce, 120))
    stopRetryTimers.push(window.setTimeout(sendZeroOnce, 300))
  }
}

function sendZeroOnce() {
  void robot.teleop(0, 0, 0).catch(() => {})
}

function close() {
  stop()
  emit('close')
}

function onVisibilityChange() {
  if (document.hidden) stop()
}

function onWindowBlur() {
  stop()
}

// ==================== 浮窗：无遮罩、标题栏拖拽 ====================
const WIN_W = 390
const win = reactive({ x: 0, y: 0 })
let dragState: { sx: number; sy: number; ox: number; oy: number } | null = null

onMounted(() => {
  // 初始停靠右上角，避开地图中心
  win.x = Math.max(12, window.innerWidth - WIN_W - 24)
  win.y = 90
})

function clampToViewport() {
  win.x = Math.min(Math.max(win.x, 64 - WIN_W), window.innerWidth - 64)
  win.y = Math.min(Math.max(win.y, 0), window.innerHeight - 48)
}

function onDragStart(e: PointerEvent) {
  dragState = { sx: e.clientX, sy: e.clientY, ox: win.x, oy: win.y }
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
}

function onDragMove(e: PointerEvent) {
  if (!dragState) return
  win.x = dragState.ox + e.clientX - dragState.sx
  win.y = dragState.oy + e.clientY - dragState.sy
  clampToViewport()
}

function onDragEnd() {
  dragState = null
}

window.addEventListener('blur', onWindowBlur)
document.addEventListener('visibilitychange', onVisibilityChange)
onBeforeUnmount(() => {
  stop()
  stopRetryTimers.forEach(t => window.clearTimeout(t))
  stopRetryTimers = []
  window.removeEventListener('blur', onWindowBlur)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <Teleport to="body">
    <div class="teleop-win" :style="{ left: win.x + 'px', top: win.y + 'px', width: WIN_W + 'px' }">
      <div class="teleop-head">
        <div
          class="teleop-drag"
          title="按住拖动"
          @pointerdown.prevent="onDragStart"
          @pointermove="onDragMove"
          @pointerup="onDragEnd"
          @pointercancel="onDragEnd"
        >
          <span class="teleop-title">全向遥控器</span>
          <span class="teleop-drag-hint">⋮⋮ 拖动</span>
        </div>
        <button class="teleop-close" title="关闭" @click="close">✕</button>
      </div>
      <div class="teleop-body">
        <el-alert
          v-if="robot.connStatus !== 'open'"
          title="机器人连接不可用，遥控指令无法发送"
          type="error"
          :closable="false"
          show-icon
        />
        <el-alert
          title="按住移动，松开即停车。速度基于机器人车体坐标系，不受地图视图旋转影响。"
          type="info"
          :closable="false"
          show-icon
        />

        <div class="pad">
          <button
            v-for="m in motions"
            :key="m.key"
            class="motion-btn"
            :class="{ active: active === m.key, stop: m.danger }"
            :disabled="robot.connStatus !== 'open'"
            :title="m.hint"
            @pointerdown.prevent="start(m, $event)"
            @pointerup.prevent="stop()"
            @pointercancel.prevent="stop()"
            @lostpointercapture="stop()"
            @contextmenu.prevent
          >
            <span class="motion-icon">{{ m.label }}</span>
            <span>{{ m.hint }}</span>
          </button>
        </div>

        <div class="speed-row">
          <span>平移速度</span>
          <el-slider v-model="linearSpeed" :min="0.05" :max="1" :step="0.05" />
          <b>{{ linearSpeed.toFixed(2) }} m/s</b>
        </div>
        <div class="speed-row">
          <span>旋转速度</span>
          <el-slider v-model="angularSpeed" :min="0.1" :max="1.5" :step="0.1" />
          <b>{{ angularSpeed.toFixed(1) }} rad/s</b>
        </div>

        <div class="axis-note">
          指令约定：+X 前进、+Y 左平移、+Z 逆时针左转。若实车方向相反，应在底盘驱动层修正坐标约定。
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.teleop-win {
  position: fixed;
  z-index: 2200;
  background: #fff;
  border: 1px solid #dcdfe6;
  border-radius: 10px;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.18);
  overflow: hidden;
}

.teleop-head {
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  background: #f5f7fa;
  border-bottom: 1px solid #e4e7ed;
}

.teleop-drag {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  cursor: move;
  touch-action: none;
  user-select: none;
}

.teleop-drag-hint {
  font-size: 11px;
  color: #c0c4cc;
  letter-spacing: 2px;
}

.teleop-title {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
}

.teleop-close {
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 15px;
  color: #909399;
  padding: 0 14px;
  border-left: 1px solid #e4e7ed;
}

.teleop-close:hover {
  color: #409eff;
  background: #ecf5ff;
}

.teleop-body {
  padding: 14px;
  display: grid;
  gap: 16px;
}

.pad { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; user-select: none; touch-action: none; }
.motion-btn { min-height: 78px; border: 1px solid #dcdfe6; border-radius: 10px; background: #fff; color: #303133; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px; cursor: pointer; font: inherit; }
.motion-btn:hover { border-color: #409eff; color: #409eff; background: #ecf5ff; }
.motion-btn.active { color: #fff; background: #409eff; border-color: #409eff; transform: scale(.97); }
.motion-btn.stop { color: #f56c6c; }
.motion-btn.stop.active { color: #fff; background: #f56c6c; border-color: #f56c6c; }
.motion-btn:disabled { cursor: not-allowed; opacity: .45; }
.motion-icon { font-size: 27px; line-height: 1; }
.speed-row { display: grid; grid-template-columns: 70px 1fr 82px; align-items: center; gap: 10px; font-size: 13px; }
.speed-row b { text-align: right; font-variant-numeric: tabular-nums; }
.axis-note { padding: 10px 12px; border-radius: 8px; background: #f5f7fa; color: #606266; font-size: 12px; line-height: 1.6; }
</style>
