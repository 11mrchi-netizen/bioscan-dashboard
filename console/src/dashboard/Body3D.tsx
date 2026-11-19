// The 3D body from the old dashboard (retired in phase 5), moved into Me › Body (web transition plan, phase 3). Same mesh and
// hologram look; drag to turn, scroll to zoom. Each region now opens what is on file for that part of the body: the
// phone's measurements there (from the BODY document, judged against their error) and the health concerns placed there.
// Regions come from where the tap lands on the mesh (height and distance from the midline), as on the old page.
import { useEffect, useRef, useState } from 'react'
import * as THREE from 'three'
import meshUrl from './assets/body-mesh.json?url'
import { BODY_REGIONS, H_MAX, regionAt, regionOfCode, type MeRow } from './meView'

// Marker anchors: front-most vertices of each region (mesh units), worked out from the mesh itself.
const ANCHORS: Record<string, [number, number, number]> = {
  head: [0, 11.68, 0.84], upper: [0.15, 9.71, 0.63], arms: [-3.4, 7.84, -0.34], core: [0.12, 7.76, 0.58],
  hips: [0, 6.38, 0.55], legs: [-0.45, 3.87, 0.39], feet: [-0.37, 0.1, 0.99],
}

function hasWebGL(): boolean {
  try { const c = document.createElement('canvas'); return !!(c.getContext('webgl2') ?? c.getContext('webgl')) } catch { return false }
}

export interface Concern { title: string; kind: string | null; category: string | null; regions: { code: string; side?: string | null }[] }

export function Body3D({ rows, concerns }: { rows: MeRow[]; concerns: Concern[] }) {
  const mount = useRef<HTMLDivElement>(null)
  const markerLayer = useRef<HTMLDivElement>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [failed, setFailed] = useState<string | null>(() => (hasWebGL() ? null : 'This browser cannot draw the 3D body (WebGL is off).'))

  const rowsIn = (key: string) => rows.filter((r) => r.region && regionOfCode(r.region) === key)
  const concernsIn = (key: string) => concerns.filter((c) => c.regions.some((g) => regionOfCode(g.code) === key))
  const flagged = new Set(BODY_REGIONS.filter((r) => concernsIn(r.key).length > 0).map((r) => r.key))

  useEffect(() => {
    const host = mount.current, layer = markerLayer.current
    if (!host || !layer || !hasWebGL()) return
    let disposed = false
    let frame = 0
    const W = () => host.clientWidth, Hh = () => host.clientHeight
    const scene = new THREE.Scene()
    const camera = new THREE.PerspectiveCamera(38, W() / Hh(), 0.1, 100)
    camera.position.set(0, 0, 9.4)
    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
    renderer.setSize(W(), Hh())
    host.prepend(renderer.domElement)
    scene.add(new THREE.AmbientLight(0x2a2848, 1.7))
    const key = new THREE.DirectionalLight(0x4cf1ff, 1.15); key.position.set(3, 5, 4); scene.add(key)
    const rim = new THREE.DirectionalLight(0xff4cd6, 0.55); rim.position.set(-4, 2, -3); scene.add(rim)
    const root = new THREE.Group(); scene.add(root)
    let body: THREE.Mesh | null = null
    let scale = 1, yOff = 0
    const markers: { key: string; v: THREE.Vector3; el: HTMLElement }[] = []

    fetch(meshUrl).then((r) => r.json()).then((raw: { positions: number[]; normals: number[]; indices: number[] }) => {
      if (disposed) return
      const g = new THREE.BufferGeometry()
      g.setAttribute('position', new THREE.BufferAttribute(new Float32Array(raw.positions), 3))
      g.setAttribute('normal', new THREE.BufferAttribute(new Float32Array(raw.normals), 3))
      g.setIndex(raw.indices)
      const fill = new THREE.MeshStandardMaterial({ color: 0x1e3550, roughness: 0.5, metalness: 0.2, transparent: true, opacity: 0.32, emissive: 0x14283f, emissiveIntensity: 0.55, side: THREE.DoubleSide, depthWrite: false })
      const wire = new THREE.MeshBasicMaterial({ color: 0x4cf1ff, wireframe: true, transparent: true, opacity: 0.18 })
      body = new THREE.Mesh(g, fill)
      const w = new THREE.Mesh(g, wire)
      scale = 5.2 / H_MAX
      yOff = -(H_MAX / 2) * scale
      for (const m of [body, w]) { m.scale.setScalar(scale); m.position.y = yOff; root.add(m) }
      for (const r of BODY_REGIONS) {
        const a = ANCHORS[r.key]
        const el = layer.querySelector<HTMLElement>(`[data-region="${r.key}"]`)
        if (el) markers.push({ key: r.key, v: new THREE.Vector3(a[0] * scale, a[1] * scale + yOff, a[2] * scale), el })
      }
    }).catch(() => setFailed('The 3D body could not be loaded.'))

    let rotY = 0.4, rotX = 0.1, tRotY = 0.4, tRotX = 0.1
    let dragging = false, px = 0, py = 0, moved = 0
    const ray = new THREE.Raycaster()
    const canvas = renderer.domElement
    const down = (e: PointerEvent) => { dragging = true; moved = 0; px = e.clientX; py = e.clientY; canvas.setPointerCapture(e.pointerId) }
    const move = (e: PointerEvent) => {
      if (!dragging) return
      const dx = e.clientX - px, dy = e.clientY - py
      moved += Math.abs(dx) + Math.abs(dy)
      tRotY += dx * 0.008; tRotX = Math.max(-0.5, Math.min(0.6, tRotX + dy * 0.006)); px = e.clientX; py = e.clientY
    }
    const up = (e: PointerEvent) => {
      dragging = false
      if (moved > 6 || !body) return
      const rect = canvas.getBoundingClientRect()
      const m = new THREE.Vector2(((e.clientX - rect.left) / rect.width) * 2 - 1, -((e.clientY - rect.top) / rect.height) * 2 + 1)
      ray.setFromCamera(m, camera)
      const hit = ray.intersectObject(body, false)[0]
      if (hit) { const local = body.worldToLocal(hit.point.clone()); setSelected(regionAt(local.x, local.y)) }
    }
    const wheel = (e: WheelEvent) => { e.preventDefault(); camera.position.z = Math.max(5, Math.min(13, camera.position.z + e.deltaY * 0.004)) }
    canvas.addEventListener('pointerdown', down); canvas.addEventListener('pointermove', move); canvas.addEventListener('pointerup', up)
    canvas.addEventListener('wheel', wheel, { passive: false })
    const ro = new ResizeObserver(() => { camera.aspect = W() / Hh(); camera.updateProjectionMatrix(); renderer.setSize(W(), Hh()) })
    ro.observe(host)
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches

    const tick = () => {
      frame = requestAnimationFrame(tick)
      rotY += (tRotY - rotY) * (reduce ? 1 : 0.1); rotX += (tRotX - rotX) * (reduce ? 1 : 0.1)
      root.rotation.y = rotY; root.rotation.x = rotX * 0.25
      for (const mk of markers) {
        const p = mk.v.clone(); root.localToWorld(p); p.project(camera)
        mk.el.style.left = `${(p.x * 0.5 + 0.5) * W()}px`
        mk.el.style.top = `${(-p.y * 0.5 + 0.5) * Hh()}px`
        mk.el.style.opacity = p.z > 0.985 ? '0.55' : '1'
        mk.el.style.display = 'flex'
      }
      renderer.render(scene, camera)
    }
    tick()
    return () => {
      disposed = true; cancelAnimationFrame(frame); ro.disconnect()
      canvas.removeEventListener('pointerdown', down); canvas.removeEventListener('pointermove', move); canvas.removeEventListener('pointerup', up); canvas.removeEventListener('wheel', wheel)
      renderer.dispose(); canvas.remove()
    }
  }, [])

  const sel = BODY_REGIONS.find((r) => r.key === selected) ?? null
  return (
    <section className="ft-card body3d">
      <div className="body3d-stage" ref={mount}>
        <div className="body3d-markers" ref={markerLayer}>
          {BODY_REGIONS.map((r) => (
            <button key={r.key} data-region={r.key} className={`body3d-marker ${flagged.has(r.key) ? 'flagged' : ''} ${selected === r.key ? 'on' : ''}`}
              onClick={() => setSelected(r.key)} aria-label={r.label} style={{ display: 'none' }}>
              <span className="ring" /><span className="lbl">{r.label.split(',')[0].split(' AND ')[0]}</span>
            </button>
          ))}
        </div>
        {failed && <p className="body3d-failed">{failed}</p>}
        <p className="body3d-hint">DRAG TO TURN · SCROLL TO ZOOM · TAP A REGION</p>
      </div>
      <div className="body3d-panel">
        {!sel ? (
          <>
            <h3 className="me-section-title">THE BODY</h3>
            <p className="me-note muted">Tap a region for what is on file there: measurements and how they moved, and any health concern placed on it.</p>
            <div className="body3d-chips">{BODY_REGIONS.map((r) => <button key={r.key} className={`body3d-chip ${flagged.has(r.key) ? 'flagged' : ''}`} onClick={() => setSelected(r.key)}>{r.label}</button>)}</div>
          </>
        ) : (
          <>
            <div className="body3d-panel-head"><h3 className="me-section-title">{sel.label}</h3><button className="dash-link" onClick={() => setSelected(null)}>ALL REGIONS</button></div>
            <div className="me-label">HEALTH</div>
            {concernsIn(sel.key).length === 0 ? <p className="me-note muted">Nothing on file here.</p> : (
              <ol className="me-rows">{concernsIn(sel.key).map((c, i) => (
                <li key={i} className="me-row"><span className="me-row-label">{c.title}</span><span className="me-row-state st-flagged">{(c.category ?? c.kind ?? 'concern').toUpperCase()}</span>
                  <span className="me-row-sub">{c.regions.filter((g) => regionOfCode(g.code) === sel.key).map((g) => `${g.side ? `${g.side} ` : ''}${g.code.replace(/_/g, ' ')}`).join(', ')}</span></li>
              ))}</ol>
            )}
            <div className="me-label">MEASUREMENTS</div>
            {rowsIn(sel.key).length === 0 ? <p className="me-note muted">No measurement taken here.</p> : (
              <ol className="me-rows">{rowsIn(sel.key).map((r, i) => (
                <li key={i} className="me-row"><span className="me-row-label">{r.label}</span>{r.value && <span className="me-row-value">{r.value}</span>}
                  {r.state && <span className={`me-row-state st-${r.state.replace(/\s+/g, '-').toLowerCase()}`}>{r.state}</span>}{r.sub && <span className="me-row-sub">{r.sub}</span>}</li>
              ))}</ol>
            )}
          </>
        )}
      </div>
    </section>
  )
}
