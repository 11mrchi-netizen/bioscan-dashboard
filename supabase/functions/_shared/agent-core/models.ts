// Personal health agent, phase 0: an OpenAI-compatible chat client (OpenRouter today, a self-hosted vLLM endpoint later
// through the same API). Models are configuration, never code: the caller passes the model slug and provider order.
//
// OpenRouter routing for health data (docs/personal-health-agent, Models): zero data retention, no data collection,
// pinned providers without fallback to others, and only providers that support every parameter sent (tool calls).
// `allowLogged` drops the first two for the golden set's planted data only: OpenRouter's free endpoints log prompts and
// would otherwise be filtered out. Real data never goes through a client built with it.

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
  /** Synthetic data only: allow endpoints that log or train on prompts. */
  allowLogged?: boolean
  /** Output cap per call. OpenRouter reserves credit for max_tokens up front (a model's full 131k without it). */
  maxTokens?: number
}

export function openAiChat(cfg: ModelConfig): Chat {
  const openRouter = /openrouter\.ai/.test(cfg.baseUrl)
  return async ({ messages, tools }): Promise<ChatReply> => {
    const body: Record<string, unknown> = { model: cfg.model, messages, tools, tool_choice: 'auto', temperature: cfg.temperature ?? 0.2, max_tokens: cfg.maxTokens ?? 1024 }
    if (openRouter) {
      body.provider = {
        ...(cfg.allowLogged ? {} : { zdr: true, data_collection: 'deny' }),
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
    return parseChatResponse(JSON.parse(text))
  }
}

// Google AI Studio's OpenAI-compatible endpoint returns a thinking model's reasoning inside `content` as
// <thought>...</thought> (cut off without its closing tag when the output cap is reached), and puts a thought
// signature in `extra_content` on each tool call, which Google expects back on the next turn.
const THOUGHT = /<thought>[\s\S]*?(?:<\/thought>|$)/g

export function stripThoughts(content: string): string {
  return content.replace(THOUGHT, '').trim()
}

// deno-lint-ignore no-explicit-any
export function parseChatResponse(json: any): ChatReply {
  const msg = json.choices?.[0]?.message ?? {}
  const content = typeof msg.content === 'string' ? stripThoughts(msg.content) : null
  return {
    content: content || null,
    toolCalls: (msg.tool_calls ?? []).map((c: { id?: string; function: { name: string; arguments: string }; extra_content?: unknown }, i: number) => ({
      id: c.id || `call_${i}`,
      name: c.function.name,
      arguments: c.function.arguments,
      ...(c.extra_content ? { extra: c.extra_content } : {}),
    })),
    usage: json.usage,
  }
}
