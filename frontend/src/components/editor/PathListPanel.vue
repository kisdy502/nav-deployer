<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CaretRight, Delete, Position, RefreshRight, Share, Switch, VideoPlay } from '@element-plus/icons-vue'
import { useEditorStore } from '@/stores/editor'
import { useRobotStore } from '@/stores/robot'
import { useTasksStore } from '@/stores/tasks'
import { PATH_STATUS_TAG } from '@/utils/format'

const emit = defineEmits<{
  (e: 'locate', payload: { points: { x: number; y: number }[] }): void
}>()

const editor = useEditorStore()
const robot = useRobotStore()
const tasks = useTasksStore()

const createDialog = reactive({ visible: false, saving: false, code: '', name: '' })
const loadedPaths = ref(new Set<number>())

async function openCreate() {
  createDialog.code = ''
  createDialog.name = ''
  createDialog.visible = true
}

async function saveCreate() {
  if (!createDialog.code.trim()) {
    ElMessage.warning('请输入路线编码')
    return
  }
  createDialog.saving = true
  try {
    const vo = await editor.createPath(createDialog.code.trim(), createDialog.name.trim() || undefined)
    createDialog.visible = false
    await editor.startPathEdit(vo.id)
    ElMessage.success('路线已创建，开始绘制：依次点击点位')
  } catch {
    /* http 层已提示 */
  } finally {
    createDialog.saving = false
  }
}

async function onSelect(id: number) {
  await editor.selectPath(id)
  await editor.ensurePathDetail(id)
  loadedPaths.value.add(id)
}

async function onEdit(id: number) {
  await editor.startPathEdit(id)
}

async function onDeploy(id: number) {
  await editor.deployPath(id)
  ElMessage.success('路线已部署')
}

async function onDisable(id: number) {
  await editor.updatePathMeta(id, { status: 'DISABLED' })
  ElMessage.success('路线已停用')
}

async function onFollow(id: number) {
  const p = editor.paths.find((x) => x.id === id)
  if (!p) return
  if (p.status !== 'DEPLOYED') {
    ElMessage.warning('只有已部署（DEPLOYED）的路线才能循线，请先部署')
    return
  }
  if (!robot.status?.pose_initialized) {
    ElMessage.warning('机器人位姿未初始化，无法循线')
    return
  }
  if (tasks.moveTaskInFlight) {
    ElMessage.warning('已有执行中的移动任务，请先取消')
    return
  }
  await tasks.followPath(id)
}

async function onDelete(id: number, code: string) {
  await ElMessageBox.confirm(`确认删除路线「${code}」？`, '删除路线', { type: 'warning' })
  await editor.deletePath(id)
  ElMessage.success('已删除')
}

function edgeCount(id: number): string {
  const d = editor.pathDetails[id]
  return d ? `${d.length} 条边` : '-'
}

function locate(id: number) {
  const detail = editor.pathDetails[id]
  if (!detail || !editor.pointsById) return
  const pts: { x: number; y: number }[] = []
  for (const e of detail) {
    const s = editor.pointsById.get(e.source_point_id)
    const t = editor.pointsById.get(e.target_point_id)
    if (s) pts.push({ x: s.x, y: s.y })
    if (t) pts.push({ x: t.x, y: t.y })
  }
  if (pts.length) emit('locate', { points: pts })
}

defineExpose({ openCreate })
</script>

<template>
  <div class="panel">
    <div class="panel-actions">
      <el-button type="primary" :icon="Share" @click="openCreate">新建路线</el-button>
      <span class="panel-tip">路线 = 点位之间的有序边链</span>
    </div>

    <el-scrollbar class="panel-list">
      <div
        v-for="p in editor.paths"
        :key="p.id"
        class="path-item"
        :class="{ selected: editor.selectedPathId === p.id, editing: editor.pathEdit?.pathId === p.id }"
        @click="onSelect(p.id)"
      >
        <div class="path-head">
          <span class="path-code">{{ p.path_code }}</span>
          <el-tag size="small" :type="PATH_STATUS_TAG[p.status].type">{{ PATH_STATUS_TAG[p.status].label }}</el-tag>
        </div>
        <div class="path-sub">
          {{ p.path_name || '未命名' }} · {{ edgeCount(p.id) }}
        </div>
        <div class="path-ops" @click.stop>
          <el-tooltip content="定位" placement="top">
            <el-button size="small" text circle :icon="Position" @click="locate(p.id)" />
          </el-tooltip>
          <el-tooltip content="编辑图形" placement="top">
            <el-button size="small" text circle type="warning" :icon="RefreshRight" @click="onEdit(p.id)" />
          </el-tooltip>
          <el-tooltip v-if="p.status !== 'DEPLOYED'" content="部署" placement="top">
            <el-button size="small" text circle type="success" :icon="VideoPlay" @click="onDeploy(p.id)" />
          </el-tooltip>
          <el-tooltip v-if="p.status === 'DEPLOYED'" content="停用" placement="top">
            <el-button size="small" text circle type="info" :icon="Switch" @click="onDisable(p.id)" />
          </el-tooltip>
          <el-tooltip content="循线" placement="top">
            <el-button size="small" text circle type="primary" :icon="CaretRight" @click="onFollow(p.id)" />
          </el-tooltip>
          <el-tooltip content="删除" placement="top">
            <el-button size="small" text circle type="danger" :icon="Delete" @click="onDelete(p.id, p.path_code)" />
          </el-tooltip>
        </div>
      </div>
      <el-empty v-if="editor.paths.length === 0" description="暂无路线" :image-size="60" />
    </el-scrollbar>

    <el-dialog v-model="createDialog.visible" title="新建路线" width="400px">
      <el-form :model="createDialog" label-width="80px" @submit.prevent>
        <el-form-item label="编码" required>
          <el-input v-model="createDialog.code" maxlength="64" placeholder="如 ROUTE_A" @keyup.enter="saveCreate" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="createDialog.name" maxlength="64" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="createDialog.saving" @click="saveCreate">创建并绘制</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.panel {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.panel-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px;
}

.panel-tip {
  font-size: 12px;
  color: #909399;
}

.panel-list {
  flex: 1;
}

.path-item {
  padding: 8px 12px;
  cursor: pointer;
  border-left: 3px solid transparent;
}

.path-item:hover {
  background: #f5f7fa;
}

.path-item.selected {
  background: #ecf5ff;
  border-left-color: #409eff;
}

.path-item.editing {
  background: #fdf6ec;
  border-left-color: #e6a23c;
}

.path-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.path-code {
  font-size: 13.5px;
  font-weight: 500;
}

.path-sub {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.path-ops {
  display: flex;
  gap: 2px;
  margin-top: 4px;
}
</style>
