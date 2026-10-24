<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Aim,
  Back,
  Box,
  Crop,
  Download,
  FolderOpened,
  Location,
  Odometer,
  Pointer,
  Promotion,
  Share,
  Tickets,
  View,
} from '@element-plus/icons-vue'
import { useMapEditor2Store, type EditorTool } from '@/stores/mapEditor2'
import { publishMapV2, downloadMapPackageV2 } from '@/api/map2'

/**
 * v2 编辑器顶栏（分组 + 上图下字工具按钮，紧凑化）：
 * 左：退出 / 地图名·版本·状态 / 楼层切换 / 楼层操作下拉
 * 中：绘制工具组（图标在上、文字在下）
 * 右：图层 / 导出 / 地图管理 / 发布
 * （左右面板折叠由面板自身边缘控制，不占顶栏）
 */
const emit = defineEmits<{
  (e: 'open-floor-create'): void
  (e: 'open-floor-edit'): void
  (e: 'remove-floor'): void
  (e: 'open-manage'): void
}>()

const router = useRouter()
const store = useMapEditor2Store()

const toolButtons: { tool: EditorTool; label: string; title: string; icon: typeof Pointer }[] = [
  { tool: 'select', label: '选择', title: '选择/平移（Esc）', icon: Pointer },
  { tool: 'waypoint', label: '点位', title: '新建点位：点击地图放置', icon: Location },
  { tool: 'path', label: '路线', title: '画路线：依次点击点位成链', icon: Share },
  { tool: 'zone', label: '区域', title: '画区域：拖拽矩形或圈多边形', icon: Crop },
  { tool: 'shelf', label: '货架', title: '画货架：拖拽矩形或圈多边形', icon: Box },
  { tool: 'initialPose', label: '定位', title: '重定位：拖出新位姿后应用', icon: Aim },
  { tool: 'measure', label: '测量', title: '测量：点击两点量距', icon: Odometer },
]

const publishing = ref(false)

const statusTagType = computed(() =>
  store.map?.status === 'PUBLISHED' ? 'success' : store.map?.status === 'ARCHIVED' ? 'info' : 'warning',
)

function onFloorCommand(command: string) {
  if (command === 'create') emit('open-floor-create')
  else if (command === 'edit') emit('open-floor-edit')
  else if (command === 'delete') emit('remove-floor')
}

async function publish() {
  if (!store.map) return
  await ElMessageBox.confirm(
    `发布地图「${store.map.map_name}」为使用中（其他已发布地图将归档）？`,
    '发布地图',
    { type: 'warning' },
  )
  publishing.value = true
  try {
    await publishMapV2(store.map.id, store.modifiedBy)
    await store.reloadMap()
    ElMessage.success('已发布')
  } finally {
    publishing.value = false
  }
}

async function exportPackage() {
  if (!store.map) return
  await downloadMapPackageV2(store.map.id, `${store.map.map_name}.zip`)
  ElMessage.success('地图包已下载（map_info.json + global_map.pgm/png/yaml）')
}
</script>

<template>
  <div class="toolbar">
    <!-- 左：地图信息 + 楼层 -->
    <div class="toolbar-left">
      <el-button :icon="Back" text title="退出编辑" @click="router.push('/')" />
      <span class="map-title" :title="store.map?.map_name">{{ store.map?.map_name ?? '…' }}</span>
      <el-tag v-if="store.map" size="small" type="info">{{ store.map.version }}</el-tag>
      <el-tag v-if="store.map" size="small" :type="statusTagType">{{ store.map.status }}</el-tag>

      <el-divider direction="vertical" />

      <el-select
        :model-value="store.currentFloorId"
        size="small"
        style="width: 150px"
        placeholder="选择楼层"
        @update:model-value="(v: number) => store.selectFloor(v)"
      >
        <el-option
          v-for="bundle in store.floors"
          :key="bundle.floor.id"
          :value="bundle.floor.id"
          :label="`${bundle.floor.floor_code} · ${bundle.floor.name}`"
        />
      </el-select>
      <el-dropdown trigger="click" @command="onFloorCommand">
        <el-button size="small" circle title="楼层操作">
          <el-icon><Tickets /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="edit">楼层设置（当前楼层）</el-dropdown-item>
            <el-dropdown-item command="create">新建楼层</el-dropdown-item>
            <el-dropdown-item command="delete" divided>删除当前楼层</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <!-- 中：绘制工具（图标在上、文字在下） -->
    <div class="tool-group">
      <button
        v-for="b in toolButtons"
        :key="b.tool"
        class="tool-btn"
        :class="{ active: store.tool === b.tool }"
        :title="b.title"
        @click="store.setTool(b.tool)"
      >
        <el-icon :size="16"><component :is="b.icon" /></el-icon>
        <span>{{ b.label }}</span>
      </button>
    </div>

    <!-- 右：图层/导出/地图管理/发布（按钮加大：默认尺寸 32px 圆钮，易于点击） -->
    <div class="toolbar-right">
      <el-divider direction="vertical" />

      <el-dropdown trigger="click">
        <el-button circle title="图层显示">
          <el-icon :size="17"><View /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item @click="store.layers.map = !store.layers.map"><el-checkbox :model-value="store.layers.map" @click.prevent />地图</el-dropdown-item>
            <el-dropdown-item @click="store.layers.points = !store.layers.points"><el-checkbox :model-value="store.layers.points" @click.prevent />点位</el-dropdown-item>
            <el-dropdown-item @click="store.layers.paths = !store.layers.paths"><el-checkbox :model-value="store.layers.paths" @click.prevent />路线</el-dropdown-item>
            <el-dropdown-item @click="store.layers.zones = !store.layers.zones"><el-checkbox :model-value="store.layers.zones" @click.prevent />区域</el-dropdown-item>
            <el-dropdown-item @click="store.layers.shelves = !store.layers.shelves"><el-checkbox :model-value="store.layers.shelves" @click.prevent />货架</el-dropdown-item>
            <el-dropdown-item @click="store.layers.robot = !store.layers.robot"><el-checkbox :model-value="store.layers.robot" @click.prevent />机器人</el-dropdown-item>
            <el-dropdown-item @click="store.layers.trail = !store.layers.trail"><el-checkbox :model-value="store.layers.trail" @click.prevent />轨迹</el-dropdown-item>
            <el-dropdown-item @click="store.layers.scan = !store.layers.scan"><el-checkbox :model-value="store.layers.scan" @click.prevent />雷达点云</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

      <el-button circle title="导出地图包（map_info.json + global_map.pgm/png/yaml）" @click="exportPackage">
        <el-icon :size="17"><Download /></el-icon>
      </el-button>
      <el-button circle title="地图管理（导入/迁移/发布/删除）" @click="emit('open-manage')">
        <el-icon :size="17"><FolderOpened /></el-icon>
      </el-button>

      <el-button type="primary" :loading="publishing" title="发布为使用中（全局唯一）" @click="publish">
        <el-icon :size="17" style="margin-right: 4px"><Promotion /></el-icon>发布
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 0 10px;
  height: 100%;
  min-width: 0;
}

.toolbar-left,
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.map-title {
  font-weight: 600;
  font-size: 15px;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 绘制工具组：图标在上、文字在下 */
.tool-group {
  display: flex;
  align-items: stretch;
  gap: 2px;
  padding: 3px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  background: #fafbfc;
}

.tool-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  min-width: 46px;
  padding: 2px 5px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #606266;
  font-size: 11px;
  line-height: 1.2;
  cursor: pointer;
  white-space: nowrap;
}

.tool-btn:hover {
  background: #f0f7ff;
  color: #409eff;
}

.tool-btn.active {
  background: #ecf5ff;
  color: #409eff;
  font-weight: 600;
}
</style>
