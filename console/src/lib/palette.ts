// Chart chrome and fixed accents for the canvas charts, which cannot read CSS variables (ECharts paints on a canvas). Each
// member resolves the design token at the moment it is read, so the charts take the Futuristic Material colours the page uses
// (design/tokens) instead of a second copy of them. Read inside an effect or an option builder, not at import time on a server.
const css = (name: string, fallback: string): string => {
  try { return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback } catch { return fallback }
}

/** A colour for a canvas: a `var(--token)` is resolved to its value, anything else is returned as it is. */
export function cssColor(v: string): string {
  const m = /^var\((--[a-z0-9-]+)\)$/i.exec(v)
  return m ? css(m[1], '#8a949a') : v
}

export const PALETTE = {
  /** Selection and the neutral series colour: platinum. Status and metric hues come from their own tokens, not from here. */
  get emerald() { return css('--ft-platinum', '#e6eaf0') },
  get amber() { return css('--warn', '#ffc24b') },
  get critical() { return css('--bad', '#ff5d6c') },
  get text() { return css('--ft-fg', '#eaf0f2') },
  get textMuted() { return css('--ft-muted', '#8a949a') },
  get border() { return css('--ft-line', 'rgba(255,255,255,0.10)') },
  get surface() { return css('--ft-bg', '#0a0c0e') },
}
