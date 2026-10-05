const AUTH_STORAGE_KEY = 'flora_user';
const SESSION_TTL_MS = 8 * 60 * 60 * 1000;

function normalizeSession(session) {
  if (!session) return null;
  const expiresAt = session.expiresAt || Date.now() + SESSION_TTL_MS;
  return { ...session, expiresAt };
}

export function getStoredSession() {
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY);
    if (!raw) return null;
    const session = normalizeSession(JSON.parse(raw));
    if (!session) return null;
    if (Date.now() > session.expiresAt) {
      localStorage.removeItem(AUTH_STORAGE_KEY);
      return null;
    }
    return session;
  } catch {
    return null;
  }
}

export function saveSession(session) {
  const normalized = normalizeSession(session);
  if (!normalized) {
    localStorage.removeItem(AUTH_STORAGE_KEY);
    return;
  }
  localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify({ ...normalized, expiresAt: Date.now() + SESSION_TTL_MS }));
}

export function clearSession() {
  localStorage.removeItem(AUTH_STORAGE_KEY);
}

export async function apiRequest(path, options = {}) {
  const session = getStoredSession();
  const headers = {
    'Content-Type': 'application/json',
    ...(session?.token ? { Authorization: `Bearer ${session.token}` } : {}),
    ...options.headers,
  };

  const response = await fetch(path, {
    ...options,
    headers,
  });

  if (!response.ok) {
    if (response.status === 401) {
      clearSession();
      window.dispatchEvent(new Event('flora:session-invalid'));
    }
    const body = await response.text();
    let message = body;
    try { message = JSON.parse(body).message || body; } catch { /* response is not JSON */ }
    throw new Error(message || `Yêu cầu thất bại (${response.status})`);
  }

  return response.status === 204 ? null : response.json();
}