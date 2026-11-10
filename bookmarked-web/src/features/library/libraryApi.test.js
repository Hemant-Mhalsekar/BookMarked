import { describe, expect, it } from 'vitest'
import { clampPage, fileUrl, progressPercent, progressText } from './libraryApi'

describe('reading progress', () => {
  it('shows the page and percentage', () => {
    expect(progressText(45, 320)).toBe('Page 45 of 320 · 14%')
    expect(progressText(320, 320)).toBe('Page 320 of 320 · 100%')
    expect(progressText(null, 320)).toBeNull()
    expect(progressPercent(1, 0)).toBe(0)
  })

  it('keeps typed page numbers inside the book', () => {
    expect(clampPage('7', 10)).toBe(7)
    expect(clampPage('0', 10)).toBe(1)
    expect(clampPage('99', 10)).toBe(10)
    expect(clampPage('abc', 10)).toBe(1)
    expect(clampPage(3.6, 10)).toBe(4)
  })
})

describe('fileUrl', () => {
  it('maps API paths onto the API base', () => {
    const base = import.meta.env.VITE_DIRECT_API_URL || import.meta.env.VITE_API_URL || '/api'
    expect(fileUrl('/api/files/abc.def')).toBe(`${base}/files/abc.def`)
    expect(fileUrl('https://cdn.example.com/x.pdf')).toBe('https://cdn.example.com/x.pdf')
  })
})
