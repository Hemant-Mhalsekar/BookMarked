// localStorage can be unavailable (private mode, blocked storage) or hold junk.
// These helpers never throw.

export function loadJson(key, fallback) {
  try {
    const raw = window.localStorage.getItem(key)
    return raw == null ? fallback : JSON.parse(raw)
  } catch {
    return fallback
  }
}

export function saveJson(key, value) {
  try {
    window.localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // storage full or blocked: the in-memory state still works for this visit
  }
}

export function removeKey(key) {
  try {
    window.localStorage.removeItem(key)
  } catch {
    // ignore
  }
}
