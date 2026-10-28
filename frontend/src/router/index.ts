import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: () => import('@/views/HomeView.vue') },
    { path: '/tasks', name: 'tasks', component: () => import('@/views/TasksView.vue') },
    { path: '/config', name: 'config', component: () => import('@/views/ConfigView.vue') },
    { path: '/editor/:id', name: 'editor', component: () => import('@/views/EditorView.vue'), props: true },
    { path: '/map2/:id', name: 'map2-editor', component: () => import('@/views/MapEditor2View.vue'), props: true },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

export default router
