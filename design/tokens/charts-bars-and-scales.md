# Field Terminal — bar graphs & diverging scales (detailed guidelines)

Covers the two families you flagged on board 14: **bar graphs** (incl. the intraday bars)
and **diverging scales**. These supersede the one-line notes in `components.css`.
All colours are `--m-*` / `--z-*` tokens from `tokens.css`; glass + wash come from `backgrounds.css`.

---

## 0. The one thing that breaks the translation

Board 14 draws bars as an SVG with `viewBox="0 0 360 66"` **`preserveAspectRatio="none"`**.
That stretches the 360×66 coordinate space to fill the container, so the **x and y scales differ**.
Consequences when it renders at, say, 400×66 px:

- `rx="3.5"` corners become **ellipses** (3.5 x-units ≠ 3.5 y-units after non-uniform scale).
- `width="9"` bars get **stretched horizontally** by ~1.1×, so the designed 9 px is not 9 px.
- Any `stroke` would scale unevenly too.

**Rule:** never use `preserveAspectRatio="none"` for bars (or anything with a corner radius or stroke).
Use one of the two correct approaches below, both of which keep 1 unit = 1 px.

---

## 1. Bar graphs — the system

### 1.1 Anatomy
```
┌───────────────────────────────── plot ─────────────────────────────────┐
│  ▁  ▃  ▂  ▅  ▃  ▄  ▆  ▅  ▄  ▇  …                                  (bars)  │
│                                                   ── target / avg line   │
└──────────────────────────────── baseline ───────────────────────────────┘
   now 72 bpm                                        today avg · 78 bpm
```
- **Plot** — the drawing area. Height is fixed per context (see 1.6); width is fluid.
- **Bars** — rounded-top columns growing up from the **baseline** (bottom).
- **Baseline** — shared bottom edge; never draw a heavy axis line, the bars imply it.
- **Readout row** — below the plot: the current/last value (left, in the metric colour) and a context stat (right, muted). This row, not an axis, carries the numbers.

### 1.2 Geometry (authoritative formulas)
Let `W`,`H` = plot size in px, `n` = bar count, `gap` = px between bars.

```
barWidth = (W - gap * (n - 1)) / n           // fill the width exactly
x(i)     = i * (barWidth + gap)
barH(v)  = (v - vMin) / (vMax - vMin) * (H - topPad)   // topPad ≈ 2–4px headroom
y(v)     = H - barH(v)
radius   = min(barWidth / 2, 4)              // never more than half the width
```
- `vMin` is usually **0** (bars encode magnitude from zero). Use a non-zero `vMin` only for a
  tightly-ranged metric, and then say so in the label (e.g. "46–78 bpm").
- Keep `radius ≤ barWidth/2` or thin bars turn into lozenges.

### 1.3 Fill — vertical gradient, metric-coloured
Each bar is a **vertical** gradient of its metric colour: solid at the top, fading to ~22 % alpha
at the baseline. This gives lift without a hard edge.

```
top  (y=0): var(--m-xxx)              at 100% alpha   (use a +8–12% lightness tint for the very top)
base (y=H): var(--m-xxx)              at 22% alpha
```
One gradient id per metric colour, reused by every bar (don't define per-bar gradients).
Intraday heart rate uses `--m-heart-rate`; a load-by-day chart uses `--m-ctl`; etc.

### 1.4 Spacing & density
| Context | n (typical) | barWidth | gap |
|---|---|---|---|
| Intraday (24 h, hourly) | 24 | 8–10 px | 5–6 px |
| Intraday (fine, 5-min) | 48–72 | 3–5 px | 1–2 px |
| Daily (1 week) | 7 | 22–30 px | 10–12 px |
| Daily (1 month) | 28–31 | 6–9 px | 2–3 px |

- **Minimum legible bar = 3 px.** Below that, **aggregate** (bin to the next grain) rather than shrink further.
- Gap is always **smaller** than the bar. For >40 bars drop the gap to 1–2 px so the series reads as a field.

### 1.5 Rounded tops only (optional but preferred)
Bars round at the **top** only, baseline stays square (they sit on the baseline). Two ways:
- SVG: a `<path>` with rounded top corners, or a `<rect rx>` when bars are tall enough that the
  square bottom is hidden by the baseline crop (acceptable for intraday where bottoms are off-plot).
- CSS/div bars: `border-radius: 4px 4px 0 0`.

### 1.6 Plot heights (fixed per context)
- Intraday strip inside a card: **64–70 px**.
- Daily bars inside a card: **70–90 px**.
- Full-width detail chart: **120–160 px**.
Keep a card's bar plot short — it's a glanceable texture, not the hero.

### 1.7 Target / threshold / average
- A **target or average** is a 1 px dashed line across the plot at its y, in the metric colour at 50 % alpha, labelled at the right end ("avg 78").
- A **goal band** (acceptable range) is a faint filled rect behind the bars, metric colour at ~8 % alpha.
- Never more than one reference line per chart.

### 1.8 Highlight / selection / "now"
- The **current / latest** bar is full alpha and gets a 1 px bright cap or a small dot above it.
- A **selected** bar (on tap) brightens; all others drop to ~45 % alpha. Show its value in the readout row.
- Past vs projected: projected bars are the same shape at **40 % alpha** (or hatched), never a different hue.

### 1.9 States
- **No data:** render the baseline and a muted "No data yet" — never zero-height bars that look like a flat reading.
- **Sparse:** if only a few of `n` slots have data, draw the empty slots as 2 px baseline stubs at `rgba(255,255,255,.06)` so the time axis stays legible (this is the pattern the stress lanes use).
- **Single value:** one centered bar at max 40 % of `W`, not a full-width block.

### 1.10 Variants
- **Zone-coloured bars** — colour each bar by the HR/effort zone it falls in (`--z-hr-1…5`) instead of one metric gradient. Use for "time in zone by hour". Legend required.
- **Stacked bars** — segments per category from the baseline up, each a `--z-*` or `--m-*` colour, 2 px gap between segments, radius only on the top-most segment. (This is the 7-day stress lane pattern.)
- **Range / floating bars** — a bar that spans low→high (daily min–max). Draw a rounded capsule from `y(high)` to `y(low)`, full radius both ends, metric colour at ~40 % with a brighter 2 px cap at the mean.
- **Diverging bars** — bars that go up (positive) or down (negative) from a **center baseline** (not the bottom). Center line at the zero row; positive bars use the "good" pole colour, negative the "warn" pole colour (see §3 for the pole palette). Mirror §3's semantics.

### 1.11 Accessibility
- The `<svg>` gets `role="img"` and an `aria-label` summarising the series
  ("Heart rate, intraday, now 72, average 78, range 46 to 142").
- Decorative gradients/defs get `aria-hidden`.
- Don't encode meaning in colour alone — the readout row and the label carry the numbers.
- Provide a visually-hidden `<table>` of values when the chart is a primary data source (detail pages), not for a glance strip.

### 1.12 Do / Don't
- ✅ 1 unit = 1 px (compute real px, or square viewBox). ✅ gradient fade to baseline. ✅ gap < bar. ✅ aggregate instead of sub-3 px bars.
- ❌ `preserveAspectRatio="none"`. ❌ radius > barWidth/2. ❌ rainbow bars for one metric. ❌ a heavy axis box. ❌ zero-height bars for missing data.

---

## 2. Intraday bars (the heart-rate pattern), precisely

The board-14 look, specified:
- **Plot:** full card width × **66 px**.
- **Bars:** `n = 24`, `barWidth ≈ 9 px`, `gap ≈ 5–6 px`, `radius 3.5 px` (top).
- **Fill:** vertical gradient of `--m-heart-rate` — top `#ff5f6e` (a +8 % tint), base `--m-heart-rate` @ 22 % alpha.
- **Baseline:** `vMin = 0`; `topPad = 4 px`.
- **Readout:** left `72 bpm` in `--m-heart-rate` (bold Nunito 24–26), right `today avg · 78 bpm` muted mono.
- **Optional:** a dashed avg line at `y(78)`; colour `--m-heart-rate` @ 50 %.
- **Zone mode:** swap the single gradient for per-bar `--z-hr-*` by the bar's HR zone; add the zone legend.

### 2.1 Correct responsive render (recommended) — real px via JS
```html
<svg class="ft-bars" role="img" aria-label="Heart rate, hourly. Now 72 bpm, average 78."></svg>
```
```js
function renderBars(svg, values, opts){
  const { color = 'var(--m-heart-rate)', gap = 6, radius = 3.5, topPad = 4, avg } = opts;
  const W = svg.clientWidth, H = svg.clientHeight;          // real px
  svg.setAttribute('viewBox', `0 0 ${W} ${H}`);             // 1 unit = 1 px, default preserveAspectRatio
  const n = values.length, bw = (W - gap*(n-1)) / n, vMax = Math.max(...values);
  const ns = 'http://www.w3.org/2000/svg';
  svg.innerHTML =
    `<defs><linearGradient id="bg" x1="0" y1="1" x2="0" y2="0">
       <stop offset="0" stop-color="${color}" stop-opacity=".22"/>
       <stop offset="1" stop-color="${color}"/>
     </linearGradient></defs>`;
  values.forEach((v,i)=>{
    const h = v/vMax*(H-topPad), x = i*(bw+gap), y = H-h, r = Math.min(bw/2, radius);
    const rect = document.createElementNS(ns,'rect');
    Object.assign(rect, {});
    rect.setAttribute('x',x.toFixed(1)); rect.setAttribute('y',y.toFixed(1));
    rect.setAttribute('width',bw.toFixed(1)); rect.setAttribute('height',h.toFixed(1));
    rect.setAttribute('rx',r); rect.setAttribute('fill','url(#bg)');
    svg.appendChild(rect);
  });
  if (avg!=null){ const ay=(H-avg/vMax*(H-topPad)).toFixed(1);
    const line=document.createElementNS(ns,'line');
    line.setAttribute('x1',0); line.setAttribute('x2',W); line.setAttribute('y1',ay); line.setAttribute('y2',ay);
    line.setAttribute('stroke',color); line.setAttribute('stroke-opacity','.5');
    line.setAttribute('stroke-width','1'); line.setAttribute('stroke-dasharray','3 3');
    svg.appendChild(line);
  }
}
// re-run on resize (ResizeObserver) so bars stay 1:1.
```

### 2.2 Simplest crisp alternative — flex div bars (no SVG)
For plain magnitude bars this is the least error-prone and naturally responsive.
```css
.ft-bars{ display:flex; align-items:flex-end; gap:6px; height:66px; }
.ft-bars > i{ flex:1 1 0; border-radius:4px 4px 0 0;
  background:linear-gradient(180deg, var(--m-heart-rate), color-mix(in srgb, var(--m-heart-rate) 22%, transparent)); }
```
```html
<div class="ft-bars" role="img" aria-label="…"><i style="height:46%"></i><i style="height:63%"></i>…</div>
```
`height` per bar = `v/vMax*100 + '%'`. Corners and widths are always correct; no viewBox math.
Use this for intraday/daily magnitude bars; reserve the SVG path for rounded-top-only or stacked/range bars.

---

## 3. Diverging scales

A single bipolar value on a horizontal axis with a meaningful **zero at the centre** — energy balance,
TSB/form, sodium balance, "since you changed it" deltas. Board 14's ENERGY BALANCE is the reference.

### 3.1 Anatomy
```
IN − OUT · 7-DAY                                           +120 kcal
   ◀ deficit ───────────────●──────────── surplus ▶
 −500      −250            0            +250      +500
                        (zero, centred)        (thumb = current)
```
- **Label row** — left: what it is + window (mono, muted). Right: the **signed value** in the pole colour (green if the good side, warn/amber or red if the undesired side).
- **Track** — a rounded bar with a **diverging gradient**: undesired pole → neutral centre → desired pole.
- **Thumb** — a white dot marking the current value. Display-only (not a control).
- **Axis** — symmetric tick labels, zero in the middle.

### 3.2 Domain & mapping
- The domain is **symmetric**: `[-D, +D]`. Pick `D` so typical values sit in the middle third
  (energy balance `D=500` kcal; TSB `D=30`). Clamp out-of-range values to the ends and flag it (3.7).
```
pos%  = clamp((value + D) / (2D) * 100, 0, 100)   // 0 → left end, 50 → centre, 100 → right end
```
- Zero is **always 50 %**. Never auto-scale a diverging axis asymmetrically — the centre must stay centred.

### 3.3 Track gradient (the diverging ramp)
```css
.ft-scale__track{
  height:8px; border-radius:999px; position:relative;
  background:linear-gradient(90deg,
    var(--pole-neg) 0%,
    #3a3f46 46%, #3a3f46 54%,     /* neutral dead-zone around zero */
    var(--pole-pos) 100%);
}
```
- The **neutral dead-zone** (`#3a3f46`, ~46–54 %) marks "near zero / balanced" and keeps the two
  hues from meeting in a muddy midpoint. Widen it (44–56 %) for metrics with a real target band.
- Poles are **lightness-distinct**, not just hue (so the direction reads without colour vision):
  keep the desired pole lighter/brighter.

### 3.4 Pole palette (which colour each side)
Assign by meaning, not by domain:
| Metric | Negative pole (`--pole-neg`) | Positive pole (`--pole-pos`) | Value-label colour |
|---|---|---|---|
| Energy balance (deficit ◀ / surplus ▶) | `--m-load` `#ff8a5b` | `--m-cardio`/recovery `#2fd6c3` | side-dependent |
| Form / TSB (fatigued ◀ / fresh ▶) | `--bad` `#ff5d6c` | `--ok` `#58e39a` | matches side |
| Sodium balance, fluid balance | `--warn` `#ffc24b` | `--m-hydration` `#3aa8ff` | side-dependent |
| "Since you changed it" delta (worse ◀ / better ▶) | `--bad` | `--ok` | matches side |
- The **value label** takes the colour of the side the value is on (green on the good side, red/amber on the bad side), or stays neutral white when inside the dead-zone.

### 3.5 Thumb
```css
.ft-scale__thumb{
  position:absolute; top:50%; left: <pos%>; transform:translate(-50%,-50%);
  width:16px; height:16px; border-radius:50%; background:#fff;
  box-shadow:0 0 0 3px var(--ft-bg), 0 2px 8px rgba(0,0,0,.5);   /* dark ring lifts it off the track */
}
```
- The `3px` background-coloured ring is essential — it separates the white thumb from the bright track.
- The thumb is **presentational**: mark the component `role="img"` with an `aria-label`
  ("Energy balance +120 kcal, surplus"), and the thumb `aria-hidden`. Do **not** use a slider role for a read-only value.

### 3.6 Axis
- 5 labels, symmetric: `−D · −D/2 · 0 · +D/2 · +D`, mono 9 px, `--ft-dim`, `margin-top:9px`.
- The `0` is visually centred under the dead-zone. Optionally a 1 px tick at centre.

### 3.7 States & edge cases
- **Clamped (|value| > D):** thumb pins to the end **and** gains a small chevron (`‹`/`›`) past it,
  with the true value still shown in the label ("+640 kcal").
- **Exactly zero / within dead-zone:** thumb sits centre, label neutral white, no pole colour.
- **No data:** show the track greyed (both poles → `rgba(255,255,255,.12)`), no thumb, "No data".
- **Confidence:** if the value is low-confidence, render the thumb hollow (white ring, transparent centre).

### 3.8 Variants
- **With a normal band:** add a brighter neutral band (e.g. `#4a515c`) spanning the acceptable range
  instead of a thin dead-zone — e.g. TSB "fresh but not detrained" −10…+5.
- **Range bullet (one-pole):** for a value vs a normal band on a **non-diverging** 0→max axis, use the
  **range bullet** component instead (band + dot), not a diverging scale. Diverging is only for bipolar zero-centred metrics.
- **Mini / inline:** 6 px track, no axis labels, thumb 12 px — for a list row or tile.

### 3.9 Do / Don't
- ✅ zero fixed at 50 %. ✅ symmetric domain. ✅ neutral dead-zone at centre. ✅ pole colours differ in lightness. ✅ value label coloured by side.
- ❌ asymmetric/auto-scaled diverging axis. ❌ slider role for a read-only thumb. ❌ white thumb with no dark ring. ❌ using a diverging scale for a one-directional 0→max metric.

---

## 4. Quick reference — tokens used here
- Bars: the metric's `--m-*` (fill gradient), `--z-hr-*` / `--z-stress-*` (zone/stacked bars).
- Diverging poles: `--m-load`+`--m-cardio` (energy), `--bad`+`--ok` (TSB, deltas), `--warn`+`--m-hydration` (balances).
- Neutral dead-zone `#3a3f46`; thumb ring `var(--ft-bg)`; muted axis `--ft-dim`.

Visual reference: board **14 · Components** (ENERGY BALANCE, HEART RATE) and board **20 · Load page**
(time-in-zones stacked bar), **22 · Balance page** (7-day stress lanes = stacked bars).
