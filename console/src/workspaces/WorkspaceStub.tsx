// Placeholder for a workspace that is planned but not built yet: says what it will hold and which phase builds it
// (docs/analysis-console/PLAN.md).
interface Props { name: string; phase: string; purpose: string }
export function WorkspaceStub({ name, phase, purpose }: Props) {
  return (
    <div className="state-empty">
      <span>{name.toUpperCase()}</span>
      <span style={{ fontSize: 11, opacity: 0.7, maxWidth: 520, textAlign: 'center', lineHeight: 1.6 }}>{purpose}</span>
      <span style={{ fontSize: 11, opacity: 0.5 }}>Planned · {phase}</span>
    </div>
  )
}
