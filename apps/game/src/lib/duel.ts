import { Client, type Room } from 'colyseus.js';
import { api } from './api';

const URL = (import.meta.env.VITE_REALTIME_URL as string | undefined) ?? 'ws://localhost:2567';

export interface DuelPlayerView { name: string; character: string; score: number; fits: number; lives: number; combo: number; alive: boolean; connected: boolean }
export interface DuelStateView { phase: 'waiting' | 'countdown' | 'playing' | 'finished'; seed: number; winner: string; players: Map<string, DuelPlayerView> }
export interface DuelResult { ranking: { sessionId: string; name: string; score: number; fits: number }[]; winner: string | null; reward: number }

export type DuelJoin = { kind: 'quick' } | { kind: 'invite' } | { kind: 'code'; code: string };

/**
 * Connects to the Colyseus duel server, authenticated with the API token:
 * quick match (joinOrCreate), a new private room to invite a friend, or a private room by invite code.
 */
export async function joinDuel(how: DuelJoin = { kind: 'quick' }): Promise<Room<DuelStateView>> {
  const client = new Client(URL);
  client.auth.token = api.token() ?? '';
  if (how.kind === 'invite') return client.create<DuelStateView>('duel', { private: true });
  if (how.kind === 'code') return client.joinById<DuelStateView>(how.code);
  return client.joinOrCreate<DuelStateView>('duel');
}
