import { defineStore } from 'pinia'
import { shallowRef } from 'vue'
import { ElMessage } from 'element-plus'
import * as mapsApi from '@/api/maps'
import type {
  ConnSaveDTO,
  ConnV2VO,
  FloorBundleVO,
  FloorSaveDTO,
  FloorVO,
  MapFullVO,
  MapV2VO,
  PathSaveDTO,
  PathV2VO,
  ShelfSaveDTO,
  ShelfV2VO,
  WaypointSaveDTO,
  WaypointV2VO,
  ZoneSaveDTO,
  ZoneV2VO,
} from '@/types/map2'
import { WAYPOINT_CODE_PREFIX } from '@/types/map2'
import type { MapGridVO } from '@/types/api'
import {
  createConnV2,
  createFloorV2,
  createPathV2,
  createShelfV2,
  createWaypointV2,
  createZoneV2,
  deleteConnV2,
  deleteFloorV2,
  deletePathV2,
  deleteShelfV2,
  deleteWaypointV2,
  deleteZoneV2,
  getMapFullV2,
  getMapGridV2,
  updateConnV2,
  updateFloorV2,
  updatePathV2,
  updateShelfV2,
  updateWaypointV2,
  updateZoneV2,
} from '@/api/map2'

export type EditorTool = 'select' | 'waypoint' | 'path' | 'zone' | 'shelf' | 'measure' | 'initialPose'
export type SelectionKind = 'waypoint' | 'zone' | 'shelf' | 'path' | 'conn'

export interface XY {
  x: number
  y: number
}

/** v2 多楼层地图编辑器状态：全量树 + 当前楼层 + 工具与草稿。 */
export const useMapEditor2Store = defineStore('mapEditor2', {
  state: () => ({
    mapId: null as number | null,
    map: null as MapV2VO | null,
    floors: [] as FloorBundleVO[],
    connections: [] as ConnV2VO[],
    // 栅格 data 可达百万元素：shallowRef 整帧替换，禁止深响应式代理
    grid: shallowRef<MapGridVO | null>(null),
    currentFloorId: null as number | null,
    selection: null as { kind: SelectionKind; id: number } | null,
    tool: 'select' as EditorTool,
    /** 画路线草稿：途经点位 id 链 */
    draftPath: [] as number[],
    /** 画区域/货架草稿：顶点（世界坐标） */
    draftVertices: [] as number[][],
    /** 测量：两点 */
    measure: { a: null as XY | null, b: null as XY | null },
    /** 图层可见性（与 v1 编辑器一致的开关） */
    layers: {
      map: true,
      points: true,
      paths: true,
      zones: true,
      shelves: true,
      robot: true,
      trail: true,
      scan: true,
    },
    /** 建图实时预览（/map 轮询，与 v1 编辑器一致：预览中禁止编辑元数据） */
    livePreview: false,
    liveGrid: shallowRef<MapGridVO | null>(null),
    /** 修改人：暂无用户系统，固定 admin；接入登录系统后改为读当前账户 */
    modifiedBy: 'admin',
    loading: false,
  }),

  getters: {
    currentBundle(state): FloorBundleVO | null {
      return state.floors.find((b) => b.floor.id === state.currentFloorId) ?? null
    },
    currentFloor(): FloorVO | null {
      return this.currentBundle?.floor ?? null
    },
    waypoints(): WaypointV2VO[] {
      return this.currentBundle?.waypoints ?? []
    },
    zones(): ZoneV2VO[] {
      return this.currentBundle?.zones ?? []
    },
    shelves(): ShelfV2VO[] {
      return this.currentBundle?.shelves ?? []
    },
    paths(): PathV2VO[] {
      return this.currentBundle?.paths ?? []
    },
    waypointById(state): Map<number, WaypointV2VO> {
      const map = new Map<number, WaypointV2VO>()
      for (const bundle of state.floors) {
        for (const w of bundle.waypoints) map.set(w.id, w)
      }
      return map
    },
    selectedWaypoint(): WaypointV2VO | null {
      return this.selection?.kind === 'waypoint'
        ? this.waypointById.get(this.selection.id) ?? null
        : null
    },
    selectedZone(): ZoneV2VO | null {
      return this.selection?.kind === 'zone'
        ? this.zones.find((z) => z.id === this.selection!.id) ?? null
        : null
    },
    selectedShelf(): ShelfV2VO | null {
      return this.selection?.kind === 'shelf'
        ? this.shelves.find((s) => s.id === this.selection!.id) ?? null
        : null
    },
    selectedPath(): PathV2VO | null {
      return this.selection?.kind === 'path'
        ? this.paths.find((p) => p.id === this.selection!.id) ?? null
        : null
    },
    selectedConn(state): ConnV2VO | null {
      return state.selection?.kind === 'conn'
        ? state.connections.find((c) => c.id === state.selection!.id) ?? null
        : null
    },
  },

  actions: {
    async loadMap(id: number) {
      this.loading = true
      try {
        const [full, grid] = await Promise.all([getMapFullV2(id), getMapGridV2(id)])
        this.applyFull(full)
        this.grid = grid
        if (!this.floors.some((b) => b.floor.id === this.currentFloorId)) {
          this.currentFloorId = this.floors[0]?.floor.id ?? null
        }
        this.selection = null
        this.cancelDraft()
      } finally {
        this.loading = false
      }
    },

    /** 保存动作后按返回 VO 局部刷新（后端保证返回完整 VO）。 */
    async reloadMap() {
      if (!this.mapId) return
      const full = await getMapFullV2(this.mapId)
      this.applyFull(full)
    },

    applyFull(full: MapFullVO) {
      this.mapId = full.map.id
      this.map = full.map
      this.floors = full.floors
      this.connections = full.connections
    },

    selectFloor(floorId: number) {
      this.currentFloorId = floorId
      this.selection = null
      this.cancelDraft()
    },

    select(kind: SelectionKind, id: number | null) {
      this.selection = id == null ? null : { kind, id }
    },

    setTool(tool: EditorTool) {
      this.tool = tool
      this.cancelDraft()
      if (tool !== 'measure') this.measure = { a: null, b: null }
    },

    cancelDraft() {
      this.draftPath = []
      this.draftVertices = []
    },

    // ---------- 建图实时预览（与 v1 编辑器一致） ----------

    /** 建图实时预览中禁止创建/修改地图元素（画布坐标是实时图，写入的却是已入库地图）。 */
    assertNotLivePreview() {
      if (this.livePreview) {
        ElMessage.warning('建图实时预览中，不能编辑地图元素；请先保存地图或退出预览')
        throw new Error('live preview active')
      }
    },

    startLivePreview() {
      if (this.livePreview) return
      this.livePreview = true
      this.cancelDraft()
      this.selection = null
      if (this.tool !== 'select') this.setTool('select')
      const tick = async () => {
        if (liveRequestInFlight) return
        liveRequestInFlight = true
        try {
          const g = await mapsApi.getLiveGrid()
          if (!g.received_at || g.received_at !== this.liveGrid?.received_at) {
            this.liveGrid = g
          }
        } catch {
          /* 后端暂无实时图，忽略 */
        } finally {
          liveRequestInFlight = false
        }
      }
      void tick()
      // 3s 轮询：机器人侧 /map 每 5s 一帧，更高频率只会拉到重复帧
      liveTimer = window.setInterval(tick, 3000)
    },

    stopLivePreview() {
      if (liveTimer) window.clearInterval(liveTimer)
      liveTimer = undefined
      this.livePreview = false
    },

    popDraftPath() {
      this.draftPath.pop()
    },

    popDraftVertex() {
      this.draftVertices.pop()
    },

    /** 提交路线草稿：途经点位链（≥2）→ 创建路线（段自动生成为直线）。 */
    async submitDraftPath() {
      try {
        if (this.draftPath.length >= 2) {
          await this.addPath({ waypoint_ids: [...this.draftPath] })
        }
      } finally {
        this.cancelDraft()
      }
    },

    /** 提交区域/货架草稿多边形（≥3 顶点）。 */
    async submitDraftPolygon(kind: 'zone' | 'shelf') {
      try {
        if (this.draftVertices.length >= 3) {
          const vertices = this.draftVertices.map((v) => [+v[0].toFixed(4), +v[1].toFixed(4)])
          if (kind === 'zone') {
            await this.addZone({ vertices, zone_type: 'slowDown', priority: 2, max_speed: 0.3 })
          } else {
            await this.addShelf({ vertices, height: 1.8, layers: [] })
          }
        }
      } finally {
        this.cancelDraft()
      }
    },

    /** 拖拽矩形快捷创建（区域/货架最常见形状）。 */
    async submitRect(kind: 'zone' | 'shelf', a: { x: number; y: number }, b: { x: number; y: number }) {
      const vertices = [
        [a.x, a.y],
        [b.x, a.y],
        [b.x, b.y],
        [a.x, b.y],
      ].map(([x, y]) => [+x.toFixed(4), +y.toFixed(4)])
      if (kind === 'zone') {
        await this.addZone({ vertices, zone_type: 'slowDown', priority: 2, max_speed: 0.3 })
      } else {
        await this.addShelf({ vertices, height: 1.8, layers: [] })
      }
    },

    // ---------- 楼层 ----------

    async saveFloor(dto: FloorSaveDTO, floorId?: number) {
      const floor = floorId ? await updateFloorV2(floorId, dto) : await createFloorV2(dto)
      await this.reloadMap()
      if (!floorId) this.currentFloorId = floor.id
      ElMessage.success(floorId ? '楼层已更新' : '楼层已创建')
      return floor
    },

    async removeFloor(floorId: number) {
      await deleteFloorV2(floorId)
      await this.reloadMap()
      if (this.currentFloorId === floorId) {
        this.currentFloorId = this.floors[0]?.floor.id ?? null
      }
      this.selection = null
      ElMessage.success('楼层已删除')
    },

    // ---------- 点位 ----------

    /** 按类型前缀生成图内唯一编码：P1/P2…（延续 v1 的首字母区分逻辑，见 WAYPOINT_CODE_PREFIX）。 */
    nextWaypointCode(type: import('@/types/map2').WaypointType = 'path'): string {
      const prefix = WAYPOINT_CODE_PREFIX[type] ?? 'P'
      const used = new Set<string>()
      for (const bundle of this.floors) {
        for (const w of bundle.waypoints) used.add(w.waypoint_code)
      }
      let seq = 1
      while (used.has(`${prefix}${seq}`)) seq++
      return `${prefix}${seq}`
    },

    async addWaypoint(
      x: number,
      y: number,
      yaw = 0,
      opts?: { waypoint_code?: string; name?: string; wp_type?: import('@/types/map2').WaypointType },
    ) {
      this.assertNotLivePreview()
      if (!this.map || !this.currentFloor) return
      const wpType = opts?.wp_type ?? 'path'
      const code = opts?.waypoint_code?.trim() || this.nextWaypointCode(wpType)
      const created = await createWaypointV2({
        map_id: this.map.id,
        floor_id: this.currentFloor.id,
        waypoint_code: code,
        name: opts?.name?.trim() || code,
        wp_type: wpType,
        x,
        y,
        yaw,
        modified_by: this.modifiedBy,
      })
      this.currentBundle?.waypoints.push(created)
      if (this.map) this.map.version = bumpVersion(this.map.version)
      this.select('waypoint', created.id)
      return created
    },

    async patchWaypoint(id: number, dto: WaypointSaveDTO) {
      const updated = await updateWaypointV2(id, dto)
      this.replaceWaypoint(updated)
      return updated
    },

    replaceWaypoint(waypoint: WaypointV2VO) {
      const bundle = this.floors.find((b) => b.floor.id === waypoint.floor_id)
      if (!bundle) return
      const index = bundle.waypoints.findIndex((w) => w.id === waypoint.id)
      if (index >= 0) bundle.waypoints[index] = waypoint
      else bundle.waypoints.push(waypoint)
    },

    async removeWaypoint(id: number) {
      await deleteWaypointV2(id)
      for (const bundle of this.floors) {
        bundle.waypoints = bundle.waypoints.filter((w) => w.id !== id)
      }
      if (this.selection?.kind === 'waypoint' && this.selection.id === id) this.selection = null
      ElMessage.success('点位已删除')
    },

    // ---------- 区域 ----------

    nextCode(prefix: string): string {
      const used = new Set<string>()
      for (const bundle of this.floors) {
        for (const z of bundle.zones) used.add(z.zone_code)
        for (const s of bundle.shelves) used.add(s.shelf_code)
        for (const p of bundle.paths) used.add(p.path_code)
      }
      for (const c of this.connections) used.add(c.conn_code)
      let seq = 1
      while (used.has(`${prefix}_${String(seq).padStart(3, '0')}`)) seq++
      return `${prefix}_${String(seq).padStart(3, '0')}`
    },

    async addZone(dto: ZoneSaveDTO) {
      this.assertNotLivePreview()
      if (!this.map || !this.currentFloor) return
      const zoneCode = dto.zone_code ?? this.nextCode('ZC')
      const name = dto.name?.trim() || zoneCode
      const created = await createZoneV2({
        map_id: this.map.id,
        floor_id: this.currentFloor.id,
        ...dto,
        zone_code: zoneCode,
        name,
        modified_by: this.modifiedBy,
      })
      this.currentBundle?.zones.push(created)
      this.select('zone', created.id)
      return created
    },

    async patchZone(id: number, dto: ZoneSaveDTO) {
      const updated = await updateZoneV2(id, dto)
      const bundle = this.floors.find((b) => b.floor.id === updated.floor_id)
      if (bundle) {
        const index = bundle.zones.findIndex((z) => z.id === id)
        if (index >= 0) bundle.zones[index] = updated
      }
      return updated
    },

    /** 顶点拖拽/整体平移后保存区域轮廓。 */
    async saveZoneVertices(id: number, vertices: number[][]) {
      await this.patchZone(id, {
        vertices: vertices.map(([x, y]) => [+x.toFixed(4), +y.toFixed(4)]),
        modified_by: this.modifiedBy,
      })
    },

    /** 顶点拖拽/整体平移后保存货架轮廓（整树：层/格位一并提交）。 */
    async saveShelfVertices(id: number, vertices: number[][]) {
      const shelf = this.shelves.find((s) => s.id === id)
      if (!shelf) return
      await this.patchShelf(id, {
        name: shelf.name,
        height: shelf.height,
        vertices: vertices.map(([x, y]) => [+x.toFixed(4), +y.toFixed(4)]),
        layers: (shelf.layers ?? []).map((layer) => ({
          layer_index: layer.layer_index,
          elevation: layer.elevation,
          access_edge: layer.access_edge,
          slots: (layer.slots ?? []).map((slot) => ({
            slot_index: slot.slot_index,
            position: slot.position,
            size: slot.size,
            product_id: slot.product_id,
            product_name: slot.product_name,
          })),
        })),
        modified_by: this.modifiedBy,
      })
      // 层/格位 id 整树重建，刷新本地引用
      await this.reloadMap()
    },

    async removeZone(id: number) {
      await deleteZoneV2(id)
      if (this.currentBundle) {
        this.currentBundle.zones = this.currentBundle.zones.filter((z) => z.id !== id)
      }
      if (this.selection?.kind === 'zone' && this.selection.id === id) this.selection = null
      ElMessage.success('区域已删除')
    },

    // ---------- 货架 ----------

    async addShelf(dto: ShelfSaveDTO) {
      this.assertNotLivePreview()
      if (!this.map || !this.currentFloor) return
      const created = await createShelfV2({
        map_id: this.map.id,
        floor_id: this.currentFloor.id,
        shelf_code: dto.shelf_code ?? this.nextCode('SH'),
        modified_by: this.modifiedBy,
        ...dto,
      })
      this.currentBundle?.shelves.push(created)
      this.select('shelf', created.id)
      return created
    },

    async patchShelf(id: number, dto: ShelfSaveDTO) {
      const updated = await updateShelfV2(id, dto)
      const bundle = this.floors.find((b) => b.floor.id === updated.floor_id)
      if (bundle) {
        const index = bundle.shelves.findIndex((s) => s.id === id)
        if (index >= 0) bundle.shelves[index] = updated
      }
      return updated
    },

    async removeShelf(id: number) {
      await deleteShelfV2(id)
      if (this.currentBundle) {
        this.currentBundle.shelves = this.currentBundle.shelves.filter((s) => s.id !== id)
      }
      if (this.selection?.kind === 'shelf' && this.selection.id === id) this.selection = null
      ElMessage.success('货架已删除')
    },

    // ---------- 路线 ----------

    async addPath(dto: PathSaveDTO) {
      this.assertNotLivePreview()
      if (!this.map || !this.currentFloor) return
      const created = await createPathV2({
        map_id: this.map.id,
        floor_id: this.currentFloor.id,
        path_code: dto.path_code ?? this.nextCode('PATH'),
        modified_by: this.modifiedBy,
        ...dto,
      })
      this.currentBundle?.paths.push(created)
      this.select('path', created.id)
      return created
    },

    async patchPath(id: number, dto: PathSaveDTO) {
      const updated = await updatePathV2(id, dto)
      const bundle = this.floors.find((b) => b.floor.id === updated.floor_id)
      if (bundle) {
        const index = bundle.paths.findIndex((p) => p.id === id)
        if (index >= 0) bundle.paths[index] = updated
      }
      return updated
    },

    /** 路线状态快捷切换：DEPLOYED 部署 / DISABLED 停用 / DRAFT 恢复草稿（v1 同款快捷按钮）。 */
    async setPathStatus(id: number, status: 'DEPLOYED' | 'DISABLED' | 'DRAFT') {
      await this.patchPath(id, { status, modified_by: this.modifiedBy })
    },

    async removePath(id: number) {
      await deletePathV2(id)
      if (this.currentBundle) {
        this.currentBundle.paths = this.currentBundle.paths.filter((p) => p.id !== id)
      }
      if (this.selection?.kind === 'path' && this.selection.id === id) this.selection = null
      ElMessage.success('路线已删除')
    },

    // ---------- 楼层间连接 ----------

    async addConn(dto: ConnSaveDTO) {
      if (!this.map) return
      const created = await createConnV2({
        map_id: this.map.id,
        conn_code: dto.conn_code ?? this.nextCode('CONN'),
        modified_by: this.modifiedBy,
        ...dto,
      })
      this.connections.push(created)
      this.select('conn', created.id)
      return created
    },

    async patchConn(id: number, dto: ConnSaveDTO) {
      const updated = await updateConnV2(id, dto)
      const index = this.connections.findIndex((c) => c.id === id)
      if (index >= 0) this.connections[index] = updated
      return updated
    },

    async removeConn(id: number) {
      await deleteConnV2(id)
      this.connections = this.connections.filter((c) => c.id !== id)
      if (this.selection?.kind === 'conn' && this.selection.id === id) this.selection = null
      ElMessage.success('连接已删除')
    },
  },
})

// 实时预览轮询定时器（模块级，不进响应式 state）
let liveTimer: number | undefined
let liveRequestInFlight = false

/** 版本号乐观本地自增（真实版本以服务端返回为准，reload 后即校正）。 */
function bumpVersion(version: string): string {
  const match = /^v(\d+)\.(\d+)\.(\d+)$/.exec(version)
  if (!match) return version
  let major = Number(match[1])
  let minor = Number(match[2])
  let patch = Number(match[3]) + 1
  if (patch > 9) {
    patch = 0
    minor++
  }
  if (minor > 9) {
    minor = 0
    major++
  }
  return `v${major}.${minor}.${patch}`
}
