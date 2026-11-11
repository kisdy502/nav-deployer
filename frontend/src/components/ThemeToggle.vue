<script setup lang="ts">
import { Moon, Sunny } from '@element-plus/icons-vue'
import { isDark, toggleTheme } from '@/utils/theme'
import { ref } from 'vue'

const dark = ref(isDark())

function onToggle() {
  toggleTheme()
  dark.value = isDark()
}
</script>

<template>
  <el-button :icon="dark ? Sunny : Moon" plain circle :title="dark ? '切换亮色' : '切换暗色'" @click="onToggle" />
</template>
