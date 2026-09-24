<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useEditorStore } from '@/stores/editor'
import { useRobotStore } from '@/stores/robot'
import { useTasksStore } from '@/stores/tasks'
import { useMapListStore } from '@/stores/mapList'
import { startMapping as startMappingApi, saveMapTask as saveMapTaskApi, getRecentMapTasks } from '@/api/tasks'
import { fmtTime, MAP_TASK_STATUS_TAG, MAP_TASK_TYPE_LABEL } from '@/utils/format'

const emit = defineEmits<{ (e: 'close'): void }>()

const editor = useEditorStore()
const robot = useRobotStore()
const tasks = useTasksStore()
const mapList = useMapListStore()

const recent = ref<Awaited<ReturnType<typeof getRecentMapTasks>>>([])
const saving = ref(false)
const saveDialog = reactive({ visible: false, name: '' })

const currentTask = computed(() => tasks.currentMapTask)
const inFlight = computed(() => tasks.mapTaskInFlight)
/** 机器人是否处于在线建图模式（/agv/status 的 mode，唯一事实源） */
const mappingMode = computed(() => robot.status?.mode === 'MAPPING')

async function loadRecent() {
  try {
    recent.value = await getRecentMapTasks(20)
  } catch {
    /* http 层已提示 */
  }
}

onMounted(() => {
  tasks.refresh()
  void loadRecent()
})

async function onStartMapping() {
  await ElMessageBox.confirm(
    '将命令机器人进入在线建图模式（导航任务会被禁止），确认继续？',
    '开始建图',
    { type: 'warning' },
  )
  const t = await startMappingApi()
  ElMessage.info('建图任务已下发…')
  void t
}

function openSaveDialog() {
  if (editor.mapInfo) saveDialog.name = `${editor.mapInfo.map_name}_new`
  saveDialog.visible = true
}

async function onSaveMap() {
  const name = saveDialog.name.trim()
  if (!name) return
  saving.value = true
  try {
    await saveMapTaskApi(name)
    saveDialog.visible = false
    ElMessage.info('保存任务已下发，机器人正在保存并重定位…')
  } catch {
    /* http 层已提示 */
  } finally {
    saving.value = false
  }
}

function toggleLive() {
  editor.livePolling ? editor.stopLivePolling() : editor.startLivePolling()
}

async function reloadCurrentMap() {
  if (editor.mapId) {
    await editor.loadMap(editor.mapId)
    ElMessage.success('已重新加载当前地图')
  }
  await mapList.load()
}
</script>

<template>
  <el-drawer title="建图" size="380px" :model-value="true" :append-to-body="true" @close="emit('close')">
    <div class="mapping">
      <div class="card">
        <div class="card-title">当前任务</div>
        <div v-if="mappingMode" class="task-line">
          <el-tag type="warning" effect="dark">建图中</el-tag>
          <span class="muted">机器人正在在线建图，实时预览已自动开启</span>
        </div>
        <template v-if="currentTask">
          <div class="task-line">
            <el-tag size="small">{{ MAP_TASK_TYPE_LABEL[currentTask.type] }}</el-tag>
            <el-tag size="small" :type="MAP_TASK_STATUS_TAG[currentTask.status].type" effect="dark">
              {{ MAP_TASK_STATUS_TAG[currentTask.status].label }}
            </el-tag>
            <span class="task-time">{{ fmtTime(currentTask.updated_at) }}</span>
          </div>
          <div v-if="currentTask.error_message" class="task-err">{{ currentTask.error_message }}</div>
        </template>
        <div v-else-if="!mappingMode" class="muted">暂无任务</div>

        <div class="btns">
          <el-button type="primary" :disabled="inFlight || mappingMode" @click="onStartMapping">开始建图</el-button>
          <el-button type="success" :disabled="inFlight" @click="openSaveDialog">保存并入库</el-button>
        </div>
        <div class="tip">
          建图期间实时预览自动开启（下方可手动开关）查看 /map 变化；保存成功后地图自动入库并激活，可在首页管理。
        </div>
      </div>

      <div class="card">
        <div class="card-title">实时预览</div>
        <div class="btns">
          <el-switch
            :model-value="editor.livePolling"
            active-text="预览中"
            inactive-text="已关闭"
            @change="toggleLive"
          />
          <el-button size="small" @click="reloadCurrentMap">重载当前地图</el-button>
        </div>
      </div>

      <div class="card">
        <div class="card-title">最近任务</div>
        <el-scrollbar max-height="260px">
          <div v-for="t in recent" :key="t.id" class="task-line recent">
            <el-tag size="small" effect="plain">{{ MAP_TASK_TYPE_LABEL[t.type] }}</el-tag>
            <el-tag size="small" :type="MAP_TASK_STATUS_TAG[t.status].type">
              {{ MAP_TASK_STATUS_TAG[t.status].label }}
            </el-tag>
            <span class="task-time">{{ fmtTime(t.created_at) }}</span>
          </div>
          <el-empty v-if="recent.length === 0" description="暂无记录" :image-size="48" />
        </el-scrollbar>
      </div>
    </div>

    <el-dialog v-model="saveDialog.visible" title="保存地图入库" width="400px" :append-to-body="true">
      <el-form label-width="80px" @submit.prevent>
        <el-form-item label="地图名称" required>
          <el-input v-model="saveDialog.name" maxlength="64" @keyup.enter="onSaveMap" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSaveMap">保存</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<style scoped>
.mapping {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.card {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 12px;
}

.card-title {
  font-weight: 600;
  margin-bottom: 10px;
}

.btns {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 10px 0;
}

.tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.task-line {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}

.task-line.recent {
  border-bottom: 1px dashed #ebeef5;
}

.task-time {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}

.task-err {
  color: #f56c6c;
  font-size: 12.5px;
  margin-top: 6px;
}

.muted {
  color: #909399;
  font-size: 13px;
}
</style>
