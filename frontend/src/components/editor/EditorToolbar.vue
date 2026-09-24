<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Aim,
  Back,
  Check,
  Location,
  Odometer,
  Pointer,
  Share,
  View,
} from '@element-plus/icons-vue'
import { useEditorStore, type EditorMode } from '@/stores/editor'
import { nextAutoCode } from '@/utils/format'

const router = useRouter()
const editor = useEditorStore()

const modeButtons: { mode: EditorMode; label: string; icon: typeof Pointer }[] = [
  { mode: 'idle', label: '选择/平移', icon: Pointer },
  { mode: 'placePoint', label: '新建点位', icon: Location },
  { mode: 'drawPath', label: '编辑路线', icon: Share },
  { mode: 'initialPose', label: '重定位', icon: Aim },
  { mode: 'measure', label: '测量', icon: Odometer },
]

const creatingPath = ref(false)
const savingPath = ref(false)

async function onModeClick(mode: EditorMode) {
  if (mode !== 'drawPath') {
    editor.setMode(mode)
    return
  }
  // 已在绘制中：恢复模式即可
  if (editor.pathEdit) {
    editor.setMode('drawPath')
    return
  }
  // 免弹框：自动生成路线编码，直接创建并进入绘制（点击起点 → 终点 → 保存）
  if (creatingPath.value) return
  creatingPath.value = true
  try {
    const code = nextAutoCode(editor.paths.map((p) => p.path_code), 'ROUTE_')
    const vo = await editor.createPath(code)
    await editor.startPathEdit(vo.id)
    ElMessage.success(`路线「${code}」已创建：依次点击起点、终点等点位，完成后点「保存路线」`)
  } catch {
    /* http 层已提示 */
  } finally {
    creatingPath.value = false
  }
}

async function onSavePath() {
  savingPath.value = true
  try {
    await editor.savePathEdit()
    ElMessage.success('路线已保存')
  } catch (err) {
    if (err instanceof Error && err.message) ElMessage.warning(err.message)
  } finally {
    savingPath.value = false
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
      <el-button v-if="editor.mode === 'drawPath' && editor.pathEdit" type="primary" :icon="Check" :loading="savingPath" @click="onSavePath">保存路线</el-button>
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
            <el-dropdown-item @click="editor.layers.scan = !editor.layers.scan">
              <el-checkbox :model-value="editor.layers.scan" @click.prevent />雷达点云
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
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
</style>
