import React, { createContext, useContext, useEffect, useState } from 'react';
import { X } from 'lucide-react';

export const PermissionContext = createContext(null);

/**
 * Chuẩn hóa mã vai trò: xóa tiền tố ROLE_ và chuyển về chữ hoa.
 * Ví dụ: 'ROLE_ADMIN' -> 'ADMIN', 'operator' -> 'OPERATOR'.
 */
export function normalizeRole(role) {
  return String(role || '').replace(/^ROLE_/i, '').trim().toUpperCase();
}

/**
 * Nhãn hiển thị tiếng Việt chính thức cho 3 vai trò:
 * ADMIN -> Quản trị viên
 * OPERATOR -> Người vận hành
 * VIEWER -> Người xem
 */
export function roleLabel(role) {
  const norm = normalizeRole(role);
  switch (norm) {
    case 'ADMIN':
      return 'Quản trị viên';
    case 'OPERATOR':
      return 'Người vận hành';
    case 'VIEWER':
      return 'Người xem';
    case 'USER':
      return 'Người dùng';
    default:
      return role || 'Chưa gán vai trò';
  }
}

/**
 * Kiểm tra người dùng có vai trò cụ thể hay không.
 */
export function hasRole(user, targetRole) {
  if (!user || !targetRole) return false;
  const target = normalizeRole(targetRole);
  return (user.roles || []).some((r) => normalizeRole(r) === target);
}

/**
 * Kiểm tra người dùng có ít nhất một trong danh sách vai trò cho phép hay không.
 */
export function hasAnyRole(user, requiredRoles) {
  if (!user || !requiredRoles) return false;
  const list = Array.isArray(requiredRoles) ? requiredRoles : [requiredRoles];
  return list.some((r) => hasRole(user, r));
}

/**
 * Hook trung tâm kiểm tra quyền dựa trên ma trận quyền duy nhất của hệ thống:
 * - canViewMonitoring: Tất cả 3 vai trò (ADMIN, OPERATOR, VIEWER)
 * - canControl: ADMIN, OPERATOR
 * - canManageAlerts: ADMIN, OPERATOR
 * - canManageSchedules: ADMIN, OPERATOR
 * - canManageDevices: ADMIN
 * - canManageUsers: ADMIN
 * - canViewAuditLogs: ADMIN
 */
export function usePermission(explicitUser) {
  const contextUser = useContext(PermissionContext);
  const user = explicitUser || contextUser;

  const primaryRole = normalizeRole(user?.roles?.[0] || 'VIEWER');
  const isAdmin = hasRole(user, 'ADMIN');
  const isOperator = hasRole(user, 'OPERATOR');
  const isViewer = !isAdmin && !isOperator; // Mọi tài khoản không phải Admin hay Operator đều là Viewer

  return {
    user,
    role: primaryRole,
    roleLabel: roleLabel(user?.roles?.[0]),
    isAdmin,
    isOperator,
    isViewer,
    canViewMonitoring: true,
    canControl: isAdmin || isOperator,
    canManageAlerts: isAdmin || isOperator,
    canManageSchedules: isAdmin || isOperator,
    canManageDevices: isAdmin,
    canManageUsers: isAdmin,
    canViewAuditLogs: isAdmin,
    hasRole: (role) => hasRole(user, role),
    hasAnyRole: (roles) => hasAnyRole(user, roles),
  };
}

/**
 * Component trung tâm kiểm tra quyền hiển thị.
 * Phần tử không có quyền sẽ KHÔNG ĐƯỢC RENDER (không chỉ disable, không làm mờ).
 *
 * Cách dùng:
 *   <Can perform="control"> <button>Ngắt khẩn cấp</button> </Can>
 *   <Can roles={["ADMIN", "OPERATOR"]}> <button>Điều khiển</button> </Can>
 */
export function Can({ perform, roles, user: explicitUser, children, fallback = null }) {
  const perm = usePermission(explicitUser);
  let allowed = false;

  if (roles) {
    allowed = perm.hasAnyRole(roles);
  } else if (perform) {
    switch (perform) {
      case 'control':
        allowed = perm.canControl;
        break;
      case 'manage-alerts':
        allowed = perm.canManageAlerts;
        break;
      case 'manage-schedules':
        allowed = perm.canManageSchedules;
        break;
      case 'manage-devices':
        allowed = perm.canManageDevices;
        break;
      case 'manage-users':
        allowed = perm.canManageUsers;
        break;
      case 'audit-logs':
        allowed = perm.canViewAuditLogs;
        break;
      case 'view-monitoring':
        allowed = perm.canViewMonitoring;
        break;
      default:
        allowed = false;
    }
  }

  if (!allowed) {
    return fallback;
  }

  return typeof children === 'function' ? children(perm) : children;
}

/**
 * Phát sự kiện hiển thị Toast toàn hệ thống
 */
export function showToast(kind, text) {
  window.dispatchEvent(new CustomEvent('flora:toast', { detail: { kind, text } }));
}

/**
 * Container hiển thị Toast thông báo ở góc màn hình
 */
export function ToastContainer() {
  const [toasts, setToasts] = useState([]);

  useEffect(() => {
    function onToast(event) {
      if (!event.detail) return;
      const { kind = 'info', text = '' } = event.detail;
      const id = Date.now() + Math.random();
      setToasts((list) => [...list, { id, kind, text }]);
      window.setTimeout(() => {
        setToasts((list) => list.filter((t) => t.id !== id));
      }, 4500);
    }
    window.addEventListener('flora:toast', onToast);
    return () => window.removeEventListener('flora:toast', onToast);
  }, []);

  if (!toasts.length) return null;

  return (
    <div className="toast-stack" role="status" aria-live="polite">
      {toasts.map((toast) => (
        <div key={toast.id} className={`toast toast-${toast.kind}`}>
          <span className="toast-text">{toast.text}</span>
          <button
            type="button"
            className="icon-button toast-close"
            aria-label="Đóng thông báo"
            onClick={() => setToasts((list) => list.filter((t) => t.id !== toast.id))}
          >
            <X size={14} />
          </button>
        </div>
      ))}
    </div>
  );
}
