// Entries that belong to one meal. Each food or drink is logged as its own `meals` row, so a meal of two things is two rows.
// They are one meal when they are close in time and do not carry different labels:
//   - each entry joins the group before it when it follows the group's last entry by 60 min or less;
//   - an unlabelled entry joins any group, a labelled one only a group that is unlabelled or has the same label (a group
//     takes the first label it meets);
//   - entries with different labels (lunch, then a snack) stay separate meals, as the label says.
// Logged times are wall clock labelled +00: their digits are the local time, so the gap is the difference of the digits.
// Kept in step with domain/NutritionRedesign.kt (mealGroups) on the phone.
export const SAME_MEAL_MIN = 60

export interface Groupable { logged_at: string; meal_slot: string | null }

const stamp = (iso: string) => {
  const m = iso.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/)
  return m ? Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]) / 60000 : NaN
}
const label = (s: string | null) => (s?.trim() ? s.trim().toLowerCase() : null)

/** The meals of [entries] in time order, each a list of its entries in time order. */
export function groupMeals<T extends Groupable>(entries: T[]): T[][] {
  const sorted = [...entries].sort((a, b) => a.logged_at.localeCompare(b.logged_at))
  const groups: { items: T[]; label: string | null; last: number }[] = []
  for (const e of sorted) {
    const at = stamp(e.logged_at), l = label(e.meal_slot)
    const g = groups[groups.length - 1]
    if (g && at - g.last <= SAME_MEAL_MIN && (l == null || g.label == null || l === g.label)) {
      g.items.push(e)
      g.label ??= l
      g.last = at
    } else groups.push({ items: [e], label: l, last: at })
  }
  return groups.map((g) => g.items)
}
