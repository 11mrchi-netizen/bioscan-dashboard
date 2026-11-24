// PLAN › FOOD (web fuel port): two views, listed in the side menu: the fuel plan (what to eat and when, day by day) and the Sunday meal prep.
import { useSearchParams } from 'react-router-dom'
import FuelPlan from './FuelPlan'
import MealPrep from './MealPrep'

export default function Food() {
  const [params] = useSearchParams()
  return params.get('view') === 'prep' ? <MealPrep /> : <FuelPlan />
}
