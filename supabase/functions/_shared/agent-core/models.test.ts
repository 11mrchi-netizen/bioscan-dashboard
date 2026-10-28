import assert from 'node:assert/strict'
import { test } from 'node:test'
import { parseChatResponse, stripThoughts } from './models.ts'
import { runTurn, type Chat, type ChatMessage } from './turn.ts'

test("AI Studio's inline reasoning is removed, closed or cut off by the output cap", () => {
  assert.equal(stripThoughts('<thought>HRV is 38, so maybe 13 % low</thought>Your HRV was 38 ms.'), 'Your HRV was 38 ms.')
  assert.equal(stripThoughts('<thought>still thinking when the cap hit'), '')
  assert.equal(stripThoughts('No reasoning here.'), 'No reasoning here.')
})

test('a reply that was only reasoning has no content, and tool calls keep their provider data', () => {
  const r = parseChatResponse({
    choices: [{ message: { content: '<thought>call the tool</thought>', tool_calls: [{ function: { name: 'get_results', arguments: '{}' }, extra_content: { google: { thought_signature: 'sig' } } }] } }],
  })
  assert.equal(r.content, null)
  assert.deepEqual(r.toolCalls, [{ id: 'call_0', name: 'get_results', arguments: '{}', extra: { google: { thought_signature: 'sig' } } }])
})

test('the thought signature goes back with its call on the next turn', async () => {
  const sent: ChatMessage[][] = []
  const replies = [
    { content: null, toolCalls: [{ id: 'c1', name: 'get_results', arguments: '{"kind":"expected_band"}', extra: { google: { thought_signature: 'sig' } } }] },
    { content: 'It reads BELOW EXPECTED.', toolCalls: [] },
  ]
  const chat: Chat = async (req) => {
    sent.push(structuredClone(req.messages))
    return replies.shift()!
  }
  await runTurn({ mode: 'explain', question: 'Why BELOW EXPECTED?', settings: { enabled: true, optIn: [] }, today: '2026-10-28', chat, executeTool: async () => ({}) })
  const assistant = sent[1].find((m) => m.role === 'assistant')!
  assert.deepEqual(assistant.tool_calls![0].extra_content, { google: { thought_signature: 'sig' } })
})
