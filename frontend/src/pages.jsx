import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  AlertTriangle,
  ArrowDownToLine,
  Bell,
  Check,
  CircleAlert,
  Clock3,
  FileClock,
  Info,
  LoaderCircle,
  LockKeyhole,
  MailPlus,
  RefreshCw,
  Search,
  Settings2,
  ShieldCheck,
  SlidersHorizontal,
  UserRound,
  UsersRound,
  Wifi,
  X,
} from 'lucide-react';
import { apiRequest } from './api.js';

const refreshMs = 10000;

/**
 * Reads a list endpoint. Paginated endpoints answer with
 * { content, page, size, totalElements, totalPages, ... } while plain ones (such as
 * /api/devices) still answer with a bare array, so both shapes are accepted here.
 * Callers only ever see `data` as an array plus the paging metadata.
 */
function useApiCollection(path, params) {
  const [data, setData] = useState([]);
  const [meta, setMeta] = useState({ page: 0, size: 20, totalElements: 0, totalPages: 0 });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [reloadKey, setReloadKey] = useState(0);

  const paramsKey = JSON.stringify(params || {});
  const query = useMemo(() => {
    const search = new URLSearchParams();
    Object.entries(JSON.parse(paramsKey)).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '' && value !== 'ALL') search.set(key, value);
    });
    const serialized = search.toString();
    return serialized ? `${path}?${serialized}` : path;
  }, [path, paramsKey]);

  const load = useCallback(async (showLoading = true) => {
    if (showLoading) setLoading(true);
    try {
      const payload = await apiRequest(query);
      if (Array.isArray(payload)) {
        setData(payload);
        setMeta({ page: 0, size: payload.length, totalElements: payload.length, totalPages: 1 });
      } else {
        setData(payload.content || []);
        setMeta({
          page: payload.page ?? 0,
          size: payload.size ?? 20,
          totalElements: payload.totalElements ?? 0,
          totalPages: payload.totalPages ?? 0,
        });
      }
      setError('');
    } catch (requestError) {
      setError(requestError.message || 'Không thể tải dữ liệu.');
    } finally {
      if (showLoading) setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    load();
    const intervalId = window.setInterval(() => load(false), refreshMs);
    return () => window.clearInterval(intervalId);
  }, [load, reloadKey]);

  return {
    data,
    loading,
    error,
    meta,
    refresh: () => setReloadKey((key) => key + 1),
  };
}

/** Page navigation shared by every paginated table. */
function Pager({ meta, onPage }) {
  if (!meta || meta.totalPages <= 1) return null;
  const { page, size, totalPages, totalElements } = meta;
  return (
    <div className="table-toolbar pager-bar">
      <span className="toolbar-count">
        Hiển thị {page * size + 1}–{Math.min((page + 1) * size, totalElements)} trên {totalElements} bản ghi
      </span>
      <div className="row-actions">
        <button className="button button-small button-secondary" disabled={page <= 0} onClick={() => onPage(page - 1)}>Trước</button>
        <span className="toolbar-count">Trang {page + 1}/{totalPages}</span>
        <button className="button button-small button-secondary" disabled={page >= totalPages - 1} onClick={() => onPage(page + 1)}>Sau</button>
      </div>
    </div>
  );
}

function ModuleHeading({ eyebrow, title, description, actions }) {
  return <div className="page-heading"><div><div className="eyebrow">{eyebrow}</div><h1>{title}</h1><p>{description}</p></div>{actions && <div className="heading-actions">{actions}</div>}</div>;
}

function ErrorNotice({ children }) {
  return children ? <div className="notice notice-error"><AlertTriangle size={17} /><span>{children}</span></div> : null;
}

function LoadingState({ label = 'Đang tải dữ liệu...' }) {
  return <div className="table-state"><LoaderCircle className="spin" size={20} /> {label}</div>;
}

function EmptyState({ label }) {
  return <div className="table-state"><Info size={19} /> {label}</div>;
}

function SearchBox({ value, onChange, placeholder }) {
  return <label className="search-field"><Search size={16} /><input value={value} onChange={(event) => onChange(event.target.value)} placeholder={placeholder} /></label>;
}

function StatusBadge({ value }) {
  const normalized = (value || 'UNKNOWN').toUpperCase();
  const tone = ['OPEN', 'CRITICAL', 'HIGH', 'FAILED', 'LOCKED', 'OFFLINE'].includes(normalized) ? 'status-danger' : ['ACKNOWLEDGED', 'PENDING', 'REGISTERED', 'PROVISIONED', 'INACTIVE'].includes(normalized) ? 'status-warning' : ['RESOLVED', 'SUCCESS', 'ACTIVE', 'SENT'].includes(normalized) ? 'status-online' : 'status-neutral';
  return <span className={`status-pill ${tone}`}><span />{normalized}</span>;
}

function TableFrame({ loading, empty, children }) {
  if (loading) return <LoadingState />;
  if (empty) return <EmptyState label={empty} />;
  return <div className="table-scroll"><table>{children}</table></div>;
}

export function AlertsPage() {
  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [status, setStatus] = useState('ALL');
  const [severity, setSeverity] = useState('ALL');
  const [page, setPage] = useState(0);
  const [busyId, setBusyId] = useState(null);

  // Debounce so typing does not fire a request per keystroke.
  useEffect(() => {
    const id = window.setTimeout(() => setDebouncedSearch(search), 300);
    return () => window.clearTimeout(id);
  }, [search]);

  useEffect(() => { setPage(0); }, [debouncedSearch, status, severity]);

  const { data, loading, error, meta, refresh } = useApiCollection('/api/alerts', {
    status, severity, search: debouncedSearch, page, size: 20,
  });

  async function updateAlert(id, action) {
    setBusyId(id);
    try { await apiRequest(`/api/alerts/${id}/${action}`, { method: 'POST' }); refresh(); }
    catch (requestError) { window.alert(requestError.message); }
    finally { setBusyId(null); }
  }

  return <>
    <ModuleHeading eyebrow="VẬN HÀNH / SỰ KIỆN" title="Cảnh báo" description="Lọc, xác nhận và đóng các cảnh báo được ghi nhận từ thiết bị." actions={<button className="button button-secondary" onClick={refresh}><RefreshCw size={15} /> Làm mới</button>} />
    <ErrorNotice>{error}</ErrorNotice>
    <div className="summary-strip"><span><strong>{data.filter((item) => item.status !== 'RESOLVED').length}</strong> đang mở (trang này)</span><span><strong>{data.filter((item) => item.severity === 'CRITICAL' || item.severity === 'HIGH').length}</strong> mức cao (trang này)</span><span><strong>{meta.totalElements}</strong> cảnh báo khớp bộ lọc</span></div>
    <section className="table-panel">
      <div className="table-toolbar filter-toolbar"><SearchBox value={search} onChange={setSearch} placeholder="Tìm thiết bị hoặc nội dung cảnh báo" /><label className="filter-select"><SlidersHorizontal size={15} /><select value={status} onChange={(event) => setStatus(event.target.value)}><option value="ALL">Mọi trạng thái</option><option value="OPEN">Đang mở</option><option value="ACKNOWLEDGED">Đã xác nhận</option><option value="RESOLVED">Đã xử lý</option></select></label><label className="filter-select"><select value={severity} onChange={(event) => setSeverity(event.target.value)}><option value="ALL">Mọi mức độ</option><option value="CRITICAL">Critical</option><option value="HIGH">High</option><option value="MEDIUM">Medium</option><option value="LOW">Low</option></select></label></div>
      <TableFrame loading={loading} empty={data.length ? null : 'Không có cảnh báo phù hợp.'}><thead><tr><th>CẢNH BÁO</th><th>THIẾT BỊ</th><th>MỨC ĐỘ</th><th>TRẠNG THÁI</th><th>THỜI GIAN</th><th>THAO TÁC</th></tr></thead><tbody>{data.map((alert) => <tr key={alert.id}><td><div className="cell-primary">{alert.message}</div><div className="cell-secondary">{alert.type}</div></td><td><div className="cell-primary">{alert.deviceName}</div><div className="cell-secondary">{alert.deviceId} · {alert.zone}</div></td><td><StatusBadge value={alert.severity} /></td><td><StatusBadge value={alert.status} /></td><td>{formatDate(alert.createdAt)}</td><td><div className="row-actions">{alert.status === 'OPEN' && <button className="button button-small button-secondary" disabled={busyId === alert.id} onClick={() => updateAlert(alert.id, 'acknowledge')}><Check size={14} /> Xác nhận</button>}{alert.status !== 'RESOLVED' && <button className="icon-button" title="Đánh dấu đã xử lý" aria-label="Đánh dấu đã xử lý" disabled={busyId === alert.id} onClick={() => updateAlert(alert.id, 'resolve')}><ShieldCheck size={16} /></button>}</div></td></tr>)}</tbody></TableFrame>
      <Pager meta={meta} onPage={setPage} />
    </section>
  </>;
}

export function CommandsPage() {
  const [capability, setCapability] = useState(null);
  const [status, setStatus] = useState('ALL');
  const [deviceId, setDeviceId] = useState('ALL');
  const [page, setPage] = useState(0);
  const { data: devices } = useApiCollection('/api/devices');

  useEffect(() => { setPage(0); }, [status, deviceId]);
  useEffect(() => { apiRequest('/api/system/status').then(setCapability).catch(() => setCapability(null)); }, []);

  const { data: commands, loading, error, meta, refresh } = useApiCollection('/api/commands', {
    status, deviceId, page, size: 20,
  });

  return <>
    <ModuleHeading eyebrow="VẬN HÀNH / ĐIỀU KHIỂN" title="Lệnh thiết bị" description="Theo dõi lịch sử yêu cầu điều khiển và trạng thái thực thi." actions={<button className="button button-secondary" onClick={refresh}><RefreshCw size={15} /> Làm mới</button>} />
    <div className="notice notice-info"><CircleAlert size={17} /><span>{capability?.mqttEnabled && capability?.commandProtocolConfigured ? 'Broker sẵn sàng; lệnh sẽ được gửi theo giao thức firmware.' : 'Gửi lệnh đang bị khóa: MQTT đang tắt hoặc chưa có giao thức topic/payload từ firmware. Hiện trang chỉ hiển thị lịch sử có thật.'}</span></div>
    <ErrorNotice>{error}</ErrorNotice>
    <section className="table-panel">
      <div className="table-toolbar filter-toolbar"><div className="toolbar-title"><strong>Lịch sử lệnh</strong><span>{meta.totalElements} bản ghi</span></div><label className="filter-select"><select value={deviceId} onChange={(event) => setDeviceId(event.target.value)}><option value="ALL">Mọi thiết bị</option>{devices.map((device) => <option value={device.deviceId} key={device.deviceId}>{device.name} · {device.deviceId}</option>)}</select></label><label className="filter-select"><select value={status} onChange={(event) => setStatus(event.target.value)}><option value="ALL">Mọi trạng thái</option>{['PENDING', 'SENT', 'SUCCESS', 'FAILED', 'TIMEOUT', 'REJECTED'].map((value) => <option key={value}>{value}</option>)}</select></label></div>
      <TableFrame loading={loading} empty={commands.length ? null : 'Chưa có lịch sử lệnh.'}><thead><tr><th>MÃ LỆNH</th><th>THIẾT BỊ</th><th>LOẠI</th><th>PAYLOAD</th><th>TRẠNG THÁI</th><th>TẠO LÚC</th></tr></thead><tbody>{commands.map((command) => <tr key={command.id}><td className="mono-cell">{command.commandId}</td><td><div className="cell-primary">{command.deviceName}</div><div className="cell-secondary">{command.deviceId}</div></td><td>{command.commandType}</td><td><code className="payload-cell">{command.payload ? JSON.stringify(command.payload) : '—'}</code></td><td><StatusBadge value={command.status} /></td><td>{formatDate(command.createdAt)}</td></tr>)}</tbody></TableFrame>
      <Pager meta={meta} onPage={setPage} />
    </section>
  </>;
}

const lifecycleStatuses = ['REGISTERED', 'PROVISIONED', 'ACTIVE', 'OFFLINE', 'MAINTENANCE', 'LOCKED', 'DECOMMISSIONED'];

export function DeviceLifecyclePage() {
  const { data, loading, error, refresh } = useApiCollection('/api/devices');
  const [busyId, setBusyId] = useState('');
  const [query, setQuery] = useState('');
  const [stage, setStage] = useState('ALL');
  const filtered = data.filter((device) => (stage === 'ALL' || device.status === stage)
    && `${device.deviceId} ${device.name} ${device.zone}`.toLowerCase().includes(query.toLowerCase()));

  async function updateStatus(deviceId, status) {
    setBusyId(deviceId);
    try { await apiRequest(`/api/devices/${encodeURIComponent(deviceId)}/status`, { method: 'PATCH', body: JSON.stringify({ status }) }); refresh(); }
    catch (requestError) { window.alert(requestError.message); }
    finally { setBusyId(''); }
  }

  return <>
    <ModuleHeading eyebrow="VẬN HÀNH / VÒNG ĐỜI" title="Vòng đời thiết bị" description="Theo dõi giai đoạn triển khai, bảo trì và ngừng hoạt động của node." actions={<button className="button button-secondary" onClick={refresh}><RefreshCw size={15} /> Làm mới</button>} />
    <ErrorNotice>{error}</ErrorNotice>
    <section className="table-panel"><div className="table-toolbar filter-toolbar"><SearchBox value={query} onChange={setQuery} placeholder="Tìm theo tên, ID hoặc khu vực" /><label className="filter-select"><select value={stage} onChange={(event) => setStage(event.target.value)}><option value="ALL">Mọi giai đoạn</option>{lifecycleStatuses.map((value) => <option key={value}>{value}</option>)}</select></label><span className="toolbar-count">{filtered.length} thiết bị</span></div>
      <TableFrame loading={loading} empty={filtered.length ? null : 'Chưa có thiết bị.'}><thead><tr><th>NODE</th><th>KHU VỰC</th><th>GIAI ĐOẠN</th><th>FIRMWARE</th><th>HOẠT ĐỘNG CUỐI</th><th>CẬP NHẬT GIAI ĐOẠN</th></tr></thead><tbody>{filtered.map((device) => <tr key={device.deviceId}><td><div className="cell-primary">{device.name}</div><div className="cell-secondary">{device.deviceId}</div></td><td>{device.zone}</td><td><StatusBadge value={device.status} /></td><td>{device.firmwareVersion || '—'}</td><td>{formatDate(device.lastSeen)}</td><td><select className="inline-select" aria-label={`Đổi trạng thái ${device.deviceId}`} disabled={busyId === device.deviceId} value={device.status} onChange={(event) => updateStatus(device.deviceId, event.target.value)}>{lifecycleStatuses.map((value) => <option key={value}>{value}</option>)}</select></td></tr>)}</tbody></TableFrame>
    </section>
  </>;
}

export function UsersPage() {
  const [query, setQuery] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const [status, setStatus] = useState('ALL');
  const [page, setPage] = useState(0);

  useEffect(() => {
    const id = window.setTimeout(() => setDebouncedQuery(query), 300);
    return () => window.clearTimeout(id);
  }, [query]);

  useEffect(() => { setPage(0); }, [debouncedQuery, status]);

  const { data, loading, error, meta, refresh } = useApiCollection('/api/users', {
    status, search: debouncedQuery, page, size: 20,
  });

  return <>
    <ModuleHeading eyebrow="QUẢN TRỊ / TRUY CẬP" title="Người dùng" description="Tra cứu tài khoản, vai trò và trạng thái trong cơ sở dữ liệu." actions={<><Link className="button button-secondary" to="/invite-user"><MailPlus size={15} /> Tạo tài khoản</Link><button className="button button-secondary" onClick={refresh}><RefreshCw size={15} /></button></>} />
    <div className="notice notice-info"><Info size={17} /><span>Tài khoản mới được gán quyền USER. Mật khẩu được mã hóa trước khi lưu.</span></div>
    <ErrorNotice>{error}</ErrorNotice>
    <section className="table-panel"><div className="table-toolbar filter-toolbar"><SearchBox value={query} onChange={setQuery} placeholder="Tìm theo tên, email hoặc vai trò" /><label className="filter-select"><select value={status} onChange={(event) => setStatus(event.target.value)}><option value="ALL">Mọi trạng thái</option><option value="ACTIVE">ACTIVE</option><option value="INACTIVE">INACTIVE</option><option value="LOCKED">LOCKED</option></select></label><span className="toolbar-count">{meta.totalElements} tài khoản</span></div>
      <TableFrame loading={loading} empty={data.length ? null : 'Chưa có tài khoản phù hợp.'}><thead><tr><th>TÀI KHOẢN</th><th>EMAIL</th><th>VAI TRÒ</th><th>TRẠNG THÁI</th><th>NGÀY TẠO</th></tr></thead><tbody>{data.map((user) => <tr key={user.id}><td><div className="user-cell"><span className="user-initial">{(user.fullName || user.username || '?').slice(0, 1).toUpperCase()}</span><span><strong>{user.fullName || user.username}</strong><small>@{user.username}</small></span></div></td><td>{user.email}</td><td><div className="tag-list">{user.roles.length ? user.roles.map((role) => <span className="role-tag" key={role}>{role}</span>) : <span className="cell-secondary">Chưa gán vai trò</span>}</div></td><td><StatusBadge value={user.status} /></td><td>{formatDate(user.createdAt)}</td></tr>)}</tbody></TableFrame>
      <Pager meta={meta} onPage={setPage} />
    </section>
  </>;
}

export function AuditLogsPage() {
  const [query, setQuery] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const [action, setAction] = useState('ALL');
  const [page, setPage] = useState(0);

  useEffect(() => {
    const id = window.setTimeout(() => setDebouncedQuery(query), 300);
    return () => window.clearTimeout(id);
  }, [query]);

  useEffect(() => { setPage(0); }, [debouncedQuery, action]);

  const { data, loading, error, meta, refresh } = useApiCollection('/api/audit-logs', {
    action, search: debouncedQuery, page, size: 20,
  });

  const actions = useMemo(
    () => [...new Set(data.map((row) => row.action).filter(Boolean))].sort(),
    [data],
  );

  return <>
    <ModuleHeading eyebrow="QUẢN TRỊ / THEO DÕI" title="Nhật ký hệ thống" description="Tra cứu các sự kiện audit được lưu trong cơ sở dữ liệu." actions={<button className="button button-secondary" onClick={refresh}><RefreshCw size={15} /> Làm mới</button>} />
    <ErrorNotice>{error}</ErrorNotice>
    <section className="table-panel"><div className="table-toolbar filter-toolbar"><SearchBox value={query} onChange={setQuery} placeholder="Tìm người dùng, tài nguyên hoặc chi tiết" /><label className="filter-select"><FileClock size={15} /><select value={action} onChange={(event) => setAction(event.target.value)}><option value="ALL">Mọi hành động</option>{actions.map((value) => <option key={value}>{value}</option>)}</select></label><button className="button button-secondary" onClick={() => downloadCsv(data)}><ArrowDownToLine size={15} /> Xuất CSV trang này</button></div>
      <TableFrame loading={loading} empty={data.length ? null : 'Chưa có bản ghi audit.'}><thead><tr><th>THỜI GIAN</th><th>NGƯỜI DÙNG</th><th>HÀNH ĐỘNG</th><th>TÀI NGUYÊN</th><th>CHI TIẾT</th><th>IP</th></tr></thead><tbody>{data.map((row) => <tr key={row.id}><td>{formatDate(row.createdAt)}</td><td>{row.username || 'System'}</td><td><span className="action-tag">{row.action}</span></td><td>{[row.resourceType, row.resourceId].filter(Boolean).join(' · ') || '—'}</td><td className="details-cell">{formatDetails(row.details)}</td><td className="mono-cell">{row.ipAddress || '—'}</td></tr>)}</tbody></TableFrame>
      <Pager meta={meta} onPage={setPage} />
    </section>
  </>;
}

export function SettingsPage() {
  const { data: devices, loading: devicesLoading, error: devicesError } = useApiCollection('/api/devices');
  const [deviceId, setDeviceId] = useState('');
  const [configuration, setConfiguration] = useState(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (!deviceId && devices.length) setDeviceId(devices[0].deviceId);
  }, [deviceId, devices]);

  useEffect(() => {
    if (!deviceId) { setConfiguration(null); return; }
    let active = true;
    setLoading(true);
    setError('');
    apiRequest(`/api/devices/${encodeURIComponent(deviceId)}/configuration`)
      .then((data) => { if (active) setConfiguration(data); })
      .catch((requestError) => { if (active) setError(requestError.message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [deviceId]);

  async function save(event) {
    event.preventDefault();
    setSaving(true);
    setError('');
    setSaved(false);
    const form = new FormData(event.currentTarget);
    const payload = {
      temperatureHigh: Number(form.get('temperatureHigh')),
      soilMoistureLow: Number(form.get('soilMoistureLow')),
      heartbeatInterval: Number(form.get('heartbeatInterval')),
      autoWatering: form.get('autoWatering') === 'on',
    };
    try { setConfiguration(await apiRequest(`/api/devices/${encodeURIComponent(deviceId)}/configuration`, { method: 'PUT', body: JSON.stringify(payload) })); setSaved(true); }
    catch (requestError) { setError(requestError.message || 'Không thể lưu cấu hình.'); }
    finally { setSaving(false); }
  }

  return <>
    <ModuleHeading eyebrow="QUẢN TRỊ / VẬN HÀNH" title="Cài đặt thiết bị" description="Điều chỉnh ngưỡng cảnh báo và chu kỳ heartbeat cho từng node." />
    <ErrorNotice>{devicesError || error}</ErrorNotice>
    <section className="settings-panel">
      <div className="section-heading"><div><div className="section-kicker">CẤU HÌNH THEO NODE</div><h2>Tham số môi trường</h2></div><Settings2 size={19} className="muted-icon" /></div>
      {devicesLoading ? <LoadingState /> : devices.length === 0 ? <EmptyState label="Đăng ký thiết bị trước khi cấu hình." /> : <>
        <label className="form-label">Thiết bị<select className="form-control" value={deviceId} onChange={(event) => setDeviceId(event.target.value)}>{devices.map((device) => <option key={device.deviceId} value={device.deviceId}>{device.name} · {device.deviceId}</option>)}</select></label>
        {loading ? <LoadingState label="Đang tải cấu hình..." /> : configuration && <form className="settings-form" onSubmit={save}>
          <div className="settings-fields"><label className="form-label">Ngưỡng nhiệt độ cao <span className="input-with-unit"><input className="form-control" type="number" name="temperatureHigh" min="0.01" max="80" step="0.1" required value={configuration.temperatureHigh} onChange={(event) => setConfiguration({ ...configuration, temperatureHigh: event.target.value })} /><span>°C</span></span></label><label className="form-label">Ngưỡng độ ẩm đất thấp <span className="input-with-unit"><input className="form-control" type="number" name="soilMoistureLow" min="0" max="100" step="0.1" required value={configuration.soilMoistureLow} onChange={(event) => setConfiguration({ ...configuration, soilMoistureLow: event.target.value })} /><span>%</span></span></label><label className="form-label">Chu kỳ heartbeat <span className="input-with-unit"><input className="form-control" type="number" name="heartbeatInterval" min="1" max="3600" step="1" required value={configuration.heartbeatInterval} onChange={(event) => setConfiguration({ ...configuration, heartbeatInterval: event.target.value })} /><span>giây</span></span></label></div>
          <label className="toggle-row"><span><strong>Tự động tưới</strong><small>Cho phép node tự điều khiển tưới theo cấu hình đã triển khai.</small></span><input type="checkbox" name="autoWatering" checked={Boolean(configuration.autoWatering)} onChange={(event) => setConfiguration({ ...configuration, autoWatering: event.target.checked })} /></label>
          <div className="form-footer">{configuration.updatedAt && <span>Cập nhật {formatDate(configuration.updatedAt)}</span>}<button className="button button-primary" disabled={saving}>{saving ? <LoaderCircle className="spin" size={15} /> : <Check size={15} />}{saving ? 'Đang lưu...' : 'Lưu cấu hình'}</button></div>
          {saved && <div className="inline-success"><Check size={15} /> Cấu hình đã được lưu vào cơ sở dữ liệu.</div>}
        </form>}
      </>}
    </section>
    <div className="notice notice-info"><Info size={17} /><span>Thay đổi đã lưu ở backend. Gửi cấu hình xuống firmware sẽ cần MQTT command protocol được xác định.</span></div>
  </>;
}

export function ProfilePage() {
  const [profile, setProfile] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    apiRequest('/api/auth/me')
      .then(setProfile)
      .catch((requestError) => setError(requestError.message || 'Không thể tải hồ sơ.'))
      .finally(() => setLoading(false));
  }, []);

  return <>
    <ModuleHeading eyebrow="TÀI KHOẢN" title="Hồ sơ cá nhân" description="Thiết lập hồ sơ và thông tin xác thực cho người đang đăng nhập." />
    <ErrorNotice>{error}</ErrorNotice>
    {loading ? <LoadingState label="Đang tải hồ sơ..." /> : profile && <section className="settings-panel profile-panel">
      <div className="section-heading"><div><div className="section-kicker">PHIÊN ĐANG ĐĂNG NHẬP</div><h2>{profile.fullName || profile.username}</h2></div><UserRound size={20} className="muted-icon" /></div>
      <div className="profile-details">
        <div><span>Tên đăng nhập</span><strong>{profile.username}</strong></div>
        <div><span>Email</span><strong>{profile.email}</strong></div>
        <div><span>Trạng thái</span><StatusBadge value={profile.status} /></div>
        <div><span>Vai trò</span><strong>{profile.roles?.length ? profile.roles.join(', ') : 'Chưa gán vai trò'}</strong></div>
      </div>
      <div className="form-footer"><span><LockKeyhole size={14} /> Hồ sơ được tải từ phiên xác thực hiện tại.</span></div>
    </section>}
  </>;
}

export function InviteUserPage() {
  const [form, setForm] = useState({ username: '', email: '', fullName: '', password: '' });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [created, setCreated] = useState(null);

  async function createUser(event) {
    event.preventDefault();
    setSaving(true);
    setError('');
    setCreated(null);
    try {
      const user = await apiRequest('/api/users', {
        method: 'POST',
        body: JSON.stringify(form),
      });
      setCreated(user);
      setForm({ username: '', email: '', fullName: '', password: '' });
    } catch (requestError) {
      setError(requestError.message || 'Không thể tạo tài khoản.');
    } finally {
      setSaving(false);
    }
  }

  return <>
    <ModuleHeading eyebrow="QUẢN TRỊ / TRUY CẬP" title="Tạo tài khoản" description="Tạo thông tin đăng nhập mới với quyền USER." actions={<Link className="button button-secondary" to="/users"><UsersRound size={15} /> Danh sách người dùng</Link>} />
    <ErrorNotice>{error}</ErrorNotice>
    <section className="settings-panel user-create-panel">
      <div className="section-heading"><div><div className="section-kicker">TÀI KHOẢN MỚI</div><h2>Thông tin đăng nhập</h2></div><MailPlus size={19} className="muted-icon" /></div>
      <form className="settings-form" onSubmit={createUser}>
        <div className="settings-fields">
          <label className="form-label">Tên đăng nhập<input className="form-control" required minLength="3" maxLength="50" autoComplete="off" value={form.username} onChange={(event) => setForm({ ...form, username: event.target.value })} /></label>
          <label className="form-label">Email<input className="form-control" required type="email" maxLength="150" autoComplete="email" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} /></label>
          <label className="form-label">Họ và tên<input className="form-control" maxLength="150" autoComplete="name" value={form.fullName} onChange={(event) => setForm({ ...form, fullName: event.target.value })} /></label>
          <label className="form-label">Mật khẩu tạm thời<input className="form-control" required type="password" minLength="8" maxLength="72" autoComplete="new-password" value={form.password} onChange={(event) => setForm({ ...form, password: event.target.value })} /></label>
        </div>
        <div className="form-footer"><span>Yêu cầu mật khẩu tối thiểu 8 ký tự.</span><button className="button button-primary" disabled={saving}>{saving ? <LoaderCircle className="spin" size={15} /> : <Check size={15} />}{saving ? 'Đang tạo...' : 'Tạo tài khoản'}</button></div>
      </form>
      {created && <div className="inline-success"><Check size={15} /> Đã tạo tài khoản @{created.username} với quyền USER.</div>}
    </section>
  </>;
}

function formatDate(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('vi-VN');
}

// `details` arrives as a JSON object from the API, but older rows or a plain-text
// value should still render instead of breaking the cell.
function formatDetails(value) {
  if (value === null || value === undefined || value === '') return '-';
  if (typeof value === 'object') return JSON.stringify(value);
  try { return JSON.stringify(JSON.parse(value)); } catch { return String(value); }
}

function downloadCsv(rows) {
  const columns = ['createdAt', 'username', 'action', 'resourceType', 'resourceId', 'ipAddress', 'details'];
  const csv = [columns.join(','), ...rows.map((row) => columns.map((column) => `"${String(row[column] ?? '').replaceAll('"', '""')}"`).join(','))].join('\r\n');
  const url = URL.createObjectURL(new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = 'audit-logs.csv';
  link.click();
  URL.revokeObjectURL(url);
}