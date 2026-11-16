import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, NavLink, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import {
  AlertsPage,
  AuditLogsPage,
  DeviceLifecyclePage,
  InviteUserPage,
  ProfilePage,
  UsersPage,
  timeAgo,
} from './pages.jsx';
import { apiRequest, clearSession, getStoredSession, saveSession } from './api.js';
import { useAvatar } from './avatar.js';
import {
  Can,
  PermissionContext,
  ToastContainer,
  hasAnyRole,
  hasRole,
  roleLabel,
  showToast,
  usePermission,
} from './permissions.jsx';
import { TimeWheelInput } from './TimeWheelPicker.jsx';
import {
  Activity,
  AlertCircle,
  AlertTriangle,
  ArrowDownRight,
  ArrowUpRight,
  Bell,
  Check,
  ChevronDown,
  Clock3,
  Cpu,
  Droplet,
  Droplets,
  Eye,
  EyeOff,
  Fan,
  FileClock,
  Home,
  Info,
  Leaf,
  Lightbulb,
  LoaderCircle,
  LogOut,
  Menu,
  Minus,
  PanelLeftClose,
  PanelLeftOpen,
  Pencil,
  Play,
  Plus,
  Power,
  RefreshCw,
  Radio,
  Search,
  Settings2,
  SlidersHorizontal,
  Sprout,
  Sun,
  Thermometer,
  Trash2,
  UserRound,
  UsersRound,
  Waves,
  Wifi,
  X,
  Zap,
} from 'lucide-react';

const navigation = [
  { title: 'Tổng quan', items: [{ label: 'Dashboard', path: '/dashboard', icon: Activity }] },
  {
    title: 'Vận hành',
    items: [
      { label: 'Thiết bị', path: '/devices', icon: Cpu },
      { label: 'Cảnh báo', path: '/alerts', icon: Bell },
      { label: 'Vòng đời thiết bị', path: '/device-lifecycle', icon: Sprout, requiredRoles: ['ADMIN'] },
    ],
  },
  {
    title: 'Quản trị',
    items: [
      { label: 'Người dùng', path: '/users', icon: UsersRound, requiredRoles: ['ADMIN'] },
      { label: 'Nhật ký hệ thống', path: '/audit-logs', icon: FileClock, requiredRoles: ['ADMIN'] },
    ],
  },
];

const pageTitles = {
  '/alerts': ['Cảnh báo', 'Theo dõi và xử lý sự kiện từ nhà kính.'],
  '/device-lifecycle': ['Vòng đời thiết bị', 'Theo dõi trạng thái triển khai và bảo trì node.'],
  '/users': ['Người dùng', 'Quản lý tài khoản và quyền truy cập hệ thống.'],
  '/audit-logs': ['Nhật ký hệ thống', 'Lịch sử hoạt động và thay đổi cấu hình.'],
  '/my-profile': ['Hồ sơ của tôi', 'Thông tin tài khoản đang đăng nhập.'],
  '/invite-user': ['Tạo tài khoản', 'Cấp quyền truy cập cho thành viên trong hệ thống.'],
};
const refreshIntervalMs = 5000;

function getStoredUser() {
  return getStoredSession();
}

function LoginPage({ onLogin }) {
  const navigate = useNavigate();
  const [form, setForm] = useState({ identifier: '', password: '' });
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  async function handleSubmit(event) {
    event.preventDefault();
    setLoading(true);
    setError('');

    try {
      const user = await apiRequest('/api/auth/login', {
        method: 'POST',
        body: JSON.stringify({
          identifier: form.identifier,
          password: form.password,
        }),
      });

      onLogin(user);
      navigate('/dashboard', { replace: true });
    } catch (loginError) {
      setError(loginError.message || 'Đăng nhập thất bại.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="auth-shell">
      <div className="auth-card">
        <div className="auth-header">
          <div className="brand-mark auth-brand-mark"><Leaf size={20} strokeWidth={2.5} /></div>
          <div>
            <div className="brand-name">FloraCore</div>
            <div className="brand-caption">GREENHOUSE SYSTEMS</div>
          </div>
        </div>

        <div className="auth-body">
          <div className="auth-kicker">Đăng nhập</div>
          <h1>Chào mừng trở lại</h1>
          <p>Vui lòng đăng nhập để tiếp tục quản lý nhà kính.</p>

          {error && <div className="notice notice-error"><AlertTriangle size={16} /><span>{error}</span></div>}

          <form className="auth-form" onSubmit={handleSubmit}>
            <label>
              <span>Tên đăng nhập hoặc email</span>
              <input
                type="text"
                value={form.identifier}
                onChange={(event) => setForm((current) => ({ ...current, identifier: event.target.value }))}
                placeholder="Nhập tên đăng nhập hoặc email"
                autoComplete="username"
              />
            </label>

            <div className="auth-field">
              <label htmlFor="login-password">Mật khẩu</label>
              <div className="password-input-wrap">
                <input
                  id="login-password"
                  type={showPassword ? 'text' : 'password'}
                  value={form.password}
                  onChange={(event) => setForm((current) => ({ ...current, password: event.target.value }))}
                  placeholder="Nhập mật khẩu"
                  autoComplete="current-password"
                />
                <button
                  className="password-toggle"
                  type="button"
                  aria-label={showPassword ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'}
                  aria-pressed={showPassword}
                  onClick={() => setShowPassword((visible) => !visible)}
                >
                  {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
              </div>
            </div>

            <button className="button button-primary auth-submit" type="submit" disabled={loading}>
              {loading ? 'Đang đăng nhập...' : 'Đăng nhập'}
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}

function ProtectedRoute({ currentUser, requiredRoles, requiredRole, children }) {
  const allowedRoles = requiredRoles || (requiredRole ? (Array.isArray(requiredRole) ? requiredRole : [requiredRole]) : null);
  const isAllowed = !currentUser || !allowedRoles || hasAnyRole(currentUser, allowedRoles);

  useEffect(() => {
    if (currentUser && !isAllowed) {
      showToast('error', 'Bạn không có quyền truy cập chức năng này');
    }
  }, [currentUser, isAllowed]);

  if (!currentUser) {
    return <Navigate to="/login" replace />;
  }
  if (!isAllowed) {
    return <Navigate to="/dashboard" replace />;
  }
  return children;
}

function TopbarExtras({ currentUser, onLogout }) {
  const [bellOpen, setBellOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [openCount, setOpenCount] = useState(0);
  const [recent, setRecent] = useState([]);
  const bellRef = useRef(null);
  const menuRef = useRef(null);

  const loadBell = useCallback(async () => {
    try {
      const payload = await apiRequest('/api/alerts?status=OPEN&page=0&size=5');
      const content = Array.isArray(payload) ? payload : payload.content || [];
      if (Array.isArray(payload)) {
        setRecent(content);
        setOpenCount(content.length);
      } else {
        setRecent(content);
        setOpenCount(payload.totalElements ?? 0);
      }
    } catch {
      setRecent([]);
      setOpenCount(0);
    }
  }, []);

  useEffect(() => {
    loadBell();
    const intervalId = window.setInterval(loadBell, 30000);
    return () => window.clearInterval(intervalId);
  }, [loadBell]);

  useEffect(() => {
    function onPointerDown(event) {
      if (bellRef.current && !bellRef.current.contains(event.target)) setBellOpen(false);
      if (menuRef.current && !menuRef.current.contains(event.target)) setMenuOpen(false);
    }
    function onKeyDown(event) {
      if (event.key === 'Escape') {
        setBellOpen(false);
        setMenuOpen(false);
      }
    }
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, []);

  const displayName = currentUser.fullName || currentUser.username || 'U';
  const initials = displayName.split(' ').map((part) => part[0]).join('').slice(0, 2).toUpperCase();
  const subline = `${roleLabel(currentUser.roles?.[0])}${currentUser.email ? ` · ${currentUser.email}` : ''}`;
  const avatar = useAvatar(currentUser);

  return <>
    <div className="topbar-dropdown" ref={bellRef}>
      <button
        className="icon-button bell-button"
        type="button"
        aria-label={`Thông báo${openCount ? `, ${openCount} chưa xử lý` : ''}`}
        aria-expanded={bellOpen}
        aria-haspopup="menu"
        onClick={() => { setBellOpen((open) => !open); setMenuOpen(false); if (!bellOpen) loadBell(); }}
      >
        <Bell size={18} />
        {openCount > 0 && <span className="bell-badge">{openCount > 99 ? '99+' : openCount}</span>}
      </button>
      {bellOpen && (
        <div className="dropdown-panel bell-panel" role="menu" aria-label="Cảnh báo chưa xử lý">
          <div className="dropdown-heading"><strong>Cảnh báo chưa xử lý</strong><span>{openCount}</span></div>
          {recent.length ? recent.map((alert) => (
            <NavLink key={alert.id} className="bell-item" to="/alerts" onClick={() => setBellOpen(false)}>
              <i className={`bell-dot ${alert.severity === 'CRITICAL' ? 'bell-dot-crit' : 'bell-dot-warn'}`} />
              <span><strong>{alert.message}</strong><small>{alert.deviceName || alert.deviceId} · {timeAgo(alert.createdAt)}</small></span>
            </NavLink>
          )) : <div className="dropdown-empty">Không có cảnh báo mở.</div>}
          <NavLink className="dropdown-footer" to="/alerts" onClick={() => setBellOpen(false)}>Xem tất cả cảnh báo</NavLink>
        </div>
      )}
    </div>
    <div className="topbar-dropdown" ref={menuRef}>
      <button
        className="avatar-button"
        type="button"
        aria-label="Mở menu tài khoản"
        aria-expanded={menuOpen}
        aria-haspopup="menu"
        onClick={() => { setMenuOpen((open) => !open); setBellOpen(false); }}
      >
        <span className="avatar-mini">{avatar ? <img src={avatar} alt="" className="avatar-img" /> : initials}</span>
        <ChevronDown size={15} />
      </button>
      {menuOpen && (
        <div className="dropdown-panel menu-panel" role="menu" aria-label="Menu tài khoản">
          <div className="menu-profile"><span className="avatar-mini">{avatar ? <img src={avatar} alt="" className="avatar-img" /> : initials}</span><span><strong>{displayName}</strong><small>{subline}</small></span></div>
          <NavLink to="/my-profile" onClick={() => setMenuOpen(false)}><UserRound size={15} /> Hồ sơ của tôi</NavLink>
          <button type="button" onClick={onLogout}><LogOut size={15} /> Đăng xuất</button>
        </div>
      )}
    </div>
  </>;
}

function App() {
  const [currentUser, setCurrentUser] = useState(getStoredUser);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const location = useLocation();
  const sidebarAvatar = useAvatar(currentUser);

  useEffect(() => {
    try {
      const settings = JSON.parse(localStorage.getItem('flora_settings') || '{}');
      document.documentElement.dataset.theme = settings.themeMode === 'dark' ? 'dark' : 'light';
    } catch {
      document.documentElement.dataset.theme = 'light';
    }
  }, []);

  useEffect(() => {
    if (window.matchMedia('(max-width: 700px)').matches) {
      setSidebarOpen(false);
    }
  }, [location.pathname]);

  useEffect(() => {
    const handleInvalidSession = () => setCurrentUser(null);
    window.addEventListener('flora:session-invalid', handleInvalidSession);
    return () => window.removeEventListener('flora:session-invalid', handleInvalidSession);
  }, []);

  useEffect(() => {
    const session = getStoredSession();
    if (!session && currentUser) {
      setCurrentUser(null);
    }
  }, [location.pathname, currentUser]);

  function handleLogin(user) {
    saveSession(user);
    setCurrentUser(user);
  }

  async function handleLogout() {
    try {
      await apiRequest('/api/auth/logout', { method: 'POST' });
    } finally {
      clearSession();
      setCurrentUser(null);
    }
  }

  if (!currentUser) {
    return (
      <Routes>
        <Route path="/login" element={<LoginPage onLogin={handleLogin} />} />
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    );
  }

  return (
    <PermissionContext.Provider value={currentUser}>
      <ToastContainer />
      <div className={`app-frame ${sidebarCollapsed ? 'sidebar-collapsed' : ''} ${sidebarOpen ? 'sidebar-drawer-open' : ''}`}>
        {sidebarOpen && <button className="sidebar-scrim" aria-label="Đóng menu" onClick={() => setSidebarOpen(false)} />}
        <aside id="main-sidebar" className={`sidebar ${sidebarOpen ? 'sidebar-open' : ''}`}>
          <div className="brand-lockup">
            {sidebarCollapsed ? (
              <button className="brand-mark brand-mark-button" type="button" aria-label="Mở rộng thanh điều hướng" onClick={() => setSidebarCollapsed(false)}><Leaf className="brand-leaf-icon" size={19} strokeWidth={2.5} /><PanelLeftOpen className="brand-open-icon" size={19} strokeWidth={2} /></button>
            ) : (
              <div className="brand-mark"><Leaf size={19} strokeWidth={2.5} /></div>
            )}
            <div className="brand-copy"><div className="brand-name">FloraCore</div><div className="brand-caption">GREENHOUSE SYSTEMS</div></div>
            <button className="icon-button sidebar-collapse" aria-label={sidebarCollapsed ? 'Mở rộng thanh điều hướng' : 'Thu gọn thanh điều hướng'} aria-expanded={!sidebarCollapsed} onClick={() => setSidebarCollapsed((collapsed) => !collapsed)}>{sidebarCollapsed ? <PanelLeftOpen size={18} /> : <PanelLeftClose size={18} />}</button>
          </div>
          <div className="facility-switcher">
            <span className="facility-avatar"><Sprout size={17} /></span>
            <span className="facility-copy"><strong>Nhà kính chính</strong><small>Hydroponic facility</small></span>
            <ChevronDown size={15} className="muted-icon" />
          </div>
          <nav className="side-navigation" aria-label="Điều hướng chính">
            {navigation.map((group) => {
              const visibleItems = group.items.filter((item) =>
                !item.requiredRoles || hasAnyRole(currentUser, item.requiredRoles)
              );
              if (!visibleItems.length) return null;
              return (
                <div className="nav-group" key={group.title}>
                  <div className="nav-group-title">{group.title}</div>
                  {visibleItems.map(({ label, path, icon: Icon }) => (
                    <NavLink className={({ isActive }) => `nav-link ${isActive ? 'nav-link-active' : ''}`} to={path} key={path} title={label}>
                      <Icon size={17} strokeWidth={1.9} /><span>{label}</span>
                    </NavLink>
                  ))}
                </div>
              );
            })}
          </nav>
          <div className="sidebar-bottom">
            <div className="broker-status" title="MQTT broker: Đang hoạt động trên cổng 1883"><span className="status-dot" style={{ background: '#22c55e', boxShadow: '0 0 0 3px #dcfce7' }} /><span><strong>MQTT broker</strong><small style={{ color: '#16a34a', fontWeight: 600 }}>Đang kết nối (1883)</small></span><Wifi size={16} style={{ color: '#16a34a' }} /></div>
            <div className="profile-block">
              <NavLink to="/my-profile" className="profile-link" title={currentUser.fullName || currentUser.username}><span className="profile-avatar">{sidebarAvatar ? <img src={sidebarAvatar} alt="" className="avatar-img" /> : (currentUser.fullName || currentUser.username || 'U').split(' ').map((part) => part[0]).join('').slice(0,2).toUpperCase()}</span><span><strong>{currentUser.fullName || currentUser.username}</strong><small>{roleLabel(currentUser.roles?.[0])}</small></span><ChevronDown size={15} /></NavLink>
              <button className="button button-secondary button-small logout-button" type="button" aria-label="Đăng xuất" title="Đăng xuất" onClick={handleLogout}><LogOut size={14} /><span>Đăng xuất</span></button>
            </div>
          </div>
        </aside>

        <div className="workspace">
          <header className="topbar">
            <button className="icon-button menu-toggle" aria-label="Mở thanh điều hướng" aria-controls="main-sidebar" onClick={() => setSidebarOpen(true)}><Menu size={20} /></button>
            <div className="breadcrumb"><NavLink to="/dashboard" className="crumb-home" aria-label="Về Dashboard"><Home size={16} /></NavLink><span className="breadcrumb-slash">/</span><span>Greenhouse Network</span><span className="breadcrumb-slash">/</span><strong className="breadcrumb-current">{getPageTitle(location.pathname)}</strong></div>
            <div className="topbar-actions">
              <TopbarExtras currentUser={currentUser} onLogout={handleLogout} />
            </div>
          </header>
          <main className="page-content">
            <Routes>
              <Route path="/" element={<Navigate to="/dashboard" replace />} />
              <Route path="/login" element={<Navigate to="/dashboard" replace />} />
              <Route path="/dashboard" element={<ProtectedRoute currentUser={currentUser}><Dashboard /></ProtectedRoute>} />
              <Route path="/devices" element={<ProtectedRoute currentUser={currentUser}><Devices /></ProtectedRoute>} />
              <Route path="/alerts" element={<ProtectedRoute currentUser={currentUser}><AlertsPage /></ProtectedRoute>} />
              <Route path="/commands" element={<Navigate to="/devices" replace />} />
              <Route path="/device-lifecycle" element={<ProtectedRoute currentUser={currentUser} requiredRoles={["ADMIN"]}><DeviceLifecyclePage /></ProtectedRoute>} />
              <Route path="/users" element={<ProtectedRoute currentUser={currentUser} requiredRoles={["ADMIN"]}><UsersPage /></ProtectedRoute>} />
              <Route path="/audit-logs" element={<ProtectedRoute currentUser={currentUser} requiredRoles={["ADMIN"]}><AuditLogsPage /></ProtectedRoute>} />
              <Route path="/my-profile" element={<ProtectedRoute currentUser={currentUser}><ProfilePage /></ProtectedRoute>} />
              <Route path="/invite-user" element={<ProtectedRoute currentUser={currentUser} requiredRoles={["ADMIN"]}><InviteUserPage /></ProtectedRoute>} />
              <Route path="*" element={<Navigate to="/dashboard" replace />} />
            </Routes>
          </main>
        </div>
      </div>
    </PermissionContext.Provider>
  );
}

function getPageTitle(path) {
  if (path === '/dashboard') return 'Dashboard';
  if (path === '/devices') return 'Thiết bị';
  return pageTitles[path]?.[0] ?? 'Dashboard';
}

function PageHeading({ eyebrow, title, description, actions }) {
  return (
    <div className="page-heading">
      <div>
        <div className="eyebrow">{eyebrow}</div>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {actions && <div className="heading-actions">{actions}</div>}
    </div>
  );
}

function Dashboard() {
  const { canControl } = usePermission();
  const [devices, setDevices] = useState([]);
  const [telemetry, setTelemetry] = useState([]);
  const [configuration, setConfiguration] = useState(null);
  const [liveStatus, setLiveStatus] = useState(null);
  const [temperatureHigh, setTemperatureHigh] = useState(35);
  const [streamRange, setStreamRange] = useState('1h');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [apiOnline, setApiOnline] = useState(false);
  const [lastUpdated, setLastUpdated] = useState(null);

  async function loadDashboard(showLoader = true) {
    if (showLoader) setLoading(true);
    try {
      const deviceData = await apiRequest('/api/devices');
      setDevices(deviceData);
      if (deviceData.length) {
        const deviceId = encodeURIComponent(deviceData[0].deviceId);
        const [telemetryData, devConfig, live, scheds] = await Promise.all([
          apiRequest(`/api/telemetry?deviceId=${deviceId}`).catch(() => []),
          apiRequest(`/api/devices/${deviceId}/configuration`).catch(() => null),
          apiRequest(`/api/devices/${deviceId}/live`).catch(() => null),
          apiRequest(`/api/devices/${deviceId}/light-schedules`).catch(() => []),
        ]);
        setTelemetry(telemetryData);
        setConfiguration(devConfig);
        if (live) setLiveStatus(live);
        setTemperatureHigh(Number(devConfig?.temperatureHigh) || 35);
        if (Array.isArray(scheds) && scheds.length > 0) {
          const active = scheds.find((s) => s.enabled) || scheds[0];
          setLightSchedule({ onTime: active.turnOnTime, offTime: active.turnOffTime, id: active.id, enabled: active.enabled });
        }
      } else {
        setTelemetry([]);
      }
      setApiOnline(true);
      setError('');
    } catch (requestError) {
      setApiOnline(false);
      setError(requestError.message || 'Không thể tải dữ liệu.');
    } finally {
      setLastUpdated(new Date());
      if (showLoader) setLoading(false);
    }
  }

  const [, setClockTick] = useState(0);
  useEffect(() => {
    loadDashboard();
    const intervalId = window.setInterval(() => loadDashboard(false), refreshIntervalMs);
    const tickId = window.setInterval(() => setClockTick((t) => t + 1), 1000);
    return () => {
      window.clearInterval(intervalId);
      window.clearInterval(tickId);
    };
  }, []);

  const onlineCount = devices.filter((device) => device.status?.toUpperCase() === 'ACTIVE').length;
  const latest = telemetry[0];

  const actuators = liveStatus?.actuators?.length ? liveStatus.actuators : [
    { id: 'valve', name: 'Van tưới nước', type: 'VALVE', state: false },
    { id: 'fan', name: 'Quạt thông gió', type: 'FAN', state: false },
    { id: 'light', name: 'Đèn quang hợp', type: 'LIGHT', state: false },
  ];
  const isDeviceOnline = Boolean(liveStatus?.online);
  const activeCount = isDeviceOnline ? actuators.filter((a) => a.state).length : 0;

  const curTemp = liveStatus?.temperature ?? latest?.temperature ?? null;
  const curHumi = liveStatus?.humidity ?? latest?.humidity ?? null;
  const curSoil = liveStatus?.soilMoisture ?? latest?.soilMoisture ?? null;
  const curLastSeen = liveStatus?.lastSeen || latest?.timestamp || null;

  // Cảm biến thời gian thực: chỉ hiển thị cảm biến khi có kết nối và có dữ liệu; mất kết nối thì biến mất
  const activeSensors = [];
  if (isDeviceOnline) {
    if (curTemp != null) {
      activeSensors.push({
        id: 'temperature',
        name: 'Nhiệt độ',
        value: `${Number(curTemp).toFixed(1)}°C`,
        icon: Thermometer,
        tone: 'tone-coral',
      });
    }
    if (curHumi != null) {
      activeSensors.push({
        id: 'humidity',
        name: 'Độ ẩm không khí',
        value: `${Number(curHumi).toFixed(0)}% RH`,
        icon: Droplets,
        tone: 'tone-blue',
      });
    }
    if (curSoil != null) {
      activeSensors.push({
        id: 'soilMoisture',
        name: 'Độ ẩm đất',
        value: `${Number(curSoil).toFixed(0)}%`,
        icon: Sprout,
        tone: 'tone-green',
      });
    }
  }

  const hasTempHumi = Boolean(isDeviceOnline && (curTemp != null || curHumi != null));
  const hasSoil = Boolean(isDeviceOnline && curSoil != null);

  const [thresholdTemp, setThresholdTemp] = useState(35.0);
  const [thresholdSoil, setThresholdSoil] = useState(30.0);
  const [lightSchedule, setLightSchedule] = useState({ onTime: '19:00', offTime: '07:00', id: null, enabled: true });

  useEffect(() => {
    if (liveStatus?.tempThreshold != null) setThresholdTemp(liveStatus.tempThreshold);
    if (liveStatus?.soilThreshold != null) setThresholdSoil(liveStatus.soilThreshold);
  }, [liveStatus?.tempThreshold, liveStatus?.soilThreshold]);

  // Lắng nghe đồng bộ thời gian thực giữa các tab hoặc giữa Dashboard và Trang Thiết bị
  useEffect(() => {
    function onLiveUpdate(e) {
      if (e.detail) setLiveStatus(e.detail);
    }
    function onStorage(e) {
      if (e.key === 'flora:liveStatus' && e.newValue) {
        try { setLiveStatus(JSON.parse(e.newValue)); } catch {}
      }
    }
    window.addEventListener('flora:liveStatus-updated', onLiveUpdate);
    window.addEventListener('storage', onStorage);
    return () => {
      window.removeEventListener('flora:liveStatus-updated', onLiveUpdate);
      window.removeEventListener('storage', onStorage);
    };
  }, []);

  function getActuatorName(act) {
    if (!act) return '';
    if (act.id === 'valve') return 'Van tưới nước';
    if (act.id === 'fan') return 'Quạt thông gió';
    if (act.id === 'light') return 'Đèn quang hợp';
    return String(act.name || '').replace(/\s*\([^)]*\)$/, '').trim() || act.name;
  }

  return (
    <>
      <PageHeading
        eyebrow={new Intl.DateTimeFormat('vi-VN', { weekday: 'long', day: '2-digit', month: 'long' }).format(new Date()).toLocaleUpperCase('vi-VN')}
        title="Tổng quan nhà kính"
        description="Theo dõi trạng thái thiết bị và điều kiện môi trường."
        actions={
          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <span
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '6px',
                padding: '6px 12px',
                borderRadius: '8px',
                fontSize: '13px',
                fontWeight: 600,
                background: liveStatus?.autoMode ? '#dcfce7' : '#f1f5f9',
                color: liveStatus?.autoMode ? '#15803d' : '#475569',
              }}
            >
              <SlidersHorizontal size={14} />
              {liveStatus?.autoMode ? 'Chế độ: Tự động' : 'Chế độ: Thủ công'}
            </span>
            <button className="button button-secondary" onClick={() => loadDashboard()} disabled={loading}>
              <RefreshCw size={16} className={loading ? 'spin' : ''} /> Làm mới
            </button>
          </div>
        }
      />
      {error && <div className="notice notice-error"><AlertTriangle size={17} /><span>{error} Hãy kiểm tra backend và kết nối cơ sở dữ liệu.</span></div>}
      <div className="refresh-status"><span className={`live-indicator ${apiOnline ? 'live-indicator-online' : 'live-indicator-offline'}`}><span /> API {apiOnline ? 'kết nối' : loading ? 'đang kiểm tra' : 'mất kết nối'}</span><span>Tự làm mới mỗi 10 giây</span><span>{lastUpdated ? `Làm mới dữ liệu lúc ${lastUpdated.toLocaleTimeString('vi-VN')}` : 'Đang lấy dữ liệu...'}</span></div>

      {/* 3 Ô CÙNG HÀNG: CẢM BIẾN -> NGƯỠNG CẢNH BÁO -> THIẾT BỊ ĐIỀU KHIỂN */}
      <section className="dashboard-top-grid" aria-label="Tổng quan hệ thống">
        {/* Ô 1: CẢM BIẾN (Tích hợp đo nhiệt độ & độ ẩm, độ ẩm đất theo mẫu tinh gọn) */}
        <article className="dashboard-top-card">
          <div>
            <div className="top-card-header">
              <div className="top-card-title-group">
                <span className="top-card-kicker">GIÁM SÁT MÔI TRƯỜNG</span>
                <h3 className="top-card-title">
                  <Radio size={16} style={{ color: '#0284c7' }} /> Cảm biến
                </h3>
              </div>
              <span className={`top-card-badge ${isDeviceOnline && activeSensors.length > 0 ? 'badge-online' : 'badge-offline'}`}>
                {isDeviceOnline && activeSensors.length > 0 ? `${activeSensors.length} đang hoạt động` : 'Mất kết nối'}
              </span>
            </div>

            <div className="sensor-showcase-box">
              {isDeviceOnline && (hasTempHumi || hasSoil) ? (
                <div className="sensor-reading-stack">
                  {hasTempHumi && (
                    <div className="sensor-reading-line">
                      <span className="sensor-glyph-icon"><Thermometer size={16} /></span>
                      <strong className="sensor-bold-val">
                        {curTemp != null ? Number(curTemp).toLocaleString('vi-VN', { maximumFractionDigits: 1 }) : '—'}°C
                      </strong>
                      <span className="sensor-dot-sep">•</span>
                      <span className="sensor-glyph-icon"><Droplets size={16} /></span>
                      <strong className="sensor-bold-val">
                        {curHumi != null ? Number(curHumi).toLocaleString('vi-VN', { maximumFractionDigits: 0 }) : '—'}% RH
                      </strong>
                    </div>
                  )}

                  {hasTempHumi && hasSoil && <div className="sensor-stack-divider" />}

                  {hasSoil && (
                    <div className="sensor-reading-line">
                      <span className="sensor-glyph-icon"><Droplet size={16} /></span>
                      <strong className="sensor-bold-val">
                        {curSoil != null ? Number(curSoil).toLocaleString('vi-VN', { maximumFractionDigits: 0 }) : '—'}%
                      </strong>
                      <span className="sensor-note-val">
                        {Number(curSoil) >= thresholdSoil ? '(Tối ưu)' : '(Cần tưới)'}
                      </span>
                    </div>
                  )}
                </div>
              ) : (
                <div className="sensor-pill-empty">
                  <AlertCircle size={15} />
                  <span>{isDeviceOnline ? 'Đang chờ bản tin từ ESP32...' : 'Không có cảm biến kết nối'}</span>
                </div>
              )}
            </div>
          </div>
          <small style={{ marginTop: '10px', fontSize: '11px', color: '#64748b', display: 'flex', alignItems: 'center', gap: '5px' }}>
            <Clock3 size={12} />
            {isDeviceOnline && curLastSeen
              ? `Cập nhật lần cuối: ${timeAgo(curLastSeen)}`
              : isDeviceOnline
              ? 'Đang chờ bản tin từ ESP32...'
              : 'Node ESP32 đang ngoại tuyến'}
          </small>
        </article>

        {/* Ô 2: NGƯỠNG CẢNH BÁO (Chỉ hiển thị - chỉnh ngưỡng tại trang Thiết bị) */}
        <article className="dashboard-top-card">
          <div>
            <div className="top-card-header">
              <div className="top-card-title-group">
                <span className="top-card-kicker">CẤU HÌNH TỰ ĐỘNG HÓA</span>
                <h3 className="top-card-title">
                  <SlidersHorizontal size={16} style={{ color: '#087b66' }} /> Ngưỡng cảnh báo
                </h3>
              </div>
            </div>

            <div className="threshold-rule-list">
              <div className="threshold-rule-item">
                <div className="threshold-device-info">
                  <span className="rule-device-icon tone-coral"><Fan size={14} /></span>
                  <span className="rule-device-name">Quạt làm mát</span>
                </div>
                <div className="threshold-condition">
                  <span className="rule-desc">Bật khi vượt quá:</span>
                  <span className="rule-badge rule-badge-temp">&gt; {thresholdTemp}°C</span>
                </div>
              </div>

              <div className="threshold-rule-item">
                <div className="threshold-device-info">
                  <span className="rule-device-icon tone-blue"><Droplet size={14} /></span>
                  <span className="rule-device-name">Van tưới nước</span>
                </div>
                <div className="threshold-condition">
                  <span className="rule-desc">Bật khi thấp hơn:</span>
                  <span className="rule-badge rule-badge-soil">&lt; {thresholdSoil}%</span>
                </div>
              </div>

              <div className="threshold-rule-item">
                <div className="threshold-device-info">
                  <span className="rule-device-icon tone-yellow"><Lightbulb size={14} /></span>
                  <span className="rule-device-name">Đèn quang hợp</span>
                </div>
                <div className="threshold-condition">
                  <span className="rule-desc">Lịch tự động:</span>
                  <span className="rule-badge rule-badge-warm">
                    {lightSchedule.enabled !== false ? `${lightSchedule.onTime || '19:00'} - ${lightSchedule.offTime || '07:00'}` : 'Đã tắt lịch'}
                  </span>
                </div>
              </div>
            </div>
          </div>
          <small style={{ marginTop: '10px', fontSize: '11px', color: '#64748b' }}>
            Chế độ: <strong>{liveStatus?.autoMode ? 'Tự động kích hoạt theo ngưỡng' : 'Điều khiển thủ công'}</strong>
          </small>
        </article>

        {/* Ô 3: THIẾT BỊ ĐIỀU KHIỂN (Chế độ giám sát - không gạt bật/tắt trên Dashboard) */}
        <article className="dashboard-top-card">
          <div>
            <div className="top-card-header">
              <div className="top-card-title-group">
                <span className="top-card-kicker">VẬN HÀNH PHẦN CỨNG</span>
                <h3 className="top-card-title">
                  <Cpu size={16} style={{ color: '#16a34a' }} /> Thiết bị điều khiển
                </h3>
              </div>
              <NavLink to="/devices" className="card-link-icon" title={canControl ? "Xem chi tiết và điều khiển tại trang Thiết bị" : "Xem chi tiết tại trang Thiết bị"}>
                <ArrowUpRight size={16} />
              </NavLink>
            </div>

            <div className="actuator-mini-list">
              {actuators.map((act) => {
                const isOn = Boolean(isDeviceOnline && act.state);
                const ActIcon = act.id === 'fan' ? Fan : act.id === 'light' ? Lightbulb : Droplet;
                return (
                  <div key={act.id} className={`actuator-mini-item ${isOn ? 'mini-active' : ''}`}>
                    <div className="actuator-mini-left">
                      <span className={`actuator-mini-icon ${isOn ? 'icon-on' : 'icon-off'}`}>
                        <ActIcon size={14} />
                      </span>
                      <span className="actuator-mini-name">{getActuatorName(act)}</span>
                    </div>
                    <div className="actuator-mini-right">
                      <span
                        className={`status-pill ${isOn ? 'status-online' : 'status-neutral'}`}
                        style={{ fontSize: '11px', padding: '2px 8px' }}
                      >
                        <span />
                        {isOn ? 'Đang bật' : 'Đang tắt'}
                      </span>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
          <small style={{ marginTop: '10px', fontSize: '11px', color: '#64748b' }}>
            {isDeviceOnline ? `${activeCount} / ${actuators.length} thiết bị đang chạy` : 'Tất cả thiết bị đang ngoại tuyến'}
          </small>
        </article>
      </section>

      {/* BIỂU ĐỒ REAL-TIME TELEMETRY STREAM (Bỏ cường độ ánh sáng, chỉ còn Nhiệt độ, Độ ẩm không khí, Độ ẩm đất) */}
      <TelemetryStream telemetry={telemetry} temperatureHigh={temperatureHigh} range={streamRange} onRangeChange={setStreamRange} />

      {/* DANH SÁCH THIẾT BỊ HẠ TẦNG */}
      <section className="content-section device-section">
        <div className="section-heading"><div><div className="section-kicker">HẠ TẦNG</div><h2>Thiết bị</h2></div><NavLink className="text-link" to="/devices">{canControl ? 'Xem tất cả & Điều khiển' : 'Xem tất cả'} <ArrowUpRight size={15} /></NavLink></div>
        <DeviceTable devices={devices.slice(0, 5)} loading={loading} compact />
      </section>
    </>
  );
}

const telemetryRanges = [
  { value: '1h', label: '1 giờ', duration: 60 * 60 * 1000 },
  { value: '6h', label: '6 giờ', duration: 6 * 60 * 60 * 1000 },
  { value: '24h', label: '24 giờ', duration: 24 * 60 * 60 * 1000 },
  { value: '7d', label: '7 ngày', duration: 7 * 24 * 60 * 60 * 1000 },
];

const telemetrySeries = [
  { key: 'temperature', label: 'Nhiệt độ', unit: '°C', color: '#c66d4c' },
  { key: 'humidity', label: 'Độ ẩm không khí', unit: '%', color: '#58859d' },
  { key: 'soilMoisture', label: 'Độ ẩm đất', unit: '%', color: '#39815d' },
];

const minuteMs = 60 * 1000;
const timeTickConfig = {
  '1h': [minuteMs, 15],
  '6h': [5 * minuteMs, 12],
  '24h': [15 * minuteMs, 16],
  '7d': [60 * minuteMs, 24],
};

function TelemetryStream({ telemetry, temperatureHigh, range, onRangeChange }) {
  const selectedRange = telemetryRanges.find((option) => option.value === range) ?? telemetryRanges[0];
  const now = Date.now();
  const startTime = now - selectedRange.duration;
  const records = telemetry
    .map((record) => ({ ...record, time: new Date(record.timestamp).getTime() }))
    .filter((record) => Number.isFinite(record.time) && record.time >= startTime && record.time <= now)
    .sort((left, right) => left.time - right.time);
  const plotLeft = 190;
  const plotRight = 884;
  const plotWidth = plotRight - plotLeft;
  const rowHeight = 68;
  const plotTop = 8;
  const axisY = plotTop + rowHeight * telemetrySeries.length + 2;
  const [tickInterval, labelEvery] = timeTickConfig[range] ?? timeTickConfig['1h'];
  const timeTickCount = Math.floor(selectedRange.duration / tickInterval);
  const axisLevels = [1, 0.75, 0.5, 0.25];
  const minuteGridPath = Array.from({ length: timeTickCount + 1 }, (_, tick) => {
    if (tick % labelEvery === 0 || tick === timeTickCount) return '';
    const x = plotLeft + (plotWidth * tick * tickInterval) / selectedRange.duration;
    return `M${x.toFixed(1)} ${plotTop}V${axisY}`;
  }).join(' ');

  return (
    <section className="content-section telemetry-stream">
      <div className="section-heading telemetry-stream-heading">
        <div><div className="section-kicker">DÒNG DỮ LIỆU TRỰC TIẾP</div><h2>Real-Time Telemetry Stream</h2></div>
        <div className="range-filter" role="group" aria-label="Khoảng thời gian biểu đồ">
          {telemetryRanges.map((option) => <button key={option.value} type="button" className={range === option.value ? 'range-filter-active' : ''} aria-pressed={range === option.value} onClick={() => onRangeChange(option.value)}>{option.label}</button>)}
        </div>
      </div>
      <div className="stream-panel">
        <div className="stream-chart-scroll">
          <svg className="stream-chart" viewBox="0 0 900 248" role="img" aria-label={`Biểu đồ nhiệt độ, độ ẩm không khí và độ ẩm đất trong ${selectedRange.label}`}>
            {minuteGridPath && <path d={minuteGridPath} className="stream-minute-tick" />}
            {Array.from({ length: timeTickCount + 1 }, (_, tick) => {
              const elapsed = tick * tickInterval;
              const x = plotLeft + (plotWidth * elapsed) / selectedRange.duration;
              const tickTime = new Date(startTime + elapsed);
              const showLabel = tick % labelEvery === 0 || tick === timeTickCount;
              if (!showLabel) return null;
              return <g key={tick}>
                <line x1={x} y1={plotTop} x2={x} y2={axisY} className="stream-grid-line" />
                {showLabel && <text x={x} y={axisY + 16} textAnchor={tick === 0 ? 'start' : tick === timeTickCount ? 'end' : 'middle'} className="stream-axis-label">
                  {tickTime.toLocaleString('vi-VN', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })}
                </text>}
              </g>;
            })}
            {telemetrySeries.map((series, index) => {
              const rowTop = plotTop + index * rowHeight;
              const rowBottom = rowTop + rowHeight - 5;
              const values = records.map((record) => Number(record[series.key])).filter(Number.isFinite);
              const threshold = series.key === 'temperature' ? Number(temperatureHigh) : null;
              const minValue = values.length ? Math.min(...values, ...(threshold == null ? [] : [threshold])) : 0;
              const maxValue = values.length ? Math.max(...values, ...(threshold == null ? [] : [threshold])) : 1;
              const padding = maxValue === minValue ? Math.max(Math.abs(maxValue) * 0.1, 1) : (maxValue - minValue) * 0.15;
              const domainMin = minValue - padding;
              const domainMax = maxValue + padding;
              const plotHeight = rowHeight - 13;
              const yFor = (value) => rowBottom - ((value - domainMin) / (domainMax - domainMin)) * plotHeight;
              const points = records.flatMap((record) => {
                const value = Number(record[series.key]);
                if (!Number.isFinite(value)) return [];
                return [{ x: plotLeft + ((record.time - startTime) / selectedRange.duration) * plotWidth, y: yFor(value), value, alert: series.key === 'temperature' && value > temperatureHigh, time: record.time }];
              });
              const path = points.map((point, pointIndex) => `${pointIndex === 0 ? 'M' : 'L'} ${point.x.toFixed(1)} ${point.y.toFixed(1)}`).join(' ');
              const alertPath = series.key === 'temperature'
                ? points.slice(1).flatMap((point, pointIndex) => {
                  const previous = points[pointIndex];
                  return point.alert || previous.alert
                    ? [`M ${previous.x.toFixed(1)} ${previous.y.toFixed(1)} L ${point.x.toFixed(1)} ${point.y.toFixed(1)}`]
                    : [];
                }).join(' ')
                : '';
              const thresholdY = threshold == null ? null : yFor(threshold);
              return <g key={series.key}>
                {index > 0 && <line x1={plotLeft} y1={rowTop} x2={plotRight} y2={rowTop} className="stream-row-line" />}
                <text x="0" y={rowTop + 17} className="stream-series-label">{series.label}</text>
                <text x="0" y={rowTop + 31} className="stream-series-unit">{series.unit}</text>
                {axisLevels.map((level) => {
                  const y = rowBottom - level * plotHeight;
                  const value = domainMin + level * (domainMax - domainMin);
                  return <g key={level}>
                    <line x1={plotLeft} y1={y} x2={plotRight} y2={y} className="stream-y-grid-line" />
                    <text x={plotLeft - 7} y={y + 3} textAnchor="end" className="stream-y-label">{`${value.toFixed(0)} ${series.unit}`}</text>
                  </g>;
                })}
                {thresholdY != null && <>
                  <line x1={plotLeft} y1={thresholdY} x2={plotRight} y2={thresholdY} className="stream-threshold-line" />
                  <text x={plotRight - 4} y={thresholdY - 3} textAnchor="end" className="stream-threshold-label">Ngưỡng {temperatureHigh}°C</text>
                </>}
                {path && <path d={path} fill="none" stroke={series.color} strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />}
                {alertPath && <path d={alertPath} fill="none" stroke="#c84d38" strokeWidth="2.5" strokeLinejoin="round" strokeLinecap="round" />}
                {points.map((point, pointIndex) => <circle key={`${series.key}-${pointIndex}`} cx={point.x} cy={point.y} r="7" fill={point.alert ? '#c84d38' : series.color} fillOpacity="0" className="stream-hover-target">
                  <title>{`${series.label}: ${point.value} ${series.unit} · ${new Date(point.time).toLocaleString('vi-VN')}${point.alert ? ` · Vượt ngưỡng ${temperatureHigh}°C` : ''}`}</title>
                </circle>)}
              </g>;
            })}
          </svg>
        </div>
        <div className="stream-footer">
          <div className="stream-legend">{telemetrySeries.map((series) => <span key={series.key}><i style={{ backgroundColor: series.color }} />{series.label}</span>)}</div>
          <span className="stream-count">{records.length} bản ghi</span>
        </div>
        {records.length === 0 && <div className="stream-empty">Chưa có dữ liệu trong khoảng thời gian này.</div>}
      </div>
    </section>
  );
}

function StatCard({ label, value, note, icon: Icon, tone }) {
  return <article className="stat-card"><div className={`stat-icon tone-${tone}`}><Icon size={18} /></div><div className="stat-label">{label}</div><div className="stat-value">{value}</div><div className="stat-note">{note}</div></article>;
}

function MetricCard({ label, value, unit, icon: Icon, tone }) {
  return <article className="metric-card"><div className="metric-top"><span>{label}</span><span className={`metric-icon tone-${tone}`}><Icon size={17} /></span></div><div className="metric-reading">{value ?? '—'}<small>{unit}</small></div><div className="metric-footnote">{value == null ? 'Chưa nhận được bản ghi' : 'Giá trị mới nhất từ telemetry'}</div></article>;
}

const ACTUATOR_STORAGE_KEY = 'flora_actuators_v1';

function loadActuatorState() {
  const defaults = { fan: true, pump: false, lamp: true, custom: [] };
  try {
    const raw = localStorage.getItem(ACTUATOR_STORAGE_KEY);
    if (!raw) return defaults;
    return { ...defaults, ...JSON.parse(raw) };
  } catch {
    return defaults;
  }
}

function Devices() {
  const { canControl, canManageDevices, canManageSchedules, isViewer } = usePermission();
  const location = useLocation();
  const [highlightedId, setHighlightedId] = useState(() => location.state?.highlightDeviceId || null);
  const [backendDevices, setBackendDevices] = useState([]);
  const [latest, setLatest] = useState(null);
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState('ALL');
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [modalOpen, setModalOpen] = useState(false);
  const [editingDevice, setEditingDevice] = useState(null);
  const [saving, setSaving] = useState(false);
  const [actuators, setActuators] = useState(loadActuatorState);

  const [busyActuator, setBusyActuator] = useState(null);
  const [liveStatus, setLiveStatus] = useState(null);
  const [showThresholdModal, setShowThresholdModal] = useState(false);
  const [thresholdTemp, setThresholdTemp] = useState(35.0);
  const [thresholdSoil, setThresholdSoil] = useState(30.0);
  const [draftTemp, setDraftTemp] = useState(35.0);
  const [draftSoil, setDraftSoil] = useState(30.0);
  const [draftLightOn, setDraftLightOn] = useState('19:00');
  const [draftLightOff, setDraftLightOff] = useState('07:00');
  const [savingThreshold, setSavingThreshold] = useState(false);

  // Khóa cuộn trang màn hình phía dưới khi mở modal cấu hình ngưỡng
  useEffect(() => {
    if (showThresholdModal) {
      const originalOverflow = document.body.style.overflow;
      document.body.style.overflow = 'hidden';
      return () => {
        document.body.style.overflow = originalOverflow;
      };
    }
  }, [showThresholdModal]);

  useEffect(() => {
    if (liveStatus?.tempThreshold != null) setThresholdTemp(liveStatus.tempThreshold);
    if (liveStatus?.soilThreshold != null) setThresholdSoil(liveStatus.soilThreshold);
  }, [liveStatus?.tempThreshold, liveStatus?.soilThreshold]);

  useEffect(() => {
    if (location.state?.highlightDeviceId) {
      setHighlightedId(location.state.highlightDeviceId);
      const timer = window.setTimeout(() => setHighlightedId(null), 4000);
      return () => window.clearTimeout(timer);
    }
  }, [location.state?.highlightDeviceId]);

  // ── Lịch bật/tắt đèn tự động ────────────────────────────────────────────
  const [lightSchedules, setLightSchedules] = useState([]);
  const [showSchedulePanel, setShowSchedulePanel] = useState(false);
  const [scheduleForm, setScheduleForm] = useState({ label: '', turnOnTime: '19:00', turnOffTime: '07:00', enabled: true });
  const [editingScheduleId, setEditingScheduleId] = useState(null);
  const [savingSchedule, setSavingSchedule] = useState(false);
  const [scheduleError, setScheduleError] = useState('');

  const activeLightSchedule = lightSchedules.find((s) => s.enabled) || lightSchedules[0];

  async function loadLightSchedules(deviceId) {
    try {
      const data = await apiRequest(`/api/devices/${encodeURIComponent(deviceId)}/light-schedules`);
      const list = Array.isArray(data) ? data : [];
      setLightSchedules(list);
      if (list.length > 0) {
        const active = list.find((s) => s.enabled) || list[0];
        setDraftLightOn(active.turnOnTime);
        setDraftLightOff(active.turnOffTime);
      }
    } catch { setLightSchedules([]); }
  }

  async function handleSaveSchedule(e) {
    e.preventDefault();
    setScheduleError('');
    const devId = backendDevices[0]?.deviceId;
    if (!devId) return;
    setSavingSchedule(true);
    try {
      const url = editingScheduleId
        ? `/api/devices/${encodeURIComponent(devId)}/light-schedules/${editingScheduleId}`
        : `/api/devices/${encodeURIComponent(devId)}/light-schedules`;
      const method = editingScheduleId ? 'PUT' : 'POST';
      await apiRequest(url, { method, body: JSON.stringify(scheduleForm) });
      await loadLightSchedules(devId);
      setScheduleForm({ label: '', turnOnTime: '19:00', turnOffTime: '07:00', enabled: true });
      setEditingScheduleId(null);
    } catch (err) {
      setScheduleError(err.message || 'Không thể lưu lịch.');
    } finally {
      setSavingSchedule(false);
    }
  }

  async function handleDeleteSchedule(id) {
    const devId = backendDevices[0]?.deviceId;
    if (!devId || !window.confirm('Xoá lịch chiếu sáng này?')) return;
    try {
      await apiRequest(`/api/devices/${encodeURIComponent(devId)}/light-schedules/${id}`, { method: 'DELETE' });
      await loadLightSchedules(devId);
    } catch (err) {
      setScheduleError(err.message || 'Không thể xoá lịch.');
    }
  }

  function handleEditSchedule(s) {
    setScheduleForm({ label: s.label, turnOnTime: s.turnOnTime, turnOffTime: s.turnOffTime, enabled: s.enabled });
    setEditingScheduleId(s.id);
  }

  async function handleToggleScheduleEnabled(s) {
    const devId = backendDevices[0]?.deviceId;
    if (!devId) return;
    try {
      await apiRequest(`/api/devices/${encodeURIComponent(devId)}/light-schedules/${s.id}`,
        { method: 'PUT', body: JSON.stringify({ ...s, enabled: !s.enabled }) });
      await loadLightSchedules(devId);
    } catch { /* ignore */ }
  }
  // ────────────────────────────────────────────────────────────────────────

  function openThresholdModal() {
    setDraftTemp(thresholdTemp);
    setDraftSoil(thresholdSoil);
    setDraftLightOn(activeLightSchedule?.turnOnTime || '19:00');
    setDraftLightOff(activeLightSchedule?.turnOffTime || '07:00');
    setShowThresholdModal(true);
  }

  useEffect(() => {
    try {
      localStorage.setItem(ACTUATOR_STORAGE_KEY, JSON.stringify(actuators));
    } catch { /* storage unavailable */ }
  }, [actuators]);

  async function loadDevices(showLoader = true) {
    if (showLoader) setLoading(true);
    try {
      const deviceData = await apiRequest('/api/devices');
      setBackendDevices(deviceData);
      if (deviceData.length) {
        try {
          const encodedId = encodeURIComponent(deviceData[0].deviceId);
          const [telemetry, live] = await Promise.all([
            apiRequest(`/api/telemetry?deviceId=${encodedId}`).catch(() => []),
            apiRequest(`/api/devices/${encodedId}/live`).catch(() => null),
          ]);
          setLatest(telemetry[0] || null);
          if (live) {
            setLiveStatus(live);
            if (live.tempThreshold != null) setThresholdTemp(live.tempThreshold);
            if (live.soilThreshold != null) setThresholdSoil(live.soilThreshold);
            if (live.lightTempThreshold != null) setThresholdLightTemp(live.lightTempThreshold);
            if (live.actuators) {
              const isLiveOnline = Boolean(live.online);
              const fanAct = live.actuators.find((a) => a.id === 'fan');
              const valveAct = live.actuators.find((a) => a.id === 'valve');
              const lightAct = live.actuators.find((a) => a.id === 'light');
              setActuators((current) => ({
                ...current,
                fan: Boolean(isLiveOnline && fanAct?.state),
                valve: Boolean(isLiveOnline && valveAct?.state),
                pump: Boolean(isLiveOnline && valveAct?.state),
                light: Boolean(isLiveOnline && lightAct?.state),
                lamp: Boolean(isLiveOnline && lightAct?.state),
              }));
            }
          }
        } catch {
          setLatest(null);
        }
      } else {
        setLatest(null);
      }
      setError('');
    }
    catch (requestError) { setError(requestError.message || 'Không thể tải danh sách thiết bị.'); }
    finally { if (showLoader) setLoading(false); }
  }

  const [, setClockTick] = useState(0);
  useEffect(() => {
    loadDevices();
    const intervalId = window.setInterval(() => loadDevices(false), refreshIntervalMs);
    const tickId = window.setInterval(() => setClockTick((t) => t + 1), 1000);
    return () => {
      window.clearInterval(intervalId);
      window.clearInterval(tickId);
    };
  }, []);

  // Lắng nghe đồng bộ thời gian thực từ Dashboard hoặc các tab khác
  useEffect(() => {
    function onLiveUpdate(e) {
      if (e.detail) {
        setLiveStatus(e.detail);
      }
    }
    function onStorage(e) {
      if (e.key === 'flora:liveStatus' && e.newValue) {
        try {
          const parsed = JSON.parse(e.newValue);
          setLiveStatus(parsed);
        } catch {}
      }
    }
    window.addEventListener('flora:liveStatus-updated', onLiveUpdate);
    window.addEventListener('storage', onStorage);
    return () => {
      window.removeEventListener('flora:liveStatus-updated', onLiveUpdate);
      window.removeEventListener('storage', onStorage);
    };
  }, []);

  // Tải lịch đèn khi biết deviceId
  useEffect(() => {
    if (backendDevices.length) {
      loadLightSchedules(backendDevices[0].deviceId);
    }
  }, [backendDevices.length > 0 ? backendDevices[0]?.deviceId : null]);

  async function handleToggle(actuatorKey, newState) {
    const devId = backendDevices[0]?.deviceId || 'esp32-01';
    const targetActuator = (actuatorKey === 'pump' || actuatorKey === 'valve') ? 'valve' : (actuatorKey === 'lamp' || actuatorKey === 'light') ? 'light' : actuatorKey;
    setBusyActuator(actuatorKey);

    // Optimistic local state update
    setActuators((current) => ({
      ...current,
      [actuatorKey]: newState,
      [targetActuator]: newState,
      ...(targetActuator === 'light' ? { lamp: newState, light: newState } : {}),
      ...(targetActuator === 'valve' ? { pump: newState, valve: newState } : {}),
    }));

    // Tự động chuyển hệ thống sang chế độ Thủ công khi người dùng gạt công tắc thiết bị
    setLiveStatus((current) => {
      if (!current || !current.actuators) return current;
      return {
        ...current,
        autoMode: false,
        actuators: current.actuators.map((a) =>
          a.id === targetActuator
            ? { ...a, state: newState }
            : a
        ),
      };
    });

    try {
      const updated = await apiRequest(`/api/devices/${encodeURIComponent(devId)}/control`, {
        method: 'POST',
        body: JSON.stringify({ actuator: targetActuator, state: newState }),
      });
      if (updated) {
        setLiveStatus(updated);
        window.dispatchEvent(new CustomEvent('flora:liveStatus-updated', { detail: updated }));
        try { localStorage.setItem('flora:liveStatus', JSON.stringify(updated)); } catch {}
        if (updated.actuators) {
          const fanAct = updated.actuators.find((a) => a.id === 'fan');
          const valveAct = updated.actuators.find((a) => a.id === 'valve');
          const lightAct = updated.actuators.find((a) => a.id === 'light');
          setActuators((current) => ({
            ...current,
            fan: Boolean(updated.online && fanAct?.state),
            valve: Boolean(updated.online && valveAct?.state),
            pump: Boolean(updated.online && valveAct?.state),
            light: Boolean(updated.online && lightAct?.state),
            lamp: Boolean(updated.online && lightAct?.state),
          }));
        }
      }
    } catch (err) {
      setError(err.message || 'Lỗi khi điều khiển thiết bị.');
    } finally {
      setBusyActuator(null);
    }
  }

  async function toggleAutoMode(newAuto) {
    const devId = backendDevices[0]?.deviceId || 'esp32-01';
    try {
      const updated = await apiRequest(`/api/devices/${encodeURIComponent(devId)}/control`, {
        method: 'POST',
        body: JSON.stringify({ autoMode: newAuto }),
      });
      if (updated) {
        setLiveStatus(updated);
        window.dispatchEvent(new CustomEvent('flora:liveStatus-updated', { detail: updated }));
        try { localStorage.setItem('flora:liveStatus', JSON.stringify(updated)); } catch {}
      }
    } catch (err) {
      setError(err.message || 'Lỗi đổi chế độ.');
    }
  }

  async function handleEmergencyStop() {
    const devId = backendDevices[0]?.deviceId || 'esp32-01';
    setLiveStatus((current) => (current ? { ...current, autoMode: false } : current));
    setActuators((current) => ({ ...current, fan: false, pump: false, lamp: false, valve: false, light: false }));
    try {
      const updated = await apiRequest(`/api/devices/${encodeURIComponent(devId)}/quick-action`, {
        method: 'POST',
        body: JSON.stringify({ action: 'stop_all' }),
      });
      if (updated) {
        setLiveStatus(updated);
        setActuators((current) => ({ ...current, fan: false, pump: false, lamp: false, valve: false, light: false }));
        window.dispatchEvent(new CustomEvent('flora:liveStatus-updated', { detail: updated }));
        try { localStorage.setItem('flora:liveStatus', JSON.stringify(updated)); } catch {}
      }
    } catch (err) {
      setError(err.message || 'Lỗi ngắt thiết bị.');
    }
  }

  async function handleSaveThresholds(e) {
    if (e) e.preventDefault();
    const devId = backendDevices[0]?.deviceId || 'esp32-01';
    setSavingThreshold(true);
    try {
      const updated = await apiRequest(`/api/devices/${encodeURIComponent(devId)}/thresholds`, {
        method: 'PUT',
        body: JSON.stringify({
          temperatureHigh: parseFloat(draftTemp),
          soilMoistureLow: parseFloat(draftSoil),
          autoMode: liveStatus?.autoMode ?? true,
        }),
      });
      if (updated) {
        setLiveStatus(updated);
        setThresholdTemp(parseFloat(draftTemp));
        setThresholdSoil(parseFloat(draftSoil));
        window.dispatchEvent(new CustomEvent('flora:liveStatus-updated', { detail: updated }));
        try { localStorage.setItem('flora:liveStatus', JSON.stringify(updated)); } catch {}
      }

      // Lưu lịch bật/tắt đèn chiếu sáng quang hợp
      if (draftLightOn && draftLightOff) {
        const targetSched = lightSchedules.find((s) => s.enabled) || lightSchedules[0];
        const url = targetSched
          ? `/api/devices/${encodeURIComponent(devId)}/light-schedules/${targetSched.id}`
          : `/api/devices/${encodeURIComponent(devId)}/light-schedules`;
        const method = targetSched ? 'PUT' : 'POST';
        await apiRequest(url, {
          method,
          body: JSON.stringify({
            label: 'Lịch chiếu sáng quang hợp',
            turnOnTime: draftLightOn,
            turnOffTime: draftLightOff,
            enabled: true,
          }),
        });
        await loadLightSchedules(devId);
      }

      setShowThresholdModal(false);
    } catch (err) {
      setError(err.message || 'Không thể lưu cấu hình.');
    } finally {
      setSavingThreshold(false);
    }
  }

  async function createDevice(event) {
    event.preventDefault();
    const formElement = event.currentTarget;
    setSaving(true);
    setError('');
    const form = new FormData(formElement);
    const kind = String(form.get('kind') || 'SENSOR_NODE');
    const name = String(form.get('name') || '').trim();
    try {
      if (kind === 'ACTUATOR') {
        const type = String(form.get('actuatorType') || 'GENERIC');
        const id = `local-${Date.now()}`;
        setActuators((current) => ({
          ...current,
          custom: [...(current.custom || []), { id, name: name || 'Thiết bị mới', type, on: false, detail: 'Thiết bị thêm thủ công' }],
        }));
      } else {
        const payload = {
          deviceId: String(form.get('deviceId') || '').trim(),
          name,
          zone: String(form.get('zone') || '').trim(),
          firmwareVersion: String(form.get('firmwareVersion') || '').trim() || undefined,
        };
        await apiRequest('/api/devices', { method: 'POST', body: JSON.stringify(payload) });
        await loadDevices(false);
      }
      setModalOpen(false);
      formElement.reset();
    } catch (requestError) {
      setError(requestError.message || 'Không thể thêm thiết bị.');
    } finally { setSaving(false); }
  }

  // Telemetry older than this means the node stopped reporting.
  const STALE_AFTER_MS = 5 * 60 * 1000;
  const apiReachable = !error;
  const hasNodes = backendDevices.length > 0;
  const lastSeenTime = liveStatus?.lastSeen || latest?.timestamp || null;
  const latestTime = lastSeenTime ? new Date(lastSeenTime).getTime() : NaN;
  const telemetryFresh = Boolean(liveStatus?.online) || (Number.isFinite(latestTime) && Date.now() - latestTime <= STALE_AFTER_MS);

  const temperature = liveStatus?.temperature ?? latest?.temperature ?? null;
  const humidity = liveStatus?.humidity ?? latest?.humidity ?? null;
  const soil = liveStatus?.soilMoisture ?? latest?.soilMoisture ?? null;

  function sensorLink(hasField) {
    if (!apiReachable) return 'down';
    if (!hasNodes) return 'empty';
    if (!Boolean(liveStatus?.online)) return 'stale';
    if (!hasField) return 'nodata';
    return 'active';
  }

  // Cảm biến: chỉ giữ Nhiệt độ & Độ ẩm, Độ ẩm đất. BỎ HOÀN TOÀN cường độ ánh sáng
  const sensorItems = [
    {
      id: 'sensor-temp-humi',
      name: 'Cảm biến Nhiệt độ & Độ ẩm',
      kind: 'SENSOR',
      link: sensorLink(temperature != null || humidity != null),
      icon: Thermometer,
      iconTone: 'dev-icon-blue',
      reading: (
        <div className="dev-sensor-reading">
          <span className="sensor-glyph-icon"><Thermometer size={15} /></span>
          <strong className="sensor-bold-val">
            {temperature != null ? Number(temperature).toLocaleString('vi-VN', { maximumFractionDigits: 1 }) : '—'}°C
          </strong>
          <span className="sensor-dot-sep">•</span>
          <span className="sensor-glyph-icon"><Droplets size={15} /></span>
          <strong className="sensor-bold-val">
            {humidity != null ? Number(humidity).toLocaleString('vi-VN', { maximumFractionDigits: 0 }) : '—'}% RH
          </strong>
        </div>
      ),
      rule: (
        <span className="dev-rule-text">
          <Clock3 size={13} /> {lastSeenTime ? `Cập nhật lần cuối: ${timeAgo(lastSeenTime)}` : 'Chưa nhận dữ liệu'}
        </span>
      ),
    },
    {
      id: 'sensor-soil',
      name: 'Cảm biến Độ ẩm đất',
      kind: 'SENSOR',
      link: sensorLink(soil != null),
      icon: Sprout,
      iconTone: 'dev-icon-blue',
      reading: (
        <div className="dev-sensor-reading">
          <span className="sensor-glyph-icon"><Droplet size={15} /></span>
          <strong className="sensor-bold-val">
            {soil != null ? Number(soil).toLocaleString('vi-VN', { maximumFractionDigits: 0 }) : '—'}%
          </strong>
          <span className="sensor-note-val">
            {soil != null && Number(soil) >= thresholdSoil ? '(Tối ưu)' : '(Cần tưới)'}
          </span>
        </div>
      ),
      rule: (
        <span className="dev-rule-text">
          <Clock3 size={13} /> {lastSeenTime ? `Cập nhật lần cuối: ${timeAgo(lastSeenTime)}` : 'Chưa nhận dữ liệu'}
        </span>
      ),
    },
  ];

  // Đồng bộ trạng thái trực tuyến theo thời gian thực với backend liveStatus (khớp 100% với Dashboard)
  const online = Boolean(liveStatus?.online);

  const fanLive = liveStatus?.actuators?.find((a) => a.id === 'fan');
  const valveLive = liveStatus?.actuators?.find((a) => a.id === 'valve');
  const lightLive = liveStatus?.actuators?.find((a) => a.id === 'light');

  const isFanOn = Boolean(online && (fanLive ? fanLive.state : actuators.fan));
  const isValveOn = Boolean(online && (valveLive ? valveLive.state : (actuators.valve ?? actuators.pump)));
  const isLightOn = Boolean(online && (lightLive ? lightLive.state : (actuators.light ?? actuators.lamp)));

  const actuatorItems = [
    {
      id: 'fan',
      actuatorId: 'fan',
      name: 'Quạt thông gió',
      kind: 'ACTUATOR',
      on: isFanOn,
      icon: Fan,
      iconTone: isFanOn ? 'dev-icon-green' : 'dev-icon-blue',
      rule: (
        <span className="dev-rule-text">
          <Fan size={13} /> {liveStatus?.autoMode ? `Tự động khi nhiệt > ${thresholdTemp}°C` : 'Điều khiển thủ công'}
        </span>
      ),
    },
    {
      id: 'valve',
      alias: 'pump',
      actuatorId: 'valve',
      name: 'Van tưới nước',
      kind: 'ACTUATOR',
      on: isValveOn,
      icon: Droplets,
      iconTone: isValveOn ? 'dev-icon-green' : 'dev-icon-blue',
      rule: (
        <span className="dev-rule-text">
          <Droplet size={13} /> {liveStatus?.autoMode ? `Tự động khi đất < ${thresholdSoil}%` : 'Điều khiển thủ công'}
        </span>
      ),
    },
    {
      id: 'light',
      alias: 'lamp',
      actuatorId: 'light',
      name: 'Đèn quang hợp',
      kind: 'ACTUATOR',
      on: isLightOn,
      icon: Lightbulb,
      iconTone: isLightOn ? 'dev-icon-green' : 'dev-icon-blue',
      rule: (
        <span className="dev-rule-text">
          <Lightbulb size={13} /> Lịch tự động: {activeLightSchedule ? `${activeLightSchedule.turnOnTime} - ${activeLightSchedule.turnOffTime}` : '19:00 - 07:00'} (hoặc gạt thủ công)
        </span>
      ),
    },
    ...(actuators.custom || []).map((item) => ({
      id: item.id,
      actuatorId: item.id,
      name: item.name,
      kind: 'ACTUATOR',
      on: Boolean(online && item.on),
      icon: Cpu,
      iconTone: online && item.on ? 'dev-icon-green' : 'dev-icon-blue',
      rule: (
        <span className="dev-rule-text">
          <Cpu size={13} /> {item.detail || 'Thiết bị thêm thủ công'}
        </span>
      ),
    })),
  ];

  const allItems = [...sensorItems, ...actuatorItems];
  const sensorCount = sensorItems.length;
  const actuatorCount = actuatorItems.length;
  const actuatorOnCount = online ? actuatorItems.filter((item) => item.on).length : 0;
  const activeSensorCount = online ? sensorItems.filter((item) => item.link === 'active').length : 0;
  const allSensorsActive = online && sensorItems.length > 0 && sensorItems.every((item) => item.link === 'active');
  const hasTempHumi = Boolean(online && (temperature != null || humidity != null) && sensorLink(temperature != null || humidity != null) === 'active');
  const hasSoil = Boolean(online && soil != null && sensorLink(soil != null) === 'active');

  const sensorLinkLabels = {
    active: 'Đang hoạt động',
    down: 'Mất kết nối',
    empty: 'Chưa kết nối',
    stale: 'Mất kết nối',
    nodata: online ? 'Đang cập nhật' : 'Chưa có dữ liệu',
  };
  const sensorLinkTones = {
    active: 'dev-status-active',
    down: 'dev-status-error',
    empty: 'dev-status-off',
    stale: 'dev-status-error',
    nodata: 'dev-status-off',
  };
  const sensorLinkHint = {
    active: lastSeenTime ? `Telemetry lúc ${new Date(lastSeenTime).toLocaleString('vi-VN')}` : '',
    down: 'Không kết nối được backend',
    empty: 'Chưa có node nào đăng ký',
    stale: 'Không nhận được telemetry mới quá 5 phút',
    nodata: online ? 'Đang chờ bản tin chỉ số này' : 'Bản tin mới nhất thiếu chỉ số này',
  };

  const filtered = allItems.filter((item) => {
    if (category === 'SENSORS' && item.kind !== 'SENSOR') return false;
    if (category === 'ACTUATORS' && item.kind !== 'ACTUATOR') return false;
    return `${item.name} ${item.id}`.toLowerCase().includes(query.toLowerCase());
  });

  const pageSize = 7;
  const pageCount = Math.max(1, Math.ceil(filtered.length / pageSize));
  const safePage = Math.min(page, pageCount - 1);
  const visibleItems = filtered.slice(safePage * pageSize, (safePage + 1) * pageSize);

  useEffect(() => { setPage(0); }, [query, category]);

  return (
    <>
      <div className="page-heading dev-heading">
        <div>
          <h1>Thiết bị &amp; Cảm biến</h1>
          <p>{canControl ? 'Theo dõi các cảm biến và điều khiển bật/tắt thiết bị trong nhà kính.' : 'Theo dõi các cảm biến và chỉ số môi trường trong nhà kính.'}</p>
        </div>
        <div className="heading-actions dev-heading-actions">
          <button className="icon-button dev-refresh" type="button" aria-label="Làm mới thiết bị" title="Làm mới" onClick={() => loadDevices()} disabled={loading}><RefreshCw size={16} className={loading ? 'spin' : ''} /></button>
          {canManageDevices && (
            <button className="button button-primary dev-add" onClick={() => setModalOpen(true)}><Plus size={16} /> Thêm thiết bị</button>
          )}
        </div>
      </div>
      {isViewer && (
        <div className="notice notice-info" style={{ alignItems: 'center', fontSize: '13px', fontWeight: 500, padding: '10px 14px', marginBottom: '16px' }}>
          <Info size={16} style={{ flexShrink: 0 }} />
          <span>Bạn đang xem với vai trò Người xem — chỉ đọc dữ liệu</span>
        </div>
      )}
      {error && <div className="notice notice-error"><AlertTriangle size={17} /><span>{error}</span></div>}
      
      <section className="dev-summary" aria-label="Tổng quan thiết bị">
        <article className="dev-summary-card">
          <div className="dev-summary-top">
            <span>Cảm biến</span>
            <span className="dev-summary-icon dev-icon-blue"><Radio size={15} /></span>
          </div>
          <div className="dev-summary-main">
            <strong>{online ? activeSensorCount : 0}</strong>
            <span>{online ? (activeSensorCount > 0 ? 'đang hoạt động' : 'chờ bản tin') : 'mất kết nối'}</span>
          </div>
          <div className="dev-summary-sensor-box">
            {online && (hasTempHumi || hasSoil) ? (
              <div className="sensor-reading-stack">
                {hasTempHumi && (
                  <div className="dev-sensor-reading">
                    <span className="sensor-glyph-icon"><Thermometer size={14} /></span>
                    <strong className="sensor-bold-val">
                      {temperature != null ? Number(temperature).toLocaleString('vi-VN', { maximumFractionDigits: 1 }) : '—'}°C
                    </strong>
                    <span className="sensor-dot-sep">•</span>
                    <span className="sensor-glyph-icon"><Droplets size={14} /></span>
                    <strong className="sensor-bold-val">
                      {humidity != null ? Number(humidity).toLocaleString('vi-VN', { maximumFractionDigits: 0 }) : '—'}% RH
                    </strong>
                  </div>
                )}
                {hasTempHumi && hasSoil && <div className="dev-summary-sensor-divider" />}
                {hasSoil && (
                  <div className="dev-sensor-reading">
                    <span className="sensor-glyph-icon"><Droplet size={14} /></span>
                    <strong className="sensor-bold-val">
                      {soil != null ? Number(soil).toLocaleString('vi-VN', { maximumFractionDigits: 0 }) : '—'}%
                    </strong>
                    <span className="sensor-note-val">
                      {Number(soil) >= thresholdSoil ? '(Tối ưu)' : '(Cần tưới)'}
                    </span>
                  </div>
                )}
              </div>
            ) : (
              <div className="sensor-pill-empty" style={{ padding: '6px 10px', fontSize: '11px' }}>
                <AlertCircle size={13} />
                <span>{online ? 'Đang chờ bản tin cảm biến...' : 'Không có cảm biến kết nối'}</span>
              </div>
            )}
          </div>
        </article>
        <article className="dev-summary-card">
          <div className="dev-summary-top"><span>Thiết bị điều khiển</span><span className={`dev-summary-icon ${online ? 'dev-icon-green' : 'dev-icon-blue'}`}><Cpu size={15} /></span></div>
          <div className="dev-summary-main"><strong>{online ? `${actuatorOnCount} / ${actuatorCount}` : `0 / ${actuatorCount}`}</strong><span>{online ? 'đang bật' : 'mất kết nối'}</span></div>
          <div className="dev-summary-note">{online ? 'Quạt thông gió, Van tưới nước, Đèn quang hợp' : 'Ngoại tuyến (Mất kết nối)'}</div>
        </article>
        <article className="dev-summary-card">
          <div className="dev-summary-top"><span>Kết nối &amp; Chế độ</span><span className="dev-summary-icon dev-icon-wifi"><Wifi size={15} /></span></div>
          <div className={`dev-summary-main ${online ? 'dev-online' : 'dev-offline'}`}>{online ? (liveStatus?.autoMode ? 'Tự động' : 'Thủ công') : 'Ngoại tuyến'}</div>
          <div className="dev-summary-note">{!apiReachable ? 'Không kết nối được backend' : !hasNodes ? 'Chưa có node nào đăng ký' : !telemetryFresh ? 'Không nhận được telemetry mới' : 'ESP32 hoạt động ổn định'}</div>
        </article>
      </section>

      {/* THANH ĐIỀU KHIỂN NHANH TRỰC TIẾP TRÊN TRANG THIẾT BỊ (ADMIN & OPERATOR) */}
      {canControl && (
        <div className="dev-control-bar">
          <div className="dev-control-bar-buttons">
            <button
              type="button"
              className={`mode-toggle-btn ${liveStatus?.autoMode ? 'auto-active' : ''}`}
              onClick={() => toggleAutoMode(!liveStatus?.autoMode)}
              title="Chuyển chế độ Tự động / Thủ công"
            >
              <SlidersHorizontal size={14} />
              {liveStatus?.autoMode ? 'Chế độ: Tự động (Auto)' : 'Chế độ: Thủ công (Manual)'}
            </button>
            <button
              type="button"
              className="btn-emergency-stop"
              onClick={handleEmergencyStop}
              title="Ngắt khẩn cấp toàn bộ tải (Tắt hết)"
            >
              <Power size={14} /> Ngắt khẩn cấp (Tắt hết)
            </button>
            <button
              type="button"
              className="btn-config-threshold"
              onClick={openThresholdModal}
              title="Cấu hình ngưỡng tự động"
            >
              <Settings2 size={14} /> Cài đặt ngưỡng
            </button>
          </div>
          <span style={{ fontSize: '12px', color: '#64748b' }}>
            {online ? '⚡ ESP32 đã kết nối và sẵn sàng nhận lệnh điều khiển' : '⚠️ ESP32 đang ngoại tuyến'}
          </span>
        </div>
      )}

      <section className="dev-panel">
        <div className="dev-toolbar">
          <div className="dev-tabs" role="group" aria-label="Lọc loại thiết bị">
            <button type="button" className={category === 'ALL' ? 'dev-tab-active' : ''} aria-pressed={category === 'ALL'} onClick={() => setCategory('ALL')}><Cpu size={13} /> Tất cả <span>{allItems.length}</span></button>
            <button type="button" className={category === 'SENSORS' ? 'dev-tab-active' : ''} aria-pressed={category === 'SENSORS'} onClick={() => setCategory('SENSORS')}><Radio size={13} /> Cảm biến <span>{sensorCount}</span></button>
            <button type="button" className={category === 'ACTUATORS' ? 'dev-tab-active' : ''} aria-pressed={category === 'ACTUATORS'} onClick={() => setCategory('ACTUATORS')}><Power size={13} /> Thiết bị điều khiển <span>{actuatorCount}</span></button>
          </div>
          <label className="dev-search"><Search size={14} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Tìm kiếm thiết bị..." aria-label="Tìm kiếm thiết bị" /></label>
        </div>
        <div className="dev-list-heading"><div><strong>Danh sách thiết bị</strong><span>{filtered.length} thiết bị</span></div><span className={`dev-system-status ${allSensorsActive ? '' : 'dev-system-warn'}`}><i />{!online ? 'Toàn bộ thiết bị & cảm biến mất kết nối (Ngoại tuyến)' : allSensorsActive ? 'Hệ thống hoạt động bình thường' : !apiReachable ? 'Mất kết nối backend' : !hasNodes ? 'Chưa có node cảm biến nào' : 'Đang cập nhật dữ liệu cảm biến'}</span></div>
        {loading ? <div className="table-state"><LoaderCircle className="spin" size={21} /> Đang tải thiết bị...</div> : !visibleItems.length ? <div className="table-state"><Cpu size={22} /><span>Chưa có thiết bị phù hợp.</span></div> : (
          <div className="table-scroll dev-table-scroll">
            <table className="dev-table">
              <thead>
                <tr>
                  <th>THIẾT BỊ</th>
                  <th>LOẠI</th>
                  <th>TRẠNG THÁI</th>
                  <th>QUY TẮC HOẠT ĐỘNG</th>
                </tr>
              </thead>
              <tbody>
                {visibleItems.map((item) => {
                  const Icon = item.icon;
                  const isSensor = item.kind === 'SENSOR';
                  return (
                    <tr key={item.id} className={highlightedId ? 'device-row-highlight' : ''}>
                      <td>
                        <div className="dev-device-name">
                          <span className={`dev-device-icon ${item.iconTone}`}><Icon size={16} /></span>
                          <div style={{ display: 'flex', flexDirection: 'column' }}>
                            <strong>{item.name}</strong>
                            {!isSensor && (
                              <small style={{ fontSize: '11px', color: item.on ? '#16a34a' : '#64748b', marginTop: '2px', fontWeight: item.on ? 600 : 400 }}>
                                {item.on ? '• Đang bật' : '• Đang tắt'}
                              </small>
                            )}
                          </div>
                        </div>
                      </td>
                      <td>
                        {isSensor
                          ? <span className="dev-kind"><Radio size={11} /> Cảm biến</span>
                          : <span className="dev-kind"><Power size={11} /> Thiết bị</span>}
                      </td>
                      <td>
                        {isSensor ? (
                          item.link === 'active' ? (
                            item.reading
                          ) : (
                            <span className={`dev-status ${sensorLinkTones[item.link]}`} title={sensorLinkHint[item.link]}>
                              <i />{sensorLinkLabels[item.link]}
                            </span>
                          )
                        ) : canControl ? (
                          <div className="actuator-switch-cell">
                            <input
                              type="checkbox"
                              role="switch"
                              aria-label={`Bật tắt ${item.name}`}
                              checked={Boolean(online && item.on)}
                              disabled={!online || busyActuator === item.id}
                              onChange={(e) => handleToggle(item.id, e.target.checked)}
                              title={!online ? 'Thiết bị mất kết nối' : 'Gạt công tắc để bật hoặc tắt'}
                            />
                            {busyActuator === item.id && (
                              <LoaderCircle size={14} className="spin actuator-switch-spin" title="Đang gửi lệnh..." />
                            )}
                          </div>
                        ) : (
                          <span
                            className={`status-pill ${item.on ? 'status-online' : 'status-neutral'}`}
                            style={{ fontSize: '11px', padding: '3px 9px' }}
                          >
                            <span />
                            {item.on ? 'Đang bật' : 'Đang tắt'}
                          </span>
                        )}
                      </td>
                      <td>
                        {item.rule}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        <div className="dev-pagination">
          <span>Hiển thị {filtered.length ? safePage * pageSize + 1 : 0} trên {filtered.length} thiết bị</span>
          <div>
            <button type="button" aria-label="Trang trước" disabled={safePage <= 0} onClick={() => setPage((current) => current - 1)}>‹</button>
            <span className="dev-page-current">{safePage + 1}</span>
            <button type="button" aria-label="Trang sau" disabled={safePage >= pageCount - 1} onClick={() => setPage((current) => current + 1)}>›</button>
          </div>
        </div>
      </section>

      {/* PHẦN LỊCH CHIẾU SÁNG QUANG HỢP TỰ ĐỘNG */}
      <section className="light-schedule-panel" aria-label="Lịch chiếu sáng quang hợp">
        <div className="light-schedule-header">
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <span style={{ display: 'inline-flex', padding: '6px', background: '#dcfce7', borderRadius: '8px', color: '#16a34a' }}>
              <Lightbulb size={18} />
            </span>
            <div>
              <h3 style={{ margin: 0, fontSize: '15px', fontWeight: 700, color: '#1e293b' }}>
                Lịch chiếu sáng quang hợp tự động
              </h3>
              <p style={{ margin: 0, fontSize: '12px', color: '#64748b' }}>
                {lightSchedules.length} lịch được cài đặt cho node điều khiển
              </p>
            </div>
          </div>
          {canManageSchedules && (
            <button
              type="button"
              className="button button-secondary button-small"
              onClick={() => {
                setShowSchedulePanel((v) => !v);
                setEditingScheduleId(null);
                setScheduleForm({ label: '', turnOnTime: '19:00', turnOffTime: '07:00', enabled: true });
              }}
            >
              <Plus size={14} /> {showSchedulePanel ? 'Đóng form' : 'Thêm lịch mới'}
            </button>
          )}
        </div>

        {/* Form tạo/sửa lịch: chỉ ADMIN và OPERATOR */}
        {canManageSchedules && showSchedulePanel && (
          <form className="light-schedule-form" onSubmit={handleSaveSchedule}>
            <div className="light-schedule-form-row">
              <label className="ls-field">
                <span>Tên mô tả lịch</span>
                <input
                  type="text"
                  placeholder="Ví dụ: Chiếu sáng ban đêm"
                  value={scheduleForm.label}
                  onChange={(e) => setScheduleForm((cur) => ({ ...cur, label: e.target.value }))}
                  required
                />
              </label>
              <label className="ls-field">
                <span>🌙 Giờ bật (24h)</span>
                <input
                  type="time"
                  value={scheduleForm.turnOnTime}
                  onChange={(e) => setScheduleForm((cur) => ({ ...cur, turnOnTime: e.target.value }))}
                  required
                />
              </label>
              <label className="ls-field">
                <span>☀️ Giờ tắt (24h)</span>
                <input
                  type="time"
                  value={scheduleForm.turnOffTime}
                  onChange={(e) => setScheduleForm((cur) => ({ ...cur, turnOffTime: e.target.value }))}
                  required
                />
              </label>
              <label className="ls-field ls-field-toggle">
                <span>Kích hoạt</span>
                <input
                  type="checkbox"
                  role="switch"
                  checked={scheduleForm.enabled}
                  onChange={(e) => setScheduleForm((cur) => ({ ...cur, enabled: e.target.checked }))}
                />
              </label>
              <div className="ls-field ls-actions">
                <button type="submit" className="button button-primary button-small" disabled={savingSchedule}>
                  {savingSchedule ? <LoaderCircle size={13} className="spin" /> : <Check size={13} />} {editingScheduleId ? 'Cập nhật' : 'Lưu lịch'}
                </button>
                <button
                  type="button"
                  className="button button-secondary button-small"
                  onClick={() => { setShowSchedulePanel(false); setEditingScheduleId(null); }}
                >
                  Hủy
                </button>
              </div>
            </div>
            {scheduleError && <p style={{ color: '#dc2626', fontSize: '11px', margin: '8px 0 0' }}>{scheduleError}</p>}
          </form>
        )}

        {/* Danh sách lịch chiếu sáng: CẢ 3 VAI TRÒ ĐỀU XEM ĐƯỢC */}
        {lightSchedules.length === 0 ? (
          <div className="light-schedule-empty">
            <Clock3 size={24} style={{ color: '#94a3b8' }} />
            <span>Chưa có lịch chiếu sáng nào. {canManageSchedules ? 'Hãy nhấn "Thêm lịch mới" để thiết lập.' : ''}</span>
          </div>
        ) : (
          <div className="light-schedule-list">
            {lightSchedules.map((s) => (
              <div key={s.id} className={`light-schedule-item ${s.enabled ? '' : 'ls-disabled'}`}>
                <div className="ls-info">
                  <strong>{s.label || 'Lịch chiếu sáng'}</strong>
                  <div className="ls-time">
                    <Sun size={13} style={{ color: '#f59e0b' }} />
                    <span>{s.turnOnTime} ➔ {s.turnOffTime}</span>
                  </div>
                  <span className={`ls-badge ${s.enabled ? 'ls-badge-on' : 'ls-badge-off'}`}>
                    {s.enabled ? 'Đang kích hoạt' : 'Đã tắt'}
                  </span>
                </div>
                {canManageSchedules && (
                  <div className="ls-row-actions">
                    <input
                      type="checkbox"
                      role="switch"
                      checked={s.enabled}
                      onChange={() => handleToggleScheduleEnabled(s)}
                      title="Bật/Tắt lịch"
                    />
                    <button
                      type="button"
                      className="icon-button"
                      onClick={() => { handleEditSchedule(s); setShowSchedulePanel(true); }}
                      title="Chỉnh sửa lịch"
                      aria-label="Chỉnh sửa lịch"
                    >
                      <Pencil size={13} />
                    </button>
                    <button
                      type="button"
                      className="icon-button"
                      onClick={() => handleDeleteSchedule(s.id)}
                      title="Xóa lịch"
                      aria-label="Xóa lịch"
                      style={{ color: '#dc2626' }}
                    >
                      <Trash2 size={13} />
                    </button>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}

        <div className="light-schedule-note">
          💡 Hệ thống tự động kích hoạt Đèn quang hợp khi thời gian nằm trong khung giờ bật ➔ tắt (hỗ trợ bật qua đêm), và tự động tắt đèn khi ngoài khung giờ.
        </div>
      </section>

      {/* MODAL CÀI ĐẶT NGƯỠNG TRONG TRANG THIẾT BỊ (ADMIN & OPERATOR) */}
      {canControl && showThresholdModal && (
        <div className="modal-overlay" onClick={() => setShowThresholdModal(false)}>
          <div className="threshold-dialog" onClick={(e) => e.stopPropagation()}>
            <div className="threshold-dialog-header">
              <h3>Cấu hình ngưỡng kích hoạt tự động</h3>
              <button type="button" onClick={() => setShowThresholdModal(false)} style={{ border: 0, background: 'transparent', cursor: 'pointer' }}>
                <X size={18} />
              </button>
            </div>
            <form onSubmit={handleSaveThresholds}>
              <div className="threshold-dialog-body">
                <p style={{ margin: 0, fontSize: 12, color: '#52665a' }}>
                  Các giá trị ngưỡng dưới đây sẽ được lưu trữ và truyền trực tiếp xuống bộ vi điều khiển ESP32 qua giao thức MQTT để tự động bật/tắt Quạt, Van tưới và Đèn quang hợp.
                </p>

                <div className="threshold-form-group">
                  <label>
                    <span>Ngưỡng nhiệt độ bật quạt thông gió:</span>
                    <strong>&gt; {draftTemp}°C</strong>
                  </label>
                  <div className="threshold-stepper-row">
                    <div className="threshold-stepper-box">
                      <button
                        type="button"
                        className="stepper-btn stepper-btn-minus"
                        onClick={() => setDraftTemp((prev) => Math.max(15, Number((prev - 0.5).toFixed(1))))}
                        title="Giảm 0.5°C"
                        aria-label="Giảm 0.5°C"
                      >
                        <Minus size={15} />
                      </button>
                      <input
                        type="number"
                        className="threshold-val-input stepper-input"
                        value={draftTemp}
                        step="0.5"
                        min="15"
                        max="50"
                        onChange={(e) => setDraftTemp(parseFloat(e.target.value) || 0)}
                      />
                      <span className="stepper-unit-badge">°C</span>
                      <button
                        type="button"
                        className="stepper-btn stepper-btn-plus"
                        onClick={() => setDraftTemp((prev) => Math.min(50, Number((prev + 0.5).toFixed(1))))}
                        title="Tăng 0.5°C"
                        aria-label="Tăng 0.5°C"
                      >
                        <Plus size={15} />
                      </button>
                    </div>

                    <div className="threshold-slider-flex">
                      <input
                        type="range"
                        min="15"
                        max="50"
                        step="0.5"
                        value={draftTemp}
                        onChange={(e) => setDraftTemp(parseFloat(e.target.value))}
                      />
                    </div>
                  </div>
                  <small style={{ color: '#7a8980', fontSize: 11 }}>Khi nhiệt độ &gt; ngưỡng này, hệ thống sẽ tự động bật quạt làm mát.</small>
                </div>

                <div className="threshold-form-group" style={{ marginTop: 12 }}>
                  <label>
                    <span>Ngưỡng độ ẩm đất bật van tưới:</span>
                    <strong>&lt; {draftSoil}%</strong>
                  </label>
                  <div className="threshold-stepper-row">
                    <div className="threshold-stepper-box">
                      <button
                        type="button"
                        className="stepper-btn stepper-btn-minus"
                        onClick={() => setDraftSoil((prev) => Math.max(10, prev - 1))}
                        title="Giảm 1%"
                        aria-label="Giảm 1%"
                      >
                        <Minus size={15} />
                      </button>
                      <input
                        type="number"
                        className="threshold-val-input stepper-input"
                        value={draftSoil}
                        step="1"
                        min="10"
                        max="80"
                        onChange={(e) => setDraftSoil(parseInt(e.target.value, 10) || 0)}
                      />
                      <span className="stepper-unit-badge">%</span>
                      <button
                        type="button"
                        className="stepper-btn stepper-btn-plus"
                        onClick={() => setDraftSoil((prev) => Math.min(80, prev + 1))}
                        title="Tăng 1%"
                        aria-label="Tăng 1%"
                      >
                        <Plus size={15} />
                      </button>
                    </div>

                    <div className="threshold-slider-flex">
                      <input
                        type="range"
                        min="10"
                        max="80"
                        step="1"
                        value={draftSoil}
                        onChange={(e) => setDraftSoil(parseInt(e.target.value, 10))}
                      />
                    </div>
                  </div>
                  <small style={{ color: '#7a8980', fontSize: 11 }}>Khi độ ẩm đất &lt; ngưỡng này, hệ thống sẽ tự động mở van tưới cấp nước.</small>
                </div>

                <div className="threshold-form-group" style={{ marginTop: 14 }}>
                  <label>
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                      <Lightbulb size={15} style={{ color: '#d97706' }} /> Lịch bật/tắt đèn quang hợp tự động:
                    </span>
                    <strong style={{ color: '#b45309' }}>
                      {draftLightOn || '19:00'} (Bật) ➔ {draftLightOff || '07:00'} (Tắt)
                    </strong>
                  </label>
                  <div style={{ display: 'flex', gap: 20, marginTop: 10, alignItems: 'flex-start', flexWrap: 'wrap' }}>
                    <div>
                      <span style={{ fontSize: 12, fontWeight: 600, color: '#475569', display: 'block', marginBottom: 4 }}>
                        🌙 Giờ bật đèn (24h):
                      </span>
                      <TimeWheelInput
                        value={draftLightOn}
                        onChange={setDraftLightOn}
                        label="Giờ bật đèn (24h)"
                        icon="🌙"
                        placeholder="19:00"
                        align="left"
                      />
                    </div>
                    <div>
                      <span style={{ fontSize: 12, fontWeight: 600, color: '#475569', display: 'block', marginBottom: 4 }}>
                        ☀️ Giờ tắt đèn (24h):
                      </span>
                      <TimeWheelInput
                        value={draftLightOff}
                        onChange={setDraftLightOff}
                        label="Giờ tắt đèn (24h)"
                        icon="☀️"
                        placeholder="07:00"
                        align="right"
                      />
                    </div>
                  </div>
                  <small style={{ color: '#7a8980', fontSize: 11, marginTop: 8, display: 'block' }}>
                    Tự động bật đèn khi thời gian hiện tại nằm trong khoảng từ <strong>{draftLightOn}</strong> đến <strong>{draftLightOff}</strong> (hỗ trợ qua đêm), ngoài khoảng này đèn sẽ tự động tắt.
                  </small>
                </div>
              </div>

              <div className="threshold-dialog-footer">
                <button type="button" className="button button-secondary" onClick={() => setShowThresholdModal(false)}>
                  Hủy bỏ
                </button>
                <button type="submit" className="button button-primary" disabled={savingThreshold}>
                  {savingThreshold ? <LoaderCircle className="spin" size={15} /> : <Check size={15} />}
                  {savingThreshold ? 'Đang lưu & áp dụng...' : 'Lưu & Áp dụng ngay'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {canManageDevices && modalOpen && <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setModalOpen(false); }}><form className="modal" onSubmit={createDevice}><div className="modal-heading"><div><div className="section-kicker">THIẾT BỊ MỚI</div><h2>Thêm thiết bị</h2></div><button type="button" className="icon-button" aria-label="Đóng" onClick={() => setModalOpen(false)}><X size={18} /></button></div><label>Loại thiết bị<select name="kind" defaultValue="SENSOR_NODE"><option value="SENSOR_NODE">Node cảm biến (lưu vào backend)</option><option value="ACTUATOR">Thiết bị điều khiển (quạt / bơm / đèn)</option></select></label><label>Mã thiết bị (chỉ cho node cảm biến)<input name="deviceId" maxLength="100" placeholder="GH01-ESP32-002" /></label><label>Tên thiết bị<input name="name" required maxLength="150" placeholder="Ví dụ: Quạt thông gió" /></label><label>Khu vực<input name="zone" maxLength="50" placeholder="Nhà kính chính" /></label><label>Loại điều khiển (chỉ cho thiết bị điều khiển)<select name="actuatorType" defaultValue="GENERIC"><option value="FAN">Quạt</option><option value="PUMP">Máy bơm</option><option value="LAMP">Đèn</option><option value="GENERIC">Khác</option></select></label><label>Phiên bản firmware<input name="firmwareVersion" maxLength="50" placeholder="1.0.0" /></label><div className="modal-actions"><button type="button" className="button button-secondary" onClick={() => setModalOpen(false)}>Hủy</button><button className="button button-primary" disabled={saving}>{saving ? <LoaderCircle size={16} className="spin" /> : <Check size={16} />} {saving ? 'Đang gửi...' : 'Thêm'}</button></div></form></div>}
    </>
  );
}

function InventorySummaryCard({ icon: Icon, label, value, note, tone }) {
  return <article className="inventory-summary-card"><span className={`inventory-summary-icon ${tone}`}><Icon size={17} /></span><div className="inventory-summary-label">{label}</div><div className="inventory-summary-value">{value}</div><div className="inventory-summary-note">{note}</div></article>;
}

function DeviceInventoryTable({ devices, latestReadings, onEdit, onDelete }) {
  const { canManageDevices, canControl } = usePermission();
  const showActions = canControl || canManageDevices;
  return <div className="table-scroll inventory-table-scroll"><table className="inventory-table"><thead><tr><th>THIẾT BỊ</th><th>TRẠNG THÁI</th><th>GIÁ TRỊ / HOẠT ĐỘNG</th>{showActions && <th style={{ textAlign: 'right' }}>THAO TÁC</th>}</tr></thead><tbody>{devices.map((device) => {
    const telemetry = latestReadings[device.deviceId];
    const isActive = ['ACTIVE', 'ONLINE'].includes(device.status?.toUpperCase()) || (device.lastSeen && (Date.now() - new Date(device.lastSeen).getTime()) < 180000);
    const readings = [
      telemetry?.temperature != null && <span className="device-reading" key="temperature"><Thermometer size={12} /> {Number(telemetry.temperature).toLocaleString('vi-VN', { maximumFractionDigits: 1 })} °C</span>,
      telemetry?.humidity != null && <span className="device-reading" key="humidity"><Activity size={12} /> {Number(telemetry.humidity).toLocaleString('vi-VN', { maximumFractionDigits: 1 })}% RH</span>,
      telemetry?.soilMoisture != null && <span className="device-reading" key="soil"><Sprout size={12} /> {Number(telemetry.soilMoisture).toLocaleString('vi-VN', { maximumFractionDigits: 1 })}% đất</span>,
    ].filter(Boolean);
    return <tr key={device.deviceId}>
      <td><div className="inventory-device-name"><span className="inventory-device-icon"><Cpu size={15} /></span><span><strong>{device.name || 'Chưa đặt tên'}</strong><small>{device.deviceId} · {device.zone || 'Chưa phân khu'}</small></span></div></td>
      <td><span className={`inventory-status ${isActive ? 'inventory-status-active' : ''}`}><i />{isActive ? 'Hoạt động' : device.status === 'OFFLINE' ? 'Mất kết nối' : device.status || 'Chưa xác định'}</span></td>
      <td><div className="device-readings">{readings.length ? readings : <span className="device-reading-empty">{device.lastSeen ? `Hoạt động ${new Date(device.lastSeen).toLocaleTimeString('vi-VN')}` : 'Chưa nhận telemetry'}</span>}</div></td>
      {showActions && (
        <td>
          <div className="inventory-action-group">
            {canControl && <NavLink className="inventory-row-action" to={`/devices`} title="Điều khiển thiết bị"><Activity size={12} /> Điều khiển</NavLink>}
            {canManageDevices && (
              <>
                <button type="button" className="inventory-row-action inventory-row-edit" onClick={() => onEdit?.(device)} title="Chỉnh sửa thiết bị"><Pencil size={12} /> Sửa</button>
                <button type="button" className="inventory-row-action inventory-row-delete" onClick={() => onDelete?.(device)} title="Xóa thiết bị"><Trash2 size={12} /> Xóa</button>
              </>
            )}
          </div>
        </td>
      )}
    </tr>;
  })}</tbody></table></div>;
}

function DeviceTable({ devices, loading, compact = false }) {
  if (loading) return <div className="table-state"><LoaderCircle className="spin" size={21} /> Đang tải thiết bị...</div>;
  if (!devices.length) return <div className="table-state"><Cpu size={22} /><span>Chưa có thiết bị phù hợp.</span></div>;
  return (
    <div className="table-scroll"><table><thead><tr><th>THIẾT BỊ</th><th>KHU VỰC</th><th>TRẠNG THÁI</th>{!compact && <th>ĐỊA CHỈ IP</th>}<th>HOẠT ĐỘNG CUỐI</th></tr></thead><tbody>{devices.map((device) => <tr key={device.id ?? device.deviceId}><td><div className="device-name"><span className="device-avatar"><Cpu size={16} /></span><span><strong>{device.name || 'Chưa đặt tên'}</strong><small>{device.deviceId}</small></span></div></td><td>{device.zone || '—'}</td><td><span className={`status-pill ${['ACTIVE', 'ONLINE'].includes(device.status?.toUpperCase()) ? 'status-online' : 'status-neutral'}`}><span />{device.status || 'UNKNOWN'}</span></td>{!compact && <td className="mono-cell">{device.ipAddress || '—'}</td>}<td>{device.lastSeen ? new Date(device.lastSeen).toLocaleString('vi-VN') : 'Chưa có dữ liệu'}</td></tr>)}</tbody></table></div>
  );
}

export default App;