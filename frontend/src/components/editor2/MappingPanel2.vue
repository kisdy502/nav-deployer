<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useMapEditor2Store } from '@/stores/mapEditor2'
import { useRobotStore } from '@/stores/robot'
import { startMapping, saveMapTask, discardMapping, getCurrentMapTask } from '@/api/tasks'
import { createMapFromLiveV2, replaceGridFromLiveV2 } from '@/api/map2'
import type { MapModeTaskVO, MapTaskType } from '@/types/api'

/**
 * v2 建图抽屉：
 * - 「完成建图并保存」一键闭环：机器人侧 save_map（退出 MAPPING、存 maps_dir，轮询任务终态）
 *   + 上位机侧从实时栅格创建 v2 地图并跳转编辑——两个动作一次完成，不再分两个按钮误导。
 * - 未建图时也可从当前实时栅格另存 v2 地图 / 替换当前地图底图。
 */
const emit = defineEmits<{
  (e: 'close'): void
  (e: 'map-created', id: number): void
  (e: 'open-teleop'): void
}>()

const store = useMapEditor2Store()
const robot = useRobotStore()

const starting = ref(false)
const replacing = ref(false)
/** 停止建图（机器人侧）闭环反馈 */
const stopState = reactive({ running: false, text: '' })
/** 完成建图并保存（机器人侧 + v2 创建）闭环反馈 */
const finishState = reactive({ running: false, text: '' })
/** 放弃建图（机器人侧回旧图）闭环反馈 */
const discardState = reactive({ running: false, text: '' })

const saveDialog = reactive({
  visible: false,
  /** finish = 机器人退出建图 + 创建 v2 地图；new = 仅从实时栅格创建 v2 地图（不动机器人） */
  mode: 'finish' as 'finish' | 'new',
  name: '',
})

const mappingMode = computed(() => robot.status?.mode === 'MAPPING')

// 机器人处于建图模式时自动开启实时预览（刷新页面后恢复上下文）
watch(
  mappingMode,
  (on) => {
    if (on && !store.livePreview) store.startLivePreview()
  },
  { immediate: true },
)

async function onStartMapping() {
  await ElMessageBox.confirm(
    '将命令机器人进入在线建图模式（导航任务会被禁止），确认继续？',
    '开始建图',
    { type: 'warning' },
  )
  starting.value = true
  try {
    await startMapping()
    ElMessage.info('建图任务已下发，机器进入建图模式后自动开启实时预览')
  } finally {
    starting.value = false
  }
}

/** 等待指定类型模式任务终态（状态机 500ms 节拍，轮询 1s；上限 100s）。 */
async function awaitTaskTerminal(type: MapTaskType): Promise<{ ok: boolean; message: string }> {
  const deadline = Date.now() + 100_000
  while (Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 1000))
    let task: MapModeTaskVO | null = null
    try {
      task = await getCurrentMapTask()
    } catch {
      continue // 网络抖动继续等
    }
    if (task && task.type === type) {
      if (task.status === 'SUCCEEDED') return { ok: true, message: '' }
      if (task.status === 'FAILED') {
        return { ok: false, message: task.error_message || '未知原因' }
      }
    }
  }
  return { ok: false, message: '任务超时未到达终态（>100s）' }
}

/** 等待 SAVE_MAP 模式任务终态（保存/放弃共用终端等待）。 */
function awaitSaveTaskTerminal(): Promise<{ ok: boolean; message: string }> {
  return awaitTaskTerminal('SAVE_MAP')
}

/** 放弃建图：不保存新图，机器人退出建图并回载建图前地图恢复定位。 */
async function onDiscardMapping() {
  await ElMessageBox.confirm(
    '放弃本次建图？新图不会被保存，机器人将退出建图模式并回载建图前的地图恢复定位。',
    '放弃建图',
    { type: 'warning', confirmButtonText: '放弃建图', confirmButtonClass: 'el-button--danger' },
  )
  discardState.running = true
  discardState.text = '已下发，机器人退出建图并回载旧图…'
  try {
    await discardMapping()
    const result = await awaitTaskTerminal('DISCARD_MAPPING')
    if (result.ok) {
      discardState.text = ''
      if (store.livePreview) store.stopLivePreview()
      ElMessage.success('已放弃建图，机器人回到建图前地图恢复定位')
    } else {
      discardState.text = `失败：${result.message}`
      ElMessage.error(`放弃建图失败：${result.message}`)
    }
  } catch (error) {
    discardState.text = ''
    throw error
  } finally {
    discardState.running = false
  }
}

/** 仅停止建图（机器人侧保存并退出 MAPPING），闭环等待终态。 */
async function onStopMapping() {
  const { value: name } = await ElMessageBox.prompt('机器人侧地图名（maps_dir 保存名）', '停止建图', {
    confirmButtonText: '停止并保存到机器人',
    cancelButtonText: '取消',
    inputValue: store.map ? `${store.map.map_name}_new` : `map_${new Date().toISOString().slice(5, 10).replace('-', '')}`,
    inputPattern: /\S+/,
    inputErrorMessage: '名称不能为空',
  })
  stopState.running = true
  stopState.text = '已下发，机器人保存中…'
  try {
    await saveMapTask(name.trim())
    const result = await awaitSaveTaskTerminal()
    if (result.ok) {
      stopState.text = ''
      ElMessage.success('机器人已保存地图并退出建图模式')
    } else {
      stopState.text = `失败：${result.message}`
      ElMessage.error(`停止建图失败：${result.message}`)
    }
  } catch (error) {
    stopState.text = ''
    throw error
  } finally {
    stopState.running = false
  }
}

function openSaveDialog(mode: 'finish' | 'new') {
  saveDialog.mode = mode
  saveDialog.name = store.map
    ? `${store.map.map_name}_new`
    : `map_${new Date().toISOString().slice(5, 10).replace('-', '')}`
  saveDialog.visible = true
}

/**
 * 一键完成建图：
 * 1) 机器人侧 save_map（退出 MAPPING，存 maps_dir）——闭环等待终态；
 * 2) 上位机侧从实时栅格（后端 LiveMapCache 最后帧）创建 v2 地图并跳转编辑。
 */
async function onSaveConfirm() {
  const name = saveDialog.name.trim()
  if (!name) return ElMessage.warning('请输入地图名')
  finishState.running = true
  try {
    if (saveDialog.mode === 'finish') {
      finishState.text = '1/2 机器人保存地图并退出建图…'
      await saveMapTask(name)
      const result = await awaitSaveTaskTerminal()
      if (!result.ok) {
        ElMessage.error(`机器人侧保存失败，v2 地图未创建：${result.message}`)
        return
      }
    }
    finishState.text = saveDialog.mode === 'finish' ? '2/2 创建 v2 地图…' : '创建 v2 地图…'
    const vo = await createMapFromLiveV2(name, store.modifiedBy)
    // 建图流程收尾：关掉实时预览再进入新地图，避免带着 livePreview=true 进编辑被保护拦截
    if (store.livePreview) store.stopLivePreview()
    ElMessage.success(`v2 地图「${vo.map_name}」已创建`)
    saveDialog.visible = false
    emit('map-created', vo.id)
  } finally {
    finishState.running = false
    finishState.text = ''
  }
}

/** 用当前实时栅格替换正在编辑的 v2 地图底图（元数据保留，版本 +1）。 */
async function onReplaceGrid() {
  if (!store.map) return
  await ElMessageBox.confirm(
    `用当前实时栅格替换「${store.map.map_name}」的底图？楼层/点位/区域/货架/路线等元数据保持不变，版本 +1。`,
    '替换底图',
    { type: 'warning' },
  )
  replacing.value = true
  try {
    await replaceGridFromLiveV2(store.map.id, store.modifiedBy)
    await store.loadMap(store.map.id)
    ElMessage.success('底图已替换')
  } finally {
    replacing.value = false
  }
}

function togglePreview() {
  if (store.livePreview) store.stopLivePreview()
  else store.startLivePreview()
}
</script>

<template>
  <el-drawer title="建图（v2 多楼层地图）" size="380px" :model-value="true" :append-to-body="true" @close="emit('close')">
    <div class="mapping-body">
      <el-alert
        :type="mappingMode ? 'success' : 'info'"
        :closable="false"
        show-icon
        :title="mappingMode ? '机器人正在建图（MAPPING）' : '机器人未在建图'"
        :description="mappingMode ? '遥控机器人走遍作业区域，实时预览跟随更新；完成后点「完成建图并保存」一键收尾' : '点击「开始建图」命令机器人进入在线建图模式'"
      />

      <div class="section">
        <div class="section-title">建图控制</div>
        <div class="btn-row">
          <el-button type="primary" :loading="starting" :disabled="mappingMode" @click="onStartMapping">开始建图</el-button>
          <el-button :loading="stopState.running" :disabled="!mappingMode" @click="onStopMapping">仅停止建图</el-button>
          <el-button type="danger" plain :loading="discardState.running" :disabled="!mappingMode" @click="onDiscardMapping">
            放弃建图
          </el-button>
          <el-button @click="emit('open-teleop')">遥控器</el-button>
        </div>
        <div v-if="stopState.text" class="task-state">{{ stopState.text }}</div>
        <div v-if="discardState.text" class="task-state">{{ discardState.text }}</div>
        <div class="muted">「仅停止建图」只在机器人侧保存并退出建图（maps_dir），不创建上位机 v2 地图。</div>
        <div class="muted">「放弃建图」不保存新图，机器人退出建图并回载建图前地图恢复定位。</div>
      </div>

      <div class="section">
        <div class="section-title">保存到上位机（v2）</div>
        <div class="btn-row">
          <el-button v-if="mappingMode" type="primary" :loading="finishState.running" @click="openSaveDialog('finish')">
            完成建图并保存
          </el-button>
          <el-button v-else type="primary" :loading="finishState.running" @click="openSaveDialog('new')">
            保存为 v2 地图
          </el-button>
          <el-button :loading="replacing" :disabled="!store.map" @click="onReplaceGrid">替换当前底图</el-button>
        </div>
        <div v-if="finishState.text" class="task-state">{{ finishState.text }}</div>
        <div class="muted">
          建图中：「完成建图并保存」= 机器人退出建图（save_map）+ 从实时栅格创建 v2 地图，一次点击全部完成；
          非建图状态：「保存为 v2 地图」从最后一帧实时栅格创建；「替换底图」保留当前地图元数据仅换栅格。
        </div>
      </div>

      <div class="section">
        <div class="section-title">实时预览</div>
        <div class="btn-row">
          <el-switch :model-value="store.livePreview" active-text="开启" inactive-text="关闭" @change="togglePreview" />
        </div>
        <div class="muted">预览中禁止编辑元数据（画布坐标属于实时图，与已入库地图不一致）。</div>
      </div>
    </div>

    <template #footer>
      <el-button @click="emit('close')">关闭</el-button>
    </template>
  </el-drawer>

  <el-dialog
    v-model="saveDialog.visible"
    :title="saveDialog.mode === 'finish' ? '完成建图并保存' : '保存为 v2 地图'"
    width="420px"
    append-to-body
  >
    <div v-if="saveDialog.mode === 'finish'" class="muted dialog-tip">
      将执行两步：① 命令机器人保存地图并退出建图模式（等待完成）；② 用最终栅格创建 v2 地图并进入编辑。地图名两步共用。
    </div>
    <el-form label-width="90px" @submit.prevent>
      <el-form-item label="地图名">
        <el-input v-model="saveDialog.name" maxlength="64" placeholder="机器人侧与 v2 地图共用名" :disabled="finishState.running" />
      </el-form-item>
    </el-form>
    <div v-if="finishState.text" class="task-state">{{ finishState.text }}</div>
    <template #footer>
      <el-button :disabled="finishState.running" @click="saveDialog.visible = false">取消</el-button>
      <el-button type="primary" :loading="finishState.running" @click="onSaveConfirm">
        {{ saveDialog.mode === 'finish' ? '完成并保存' : '创建并编辑' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.mapping-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 12px;
}

.section-title {
  font-weight: 600;
  margin-bottom: 10px;
  color: #303133;
}

.btn-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.muted {
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}

.dialog-tip {
  margin-bottom: 12px;
  font-size: 12.5px;
  line-height: 1.6;
  color: #606266;
  background: #f5f7fa;
  border-radius: 6px;
  padding: 8px 10px;
}

.task-state {
  font-size: 12.5px;
  color: #409eff;
  margin-bottom: 6px;
}
</style>
