// Makes the spoken audio of a wellness script with ElevenLabs, one mp3 per cue:
//   ELEVENLABS_API_KEY=... ELEVENLABS_VOICE_ID=... node scripts/wellness-audio/generate.mjs [stopstart-v1] [--force]
// Reads android-app/app/src/main/assets/wellness/<version>.json and writes assets/wellness/audio/<cue id>.mp3. A cue that
// already has a file is skipped unless --force, so only changed scripts cost characters. Run it on your own computer: the
// key stays there, and the only thing sent out is the script text. The phone plays these files when present and falls back
// to its own speech engine for any cue without one (data/WellnessVoice.kt).
import { mkdir, readFile, writeFile, access } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const key = process.env.ELEVENLABS_API_KEY;
const voice = process.env.ELEVENLABS_VOICE_ID;
if (!key || !voice) {
  console.error('Set ELEVENLABS_API_KEY and ELEVENLABS_VOICE_ID.');
  process.exit(1);
}
const force = process.argv.includes('--force');
const version = process.argv.slice(2).find((a) => !a.startsWith('--')) ?? 'stopstart-v1';
const assets = join(dirname(fileURLToPath(import.meta.url)), '../../android-app/app/src/main/assets/wellness');
const script = JSON.parse(await readFile(join(assets, `${version}.json`), 'utf8'));
const out = join(assets, 'audio');
await mkdir(out, { recursive: true });

for (const [id, text] of Object.entries(script.cues)) {
  const file = join(out, `${id}.mp3`);
  if (!force && (await access(file).then(() => true, () => false))) {
    console.log(`skip ${id} (exists)`);
    continue;
  }
  const res = await fetch(`https://api.elevenlabs.io/v1/text-to-speech/${voice}?output_format=mp3_44100_128`, {
    method: 'POST',
    headers: { 'xi-api-key': key, 'content-type': 'application/json', accept: 'audio/mpeg' },
    // Slow, steady delivery: a high stability and a little below normal speed read as a calm coach.
    body: JSON.stringify({ text, model_id: 'eleven_multilingual_v2', voice_settings: { stability: 0.7, similarity_boost: 0.75, style: 0, speed: 0.9 } }),
  });
  if (!res.ok) {
    console.error(`${id}: ${res.status} ${(await res.text()).slice(0, 300)}`);
    process.exitCode = 1;
    continue;
  }
  await writeFile(file, Buffer.from(await res.arrayBuffer()));
  console.log(`wrote ${id}.mp3 (${text.length} characters)`);
}
