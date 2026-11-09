/** Saves an object URL (e.g. a PDF fetched with auth) as a file, then frees it. */
export function saveObjectUrl(url, fileName) {
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 10_000)
}
