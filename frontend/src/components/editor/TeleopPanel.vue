<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRobotStore } from '@/stores/robot'
import { armMove } from '@/api/robot'
import { getRobotLayer } from '@/three/robotLayerRef'
import { loadTeachPoints, saveTeachPoint, deleteTeachPoint, type ArmTeachPoint } from '@/utils/teachPoints'

const emit = defineEmits<{ (e: 'close'): void }>()
const robot = useRobotStore()

const activeTab = ref<'chassis' | 'arm' | 'teach'>('chassis')

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
  jogHoldStop()
}

// 离开示教页签时停止连续点动
watch(activeTab, () => jogHoldStop())

// ==================== 机械臂控制（/agv/arm_move 调试入口） ====================
/** 关节顺序契约：/joint_states 中该臂关节名排序后的第 i 个（ur5e 即 pan→lift→elbow→wrist1→wrist2→wrist3） */
const ARM_JOINT_COUNT = 6

/** 收拢归位（弧度）：联锁基准姿态——后续"未归位禁止移动"闸门以此为准，改联锁容差请同步改这里 */
const PRESET_HOME_RAD = [0, 0.6, -1.2, 0.5, 0, 0]
/** 展开质检位（弧度）：来自《机械臂控制接口对接指引》示例 */
const PRESET_INSPECT_RAD = [0.5, -1.2, 1.0, -0.5, 0, 0]

const armSide = ref<'left' | 'right'>('left')
const armDuration = ref(3)
const armSending = ref(false)
/** 自定义关节角输入（角度制，便于人读；发送时转弧度） */
const armDegrees = ref<number[]>(PRESET_INSPECT_RAD.map((v) => +((v * 180) / Math.PI).toFixed(1)))

/** 当前选中臂的实时关节角（弧度，按名字排序对齐输入框顺序）；无数据为空数组 */
const currentArmJoints = computed<number[]>(() => {
  const j = robot.joints
  if (!j) return []
  const side = armSide.value
  const pairs: [string, number][] = []
  for (let i = 0; i < j.name.length && i < j.position.length; i++) {
    const lower = j.name[i].toLowerCase()
    if (lower.includes('arm') && lower.includes(side)) pairs.push([j.name[i], j.position[i]])
  }
  pairs.sort((a, b) => a[0].localeCompare(b[0]))
  return pairs.map(([, v]) => v)
})

function fillInputs(rad: number[]) {
  armDegrees.value = rad.slice(0, ARM_JOINT_COUNT).map((v) => +((v * 180) / Math.PI).toFixed(1))
}

function readCurrentIntoInputs() {
  if (currentArmJoints.value.length === 0) {
    ElMessage.warning('暂无该臂的 /joint_states 数据')
    return
  }
  fillInputs(currentArmJoints.value)
}

async function sendArm(rad: number[]) {
  if (rad.length !== ARM_JOINT_COUNT) {
    ElMessage.warning(`关节角数量应为 ${ARM_JOINT_COUNT}，实际 ${rad.length}`)
    return
  }
  armSending.value = true
  try {
    const r = await armMove(armSide.value, rad.map((v) => +v.toFixed(4)), armDuration.value)
    if (r?.success) {
      ElMessage.success(`机械臂（${armSide.value}）已受理，执行进度看 3D 模型`)
    } else {
      ElMessage.error(`机械臂指令被拒绝：${r?.message ?? '未知原因'}`)
    }
  } catch {
    /* http 层已提示 */
  } finally {
    armSending.value = false
  }
}

function sendPreset(rad: number[]) {
  fillInputs(rad)
  void sendArm(rad)
}

function sendCustom() {
  void sendArm(armDegrees.value.map((deg) => (deg * Math.PI) / 180))
}

// ==================== 示教控制：关节/笛卡尔点动 + 示教点管理 ====================
const tSide = ref<'left' | 'right'>('left')
const jogMode = ref<'joint' | 'cart'>('joint')
const jointStepDeg = ref(1)
const cartStepMm = ref(5)
const teachPoints = ref<ArmTeachPoint[]>(loadTeachPoints())
const selectedTeachId = ref<string | null>(null)
let jogTimer: number | undefined

/** 指定侧臂的实时关节角（弧度，/joint_states 按名排序） */
function sideJoints(side: 'left' | 'right'): number[] {
  const j = robot.joints
  if (!j) return []
  const pairs: [string, number][] = []
  for (let i = 0; i < j.name.length && i < j.position.length; i++) {
    const lower = j.name[i].toLowerCase()
    if (lower.includes('arm') && lower.includes(side)) pairs.push([j.name[i], j.position[i]])
  }
  pairs.sort((a, b) => a[0].localeCompare(b[0]))
  return pairs.map(([, v]) => v)
}

const teachCurrent = computed(() => sideJoints(tSide.value))
const urdfReady = computed(() => getRobotLayer()?.hasUrdf ?? false)

/** 关节点动：第 idx 关节 ± stepDeg */
function jogJoint(idx: number, sign: 1 | -1) {
  const cur = sideJoints(tSide.value)
  if (cur.length !== 6) {
    ElMessage.warning('暂无关节数据，无法点动')
    return
  }
  const next = cur.map((v, i) => (i === idx ? v + sign * (jointStepDeg.value * Math.PI) / 180 : v))
  void sendArmRaw(next, 0.35)
}

/** 笛卡尔点动（基座系）：axis 0/1/2 → x/y/z，sign 方向 */
function jogCart(axis: 0 | 1 | 2, sign: 1 | -1) {
  const layer = getRobotLayer()
  if (!layer?.hasUrdf) {
    ElMessage.warning('笛卡尔点动需要 URDF 模型（机器人未连接或无 robot_description）')
    return
  }
  const delta = [0, 0, 0]
  delta[axis] = sign * cartStepMm.value / 1000
  const solved = layer.armIkSolve(tSide.value, delta[0], delta[1], delta[2])
  if (!solved) {
    ElMessage.warning('逆解未收敛（接近极限位或步长不可达），未发送')
    return
  }
  void sendArmRaw(solved, 0.35)
}

async function sendArmRaw(rad: number[], durationSec: number) {
  try {
    const r = await armMove(tSide.value, rad.map((v) => +v.toFixed(4)), durationSec)
    if (r && !r.success) ElMessage.error(`点动被拒绝：${r.message ?? '未知原因'}`)
  } catch {
    /* http 层已提示 */
  }
}

/** 按住连续点动（450ms 步进），松开停止 */
function jogHoldStart(fn: () => void) {
  fn()
  if (jogTimer) window.clearInterval(jogTimer)
  jogTimer = window.setInterval(fn, 450)
}

function jogHoldStop() {
  if (jogTimer) window.clearInterval(jogTimer)
  jogTimer = undefined
}

async function saveCurrentAsTeachPoint() {
  const joints = sideJoints(tSide.value)
  if (joints.length !== 6) {
    ElMessage.warning('暂无关节数据，无法保存示教点')
    return
  }
  try {
    const { value } = await ElMessageBox.prompt('示教点名称', '保存当前位姿', {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValue: `P${teachPoints.value.length + 1}`,
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空',
    })
    saveTeachPoint({ name: value.trim(), side: tSide.value, joints })
    teachPoints.value = loadTeachPoints()
    ElMessage.success(`示教点「${value.trim()}」已保存`)
  } catch {
    /* 取消 */
  }
}

const teachOptions = computed(() => teachPoints.value.filter((p) => p.side === tSide.value))
const selectedTeach = computed(() => teachOptions.value.find((p) => p.id === selectedTeachId.value) ?? null)

function gotoTeachPoint() {
  if (!selectedTeach.value) {
    ElMessage.warning('请先选择示教点')
    return
  }
  void sendArmRaw(selectedTeach.value.joints, 2)
  ElMessage.info(`正在转到「${selectedTeach.value.name}」…`)
}

function removeTeachPoint() {
  const p = selectedTeach.value
  if (!p) {
    ElMessage.warning('请先选择示教点')
    return
  }
  deleteTeachPoint(p.id)
  teachPoints.value = loadTeachPoints()
  selectedTeachId.value = null
  ElMessage.success(`示教点「${p.name}」已删除`)
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
  jogHoldStop()
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
        <div class="tabbar">
          <button class="tabbtn" :class="{ on: activeTab === 'chassis' }" @click="activeTab = 'chassis'">底盘遥控</button>
          <button class="tabbtn" :class="{ on: activeTab === 'arm' }" @click="activeTab = 'arm'">机械臂</button>
          <button class="tabbtn" :class="{ on: activeTab === 'teach' }" @click="activeTab = 'teach'">示教</button>
        </div>

        <el-alert
          v-if="robot.connStatus !== 'open'"
          title="机器人连接不可用，遥控指令无法发送"
          type="error"
          :closable="false"
          show-icon
        />

        <template v-if="activeTab === 'chassis'">
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
        </template>

        <template v-else-if="activeTab === 'arm'">
          <div class="arm-row">
            <el-radio-group v-model="armSide" size="small">
              <el-radio-button value="left">左臂</el-radio-button>
              <el-radio-button value="right">右臂</el-radio-button>
            </el-radio-group>
            <span class="arm-current">
              当前：
              <template v-if="currentArmJoints.length === ARM_JOINT_COUNT">
                {{ currentArmJoints.map(v => ((v * 180) / Math.PI).toFixed(0) + '°').join(' ') }}
              </template>
              <template v-else>无关节数据</template>
            </span>
          </div>

          <div class="arm-presets">
            <el-button type="success" plain :disabled="armSending || robot.connStatus !== 'open'" @click="sendPreset(PRESET_HOME_RAD)">
              收拢归位
            </el-button>
            <el-button type="warning" plain :disabled="armSending || robot.connStatus !== 'open'" @click="sendPreset(PRESET_INSPECT_RAD)">
              展开质检位
            </el-button>
          </div>

          <div class="arm-joints">
            <div v-for="i in ARM_JOINT_COUNT" :key="i" class="arm-joint">
              <span>J{{ i }}</span>
              <el-input-number
                v-model="armDegrees[i - 1]"
                :min="-180" :max="180" :step="5" :precision="1" size="small" controls-position="right"
              />
            </div>
          </div>

          <div class="arm-actions">
            <span class="arm-duration">
              时长 <el-input-number v-model="armDuration" :min="0.5" :max="30" :step="0.5" size="small" controls-position="right" /> s
            </span>
            <el-button size="small" @click="readCurrentIntoInputs">读取当前</el-button>
            <el-button type="primary" size="small" :loading="armSending" :disabled="robot.connStatus !== 'open'" @click="sendCustom">
              发送
            </el-button>
          </div>

          <div class="axis-note">
            关节顺序 = /joint_states 中该臂关节名排序（pan→lift→elbow→wrist1→wrist2→wrist3），输入单位为角度。
            「收拢归位」为联锁基准姿态——机械臂不在该位附近时移动作业应被拒绝。
          </div>
        </template>

        <template v-else-if="activeTab === 'teach'">
          <div class="arm-row">
            <el-radio-group v-model="tSide" size="small">
              <el-radio-button value="left">左臂</el-radio-button>
              <el-radio-button value="right">右臂</el-radio-button>
            </el-radio-group>
            <span class="arm-current">
              当前：
              <template v-if="teachCurrent.length === 6">
                {{ teachCurrent.map(v => ((v * 180) / Math.PI).toFixed(0) + '°').join(' ') }}
              </template>
              <template v-else>无关节数据</template>
            </span>
          </div>

          <div class="arm-row">
            <el-radio-group v-model="jogMode" size="small">
              <el-radio-button value="joint">关节</el-radio-button>
              <el-radio-button value="cart" :disabled="!urdfReady">笛卡尔</el-radio-button>
            </el-radio-group>
            <template v-if="jogMode === 'joint'">
              <el-select v-model="jointStepDeg" size="small" style="width: 110px">
                <el-option :value="0.5" label="步长 0.5°" />
                <el-option :value="1" label="步长 1°" />
                <el-option :value="5" label="步长 5°" />
              </el-select>
            </template>
            <template v-else>
              <el-select v-model="cartStepMm" size="small" style="width: 110px">
                <el-option :value="1" label="步长 1mm" />
                <el-option :value="5" label="步长 5mm" />
                <el-option :value="10" label="步长 10mm" />
              </el-select>
            </template>
          </div>

          <div v-if="jogMode === 'joint'" class="jog-joint">
            <div v-for="i in 6" :key="i" class="jog-row">
              <span class="jog-label">J{{ i }}</span>
              <button class="jog-btn" :disabled="robot.connStatus !== 'open' || teachCurrent.length !== 6"
                      @pointerdown.prevent="jogHoldStart(() => jogJoint(i - 1, -1))"
                      @pointerup.prevent="jogHoldStop" @pointercancel.prevent="jogHoldStop"
                      @lostpointercapture="jogHoldStop" @contextmenu.prevent>−</button>
              <button class="jog-btn" :disabled="robot.connStatus !== 'open' || teachCurrent.length !== 6"
                      @pointerdown.prevent="jogHoldStart(() => jogJoint(i - 1, 1))"
                      @pointerup.prevent="jogHoldStop" @pointercancel.prevent="jogHoldStop"
                      @lostpointercapture="jogHoldStop" @contextmenu.prevent>+</button>
            </div>
          </div>

          <div v-else class="jog-cart">
            <button class="jog-btn jog-cart-btn" :disabled="robot.connStatus !== 'open'"
                    @pointerdown.prevent="jogHoldStart(() => jogCart(0, -1))"
                    @pointerup.prevent="jogHoldStop" @pointercancel.prevent="jogHoldStop"
                    @lostpointercapture="jogHoldStop" @contextmenu.prevent>X−</button>
            <button class="jog-btn jog-cart-btn" :disabled="robot.connStatus !== 'open'"
                    @pointerdown.prevent="jogHoldStart(() => jogCart(0, 1))"
                    @pointerup.prevent="jogHoldStop" @pointercancel.prevent="jogHoldStop"
                    @lostpointercapture="jogHoldStop" @contextmenu.prevent>X+</button>
            <button class="jog-btn jog-cart-btn" :disabled="robot.connStatus !== 'open'"
                    @pointerdown.prevent="jogHoldStart(() => jogCart(1, -1))"
                    @pointerup.prevent="jogHoldStop" @pointercancel.prevent="jogHoldStop"
                    @lostpointercapture="jogHoldStop" @contextmenu.prevent>Y−</button>
            <button class="jog-btn jog-cart-btn" :disabled="robot.connStatus !== 'open'"
                    @pointerdown.prevent="jogHoldStart(() => jogCart(1, 1))"
                    @pointerup.prevent="jogHoldStop" @pointercancel.prevent="jogHoldStop"
                    @lostpointercapture="jogHoldStop" @contextmenu.prevent>Y+</button>
            <button class="jog-btn jog-cart-btn" :disabled="robot.connStatus !== 'open'"
                    @pointerdown.prevent="jogHoldStart(() => jogCart(2, -1))"
                    @pointerup.prevent="jogHoldStop" @pointercancel.prevent="jogHoldStop"
                    @lostpointercapture="jogHoldStop" @contextmenu.prevent>Z−</button>
            <button class="jog-btn jog-cart-btn" :disabled="robot.connStatus !== 'open'"
                    @pointerdown.prevent="jogHoldStart(() => jogCart(2, 1))"
                    @pointerup.prevent="jogHoldStop" @pointercancel.prevent="jogHoldStop"
                    @lostpointercapture="jogHoldStop" @contextmenu.prevent>Z+</button>
          </div>

          <el-divider style="margin: 2px 0">
            <span class="divider-text">示教点</span>
          </el-divider>

          <div class="teach-actions">
            <el-button size="small" type="primary" plain :disabled="teachCurrent.length !== 6" @click="saveCurrentAsTeachPoint">
              保存当前位姿
            </el-button>
            <el-select v-model="selectedTeachId" size="small" placeholder="选择示教点" style="flex: 1" clearable>
              <el-option v-for="p in teachOptions" :key="p.id" :value="p.id" :label="`${p.name}（${p.joints.length} 关节）`" />
            </el-select>
            <el-button size="small" :disabled="!selectedTeach" @click="gotoTeachPoint">转到</el-button>
            <el-button size="small" type="danger" plain :disabled="!selectedTeach" @click="removeTeachPoint">删除</el-button>
          </div>

          <div class="axis-note">
            按住连续点动、松开停止。关节步进直接叠加角度；笛卡尔步进沿机器人基座系平移（数值逆解，需 URDF 模型在线）。
            示教点保存在本机浏览器（localStorage），"转到"按保存的关节角插值运动（2s）。
          </div>
        </template>
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

.tabbar { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 6px; }
.tabbtn { padding: 7px 0; border: 1px solid #dcdfe6; border-radius: 8px; background: #fff; color: #606266; font: inherit; font-size: 13px; cursor: pointer; }
.tabbtn:hover { color: #409eff; border-color: #409eff; }
.tabbtn.on { color: #fff; background: #409eff; border-color: #409eff; font-weight: 600; }

.arm-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.arm-current { font-size: 12px; color: #606266; font-variant-numeric: tabular-nums; text-align: right; }
.arm-presets { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.arm-presets .el-button { width: 100%; }
.arm-joints { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px 10px; }
.arm-joint { display: grid; grid-template-columns: 22px 1fr; align-items: center; gap: 6px; font-size: 12px; color: #606266; }
.arm-actions { display: flex; align-items: center; gap: 10px; justify-content: flex-end; }
.arm-duration { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: #606266; margin-right: auto; }

.jog-joint { display: grid; grid-template-columns: 1fr; gap: 6px; }
.jog-row { display: grid; grid-template-columns: 34px 1fr 1fr; gap: 6px; align-items: center; }
.jog-label { font-size: 12px; color: #606266; text-align: center; font-variant-numeric: tabular-nums; }
.jog-btn { min-height: 34px; border: 1px solid #dcdfe6; border-radius: 8px; background: #fff; color: #303133; font: inherit; font-size: 14px; cursor: pointer; user-select: none; touch-action: none; }
.jog-btn:hover:not(:disabled) { border-color: #409eff; color: #409eff; background: #ecf5ff; }
.jog-btn:active:not(:disabled) { color: #fff; background: #409eff; border-color: #409eff; }
.jog-btn:disabled { cursor: not-allowed; opacity: .45; }
.jog-cart { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; }
.jog-cart-btn { min-height: 44px; font-weight: 600; }
.divider-text { font-size: 12px; color: #909399; }
.teach-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
</style>
