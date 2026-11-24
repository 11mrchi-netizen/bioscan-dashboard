// A page that knows its own name better than the address does (the metric page, once loaded) says it here; the top bar reads it.
import { create } from 'zustand'

interface PageTitle { title: string | null; set: (t: string | null) => void }
export const usePageTitle = create<PageTitle>((set) => ({ title: null, set: (title) => set({ title }) }))
