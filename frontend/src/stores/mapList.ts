import { ref } from 'vue'
import { defineStore } from 'pinia'
import {
  activateMap,
  deleteMap,
  getLiveGrid,
  listMaps,
  renameMap,
  saveLiveMap,
  switchMap,
} from '@/api/maps'
import type { MapGridVO, NavMapVO } from '@/types/api'

export const useMapListStore = defineStore('mapList', () => {
  const maps = ref<NavMapVO[]>([])
  const loading = ref(false)
  const liveGrid = ref<MapGridVO | null>(null)
  const liveGridAt = ref<string>('')

  async function load() {
    loading.value = true
    try {
      maps.value = await listMaps()
    } finally {
      loading.value = false
    }
  }

  /** 拉一次实时栅格；后端未收到 /map 时返回 400，静默处理 */
  async function fetchLive() {
    try {
      liveGrid.value = await getLiveGrid()
      liveGridAt.value = liveGrid.value.received_at ?? ''
    } catch {
      liveGrid.value = null
      liveGridAt.value = ''
    }
  }

  async function saveLiveAsMap(map_name: string) {
    const vo = await saveLiveMap(map_name)
    await load()
    return vo
  }

  async function rename(id: number, map_name: string) {
    await renameMap(id, map_name)
    await load()
  }

  async function remove(id: number) {
    await deleteMap(id)
    await load()
  }

  async function activate(id: number) {
    await activateMap(id)
    await load()
  }

  async function switchTo(id: number, robotMapName?: string) {
    await switchMap(id, robotMapName)
  }

  return { maps, loading, liveGrid, liveGridAt, load, fetchLive, saveLiveAsMap, rename, remove, activate, switchTo }
})
