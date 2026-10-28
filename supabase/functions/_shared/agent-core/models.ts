// Personal health agent, phase 0: an OpenAI-compatible chat client (OpenRouter today, a self-hosted vLLM endpoint later
// through the same API). Models are configuration, never code: the caller passes the model slug and provider order.
//
// OpenRouter routing for health data (docs/personal-health-agent, Models): zero data retention, no data collection,
// pinned providers without fallback to others, and only providers that support every parameter sent (tool calls).

import type { Chat, ChatReply } from './turn.ts'

export interface ModelConfig {
  /** e.g. https://openrouter.ai/api/v1 */
  baseUrl: string
  apiKey: string
  model: string
  /** OpenRouter provider slugs in order of preference; empty lets OpenRouter choose among zero-retention providers. */
  providers?: string[]
  temperature?: number
  timeoutMs?: number
}

export function openAiChat(cfg: ModelConfig): Chat {
  const openRouter = /openrouter\.ai/.test(cfg.baseUrl)
  return async ({ messages, tools }): Promise<ChatReply> => {
    const body: Record<string, unknown> = { model: cfg.model, messages, tools, tool_choice: 'auto', temperature: cfg.temperature ?? 0.2 }
    if (openRouter) {
      body.provider = {
        zdr: true,
        data_collection: 'deny',
        require_parameters: true,
        ...(cfg.providers?.length ? { order: cfg.providers, allow_fallbacks: false } : {}),
      }
    }
    const res = await fetch(`${cfg.baseUrl.replace(/\/$/, '')}/chat/completions`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${cfg.apiKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(cfg.timeoutMs ?? 60_000),
    })
    const text = await res.text()
    if (!res.ok) throw new Error(`${cfg.model}: HTTP ${res.status} ${text.slice(0, 300)}`)
    const json = JSON.parse(text)
    const msg = json.choices?.[0]?.message ?? {}
    return {
      content: typeof msg.content === 'string' ? msg.content : null,
      toolCalls: (msg.tool_calls ?? []).map((c: { id: string; function: { name: string; arguments: string } }) => ({ id: c.id, name: c.function.name, arguments: c.function.arguments })),
      usage: json.usage,
    }
  }
}
