// Client for the conversation hub ("second brain") backend (/v1/history/*).
//
// The hub shows EVERY conversation across surfaces — desktop voice, phone voice,
// and typed chat — from the central archive the voice sidecar maintains. Typed
// chats live in localStorage as the source of truth; we additionally sync them
// up here (fire-and-forget) so they appear alongside the voice conversations.
import { apiFetch } from './api';
import type { Conversation } from '../types';

export type HistorySource = 'phone-voice' | 'desktop-voice' | 'typed-chat';

export interface HistoryConversation {
  id: string;
  source: HistorySource;
  title: string;
  started_at: number;
  ended_at: number;
  msg_count: number;
  tool_count: number;
  total_tokens: number;
  snippet?: string;
}

export interface HistoryMessage {
  seq: number;
  // user/assistant/sms = spoken/typed turns; delegation/tool = hand-offs + their results.
  role: 'user' | 'assistant' | 'sms' | 'delegation' | 'tool' | 'system';
  ts: number;
  text: string;
  meta: {
    tool?: string;
    target?: string;
    args?: string;
    ok?: boolean;
    detail?: string;
    status?: string;
    usage?: { total_tokens?: number };
    from?: string;
    // Delegation (jarvis_agent) step tree — the per-brain waterfall.
    task?: string;
    deleg_id?: string;
    brain?: string;
    result?: string;
    failures?: string[];
    total_latency_ms?: number;
    total_tokens?: number;
    steps?: {
      brain?: string;
      phase?: 'try' | 'fail' | 'answer';
      detail?: string;
      ok?: boolean;
      latency_ms?: number;
      tokens?: number;
    }[];
  };
}

export interface HistoryConversationDetail extends HistoryConversation {
  messages: HistoryMessage[];
}

export async function listHistory(source?: HistorySource): Promise<HistoryConversation[]> {
  const q = source ? `?source=${encodeURIComponent(source)}&limit=300` : '?limit=300';
  const res = await apiFetch(`/v1/history/conversations${q}`);
  if (!res.ok) throw new Error(`history list failed: ${res.status}`);
  const data = await res.json();
  return data.conversations ?? [];
}

export async function getHistory(convId: string): Promise<HistoryConversationDetail> {
  const res = await apiFetch(`/v1/history/conversations/${encodeURIComponent(convId)}`);
  if (!res.ok) throw new Error(`history get failed: ${res.status}`);
  return res.json();
}

export async function searchHistory(query: string): Promise<HistoryConversation[]> {
  const res = await apiFetch(`/v1/history/search?q=${encodeURIComponent(query)}&limit=40`);
  if (!res.ok) throw new Error(`history search failed: ${res.status}`);
  const data = await res.json();
  return data.results ?? [];
}

// --- typed-chat sync (fire-and-forget, debounced) ------------------------- //
async function syncConversation(conv: Conversation): Promise<void> {
  try {
    await apiFetch('/v1/history/sync', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(conv),
    });
  } catch {
    // The hub is best-effort; localStorage stays the source of truth.
  }
}

const _pending: Record<string, ReturnType<typeof setTimeout>> = {};

/** Debounced per-conversation sync — coalesces the burst of updates during
 * streaming into a single POST ~1.2s after the last change. */
export function scheduleConversationSync(conv: Conversation): void {
  if (!conv || !conv.messages?.length) return; // nothing worth archiving yet
  const existing = _pending[conv.id];
  if (existing) clearTimeout(existing);
  _pending[conv.id] = setTimeout(() => {
    delete _pending[conv.id];
    void syncConversation(conv);
  }, 1200);
}
