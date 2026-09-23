<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DataLine, More, Refresh } from '@element-plus/icons-vue'
import { useMapListStore } from '@/stores/mapList'
import { useTasksStore } from '@/stores/tasks'
import { fmtTime, MAP_STATUS_TAG } from '@/utils/format'

const router = useRouter()
const mapStore = useMapListStore()
const tasks = useTasksStore()

const saveDialog = reactive({ visible: false, name: '' })
const saving = ref(false)

const switchTask = computed(() =>
  tasks.currentMapTask && tasks.currentMapTask.type === 'SWITCH_MAP' && tasks.mapTaskInFlight
    ? tasks.currentMapTask
    : null,
)

const statusTag = (s: 'DRAFT' | 'ACTIVE' | 'ARCHIVED') => MAP_STATUS_TAG[s]

onMounted(() => {
  mapStore.load()
  mapStore.fetchLive()
  tasks.refresh()
})

// 切图任务结束后刷新列表
const stopWatch = watch(
  () => tasks.currentMapTask,
  (t, prev) => {
    if (!t || !prev) return
    if (t.type === 'SWITCH_MAP' && (t.status === 'SUCCEEDED' || t.status === 'FAILED')) {
      mapStore.load()
    }
  },
)
onBeforeUnmount(() => stopWatch())

function openEditor(id: number) {
  router.push({ name: 'editor', params: { id: String(id) } })
}

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

async function onSwitch(id: number, robotMapName: string | null) {
  await ElMessageBox.confirm(
    '将命令机器人加载该地图并重定位，期间机器人不可执行其他任务，确认继续？',
    '切换机器人地图',
    { type: 'warning', confirmButtonText: '切换', cancelButtonText: '取消' },
  )
  await mapStore.switchTo(id, robotMapName ?? undefined)
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
</script>

<template>
  <div class="home">
    <header class="home-header">
      <div class="home-title">
        <el-icon :size="22"><MapLocation /></el-icon>
        <h1>Nav Deployer 部署控制台</h1>
      </div>
      <div class="home-actions">
        <el-button type="primary" :icon="DataLine" @click="saveDialog.visible = true">保存实时图为新地图</el-button>
        <el-button :icon="Refresh" @click="mapStore.load(); mapStore.fetchLive()">刷新</el-button>
      </div>
    </header>

    <el-alert
      v-if="switchTask"
      class="switch-alert"
      type="warning"
      :closable="false"
      show-icon
    >
      <template #title>
        正在切换机器人地图「{{ switchTask.map_name ?? switchTask.robot_map_name }}」：
        {{ switchTask.status === 'RELOCATING' ? '机器人重定位中…' : '任务已下发…' }}
      </template>
    </el-alert>

    <main class="home-main">
      <div v-loading="mapStore.loading" class="map-grid">
        <el-empty v-if="!mapStore.loading && mapStore.maps.length === 0" description="还没有地图：先开始建图，或在机器人建图完成后保存入库" />

        <el-card v-for="m in mapStore.maps" :key="m.id" class="map-card" shadow="hover">
          <div class="map-card-head">
            <span class="map-name" :title="m.map_name">{{ m.map_name }}</span>
            <el-tag :type="statusTag(m.status).type" size="small">{{ statusTag(m.status).label }}</el-tag>
          </div>
          <div class="map-card-info">
            <div>分辨率：{{ m.resolution ?? '-' }} m/格</div>
            <div>尺寸：{{ m.width ?? '-' }} × {{ m.height ?? '-' }}</div>
            <div>机器人地图名：{{ m.robot_map_name || '-' }}</div>
            <div>更新时间：{{ fmtTime(m.updated_at) }}</div>
          </div>
          <template #footer>
            <div class="map-card-btns">
              <el-button type="primary" size="small" @click="openEditor(m.id)">编辑部署</el-button>
              <el-button
                size="small"
                :disabled="m.status === 'ACTIVE'"
                @click="onActivate(m.id)"
              >设为部署图</el-button>
              <el-button
                size="small"
                type="success"
                plain
                :disabled="!!switchTask"
                @click="onSwitch(m.id, m.robot_map_name)"
              >切换到机器人</el-button>
              <el-dropdown trigger="click" @command="(cmd: string) => (cmd === 'rename' ? onRename(m.id, m.map_name) : onDelete(m.id, m.map_name))">
                <el-button size="small" text :icon="More"></el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="rename">重命名</el-dropdown-item>
                    <el-dropdown-item command="delete" divided>
                      <span style="color: var(--el-color-danger)">删除</span>
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-card>
      </div>
    </main>

    <el-dialog v-model="saveDialog.visible" title="保存实时图为新地图" width="420px">
      <el-alert
        v-if="!mapStore.liveGrid"
        type="info"
        :closable="false"
        show-icon
        title="当前没有可用的实时地图（机器人尚未上传 /map）"
        style="margin-bottom: 12px"
      />
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
  </div>
</template>

<style scoped>
.home {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.home-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 24px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.home-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.home-title h1 {
  font-size: 18px;
  margin: 0;
  font-weight: 600;
}

.home-actions {
  display: flex;
  gap: 8px;
}

.switch-alert {
  margin: 12px 24px 0;
}

.home-main {
  flex: 1;
  overflow: auto;
  padding: 20px 24px;
}

.map-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
  min-height: 200px;
}

.map-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.map-name {
  font-size: 15px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.map-card-info {
  margin-top: 10px;
  display: grid;
  gap: 4px;
  font-size: 12.5px;
  color: #606266;
}

.map-card-btns {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}
</style>
