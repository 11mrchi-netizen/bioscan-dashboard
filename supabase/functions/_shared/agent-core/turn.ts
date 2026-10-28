// Personal health agent, phase 0: one turn of the agent, independent of the model provider and of where tool results
// come from. health-agent (phase 1) passes a real tool executor over Supabase; scripts/agent-golden passes the golden
// set's planted results. The order is fixed: triage, the tool loop with every call checked, then the guards with one
// regeneration, then the cleaned reply.

import { checkReply, type ToolOutput, type Violation } from './guards.ts'
import { systemPrompt } from './prompts.ts'
import { triage, type Triage } from './redFlags.ts'
import type { ScopeSettings } from './scope.ts'
import { checkCall, toOpenAiTools, toolsFor, type Mode } from './tools.ts'

export interface ChatMessage {
  role: 'system' | 'user' | 'assistant' | 'tool'
  content: string | null
  tool_calls?: { id: string; type: 'function'; function: { name: string; arguments: string }; extra_content?: unknown }[]
  tool_call_id?: string
}

export interface ChatReply {
  content: string | null
  /** `extra` is provider data that must go back with the call on the next turn (AI Studio's thought signature). */
  toolCalls: { id: string; name: string; arguments: string; extra?: unknown }[]
  usage?: { prompt_tokens?: number; completion_tokens?: number }
}

export type Chat = (req: { messages: ChatMessage[]; tools: ReturnType<typeof toOpenAiTools> }) => Promise<ChatReply>
export type ExecuteTool = (name: string, args: Record<string, unknown>) => Promise<unknown>

export interface TurnInput {
  mode: Mode
  question: string
  context?: unknown
  settings: ScopeSettings
  today: string
  chat: Chat
  executeTool: ExecuteTool
  maxSteps?: number
}

export interface TurnResult {
  triage: Triage | null
  /** The model's last draft, before cleaning. */
  draft: string | null
  /** What the person sees: fixed triage text first, then the cleaned reply. */
  shown: string
  violations: Violation[]
  regenerated: boolean
  calls: { name: string; args: unknown; ok: boolean; error?: string }[]
  outputs: ToolOutput[]
  steps: number
  tokens: { prompt: number; completion: number }
}

const REMOVED = 'Part of this answer was removed because it could not be checked against your data.'

export async function runTurn(input: TurnInput): Promise<TurnResult> {
  const t = triage(input.question)
  const result: TurnResult = { triage: t, draft: null, shown: '', violations: [], regenerated: false, calls: [], outputs: [], steps: 0, tokens: { prompt: 0, completion: 0 } }
  if (t && !t.callModel) {
    result.shown = t.text
    return result
  }

  const tools = toOpenAiTools(toolsFor(input.mode, input.settings))
  const messages: ChatMessage[] = [
    { role: 'system', content: systemPrompt(input.mode, input.today, input.settings) },
    { role: 'user', content: input.context ? `${input.question}\n\nScreen: ${JSON.stringify(input.context)}` : input.question },
  ]
  const maxSteps = input.maxSteps ?? 8

  const draftOnce = async (): Promise<string | null> => {
    while (result.steps < maxSteps) {
      result.steps++
      const r = await input.chat({ messages, tools })
      result.tokens.prompt += r.usage?.prompt_tokens ?? 0
      result.tokens.completion += r.usage?.completion_tokens ?? 0
      if (!r.toolCalls.length) return r.content
      messages.push({ role: 'assistant', content: r.content, tool_calls: r.toolCalls.map((c) => ({ id: c.id, type: 'function', function: { name: c.name, arguments: c.arguments }, ...(c.extra ? { extra_content: c.extra } : {}) })) })
      for (const c of r.toolCalls) {
        let args: unknown
        try {
          args = c.arguments ? JSON.parse(c.arguments) : {}
        } catch {
          args = null
        }
        const check = args === null ? { ok: false as const, error: 'arguments are not JSON' } : checkCall(input.mode, input.settings, c.name, args)
        if (!check.ok) {
          result.calls.push({ name: c.name, args, ok: false, error: check.error })
          messages.push({ role: 'tool', tool_call_id: c.id, content: JSON.stringify({ error: check.error }) })
          continue
        }
        const data = await input.executeTool(c.name, args as Record<string, unknown>)
        result.calls.push({ name: c.name, args, ok: true })
        result.outputs.push({ tool: c.name, args, data })
        messages.push({ role: 'tool', tool_call_id: c.id, content: JSON.stringify(data) })
      }
    }
    return null
  }

  let draft = await draftOnce()
  let check = checkReply({ reply: draft ?? '', question: input.question, context: input.context, outputs: result.outputs })
  if (!check.ok && result.steps < maxSteps) {
    result.regenerated = true
    messages.push({ role: 'assistant', content: draft })
    messages.push({
      role: 'user',
      content: `The app could not show that reply: ${check.violations.map((v) => `${v.code} (${v.text})`).join('; ')}. Rewrite it following the rules.`,
    })
    draft = await draftOnce()
    check = checkReply({ reply: draft ?? '', question: input.question, context: input.context, outputs: result.outputs })
  }
  result.draft = draft
  result.violations = check.violations
  const body = check.ok ? draft! : check.cleaned ? `${check.cleaned} ${REMOVED}` : REMOVED
  result.shown = t ? `${t.text}\n\n${body}` : body
  return result
}
