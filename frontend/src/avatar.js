import { useEffect, useState } from 'react';

function userKey(user) {
  return user?.userId ?? user?.id ?? null;
}

const avatarKey = (id) => `flora_avatar_${id}`;
const phoneKey = (id) => `flora_phone_${id}`;

export function getAvatar(user) {
  const id = userKey(user);
  if (id == null) return null;
  try {
    return localStorage.getItem(avatarKey(id));
  } catch {
    return null;
  }
}

export function setAvatar(user, dataUrl) {
  const id = userKey(user);
  if (id == null) return false;
  try {
    if (dataUrl) localStorage.setItem(avatarKey(id), dataUrl);
    else localStorage.removeItem(avatarKey(id));
    window.dispatchEvent(new CustomEvent('flora:avatar', { detail: { userId: id } }));
    return true;
  } catch {
    window.alert('Không thể lưu ảnh đại diện (bộ nhớ trình duyệt đầy?).');
    return false;
  }
}

// Single source of truth for the avatar: Topbar, Sidebar and Profile all read
// through this hook, so a change is reflected everywhere instantly (and across
// tabs via the storage event). Avatars are stored per user id in localStorage
// because the backend has no avatar upload API.
export function useAvatar(user) {
  const id = userKey(user);
  const [avatar, setAvatarState] = useState(() => getAvatar(user));

  useEffect(() => {
    setAvatarState(getAvatar(user));
    const onAvatar = (event) => {
      if (!event.detail || event.detail.userId === id) setAvatarState(getAvatar(user));
    };
    const onStorage = (event) => {
      if (event.key === avatarKey(id)) setAvatarState(event.newValue);
    };
    window.addEventListener('flora:avatar', onAvatar);
    window.addEventListener('storage', onStorage);
    return () => {
      window.removeEventListener('flora:avatar', onAvatar);
      window.removeEventListener('storage', onStorage);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  return avatar;
}

export function fileToAvatarDataUrl(file, size = 128) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      try {
        const canvas = document.createElement('canvas');
        canvas.width = size;
        canvas.height = size;
        const ctx = canvas.getContext('2d');
        const side = Math.min(img.width, img.height);
        ctx.drawImage(img, (img.width - side) / 2, (img.height - side) / 2, side, side, 0, 0, size, size);
        URL.revokeObjectURL(url);
        resolve(canvas.toDataURL('image/jpeg', 0.85));
      } catch (error) {
        reject(error);
      }
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('Không đọc được file ảnh.'));
    };
    img.src = url;
  });
}

export function getPhone(user) {
  const id = userKey(user);
  if (id == null) return '';
  try {
    return localStorage.getItem(phoneKey(id)) || '';
  } catch {
    return '';
  }
}

export function setPhone(user, value) {
  const id = userKey(user);
  if (id == null) return;
  try {
    localStorage.setItem(phoneKey(id), value);
  } catch {
    /* storage unavailable */
  }
}

export function roleLabel(role) {
  switch (String(role || '').replace(/^ROLE_/i, '').toUpperCase()) {
    case 'ADMIN':
      return 'Quản trị viên (Admin)';
    case 'OPERATOR':
      return 'Giám sát (Operator)';
    case 'VIEWER':
      return 'Người xem (Viewer)';
    case 'USER':
      return 'Người dùng';
    default:
      return role || 'Chưa gán vai trò';
  }
}
