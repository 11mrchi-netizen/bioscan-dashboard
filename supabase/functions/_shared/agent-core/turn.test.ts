import assert from 'node:assert/strict'
import { test } from 'node:test'
import { runTurn, type Chat, type ChatReply } from './turn.ts'

const settings = { enabled: true, optIn: [] }
const band = { kind: 'expected_band', key: 'hrv', state: 'BELOW EXPECTED', display: { value: '38 ms', band: '44–61 ms' } }

/** A chat that replays scripted replies and records what it was sent. */
function scripted(replies: ChatReply[]) {
  const sent: Parameters<Chat>[0][] = []
  const chat: Chat = async (req) => {
    sent.push(structuredClone(req))
    const r = replies.shift()
    if (!r) throw new Error('no scripted reply left')
    return r
  }
  return { chat, sent }
}
const call = (name: string, args: unknown, id = name): ChatReply => ({ content: null, toolCalls: [{ id, name, arguments: JSON.stringify(args) }] })
const say = (content: string): ChatReply => ({ content, toolCalls: [] })
const base = { mode: 'explain' as const, question: 'Why BELOW EXPECTED?', settings, today: '2026-10-28', executeTool: async () => band }

test('an urgent symptom never reaches the model', async () => {
  const { chat, sent } = scripted([])
  const r = await runTurn({ ...base, question: 'I have chest pain, is my HRV ok?', chat })
  assert.equal(sent.length, 0)
  assert.match(r.shown, /119/)
})

test('a tool result grounds the reply, which is shown as written', async () => {
  const { chat, sent } = scripted([call('get_results', { kind: 'expected_band', key: 'hrv' }), say('HRV was 38 ms against 44–61 ms, so it reads BELOW EXPECTED.')])
  const r = await runTurn({ ...base, chat })
  assert.equal(r.shown, 'HRV was 38 ms against 44–61 ms, so it reads BELOW EXPECTED.')
  assert.deepEqual(r.calls.map((c) => c.ok), [true])
  assert.equal(sent[1].messages.at(-1)!.role, 'tool')
  assert.ok(!sent[0].tools.some((t) => t.function.name.startsWith('propose_')), 'explain offers no proposals')
})

test('a refused tool call is answered with its error and recorded', async () => {
  const { chat, sent } = scripted([call('propose_goal', { text: 'x' }), say('It reads BELOW EXPECTED.')])
  const r = await runTurn({ ...base, chat, executeTool: async () => assert.fail('a refused call must not execute') })
  assert.equal(r.calls[0].ok, false)
  assert.match(String(sent[1].messages.at(-1)!.content), /not available in explain/)
})

test('a reply that breaks a rule is regenerated once with the reasons', async () => {
  const { chat, sent } = scripted([call('get_results', { kind: 'expected_band' }), say('HRV was 38 ms, 13 % low.'), say('HRV was 38 ms, BELOW EXPECTED.')])
  const r = await runTurn({ ...base, chat })
  assert.equal(r.regenerated, true)
  assert.equal(r.shown, 'HRV was 38 ms, BELOW EXPECTED.')
  assert.match(String(sent[2].messages.at(-1)!.content), /ungrounded_number \(13\)/)
})

test('when the second draft still breaks a rule, the breaking sentences are removed and that is said', async () => {
  const { chat } = scripted([call('get_results', { kind: 'expected_band' }), say('Bad 99.'), say('HRV was 38 ms. It is 13 % low.')])
  const r = await runTurn({ ...base, chat })
  assert.equal(r.shown, 'HRV was 38 ms. Part of this answer was removed because it could not be checked against your data.')
})

test('a clinician symptom puts the fixed text before the reply', async () => {
  const { chat } = scripted([call('get_results', { kind: 'expected_band' }), say('HRV was 38 ms, BELOW EXPECTED.')])
  const r = await runTurn({ ...base, mode: 'ask', question: 'Fever of 39.5 since yesterday, why BELOW EXPECTED?', chat })
  assert.match(r.shown, /^A temperature of 39 °C or more[\s\S]*\n\nHRV was 38 ms, BELOW EXPECTED\.$/)
})

test('the system prompt lists what is not shared, from the same settings as the tool layer', async () => {
  const { chat, sent } = scripted([say('It reads BELOW EXPECTED.')])
  await runTurn({ ...base, settings: { enabled: true, optIn: ['health_concerns'] }, chat })
  const system = String(sent[0].messages[0].content)
  assert.match(system, /Not shared with you: phone use, places, trips and other routine data;[^\n]*sexual health/)
  assert.doesNotMatch(system, /health concerns and their check-ins/)
})
