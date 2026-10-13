// 32-bit FNV-1a over a string, hex encoded. Used as analysis_results.inputs_hash: staleness is "the hash of today's inputs
// differs from the stored one". Not cryptographic and not meant to be.
export function fnv1a(text: string): string {
  let h = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return (h >>> 0).toString(16).padStart(8, '0')
}

// Hash of a dated series (missing days are part of the signature).
export function hashSeries(dates: string[], values: (number | null)[]): string {
  return fnv1a(dates.map((d, i) => `${d}:${values[i] === null ? '' : values[i]}`).join('|'))
}
