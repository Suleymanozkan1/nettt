import { DAILY_REWARDS } from './economy';
import { dayDiff } from './missions';

export interface DailyState { streak: number; lastClaimDay: string | null; graceUsed: boolean }
export type DailyCheck =
  | { canClaim: false; reason: 'already_claimed'; next: DailyState }
  | { canClaim: true; next: DailyState; cycleDay: number; reward: { credits: number; gems: number } };

/**
 * Streak rules (non-punitive): claim on consecutive days → streak + 1. Miss exactly one day →
 * a single grace day keeps the streak. Longer gaps restart the 7-day cycle; nothing is taken away.
 */
export function checkDaily(state: DailyState, today: string): DailyCheck {
  if (state.lastClaimDay === today) return { canClaim: false, reason: 'already_claimed', next: state };
  let streak = 1;
  let graceUsed = false;
  if (state.lastClaimDay) {
    const gap = dayDiff(state.lastClaimDay, today);
    if (gap === 1) { streak = state.streak + 1; graceUsed = state.graceUsed; }
    else if (gap === 2 && !state.graceUsed) { streak = state.streak + 1; graceUsed = true; }
  }
  const cycleDay = ((streak - 1) % DAILY_REWARDS.length) + 1;
  if (cycleDay === 1) graceUsed = false;
  return { canClaim: true, cycleDay, reward: DAILY_REWARDS[cycleDay - 1]!, next: { streak, lastClaimDay: today, graceUsed } };
}
