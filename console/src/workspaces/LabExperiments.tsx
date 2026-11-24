// LAB › EXPERIMENTS (web redesign, phase 4): everything that is tried on purpose, one page with four views (?view=): the
// randomised self-experiments, the interventions and protocols, the informal repeatable ones, and the fuelling experiments.
// All read-only: they are registered and carried out on the phone.
import { useSearchParams } from 'react-router-dom'
import { Experiments } from './Experiments'
import { Interventions } from './Interventions'
import { Repeatable } from './Repeatable'
import { Fueling } from './Fueling'
import './Evidence.css'

const VIEWS = [
  { id: 'randomised', label: 'RANDOMISED' }, { id: 'interventions', label: 'INTERVENTIONS' },
  { id: 'repeatable', label: 'REPEATABLE' }, { id: 'fueling', label: 'FUELLING' },
] as const

export default function LabExperiments() {
  const [params, setParams] = useSearchParams()
  const view = VIEWS.find((v) => v.id === params.get('view'))?.id ?? 'randomised'
  return (
    <>
      <div className="tabbed-views" role="group" aria-label="Experiments">
        {VIEWS.map((v) => <button key={v.id} className="ft-chip" aria-current={v.id === view} onClick={() => setParams(v.id === 'randomised' ? {} : { view: v.id }, { replace: true })}>{v.label}</button>)}
      </div>
      <div className="rlab">
        {view === 'randomised' ? <Experiments /> : view === 'interventions' ? <Interventions /> : view === 'repeatable' ? <Repeatable /> : <Fueling />}
      </div>
    </>
  )
}
