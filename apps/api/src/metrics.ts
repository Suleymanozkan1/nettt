import { Counter, Gauge, Registry, collectDefaultMetrics } from 'prom-client';

/** Prometheus metrics exposed on GET /metrics (API) and shared with the realtime server. */
export const registry = new Registry();
collectDefaultMetrics({ register: registry });

export const metrics = {
  httpRequests: new Counter({ name: 'golge_http_requests_total', help: 'HTTP requests', labelNames: ['route', 'status'], registers: [registry] }),
  runsFinished: new Counter({ name: 'golge_runs_finished_total', help: 'Verified runs', labelNames: ['mode'], registers: [registry] }),
  runsRejected: new Counter({ name: 'golge_runs_rejected_total', help: 'Rejected runs', labelNames: ['reason'], registers: [registry] }),
  creditsGranted: new Counter({ name: 'golge_credits_granted_total', help: 'Credits granted', labelNames: ['reason'], registers: [registry] }),
  rewardsPaused: new Gauge({ name: 'golge_rewards_paused', help: '1 while the economy circuit breaker is open', registers: [registry] }),
  duelRooms: new Gauge({ name: 'golge_duel_rooms', help: 'Open duel rooms', registers: [registry] }),
  duelMatches: new Counter({ name: 'golge_duel_matches_total', help: 'Finished duels', registers: [registry] }),
  pushSent: new Counter({ name: 'golge_push_sent_total', help: 'Push notifications sent via FCM', labelNames: ['result'], registers: [registry] }),
  duelRejectedTaps: new Counter({ name: 'golge_duel_rejected_taps_total', help: 'Duel taps outside the server time window', registers: [registry] }),
};
