// Placeholder for workspace routes not yet implemented.
interface Props { name: string }
export function WorkspaceStub({ name }: Props) {
  return (
    <div className="state-empty">
      <span>{name.toUpperCase()}</span>
      <span style={{ fontSize: 11, opacity: 0.5 }}>Not yet implemented</span>
    </div>
  )
}
