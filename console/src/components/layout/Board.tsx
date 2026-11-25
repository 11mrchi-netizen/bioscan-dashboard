// The packed board (layout, Nov 2026): cards of any height in as many columns as the width allows, each card placed in the
// next free spot so a short card beside a tall one leaves no hole under it. The columns come from the board's CSS
// (`repeat(auto-fit, minmax(…))`); the board measures each card and makes it span as many fine rows (`--board-row`) as its
// height needs. Reading order stays the source order, left to right. A child with `wide` (or `grid-column: 1 / -1`) spans
// every column. Without JavaScript, or before the first measure, it is the plain grid it was.
import { useLayoutEffect, useRef, type HTMLAttributes, type ReactNode } from 'react'

export const BOARD_ROW = 4 // px: the fine row every card's height is rounded up to

/** Packs the direct children of the element `ref` points at, and keeps them packed as they or the width change. */
export function useBoard<T extends HTMLElement>() {
  const ref = useRef<T>(null)
  useLayoutEffect(() => {
    const board = ref.current
    if (!board) return
    let frame = 0
    const pack = () => {
      frame = 0
      const style = getComputedStyle(board)
      if (style.display !== 'grid') { delete board.dataset.packed; return }
      const gap = parseFloat(style.columnGap) || 0
      for (const el of Array.from(board.children) as HTMLElement[]) {
        const h = el.getBoundingClientRect().height
        el.style.gridRowEnd = h > 0 ? `span ${Math.max(1, Math.ceil((h + gap) / BOARD_ROW))}` : ''
      }
      board.dataset.packed = ''
    }
    const schedule = () => { if (!frame) frame = requestAnimationFrame(pack) }
    const sizes = new ResizeObserver(schedule)
    const watch = () => { sizes.disconnect(); sizes.observe(board); for (const el of Array.from(board.children)) sizes.observe(el) }
    const kids = new MutationObserver(() => { watch(); schedule() })
    kids.observe(board, { childList: true })
    watch()
    pack()
    return () => { sizes.disconnect(); kids.disconnect(); if (frame) cancelAnimationFrame(frame) }
  }, [])
  return ref
}

/** A packed board. `className` names the page's grid (its columns and gap); `ft-board` adds the packing rows. */
export function Board({ className = '', children, ...rest }: HTMLAttributes<HTMLDivElement> & { children: ReactNode }) {
  const ref = useBoard<HTMLDivElement>()
  return <div ref={ref} className={`ft-board ${className}`.trim()} {...rest}>{children}</div>
}
