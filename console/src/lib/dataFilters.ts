// The Data workspace's filters as they live in the URL (#/data?table=..&from=..&to=..|date=..&id=..&source=..&col=..&empty=..),
// so issues, evidence and the Inspector can link to a filtered view (lib/links.ts). Pure: tested in workflow.test.ts.
export interface Filters {
  table: string
  from: string
  to: string
  id: string
  source: string
  col: string
  empty: '' | 'null' | 'notnull'
}

export function readFilters(p: URLSearchParams): Filters {
  const date = p.get('date') ?? ''
  const empty = p.get('empty')
  return {
    table: p.get('table') ?? 'wearable_daily',
    from: p.get('from') ?? date,
    to: p.get('to') ?? date,
    id: p.get('id') ?? '',
    source: p.get('source') ?? '',
    col: p.get('col') ?? '',
    empty: empty === 'null' || empty === 'notnull' ? empty : '',
  }
}
