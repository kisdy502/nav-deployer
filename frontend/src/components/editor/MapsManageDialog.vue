<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DataLine, Refresh } from '@element-plus/icons-vue'
import { useMapListStore } from '@/stores/mapList'
import { useTasksStore } from '@/stores/tasks'
import { useRobotStore } from '@/stores/robot'
import { fmtTime, MAP_STATUS_TAG } from '@/utils/format'

const visible = defineModel<boolean>({ required: true })

const emit = defineEmits<{
  (e: 'browse', id: number): void
}>()

const mapStore = useMapListStore()
const tasks = useTasksStore()
const robot = useRobotStore()

const saveDialog = reactive({ visible: false, name: '' })
const saving = ref(false)

const switchTask = computed(() =>
  tasks.currentMapTask && tasks.currentMapTask.type === 'SWITCH_MAP' && tasks.mapTaskInFlight
    ? tasks.currentMapTask
    : null,
)

const isCurrentRobotMap = (row: { map_name: string; robot_map_name: string | null }) => {
  const name = robot.status?.map_name
  return !!name && (name === row.map_name || name === row.robot_map_name)
}

onMounted(() => {
  mapStore.fetchLive()
})

watch(visible, (v) => {
  if (v) {
    mapStore.load()
    tasks.refresh()
    mapStore.fetchLive()
  }
})

async function onSaveLive() {
  const name = saveDialog.name.trim()
  if (!name) return
  saving.value = true
  try {
    const vo = await mapStore.saveLiveAsMap(name)
    ElMessage.success(`已保存为地图「${vo.map_name}」`)
    saveDialog.visible = false
    saveDialog.name = ''
  } finally {
    saving.value = false
  }
}

async function onActivate(id: number) {
  await mapStore.activate(id)
  ElMessage.success('已设为部署地图')
}

async function onSwitch(row: { id: number; robot_map_name: string | null; map_name: string }) {
  await ElMessageBox.confirm(
    '将命令机器人加载该地图并重定位，期间机器人不可执行其他任务，确认继续？',
    '切换机器人地图',
    { type: 'warning', confirmButtonText: '切换', cancelButtonText: '取消' },
  )
  await mapStore.switchTo(row.id, row.robot_map_name ?? undefined)
  ElMessage.info('切换任务已下发，等待机器人完成重定位…')
}

async function onRename(id: number, oldName: string) {
  const { value } = await ElMessageBox.prompt('输入新的地图名称', '重命名地图', {
    inputValue: oldName,
    inputPattern: /^\S{1,64}$/,
    inputErrorMessage: '1~64 个非空白字符',
  })
  await mapStore.rename(id, value.trim())
  ElMessage.success('已重命名')
}

async function onDelete(id: number, name: string) {
  await ElMessageBox.confirm(`确认删除地图「${name}」？该操作不可恢复。`, '删除地图', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消',
  })
  await mapStore.remove(id)
  ElMessage.success('已删除')
}

function browse(id: number) {
  visible.value = false
  emit('browse', id)
}
</script>

<template>
  <el-dialog v-model="visible" title="地图管理" width="780px" :append-to-body="true">
    <el-alert
      v-if="switchTask"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 10px"
      :title="`正在切换机器人地图「${switchTask.map_name ?? switchTask.robot_map_name}」：${switchTask.status === 'RELOCATING' ? '机器人重定位中…' : '任务已下发…'}`"
    />
    <div class="manage-actions">
      <el-button type="primary" :icon="DataLine" :disabled="!mapStore.liveGrid" @click="saveDialog.visible = true">保存实时图为新地图</el-button>
      <el-button :icon="Refresh" @click="mapStore.load(); mapStore.fetchLive()">刷新</el-button>
      <span v-if="!mapStore.liveGrid" class="muted">当前没有可用的实时地图（机器人尚未上传 /map）</span>
    </div>

    <el-table :data="mapStore.maps" size="small" max-height="380">
      <el-table-column label="地图名称" min-width="140">
        <template #default="{ row }">
          {{ row.map_name }}
          <el-tag v-if="isCurrentRobotMap(row)" size="small" type="success" effect="light">机器人当前</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="MAP_STATUS_TAG[row.status as 'DRAFT' | 'ACTIVE' | 'ARCHIVED'].type">
            {{ MAP_STATUS_TAG[row.status as 'DRAFT' | 'ACTIVE' | 'ARCHIVED'].label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="分辨率" width="90">
        <template #default="{ row }">{{ row.resolution ?? '-' }} m/格</template>
      </el-table-column>
      <el-table-column label="尺寸" width="90">
        <template #default="{ row }">{{ row.width ?? '-' }} × {{ row.height ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="更新时间" width="150">
        <template #default="{ row }">{{ fmtTime(row.updated_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="270" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="browse(row.id)">浏览</el-button>
          <el-button size="small" link :disabled="row.status === 'ACTIVE'" @click="onActivate(row.id)">设为部署图</el-button>
          <el-button size="small" link type="success" :disabled="!!switchTask" @click="onSwitch(row)">切换到机器人</el-button>
          <el-button size="small" link @click="onRename(row.id, row.map_name)">重命名</el-button>
          <el-button size="small" link type="danger" @click="onDelete(row.id, row.map_name)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="saveDialog.visible" title="保存实时图为新地图" width="420px" :append-to-body="true">
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="地图名称" required>
          <el-input v-model="saveDialog.name" maxlength="64" placeholder="1~64 字符" @keyup.enter="onSaveLive" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!mapStore.liveGrid" @click="onSaveLive">保存</el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<style scoped>
.manage-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.muted {
  font-size: 12px;
  color: #909399;
}
</style>
