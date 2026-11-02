<script setup lang="ts">
import { onMounted } from 'vue'
import { useRobotStore } from '@/stores/robot'
import { useTasksStore } from '@/stores/tasks'

const robot = useRobotStore()
onMounted(() => {
  robot.start()
  // 应用级兜底刷新：SSE 只推增量，页面刷新（F5）后必须拉一次当前任务状态；
  // 否则在未自行调用 refresh 的路由（如 v2 地图编辑器）上，进行中的充电/移动任务
  // 及其操作按钮（取消充电等）会消失，直到下一条 SSE 事件到达
  useTasksStore().refresh()
})
</script>

<template>
  <router-view />
</template>
