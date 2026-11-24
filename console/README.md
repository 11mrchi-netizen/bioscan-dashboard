# React + TypeScript + Vite

This template provides a minimal setup to get React working in Vite with HMR and some Oxlint rules.

Currently, two official plugins are available:

- [@vitejs/plugin-react](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react) uses [Oxc](https://oxc.rs)
- [@vitejs/plugin-react-swc](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react-swc) uses [SWC](https://swc.rs/)

## React Compiler

The React Compiler is not enabled on this template because of its impact on dev & build performances. To add it, see [this documentation](https://react.dev/learn/react-compiler/installation).

## Expanding the Oxlint configuration

If you are developing a production application, we recommend enabling type-aware lint rules by installing `oxlint-tsgolint` and editing `.oxlintrc.json`:

```json
{
  "$schema": "./node_modules/oxlint/configuration_schema.json",
  "plugins": ["react", "typescript", "oxc"],
  "options": {
    "typeAware": true
  },
  "rules": {
    "react/rules-of-hooks": "error",
    "react/only-export-components": ["warn", { "allowConstantExport": true }]
  }
}
```

See the [Oxlint rules documentation](https://oxc.rs/docs/guide/usage/linter/rules) for the full list of rules and categories.

## The app

One app (web redesign, Nov 2026: [`docs/web-redesign/README.md`](../docs/web-redesign/README.md)): five tabs and a Setup gear behind
one router (`src/App.tsx`) and one shell (`src/components/layout/Shell.tsx`). Today and Me live in `src/dashboard/`; the Lab, Plan
and Setup are tab pages (`src/components/layout/TabbedPage.tsx`) over the pages in `src/workspaces/`; every tappable number opens
the metric page (`src/workspaces/Metric.tsx`, addresses in `src/lib/metrics.ts`). Old routes redirect with their query
(`MOVED` in `App.tsx`).

## Source entry vs served output

The Vite **source** entry is `app.html` (`npm run dev` opens it): the one entry page. The deploy workflow
(`.github/workflows/deploy-console.yml`) builds it and commits the result: the site root `index.html` (the build's `app.html` with
its asset paths pointing into `console/assets/`), the hashed chunks in `console/assets/`, and `console/index.html`, a stub that
forwards old `/console/#/...` links, hash included, to the root. GitHub Pages serves them from `main`. Never edit `index.html`,
`console/index.html` or `assets/` by hand.

Until 2026-10-12 the build used `index.html` as its entry, so each CI run re-bundled the previous build instead of `src/`; none of
the source changes after 2026-09-30 reached the deployed console.

The Supabase URL and publishable key are public (same values as the root `index.html` and the Android app). `src/lib/supabase.ts`
uses them as defaults; the `VITE_SUPABASE_*` variables override them when set.

## Checks

- `node --test "src/lib/*.test.ts"` (from the repo root: `node --test "console/src/lib/*.test.ts"`): the console's pure
  modules, including `workflow.test.ts`, the end-to-end chain from a data problem to a decision, `weeks.test.ts` (the whole-week
  windows of Plan › Sessions) and `compare.test.ts` (the phone's pair statistic, ported). Runs in CI.
- `npm run build && npm run e2e`: the browser walkthrough (`e2e/walkthrough.cjs`) against a mocked Supabase: every route and its
  old address, the Lab, Plan and Setup tabs, the metric page and compare, the Google sign-in return, phone-width fit. Needs
  Playwright with Chromium. Screenshots go to `e2e/out/`. `e2e/planning.cjs` (the block planner and meal prep) and
  `e2e/risk.mts` (the risk analysis in Me › EXPOSURES) are separate walkthroughs, run the same way; they print `ok` / `FAIL` lines
  and no summary.
