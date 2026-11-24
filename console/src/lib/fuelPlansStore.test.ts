// Run: node --test "src/lib/*.test.ts"
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { decide } from './fuelPlansDecide.ts'

const row = (hash: string, by?: string) => ({ engine_version: '4', inputs_hash: hash, plan: by ? { by } : {} })

test('a plan the stored one equals is not stored again', () => assert.equal(decide(row('1a'), '1a'), 'unchanged'))
test('the first copy of a day is stored', () => assert.equal(decide(null, '1a'), 'insert'))
test('a changed plan is stored over the web\'s own row', () => assert.equal(decide(row('1a', 'web'), '2b'), 'insert'))
test('the phone\'s row is never competed with: the difference is shown, not stored', () => assert.equal(decide(row('1a'), '2b'), 'phone'))
