<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Refresh } from '@element-plus/icons-vue'
import { getSystemConfig } from '@/api/system'
import type { SystemConfig } from '@/types/api'

const router = useRouter()
const config = ref<SystemConfig | null>(null)
const loading = ref(false)

const chargeItems = computed(() => {
  const labels: Record<string, string> = {
    service: '充电服务',
    target_battery_pct: '目标电量（%）',
    service_timeout_ms: '服务超时（ms）',
    confirm_timeout_ms: '遥测确认超时（ms）',
    dock_settle_min_ms: '到桩最小停稳（ms）',
    dock_settle_max_ms: '停稳等待上限（ms）',
    poll_interval_ms: '轮询周期（ms）',
    stale_grace_ms: '充电丢失宽限（ms）',
    max_duration_ms: '全流程看门狗（ms）',
  }
  if (!config.value) return []
  return Object.entries(config.value.charge).map(([key, value]) => ({
    key,
    label: labels[key] ?? key,
    value: String(value),
  }))
})

async function load() {
  loading.value = true
  try {
    config.value = await getSystemConfig()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载配置失败')
  } finally {
    loading.value = false
  }
}

function onOff(value: boolean) {
  return value ? '开启' : '关闭'
}

onMounted(load)
</script>

<template>
  <div class="config-page" v-loading="loading">
    <header class="page-header">
      <el-button :icon="ArrowLeft" @click="router.push('/')">返回主页</el-button>
      <span class="page-title">系统配置</span>
      <span class="spacer"></span>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </header>

    <div v-if="config" class="config-body">
      <!-- 接入状态总览 -->
      <section class="card">
        <h3>接入状态</h3>
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="RMS 对接">
            <el-tag :type="config.rms.enabled ? 'success' : 'info'" size="small">
              {{ config.rms.enabled ? '已开启' : '未开启' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="Zenoh 会话">
            <el-tag :type="config.rms.session_open ? 'success' : 'danger'" size="small">
              {{ config.rms.session_open ? '已建立' : '未建立' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="RMS 注册">
            <el-tag :type="config.rms.registered ? 'success' : 'warning'" size="small">
              {{ config.rms.registered ? '已注册' : '未注册' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="机器人编号">{{ config.rms.robot.robot_code || '（RMS 分配）' }}</el-descriptions-item>
          <el-descriptions-item label="机器人 SN">{{ config.rms.robot.robot_sn }}</el-descriptions-item>
          <el-descriptions-item label="机器人 IP">{{ config.rms.robot.ip }}</el-descriptions-item>
          <el-descriptions-item label="rosbridge 地址">{{ config.sim_agv.ws_url }}</el-descriptions-item>
          <el-descriptions-item label="rosbridge 连接">
            <el-tag :type="config.sim_agv.connected ? 'success' : 'danger'" size="small">
              {{ config.sim_agv.connected ? '已连接' : '未连接' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="服务端口">{{ config.app.server_port }}</el-descriptions-item>
        </el-descriptions>
      </section>

      <!-- RMS / Zenoh -->
      <section class="card">
        <h3>RMS · Zenoh 连接</h3>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="接入点（endpoints）">
            <el-tag
              v-for="ep in config.rms.zenoh.endpoints"
              :key="ep"
              size="small"
              type="primary"
              effect="plain"
              style="margin-right: 6px"
            >{{ ep }}</el-tag>
            <span v-if="!config.rms.zenoh.endpoints.length" class="warn">
              {{ config.rms.zenoh.config_error || '未解析到 endpoints' }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="namespace">{{ config.rms.zenoh.namespace || '（默认）' }}</el-descriptions-item>
          <el-descriptions-item label="模式">{{ config.rms.zenoh.mode || '—' }}</el-descriptions-item>
          <el-descriptions-item label="配置文件">{{ config.rms.zenoh.config_file }}</el-descriptions-item>
          <el-descriptions-item label="心跳周期">{{ config.rms.report.heartbeat_interval_ms }} ms</el-descriptions-item>
          <el-descriptions-item label="状态上报周期">{{ config.rms.report.status_interval_ms }} ms</el-descriptions-item>
          <el-descriptions-item label="查询超时">{{ config.rms.zenoh.query_timeout_ms }} ms</el-descriptions-item>
          <el-descriptions-item label="key 前缀">{{ config.rms.zenoh.robot_key_prefix || '（默认）' }}</el-descriptions-item>
          <el-descriptions-item label="充电点坐标">
            <span v-if="config.rms.behavior.charge_pose">
              ({{ config.rms.behavior.charge_pose.x }}, {{ config.rms.behavior.charge_pose.y }})
            </span>
            <span v-else class="warn">未配置（RMS 充电任务将回退报文目标）</span>
          </el-descriptions-item>
          <el-descriptions-item label="回家点坐标">
            <span v-if="config.rms.behavior.home_pose">
              ({{ config.rms.behavior.home_pose.x }}, {{ config.rms.behavior.home_pose.y }})
            </span>
            <span v-else class="warn">未配置</span>
          </el-descriptions-item>
        </el-descriptions>
      </section>

      <!-- 机器人连接与导航 -->
      <section class="card">
        <h3>机器人连接 · 导航</h3>
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="对接开关">
            {{ onOff(config.sim_agv.enabled) }}
          </el-descriptions-item>
          <el-descriptions-item label="状态新鲜度阈值">{{ config.sim_agv.status_fresh_ms }} ms</el-descriptions-item>
          <el-descriptions-item label="导航看门狗">{{ config.sim_agv.goal_timeout_s }} s</el-descriptions-item>
          <el-descriptions-item label="默认速度">{{ config.sim_agv.default_max_speed }} m/s</el-descriptions-item>
          <el-descriptions-item label="默认步长">{{ config.sim_agv.default_step }} m</el-descriptions-item>
          <el-descriptions-item label="起点纠偏半径">{{ config.sim_agv.follow_path_start_radius }} m</el-descriptions-item>
          <el-descriptions-item label="机械臂归位联锁">
            <el-tag :type="config.sim_agv.arm_gate_enabled ? 'success' : 'danger'" size="small">
              {{ onOff(config.sim_agv.arm_gate_enabled) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="未知臂态策略">
            {{ config.sim_agv.arm_strict_unknown ? '严格（拒绝移动）' : '放行' }}
          </el-descriptions-item>
          <el-descriptions-item label="归位容差">{{ config.sim_agv.arm_home_tolerance_rad }} rad</el-descriptions-item>
        </el-descriptions>
      </section>

      <!-- 话题监控 -->
      <section class="card">
        <h3>话题监控</h3>
        <el-table :data="config.sim_agv.topics" size="small" border>
          <el-table-column prop="label" label="用途" width="120" />
          <el-table-column prop="topic" label="话题" min-width="160" />
          <el-table-column prop="type" label="消息类型" min-width="220" />
          <el-table-column label="监控" width="90">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                {{ row.enabled ? '监控中' : '未开启' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-descriptions :column="3" border size="small" style="margin-top: 12px">
          <el-descriptions-item label="rosbridge 消息日志">
            {{ onOff(config.sim_agv.ws_message_log.enabled) }}
          </el-descriptions-item>
          <el-descriptions-item label="日志截断长度">{{ config.sim_agv.ws_message_log.max_chars }} 字符</el-descriptions-item>
          <el-descriptions-item label="话题数据节流">
            {{ config.sim_agv.ws_message_log.topic_interval_ms }} ms
          </el-descriptions-item>
        </el-descriptions>
      </section>

      <!-- 机器人服务 -->
      <section class="card">
        <h3>机器人服务</h3>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item v-for="(value, key) in config.sim_agv.services" :key="key" :label="String(key)">
            {{ value }}
          </el-descriptions-item>
        </el-descriptions>
      </section>

      <!-- 充电参数 -->
      <section class="card">
        <h3>充电参数</h3>
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item v-for="item in chargeItems" :key="item.key" :label="item.label">
            {{ item.value }}
          </el-descriptions-item>
        </el-descriptions>
      </section>
    </div>

    <el-empty v-else-if="!loading" description="暂无配置数据" />
  </div>
</template>

<style scoped>
.config-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  padding: 16px 20px;
  box-sizing: border-box;
  gap: 12px;
}

.page-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
}

.spacer {
  flex: 1;
}

.config-body {
  flex: 1;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.card {
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 12px 16px;
  background: var(--el-bg-color);
}

.card h3 {
  margin: 0 0 12px;
  font-size: 15px;
}

.warn {
  color: var(--el-color-warning);
  font-size: 13px;
}
</style>
