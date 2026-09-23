<script setup lang="ts">
import { onMounted } from 'vue'
import { useRobotStore } from '@/stores/robot'

const robot = useRobotStore()
onMounted(() => robot.start())
</script>

<template>
  <router-view />
</template>
