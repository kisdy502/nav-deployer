<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile } from 'element-plus'
import { useMapEditor2Store } from '@/stores/mapEditor2'
import {
  createMapFromLiveV2,
  deleteMapV2,
  downloadMapPackageV2,
  importMapZipV2,
  listMapsV2,
  migrateLegacyMapV2,
  publishMapV2,
  replaceGridFromLiveV2,
} from '@/api/map2'
import { listMaps } from '@/api/maps'
import type { MapV2VO } from '@/types/map2'
import type { NavMapVO as LegacyMapVO } from '@/types/api'

/** v2 地图管理：从实时建图 / 导入新格式 zip / 迁移旧地图 / 发布 / 导出 / 删除。 */
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()
const router = useRouter()
const store = useMapEditor2Store()

const maps = ref<MapV2VO[]>([])
const legacyMaps = ref<LegacyMapVO[]>([])
const loading = ref(false)
const creating = ref(false)
const createName = ref('')
const importName = ref('')
const fileRef = ref<UploadFile | null>(null)
const migrateNavMapId = ref<number | null>(null)

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

async function refresh() {
  loading.value = true
  try {
    maps.value = await listMapsV2()
    legacyMaps.value = await listMaps()
  } finally {
    loading.value = false
  }
}

watch(visible, (v) => {
  if (v) void refresh()
})

async function createFromLive() {
  if (!createName.value.trim()) return ElMessage.warning('请输入地图名')
  creating.value = true
  try {
    await createMapFromLiveV2(createName.value.trim(), store.modifiedBy)
    ElMessage.success('已从实时栅格建图（含默认楼层 F1）')
    createName.value = ''
    await refresh()
  } finally {
    creating.value = false
  }
}

async function importZip(uploadFile: UploadFile) {
  const raw = uploadFile.raw
  if (!raw) return
  try {
    await importMapZipV2(raw, importName.value || undefined, store.modifiedBy)
    ElMessage.success('地图包导入成功')
    importName.value = ''
    fileRef.value = null
    await refresh()
  } finally {
    // 阻止 el-upload 自动上传
  }
}

function beforeUpload() {
  return false
}

async function migrate() {
  if (!migrateNavMapId.value) return ElMessage.warning('请选择旧地图')
  await migrateLegacyMapV2(migrateNavMapId.value, { modified_by: store.modifiedBy })
  ElMessage.success('旧地图已迁移为 v2 格式（点位类型映射 + 路线曲线保留）')
  migrateNavMapId.value = null
  await refresh()
}

function edit(map: MapV2VO) {
  visible.value = false
  void router.push({ name: 'map2-editor', params: { id: String(map.id) } })
}

async function publish(map: MapV2VO) {
  await ElMessageBox.confirm(`发布「${map.map_name}」为使用中（其他已发布地图将归档）？`, '发布', { type: 'warning' })
  await publishMapV2(map.id, store.modifiedBy)
  ElMessage.success('已发布')
  await refresh()
}

async function replaceGrid(map: MapV2VO) {
  await ElMessageBox.confirm(`用当前实时栅格替换「${map.map_name}」底图？元数据不变，版本 +1`, '替换底图', { type: 'warning' })
  await replaceGridFromLiveV2(map.id, store.modifiedBy)
  ElMessage.success('底图已替换')
  await refresh()
}

async function exportZip(map: MapV2VO) {
  await downloadMapPackageV2(map.id, `${map.map_name}.zip`)
  ElMessage.success('地图包已下载')
}

async function remove(map: MapV2VO) {
  await ElMessageBox.confirm(`删除地图「${map.map_name}」？楼层/点位/区域/货架/路线将一并删除`, '删除地图', { type: 'error' })
  await deleteMapV2(map.id)
  ElMessage.success('已删除')
  await refresh()
}

function statusTag(status: string) {
  return status === 'PUBLISHED' ? 'success' : status === 'ARCHIVED' ? 'info' : 'warning'
}
</script>

<template>
  <el-dialog v-model="visible" title="地图管理（v2 多楼层格式）" width="880px" top="6vh">
    <!-- 新建区 -->
    <div class="create-row">
      <el-input v-model="createName" placeholder="新地图名（从实时栅格建图）" style="width: 220px" clearable />
      <el-button type="primary" :loading="creating" @click="createFromLive">从实时建图</el-button>

      <el-divider direction="vertical" />

      <el-input v-model="importName" placeholder="导入名（留用包内名）" style="width: 180px" clearable />
      <el-upload :show-file-list="false" :auto-upload="false" :on-change="importZip" :before-upload="beforeUpload" accept=".zip">
        <el-button>导入新格式 zip</el-button>
      </el-upload>

      <el-divider direction="vertical" />

      <el-select v-model="migrateNavMapId" placeholder="选择旧版地图" style="width: 200px" clearable filterable>
        <el-option v-for="m in legacyMaps" :key="m.id" :value="m.id" :label="m.map_name" />
      </el-select>
      <el-button @click="migrate">迁移为 v2</el-button>
    </div>

    <el-table :data="maps" v-loading="loading" size="default" border>
      <el-table-column prop="map_name" label="地图" min-width="140" />
      <el-table-column prop="version" label="版本" width="90" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="source" label="来源" width="100" />
      <el-table-column label="栅格" width="110">
        <template #default="{ row }">{{ row.width }}×{{ row.height }}@{{ row.resolution }}</template>
      </el-table-column>
      <el-table-column prop="modified_by" label="修改人" width="110" />
      <el-table-column label="更新时间" width="160">
        <template #default="{ row }">{{ (row.updated_at ?? '').replace('T', ' ').slice(0, 19) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="edit(row)">编辑</el-button>
          <el-button size="small" type="success" link @click="publish(row)">发布</el-button>
          <el-button size="small" link @click="exportZip(row)">导出包</el-button>
          <el-button size="small" link @click="replaceGrid(row)">换底图</el-button>
          <el-button size="small" type="danger" link @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>

<style scoped>
.create-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
</style>
