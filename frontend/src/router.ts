import { createRouter, createWebHistory } from 'vue-router'
import TicketListPage from './features/tickets/TicketListPage.vue'
import TicketCreatePage from './features/tickets/TicketCreatePage.vue'
import TicketDetailPage from './features/tickets/TicketDetailPage.vue'
import AgentWorkPage from './features/tickets/AgentWorkPage.vue'
import TeamWorkPage from './features/team/TeamWorkPage.vue'
import AdminPage from './features/admin/AdminPage.vue'
import NotificationPage from './features/notifications/NotificationPage.vue'
import StatisticsPage from './features/statistics/StatisticsPage.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/tickets' },
    { path: '/tickets', component: TicketListPage },
    { path: '/work', component: AgentWorkPage },
    { path: '/team', component: TeamWorkPage },
    { path: '/admin', component: AdminPage },
    { path: '/notifications', component: NotificationPage },
    { path: '/statistics', component: StatisticsPage },
    { path: '/tickets/new', component: TicketCreatePage },
    { path: '/tickets/:id(\\d+)', component: TicketDetailPage },
    { path: '/:pathMatch(.*)*', redirect: '/tickets' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
