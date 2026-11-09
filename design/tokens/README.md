# Field Terminal — design handoff (v1)

Dark, glassmorphic health app. One colour hierarchy drives everything:

- **Background = domain** — a soft wash of the domain hue (Sleep violet, Cardio aqua, Load amber, Fuel lime, Balance rose).
- **Surface = neutral** — faint-platinum frosted glass, never tinted by a domain or metric.
- **Ink = metric** — every metric keeps a fixed, app-wide hue (heart red, HRV pink, SpO₂ blue, effort zones blue→red …).

So a page tells you *which domain* by its wash, and *which metric* by the chart colour; the glass stays out of the way.

## Files

| File | What it is |
|---|---|
| `tokens.css` | Colour tokens as CSS custom properties — domains, `--m-*` metrics, `--z-*` zones, semantic. |
| `tokens.json` | Same values as a nested JSON map (camelCase) for Style Dictionary / JS. |
| `backgrounds.css` | Page ground, per-domain background wash (`.ft-wash`), neutral glass `.ft-card` / `.ft-tile`, optional holo hairline. |
| `components.css` | Buttons, chips, segmented control, toggle, badges, dock, list row, bullet, diverging scale, progress, range input, search pill, stat tile, gauge shell. |
| `gauge.js` | Generates the radial tick gauge + internal radar glow (`ftGauge`) and the segmented macro donut (`ftMacroTicks`). |

## Dependencies

- **Fonts:** Nunito (numbers/headings), Inter (body), IBM Plex Mono (micro-labels).
- **CSS `color-mix()`** — current Chromium / Safari / Firefox. For older targets, precompute the mixed values from the tokens.

## Load order

```html
<link rel="stylesheet" href="tokens.css">
<link rel="stylesheet" href="backgrounds.css">
<link rel="stylesheet" href="components.css">
```

## Assembling a detail page

```html
<div class="ft-page ft-wash" style="--ft-wash: var(--ft-domain-cardio)">
  <header> … back · title · range chips … </header>

  <!-- hero gauge -->
  <div class="ft-gauge" style="--size:132px">
    <div class="ft-gauge__radar"></div>
    <svg viewBox="0 0 100 100"><!-- ticks + cap from gauge.js --></svg>
    <div class="ft-gauge__center"><span class="num">74</span><span class="k">RECOVERY</span></div>
  </div>

  <!-- a chart card: neutral glass, metric-coloured ink -->
  <section class="ft-card">
    <div class="c-t" style="color: var(--m-heart-rate)">HEART RATE</div>
    <!-- red bars using --m-heart-rate -->
  </section>
</div>
```

```js
import { ftGauge } from './gauge.js';
const g = ftGauge({ N:56, pct:0.74, stops:['#7af0df','#2fd6c3'], radarHex:'#2fd6c3' });
// render g.ticks as rotated <line>s, set .ft-gauge__radar background to g.radar, draw g.cap
```

## Rules

- **Never** colour a metric with a domain hue, and never tint a card — the wash (domain) and the ink (metric) carry colour; glass is neutral.
- Reserve the holographic spectrum for **CHARGE** (headline gauge sweep, `--badge--primed`) and premium moments only.
- Intentional shares: `--m-arousal` = `--m-hrv`, `--m-sodium` = `--m-weight`, `--m-sleeping-hr` = `--m-heart-rate`.
- Lab markers keep their existing per-marker themes (`domain/LabMarkerThemes.kt`).

## Visual reference on the canvas

- Board **14 · Components** — every component rendered.
- Board **17 · Metric colors (v1)** — the metric dictionary.
- Board **23 · Design tokens** — the token list.
- Boards **18–22** — the five domain detail pages (background wash + neutral glass + metric ink).
