<script setup lang="ts">
import { computed, reactive } from 'vue'
import { useRouter } from 'vue-router'
import {
  Aim,
  Back,
  Location,
  Odometer,
  Pointer,
  Share,
  View,
} from '@element-plus/icons-vue'
import { useEditorStore, type EditorMode } from '@/stores/editor'
import { PATH_STATUS_TAG } from '@/utils/format'

const router = useRouter()
const editor = useEditorStore()

const pathPick = reactive({ visible: false, code: '', name: '', creating: false })

const modeButtons: { mode: EditorMode; label: string; icon: typeof Pointer }[] = [
  { mode: 'idle', label: '选择/平移', icon: Pointer },
  { mode: 'placePoint', label: '新建点位', icon: Location },
  { mode: 'drawPath', label: '编辑路线', icon: Share },
  { mode: 'initialPose', label: '重定位', icon: Aim },
  { mode: 'measure', label: '测量', icon: Odometer },
]

function onModeClick(mode: EditorMode) {
  if (mode === 'drawPath' && !editor.pathEdit) {
    pathPick.visible = true
    return
  }
  editor.setMode(mode)
}

async function onCreateAndEdit() {
  const code = pathPick.code.trim()
  if (!code) return
  pathPick.creating = true
  try {
    const vo = await editor.createPath(code, pathPick.name.trim() || undefined)
    pathPick.visible = false
    pathPick.code = ''
    pathPick.name = ''
    await editor.startPathEdit(vo.id)
  } catch {
    /* http 层已提示 */
  } finally {
    pathPick.creating = false
  }
}

async function onPickExisting(id: number) {
  try {
    await editor.startPathEdit(id)
    pathPick.visible = false
  } catch {
    /* http 层已提示 */
  }
}

function exitDraw() {
  editor.cancelPathEdit()
}

const mapName = computed(() => editor.mapInfo?.map_name ?? '')
</script>

<template>
  <div class="toolbar">
    <div class="toolbar-left">
      <el-button :icon="Back" text @click="router.push('/')">退出编辑</el-button>
      <el-divider direction="vertical" />
      <span class="map-title">{{ mapName }}</span>
      <el-tag v-if="editor.mapInfo" size="small" :type="editor.mapInfo.status === 'ACTIVE' ? 'success' : 'info'">
        {{ editor.mapInfo.status === 'ACTIVE' ? '部署中' : editor.mapInfo.status }}
      </el-tag>
    </div>

    <div class="toolbar-center">
      <el-radio-group :model-value="editor.mode" @change="(v: unknown) => onModeClick(v as EditorMode)">
        <el-radio-button v-for="b in modeButtons" :key="b.mode" :value="b.mode">
          <el-icon><component :is="b.icon" /></el-icon>
          {{ b.label }}
        </el-radio-button>
      </el-radio-group>
      <el-button v-if="editor.mode === 'drawPath'" type="warning" plain @click="exitDraw">退出路线编辑</el-button>
    </div>

    <div class="toolbar-right">
      <el-dropdown trigger="click">
        <el-button :icon="View">图层</el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item @click="editor.layers.map = !editor.layers.map">
              <el-checkbox :model-value="editor.layers.map" @click.prevent />地图
            </el-dropdown-item>
            <el-dropdown-item @click="editor.layers.points = !editor.layers.points">
              <el-checkbox :model-value="editor.layers.points" @click.prevent />点位
            </el-dropdown-item>
            <el-dropdown-item @click="editor.layers.paths = !editor.layers.paths">
              <el-checkbox :model-value="editor.layers.paths" @click.prevent />路线
            </el-dropdown-item>
            <el-dropdown-item @click="editor.layers.robot = !editor.layers.robot">
              <el-checkbox :model-value="editor.layers.robot" @click.prevent />机器人
            </el-dropdown-item>
            <el-dropdown-item @click="editor.layers.trail = !editor.layers.trail">
              <el-checkbox :model-value="editor.layers.trail" @click.prevent />轨迹
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>

  <el-dialog v-model="pathPick.visible" title="编辑路线" width="500px">
    <div class="pick-title">新建路线</div>
    <el-form :model="pathPick" inline label-width="60px" @submit.prevent>
      <el-form-item label="编码" required>
        <el-input v-model="pathPick.code" placeholder="如 ROUTE_A" style="width: 150px" />
      </el-form-item>
      <el-form-item label="名称">
        <el-input v-model="pathPick.name" placeholder="可选" style="width: 150px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="pathPick.creating" @click="onCreateAndEdit">创建并绘制</el-button>
      </el-form-item>
    </el-form>
    <el-divider />
    <div class="pick-title">选择已有路线继续编辑</div>
    <el-scrollbar max-height="220px">
      <div v-for="p in editor.paths" :key="p.id" class="pick-item" @click="onPickExisting(p.id)">
        <span>{{ p.path_code }}</span>
        <span class="pick-item-name">{{ p.path_name || '' }}</span>
        <el-tag size="small" :type="PATH_STATUS_TAG[p.status].type">{{ PATH_STATUS_TAG[p.status].label }}</el-tag>
      </div>
      <el-empty v-if="editor.paths.length === 0" description="暂无路线" :image-size="60" />
    </el-scrollbar>
  </el-dialog>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 12px;
  height: 100%;
}

.toolbar-left,
.toolbar-center,
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.map-title {
  font-weight: 600;
  font-size: 15px;
}

.pick-title {
  font-weight: 600;
  margin-bottom: 8px;
}

.pick-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
}

.pick-item:hover {
  background: #f5f7fa;
}

.pick-item-name {
  color: #909399;
  font-size: 12px;
  flex: 1;
}
</style>
