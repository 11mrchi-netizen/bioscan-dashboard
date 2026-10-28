// Personal health agent: which provider a model slug goes to, with which key (docs/personal-health-agent, Models).
//
//   requesty/<model>   Requesty's router, REQUESTY_API (the account allows only models without retention or training)
//   aistudio/<model>   Google AI Studio's OpenAI-compatible endpoint, GEMINI_API_KEY (data terms follow the key's tier)
//   <model>            OpenRouter, OPENROUTER_API, zero data retention required; ":free" slugs allow logged endpoints
//
// `purpose` decides what real data may use: 'eval' (planted cases) takes any route; 'live' (the person's data) refuses
// OpenRouter's free endpoints, which log prompts, and AI Studio, whose terms depend on a tier the app cannot check.

import type { ModelConfig } from './models.ts'

export const REQUESTY = 'https://router.requesty.ai/v1'
export const AISTUDIO_NATIVE = 'https://generativelanguage.googleapis.com/v1beta'
export const AISTUDIO_OPENAI = `${AISTUDIO_NATIVE}/openai`
export const OPENROUTER = 'https://openrouter.ai/api/v1'

/** OpenRouter's free endpoints log prompts, so they are allowed only for planted data and only by name. */
export const isFreeSlug = (model: string) => model.endsWith(':free')

export type Env = (name: string) => string | undefined

export function modelConfig(
  slug: string,
  env: Env,
  opts: { purpose: 'eval' | 'live'; maxTokens: number; timeoutMs?: number },
): ModelConfig | { error: string } {
  const common = { timeoutMs: opts.timeoutMs ?? 45_000, maxTokens: opts.maxTokens }
  if (slug.startsWith('requesty/')) {
    const key = env('REQUESTY_API')
    return key ? { ...common, baseUrl: REQUESTY, apiKey: key, model: slug.slice('requesty/'.length) } : { error: 'REQUESTY_API is not set' }
  }
  if (slug.startsWith('aistudio/')) {
    if (opts.purpose === 'live') return { error: 'AI Studio is for tests only: its data terms depend on the key tier' }
    const key = env('GEMINI_API_KEY')
    return key ? { ...common, baseUrl: AISTUDIO_OPENAI, apiKey: key, model: slug.slice('aistudio/'.length) } : { error: 'GEMINI_API_KEY is not set' }
  }
  if (opts.purpose === 'live' && isFreeSlug(slug)) return { error: 'free OpenRouter endpoints log prompts and are for tests only' }
  const key = env('OPENROUTER_API')
  return key ? { ...common, baseUrl: OPENROUTER, apiKey: key, model: slug, allowLogged: isFreeSlug(slug) } : { error: 'OPENROUTER_API is not set' }
}
