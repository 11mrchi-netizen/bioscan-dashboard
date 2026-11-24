// LAB › EXPERIMENTS (web redesign, phase 4): everything that is tried on purpose, one page with four views (?view=, in the side menu): the
// randomised self-experiments, the interventions and protocols, the informal repeatable ones, and the fuelling experiments.
// All read-only: they are registered and carried out on the phone.
import { useSearchParams } from 'react-router-dom'
import { Experiments } from './Experiments'
import { Interventions } from './Interventions'
import { Repeatable } from './Repeatable'
import { Fueling } from './Fueling'
import './Evidence.css'

export default function LabExperiments() {
  const [params] = useSearchParams()
  const view = ['interventions', 'repeatable', 'fueling'].find((v) => v === params.get('view')) ?? 'randomised'
  return (
    <div className="rlab">
      {view === 'randomised' ? <Experiments /> : view === 'interventions' ? <Interventions /> : view === 'repeatable' ? <Repeatable /> : <Fueling />}
    </div>
  )
}
