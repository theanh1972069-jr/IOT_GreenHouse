const AUTH_STORAGE_KEY = 'flora_user';
const SESSION_TTL_MS = 8 * 60 * 60 * 1000;
const DEFAULT_REQUEST_TIMEOUT_MS = 15000;

export class ApiRequestError extends Error {
  constructor(message, status, body = null) {
    super(message);
    this.name = 'ApiRequestError';
    this.status = status;
    this.body = body;
  }
}

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

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '');

export function clearSession() {
  localStorage.removeItem(AUTH_STORAGE_KEY);
}

export async function apiRequest(path, options = {}) {
  const session = getStoredSession();
  const { timeoutMs = DEFAULT_REQUEST_TIMEOUT_MS, signal: callerSignal, ...fetchOptions } = options;
  const headers = {
    'Content-Type': 'application/json',
    ...(session?.token ? { Authorization: `Bearer ${session.token}` } : {}),
    ...fetchOptions.headers,
  };
  const url = path.startsWith('http') ? path : `${API_BASE_URL}${path}`;
  const controller = new AbortController();
  let timedOut = false;
  const timeoutId = window.setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  const abortFromCaller = () => controller.abort(callerSignal.reason);

  if (callerSignal?.aborted) {
    abortFromCaller();
  } else {
    callerSignal?.addEventListener('abort', abortFromCaller, { once: true });
  }

  try {
    let response;
    try {
      response = await fetch(url, {
        ...fetchOptions,
        headers,
        signal: controller.signal,
      });
    } catch (requestError) {
      if (timedOut) {
        throw new ApiRequestError('Máy chủ không phản hồi trong thời gian cho phép.', 0);
      }
      if (controller.signal.aborted) {
        throw new ApiRequestError('Yêu cầu đã bị hủy.', 0);
      }
      if (requestError instanceof TypeError) {
        throw new ApiRequestError('Không thể kết nối đến máy chủ API.', 0);
      }
      throw requestError;
    }

    const body = await response.text();
    let payload = null;
    let invalidJson = false;
    if (body) {
      try {
        payload = JSON.parse(body);
      } catch {
        invalidJson = true;
        payload = body;
      }
    }

    if (!response.ok) {
      if (response.status === 401) {
        clearSession();
        window.dispatchEvent(new Event('flora:session-invalid'));
      }
      let message = typeof payload === 'object' && payload !== null
        ? payload.message || payload.error
        : payload;
      if (typeof message !== 'string' || !message.trim() || message.trim().startsWith('<')) {
        message = `Yêu cầu thất bại (${response.status})`;
      }
      throw new ApiRequestError(message, response.status, payload);
    }

    if (response.status === 204 || !body) return null;
    if (invalidJson) {
      throw new ApiRequestError('Máy chủ trả về dữ liệu không đúng định dạng.', response.status, payload);
    }
    return payload;
  } catch (requestError) {
    if (timedOut) {
      throw new ApiRequestError('Máy chủ không phản hồi trong thời gian cho phép.', 0);
    }
    if (controller.signal.aborted && !(requestError instanceof ApiRequestError)) {
      throw new ApiRequestError('Yêu cầu đã bị hủy.', 0);
    }
    throw requestError;
  } finally {
    window.clearTimeout(timeoutId);
    callerSignal?.removeEventListener('abort', abortFromCaller);
  }
}