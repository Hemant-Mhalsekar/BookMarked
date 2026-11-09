import { useCallback, useEffect, useRef, useState } from 'react'
import { Document, Page, pdfjs } from 'react-pdf'
import 'react-pdf/dist/Page/AnnotationLayer.css'
import 'react-pdf/dist/Page/TextLayer.css'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import { Spinner } from '../../components/ui'
import { clampPage } from './libraryApi'

// pdf.js parses the PDF in a web worker, served from our own origin
pdfjs.GlobalWorkerOptions.workerSrc = workerUrl

const ZOOMS = [0.6, 0.75, 0.9, 1, 1.15, 1.3, 1.5, 1.75, 2]

/**
 * In-browser PDF reader: page by page, zoom, dark mode, full screen and arrow keys.
 * {@code onPage(page, numPages)} fires whenever the page changes (the caller saves progress).
 */
export default function PdfReader({ file, title, initialPage = 1, onPage, onClose, banner }) {
  const rootRef = useRef(null)
  const areaRef = useRef(null)
  const [numPages, setNumPages] = useState(null)
  const [page, setPage] = useState(1)
  const [typed, setTyped] = useState('1')
  const [zoomIndex, setZoomIndex] = useState(3)
  const [width, setWidth] = useState(720)
  const [dark, setDark] = useState(() => {
    try {
      return localStorage.getItem('bm-reader-dark') === '1'
    } catch {
      return false
    }
  })
  const [full, setFull] = useState(false)
  const [error, setError] = useState(null)

  // Page width follows the reading area (re-measured on resize)
  useEffect(() => {
    const el = areaRef.current
    if (!el) return undefined
    const measure = () => setWidth(Math.min(900, Math.max(260, el.clientWidth - 32)))
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  useEffect(() => {
    const onChange = () => setFull(Boolean(document.fullscreenElement))
    document.addEventListener('fullscreenchange', onChange)
    return () => document.removeEventListener('fullscreenchange', onChange)
  }, [])

  const go = useCallback((next) => {
    if (!numPages) return
    const p = clampPage(next, numPages)
    setPage(p)
    setTyped(String(p))
    onPage?.(p, numPages)
    areaRef.current?.scrollTo({ top: 0 })
  }, [numPages, onPage])

  useEffect(() => {
    const onKey = (e) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return
      if (e.key === 'ArrowRight' || e.key === 'PageDown') go(page + 1)
      else if (e.key === 'ArrowLeft' || e.key === 'PageUp') go(page - 1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [go, page])

  const toggleDark = () => {
    setDark((d) => {
      try {
        localStorage.setItem('bm-reader-dark', d ? '0' : '1')
      } catch {
        // private mode: the choice just isn't remembered
      }
      return !d
    })
  }

  const toggleFull = () => {
    if (document.fullscreenElement) document.exitFullscreen?.()
    else rootRef.current?.requestFullscreen?.()
  }

  const zoom = ZOOMS[zoomIndex]

  return (
    <div ref={rootRef} className={`flex h-[calc(100vh-4rem)] flex-col ${dark ? 'bg-slate-900 text-slate-100' : 'bg-slate-200 text-slate-900'} ${full ? 'h-screen' : ''}`}>
      <div className={`flex flex-wrap items-center gap-2 border-b px-3 py-2 text-sm ${dark ? 'border-slate-700 bg-slate-800' : 'border-slate-300 bg-white'}`}>
        {onClose && (
          <button type="button" onClick={onClose} className="rounded-md px-2 py-1 font-medium hover:bg-black/5" aria-label="Back">
            ←
          </button>
        )}
        <p className="hidden min-w-0 flex-1 truncate font-medium sm:block" title={title}>{title}</p>
        <span className="flex-1 sm:hidden" />

        <div className="flex items-center gap-1" aria-label="Pages">
          <ToolButton dark={dark} onClick={() => go(page - 1)} disabled={!numPages || page <= 1} label="Previous page">‹</ToolButton>
          <form onSubmit={(e) => {
            e.preventDefault()
            go(typed)
          }} className="flex items-center gap-1">
            <input value={typed} onChange={(e) => setTyped(e.target.value.replace(/\D/g, ''))} onBlur={() => go(typed)}
                   inputMode="numeric" aria-label="Page number"
                   className={`w-12 rounded border px-1 py-0.5 text-center ${dark ? 'border-slate-600 bg-slate-900' : 'border-slate-300'}`} />
            <span className="whitespace-nowrap text-xs opacity-70">of {numPages ?? '…'}</span>
          </form>
          <ToolButton dark={dark} onClick={() => go(page + 1)} disabled={!numPages || page >= numPages} label="Next page">›</ToolButton>
        </div>

        {/* phones pinch to zoom */}
        <div className="hidden items-center gap-1 sm:flex" aria-label="Zoom">
          <ToolButton dark={dark} onClick={() => setZoomIndex((z) => Math.max(0, z - 1))} disabled={zoomIndex === 0} label="Zoom out">−</ToolButton>
          <span className="w-10 text-center text-xs tabular-nums">{Math.round(zoom * 100)}%</span>
          <ToolButton dark={dark} onClick={() => setZoomIndex((z) => Math.min(ZOOMS.length - 1, z + 1))} disabled={zoomIndex === ZOOMS.length - 1} label="Zoom in">+</ToolButton>
        </div>
        <ToolButton dark={dark} onClick={toggleDark} label={dark ? 'Light mode' : 'Dark mode'} pressed={dark}>
          {dark ? '☀' : '☾'}
        </ToolButton>
        <ToolButton dark={dark} onClick={toggleFull} label={full ? 'Exit full screen' : 'Full screen'} pressed={full}>⛶</ToolButton>
      </div>

      {banner}

      <div ref={areaRef} className="flex-1 overflow-auto">
        {error ? (
          <div className="mx-auto max-w-md p-10 text-center">
            <p className="font-medium">We couldn&apos;t open this book.</p>
            <p className="mt-1 text-sm opacity-80">{error}</p>
          </div>
        ) : (
          <Document
            file={file}
            onLoadSuccess={({ numPages: n }) => {
              setNumPages(n)
              const start = clampPage(initialPage, n)
              setPage(start)
              setTyped(String(start))
            }}
            onLoadError={() => setError('The link may have expired. Reload the page to try again.')}
            loading={<div className="flex justify-center p-16"><Spinner className="h-8 w-8" /></div>}
            className="flex justify-center py-4"
          >
            {numPages && (
              <div className={`shadow-lg ${dark ? '[&_canvas]:[filter:invert(0.88)_hue-rotate(180deg)]' : ''}`}>
                <Page pageNumber={page} width={Math.round(width * zoom)}
                      loading={<div style={{ width: Math.round(width * zoom), height: Math.round(width * zoom * 1.4) }} className="bg-white/60" />} />
              </div>
            )}
          </Document>
        )}
      </div>

      {numPages && (
        <div className="h-1 w-full bg-black/10" aria-hidden="true">
          <div className="h-1 bg-amber-400 transition-all" style={{ width: `${(page / numPages) * 100}%` }} />
        </div>
      )}
    </div>
  )
}

function ToolButton({ children, onClick, disabled, label, pressed, dark }) {
  return (
    <button type="button" onClick={onClick} disabled={disabled} aria-label={label} title={label}
            aria-pressed={pressed}
            className={`flex h-8 min-w-8 items-center justify-center rounded-md px-2 text-base disabled:opacity-40 ${
              pressed ? 'bg-amber-400 text-slate-900' : dark ? 'hover:bg-white/10' : 'hover:bg-slate-100'}`}>
      {children}
    </button>
  )
}
