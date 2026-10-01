import { Client, type Room } from 'colyseus.js';
import { api } from './api';

const URL = (import.meta.env.VITE_REALTIME_URL as string | undefined) ?? 'ws://localhost:2567';

export interface DuelPlayerView { name: string; character: string; score: number; fits: number; lives: number; combo: number; alive: boolean; connected: boolean }
export interface DuelStateView { phase: 'waiting' | 'countdown' | 'playing' | 'finished'; seed: number; winner: string; players: Map<string, DuelPlayerView> }
export interface DuelResult { ranking: { sessionId: string; name: string; score: number; fits: number }[]; winner: string | null; reward: number }

/** Joins (or creates) a live duel room on the Colyseus server, authenticated with the API token. */
export async function joinDuel(): Promise<Room<DuelStateView>> {
  const client = new Client(URL);
  client.auth.token = api.token() ?? '';
  return client.joinOrCreate<DuelStateView>('duel');
}
