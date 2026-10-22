import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router-dom';
import {
  AlertTriangle,
  BellRing,
  Bell,
  Check,
  CircleAlert,
  CircleX,
  Clock3,
  Cpu,
  Droplet,
  Eye,
  EyeOff,
  Fan,
  FileClock,
  Info,
  Lightbulb,
  LoaderCircle,
  LockKeyhole,
  Mail,
  MailPlus,
  MapPin,
  Moon,
  Pencil,
  Phone,
  Play,
  Power,
  Radio,
  RefreshCw,
  RotateCcw,
  Search,
  Settings2,
  ShieldCheck,
  SlidersHorizontal,
  Sprout,
  Sun,
  Timer,
  Thermometer,
  UserRound,
  UsersRound,
  Wifi,
  Wrench,
  X,
  Zap,
} from 'lucide-react';
import { apiRequest, getStoredSession } from './api.js';
import { fileToAvatarDataUrl, getPhone, roleLabel, setAvatar, setPhone, useAvatar } from './avatar.js';

const refreshMs = 10000;
const accountRoles = [
  { value: 'ADMIN', title: 'Administrator (Quản trị viên)', badge: 'Admin', description: 'Toàn quyền quản lý người dùng, thiết bị và cấu hình hệ thống.' },
  { value: 'OPERATOR', title: 'Giám sát & Vận hành (Operator)', badge: 'Giám sát', description: 'Theo dõi dữ liệu nhà kính và xử lý các cảnh báo vận hành.' },
  { value: 'VIEWER', title: 'Người xem (Viewer / Observer)', badge: 'Người xem', description: 'Chỉ xem dữ liệu cảm biến và báo cáo, không quản lý tài khoản.' },
];

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

const alertTabs = [
  { value: 'ALL', label: 'Tất cả' },
  { value: 'CRITICAL', label: 'Nghiêm trọng' },
  { value: 'HIGH', label: 'Cảnh báo' },
  { value: 'RESOLVED', label: 'Đã giải quyết' },
];

const severityMeta = {
  CRITICAL: { label: 'Nghiêm trọng', className: 'al-pill-crit' },
  HIGH: { label: 'Cảnh báo', className: 'al-pill-warn' },
  MEDIUM: { label: 'Cảnh báo', className: 'al-pill-warn' },
  LOW: { label: 'Thông tin', className: 'al-pill-info' },
};

const alertStatusMeta = {
  OPEN: { label: 'Chưa xử lý', className: 'al-pill-crit' },
  ACKNOWLEDGED: { label: 'Đang theo dõi', className: 'al-pill-warn' },
  RESOLVED: { label: 'Đã xử lý', className: 'al-pill-ok' },
};

function tabQuery(tab) {
  if (tab === 'CRITICAL') return { severity: 'CRITICAL' };
  if (tab === 'HIGH') return { severity: 'HIGH' };
  if (tab === 'RESOLVED') return { status: 'RESOLVED' };
  return {};
}

function buildAlertQuery(params, page, size) {
  const search = new URLSearchParams();
  Object.entries(params || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') search.set(key, value);
  });
  if (page !== undefined && page !== null) search.set('page', page);
  if (size !== undefined && size !== null) search.set('size', size);
  const serialized = search.toString();
  return `/api/alerts${serialized ? `?${serialized}` : ''}`;
}

async function fetchAlertTotal(params) {
  const payload = await apiRequest(buildAlertQuery(params, 0, 1));
  if (Array.isArray(payload)) return payload.length;
  return payload.totalElements ?? 0;
}

// Messages embed the reading, e.g. "Nhiệt độ 35.4°C vượt ngưỡng 35.0°C."
function parseAlertReading(alert) {
  const text = alert?.message || '';
  const numbers = [...text.matchAll(/(\d+(?:[.,]\d+)?)/g)].map((match) => match[1].replace(',', '.'));
  if (!numbers.length) return null;
  const unit = text.includes('°C') ? '°C' : text.includes('%') ? '%' : '';
  let thresholdLabel = 'Ngưỡng';
  if (alert.type === 'TEMPERATURE_HIGH') thresholdLabel = 'Ngưỡng tối đa';
  else if (alert.type === 'SOIL_MOISTURE_LOW') thresholdLabel = 'Ngưỡng tối thiểu';
  return {
    value: `${numbers[0]}${unit ? ` ${unit}` : ''}`,
    threshold: numbers[1] ? `${thresholdLabel}: ${numbers[1]}${unit ? ` ${unit}` : ''}` : null,
  };
}

export function timeAgo(value) {
  if (!value) return '—';
  const time = new Date(value).getTime();
  if (!Number.isFinite(time)) return '—';
  const minutes = Math.floor((Date.now() - time) / 60000);
  if (minutes < 1) return 'Vừa xong';
  if (minutes < 60) return `${minutes} phút trước`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} giờ trước`;
  return `${Math.floor(hours / 24)} ngày trước`;
}

const ALERT_PAGE_SIZE = 7;

export function AlertsPage() {
  const [tab, setTab] = useState('ALL');
  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [page, setPage] = useState(0);
  const [rows, setRows] = useState([]);
  const [total, setTotal] = useState(0);
  const [reloadKey, setReloadKey] = useState(0);
  const [counts, setCounts] = useState({ open: 0, ack: 0, critical: 0, high: 0, resolved: 0, resolvedToday: 0, all: 0 });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // Debounce so typing does not fire a request per keystroke.
  useEffect(() => {
    const id = window.setTimeout(() => setDebouncedSearch(search), 300);
    return () => window.clearTimeout(id);
  }, [search]);

  useEffect(() => { setPage(0); }, [tab, debouncedSearch]);

  useEffect(() => {
    let active = true;
    async function load(showLoader) {
      if (showLoader) setLoading(true);
      try {
        const tableParams = { ...tabQuery(tab), ...(debouncedSearch ? { search: debouncedSearch } : {}) };
        const [table, open, ack, critical, high, resolved, all, recentResolved] = await Promise.all([
          apiRequest(buildAlertQuery(tableParams, page, ALERT_PAGE_SIZE)),
          fetchAlertTotal({ status: 'OPEN' }),
          fetchAlertTotal({ status: 'ACKNOWLEDGED' }),
          fetchAlertTotal({ severity: 'CRITICAL' }),
          fetchAlertTotal({ severity: 'HIGH' }),
          fetchAlertTotal({ status: 'RESOLVED' }),
          fetchAlertTotal({}),
          apiRequest(buildAlertQuery({ status: 'RESOLVED' }, 0, 200)),
        ]);
        if (!active) return;
        const content = Array.isArray(table) ? table : table.content || [];
        setRows(content);
        setTotal(Array.isArray(table) ? content.length : table.totalElements ?? 0);
        const resolvedRows = Array.isArray(recentResolved) ? recentResolved : recentResolved.content || [];
        const today = new Date().toDateString();
        setCounts({
          open,
          ack,
          critical,
          high,
          resolved,
          all,
          resolvedToday: resolvedRows.filter((alert) => {
            const stamp = alert.resolvedAt || alert.createdAt;
            return stamp && new Date(stamp).toDateString() === today;
          }).length,
        });
        setError('');
      } catch (requestError) {
        if (active) setError(requestError.message || 'Không thể tải cảnh báo.');
      } finally {
        if (active && showLoader) setLoading(false);
      }
    }
    load(true);
    const intervalId = window.setInterval(() => load(false), refreshMs);
    return () => {
      active = false;
      window.clearInterval(intervalId);
    };
  }, [tab, debouncedSearch, page, reloadKey]);

  const pageCount = Math.max(1, Math.ceil(total / ALERT_PAGE_SIZE));
  const safePage = Math.min(page, pageCount - 1);
  const from = total === 0 ? 0 : safePage * ALERT_PAGE_SIZE + 1;
  const to = Math.min((safePage + 1) * ALERT_PAGE_SIZE, total);
  const tabCount = (value) => {
    if (value === 'CRITICAL') return counts.critical;
    if (value === 'HIGH') return counts.high;
    if (value === 'RESOLVED') return counts.resolved;
    return counts.all;
  };

  return <>
    <div className="page-heading dev-heading">
      <div>
        <h1>Cảnh báo &amp; Sự cố</h1>
        <p>Theo dõi các cảnh báo và ngưỡng vượt vì khí hậu trong nhà kính.</p>
      </div>
      <div className="heading-actions dev-heading-actions">
        <button className="icon-button dev-refresh" type="button" aria-label="Làm mới cảnh báo" title="Làm mới" onClick={() => setReloadKey((key) => key + 1)} disabled={loading}><RefreshCw size={16} className={loading ? 'spin' : ''} /></button>
      </div>
    </div>
    {error && <div className="notice notice-error"><AlertTriangle size={17} /><span>{error}</span></div>}

    <section className="al-summary" aria-label="Tổng quan cảnh báo">
      <article className="al-card">
        <div className="al-card-top"><span>Cảnh báo mở</span><span className="al-pill al-pill-crit">Cần xử lý</span></div>
        <div className="al-number al-num-red">{String(counts.open).padStart(2, '0')}</div>
        <div className="al-note">{counts.open} sự cố vượt ngưỡng cần kiểm tra</div>
      </article>
      <article className="al-card">
        <div className="al-card-top"><span>Đang xử lý</span><span className="al-pill al-pill-warn">Theo dõi</span></div>
        <div className="al-number al-num-orange">{String(counts.ack).padStart(2, '0')}</div>
        <div className="al-note">{counts.ack} kỹ thuật viên đang thao tác</div>
      </article>
      <article className="al-card">
        <div className="al-card-top"><span>Đã xử lý hôm nay</span><span className="al-pill al-pill-ok">Hoàn thành</span></div>
        <div className="al-number al-num-green">{String(counts.resolvedToday).padStart(2, '0')}</div>
        <div className="al-note">Các ngưỡng đã trở lại an toàn</div>
      </article>
    </section>

    <section className="al-panel">
      <div className="al-toolbar">
        <label className="al-search"><Search size={14} /><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Tìm theo tên cảnh báo hoặc thiết bị..." aria-label="Tìm kiếm cảnh báo" /></label>
        <div className="al-tabs" role="group" aria-label="Lọc cảnh báo">
          {alertTabs.map((option) => (
            <button key={option.value} type="button" className={tab === option.value ? 'al-tab-active' : ''} aria-pressed={tab === option.value} onClick={() => setTab(option.value)}>
              {option.label} ({tabCount(option.value)})
            </button>
          ))}
        </div>
      </div>
      <div className="al-list-heading"><div><strong>Danh sách sự kiện</strong><span>{total} cảnh báo</span></div></div>
      {loading ? <div className="table-state"><LoaderCircle className="spin" size={21} /> Đang tải cảnh báo...</div> : !rows.length ? <div className="table-state"><Bell size={22} /><span>Không có cảnh báo phù hợp.</span></div> : (
        <div className="table-scroll al-table-scroll">
          <table className="al-table">
            <thead><tr><th>MỨC ĐỘ</th><th>CẢNH BÁO &amp; VỊ TRÍ</th><th>GIÁ TRỊ VI PHẠM / NGƯỠNG</th><th>TRẠNG THÁI</th><th>THỜI GIAN</th></tr></thead>
            <tbody>
              {rows.map((alert) => {
                const sev = severityMeta[alert.severity] || { label: alert.severity || 'Thông tin', className: 'al-pill-info' };
                const st = alertStatusMeta[alert.status] || { label: alert.status, className: 'al-pill-info' };
                const reading = parseAlertReading(alert);
                const valueTone = alert.status === 'RESOLVED' ? 'al-val-ok' : alert.severity === 'CRITICAL' ? 'al-val-bad' : 'al-val-warn';
                return (
                  <tr key={alert.id}>
                    <td><span className={`al-pill ${sev.className}`}>{sev.label}</span></td>
                    <td>
                      <div className="al-message">{alert.message}</div>
                      <div className="al-place">{[alert.zone || alert.deviceName, alert.deviceId ? `(${alert.deviceId})` : ''].filter(Boolean).join(' ')}</div>
                    </td>
                    <td>
                      {reading
                        ? <>
                          <div className={`al-value ${valueTone}`}>{reading.value}</div>
                          {reading.threshold && <div className="al-threshold">{reading.threshold}</div>}
                        </>
                        : <div className="al-value al-val-muted">{alert.message}</div>}
                    </td>
                    <td><span className={`al-pill ${st.className}`}>{st.label}</span></td>
                    <td><span className="al-time">{timeAgo(alert.createdAt)}</span></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      <div className="al-footer">
        <span className="al-live"><i />Đang cập nhật tự động</span>
        <span className="al-range">Hiển thị {from}–{to} trong {total} cảnh báo</span>
        <div className="al-pager">
          <button type="button" aria-label="Trang trước" disabled={safePage <= 0} onClick={() => setPage((current) => current - 1)}>‹</button>
          <span className="al-page-current">{safePage + 1}</span>
          <button type="button" aria-label="Trang sau" disabled={safePage >= pageCount - 1} onClick={() => setPage((current) => current + 1)}>›</button>
        </div>
      </div>
    </section>
  </>;
}

export function CommandsPage() {
  return <Navigate to="/devices" replace />;
}

const deviceStatusMeta = {
  ACTIVE: { label: 'Đang hoạt động', tone: 'status-online' },
  OFFLINE: { label: 'Mất kết nối', tone: 'status-danger' },
  MAINTENANCE: { label: 'Đang bảo trì', tone: 'status-warning' },
  REGISTERED: { label: 'Mới đăng ký', tone: 'status-warning' },
  PROVISIONED: { label: 'Chờ kích hoạt', tone: 'status-warning' },
  LOCKED: { label: 'Đã khóa', tone: 'status-danger' },
  DECOMMISSIONED: { label: 'Đã ngừng', tone: 'status-neutral' },
};

const hardwareStages = [
  { key: 'init', label: 'Khởi tạo', doneBadge: 'Đã xong', runBadge: 'Đang thực hiện' },
  { key: 'verify', label: 'Kiểm định', doneBadge: 'Đạt', runBadge: 'Đang kiểm' },
  { key: 'operate', label: 'Vận hành', doneBadge: 'Hoàn thành', runBadge: 'Đang chạy' },
  { key: 'retire', label: 'Thu hồi', doneBadge: 'Đã thu hồi', runBadge: 'Đang thu hồi' },
];

// Backend statuses mapped onto the 4 hardware stages of the mockup.
function hardwareStageIndex(status) {
  switch (status) {
    case 'REGISTERED': return 0;
    case 'PROVISIONED': return 1;
    case 'DECOMMISSIONED': return 3;
    default: return 2;
  }
}

const STALE_AFTER_MS = 5 * 60 * 1000;

export function DeviceLifecyclePage() {
  const { data, loading, error, refresh } = useApiCollection('/api/devices');
  const [selectedId, setSelectedId] = useState('');
  const [telemetry, setTelemetry] = useState([]);
  const [firmwareLatest, setFirmwareLatest] = useState(null);
  const [busy, setBusy] = useState('');

  useEffect(() => {
    if (!data.length) {
      setSelectedId('');
      return;
    }
    if (!selectedId || !data.some((device) => device.deviceId === selectedId)) {
      setSelectedId(data[0].deviceId);
    }
  }, [data, selectedId]);

  const selected = data.find((device) => device.deviceId === selectedId) || null;

  useEffect(() => {
    if (!selectedId) {
      setTelemetry([]);
      return undefined;
    }
    let active = true;
    const load = async () => {
      try {
        const records = await apiRequest(`/api/telemetry?deviceId=${encodeURIComponent(selectedId)}`);
        if (active) setTelemetry(records);
      } catch {
        if (active) setTelemetry([]);
      }
    };
    load();
    const intervalId = window.setInterval(() => load(), refreshMs);
    return () => {
      active = false;
      window.clearInterval(intervalId);
    };
  }, [selectedId]);

  useEffect(() => {
    apiRequest('/api/firmware-versions?size=1')
      .then((page) => setFirmwareLatest(page?.content?.[0]?.version || null))
      .catch(() => setFirmwareLatest(null));
  }, []);

  async function changeStatus(next, confirmText) {
    if (!selected) return;
    if (confirmText && !window.confirm(confirmText)) return;
    setBusy(next);
    try {
      await apiRequest(`/api/devices/${encodeURIComponent(selected.deviceId)}/status`, { method: 'PATCH', body: JSON.stringify({ status: next }) });
      refresh();
    } catch (requestError) {
      window.alert(requestError.message);
    } finally {
      setBusy('');
    }
  }

  const status = selected?.status || 'UNKNOWN';
  const statusMeta = deviceStatusMeta[status] || { label: status, tone: 'status-neutral' };
  const stageIndex = selected ? hardwareStageIndex(status) : 0;
  const latest = telemetry[0] || null;
  const latestTime = latest?.timestamp ? new Date(latest.timestamp).getTime() : NaN;
  const linkFresh = Number.isFinite(latestTime) && Date.now() - latestTime <= STALE_AFTER_MS;
  const retired = status === 'DECOMMISSIONED';
  const inMaintenance = status === 'MAINTENANCE';

  const uptimeDays = (() => {
    if (!selected?.createdAt) return null;
    const created = new Date(selected.createdAt).getTime();
    if (!Number.isFinite(created)) return null;
    return Math.max(0, Math.floor((Date.now() - created) / (24 * 60 * 60 * 1000)));
  })();

  const firmwareCurrent = selected?.firmwareVersion || null;
  const firmwareIsLatest = !!(firmwareLatest && firmwareCurrent && firmwareCurrent === firmwareLatest);
  const firmwareHasUpdate = !!(firmwareLatest && firmwareCurrent && firmwareCurrent !== firmwareLatest);

  const operateBadge = status === 'ACTIVE' ? 'Đang chạy' : status === 'MAINTENANCE' ? 'Bảo trì' : status === 'OFFLINE' ? 'Gián đoạn' : status === 'LOCKED' ? 'Bị khóa' : 'Đang chạy';
  const stageSummary = stageIndex === 0 ? 'Đang khởi tạo' : stageIndex === 1 ? 'Đang kiểm định' : stageIndex === 2 ? (status === 'ACTIVE' ? 'Đang hoạt động ổn định' : statusMeta.label) : 'Đã thu hồi';

  if (loading) {
    return <>
      <ModuleHeading eyebrow="VẬN HÀNH / VÒNG ĐỜI" title="Quản lý Vòng đời Thiết bị" description="Theo dõi trạng thái vòng đời và vận hành của bộ điều khiển ESP32." />
      <LoadingState label="Đang tải thiết bị..." />
    </>;
  }

  return <>
    <ModuleHeading
      eyebrow="VẬN HÀNH / VÒNG ĐỜI"
      title="Quản lý Vòng đời Thiết bị"
      description="Theo dõi trạng thái vòng đời và vận hành của bộ điều khiển ESP32."
      actions={<>
        {data.length > 1 && <label className="filter-select"><select value={selectedId} onChange={(event) => setSelectedId(event.target.value)} aria-label="Chọn thiết bị">{data.map((device) => <option value={device.deviceId} key={device.deviceId}>{device.name} · {device.deviceId}</option>)}</select></label>}
        <button className="button button-secondary" onClick={refresh}><RefreshCw size={15} /> Làm mới</button>
      </>}
    />
    <ErrorNotice>{error}</ErrorNotice>
    {!selected ? <EmptyState label="Chưa có thiết bị nào. Hãy đăng ký node ở trang Thiết bị." /> : <>
      <section className="lc-hero">
        <span className="lc-hero-icon"><Cpu size={20} /></span>
        <div className="lc-hero-copy">
          <div className="lc-hero-title">
            <strong>{selected.name || 'Chưa đặt tên'}</strong>
            <code className="lc-chip">{selected.deviceId}</code>
            <span className={`status-pill ${statusMeta.tone}`}><span />{statusMeta.label}</span>
          </div>
          <div className="lc-hero-meta">
            <span><MapPin size={13} /> Vị trí: {selected.zone || '—'}</span>
            <span><Clock3 size={13} /> Uptime: {uptimeDays == null ? '—' : `${uptimeDays} ngày`}</span>
          </div>
        </div>
      </section>

      <section className="lc-progress">
        <div className="lc-progress-head">
          <span className="lc-progress-title"><SlidersHorizontal size={13} /> TIẾN TRÌNH VÒNG ĐỜI PHẦN CỨNG</span>
          <span className="lc-stage-pill">Giai đoạn {stageIndex + 1} / 4 • {stageSummary}</span>
        </div>
        <div className="lc-stages">
          {hardwareStages.map((step, index) => {
            const done = index < stageIndex || (index === 3 && retired);
            const current = index === stageIndex && !retired;
            const badge = done ? step.doneBadge : current ? (index === 2 ? operateBadge : step.runBadge) : 'Dự kiến';
            return (
              <div key={step.key} className={`lc-stage ${current ? 'lc-stage-current' : ''}`}>
                <span className={`lc-stage-icon ${done ? 'lc-stage-done' : current ? 'lc-stage-run' : 'lc-stage-todo'}`}>
                  {done ? <Check size={15} /> : current ? <Play size={14} /> : <span>{index + 1}</span>}
                </span>
                <span className="lc-stage-name">{index + 1}. {step.label}</span>
                <span className={`lc-badge ${done ? 'lc-badge-done' : current ? 'lc-badge-run' : 'lc-badge-todo'}`}>{badge}</span>
              </div>
            );
          })}
        </div>
      </section>

      <section className="lc-grid">
        <article className="lc-card">
          <div className="lc-card-kicker"><span>PHIÊN BẢN FIRMWARE</span><Cpu size={15} /></div>
          <div className="lc-fw-row"><strong>{firmwareCurrent || '—'}</strong>{firmwareIsLatest && <span className="lc-badge lc-badge-done">MỚI NHẤT</span>}{firmwareHasUpdate && <span className="lc-badge lc-badge-warn">CÓ BẢN MỚI {firmwareLatest}</span>}</div>
          <button className="lc-btn-primary" type="button" disabled title="Backend chưa hỗ trợ cập nhật OTA"><RefreshCw size={14} /> Cập nhật</button>
        </article>

        <article className="lc-card">
          <div className="lc-card-kicker"><span>KẾT NỐI &amp; TÍN HIỆU</span><Wifi size={15} /></div>
          <div className="lc-conn"><i className={linkFresh ? 'lc-dot-online' : 'lc-dot-offline'} /><strong>{linkFresh ? 'Trực tuyến • Ổn định' : 'Ngoại tuyến'}</strong></div>
          <div className="lc-conn-note">{latest?.timestamp ? `Bản tin mới nhất: ${formatDate(latest.timestamp)}` : selected.lastSeen ? `Hoạt động cuối: ${formatDate(selected.lastSeen)}` : 'Chưa nhận telemetry'}</div>
          <button
            className="lc-btn-ghost"
            type="button"
            disabled={busy === 'MAINTENANCE' || busy === 'ACTIVE' || retired}
            title={retired ? 'Thiết bị đã kết thúc vòng đời' : inMaintenance ? 'Đưa thiết bị trở lại hoạt động' : 'Chuyển thiết bị sang chế độ bảo trì'}
            onClick={() => changeStatus(inMaintenance ? 'ACTIVE' : 'MAINTENANCE')}
          >
            <Wrench size={14} /> {busy === 'MAINTENANCE' || busy === 'ACTIVE' ? 'Đang gửi...' : inMaintenance ? 'Kết thúc bảo trì' : 'Chế độ bảo trì'}
          </button>
        </article>

        <article className="lc-card">
          <div className="lc-card-kicker"><span>THAO TÁC</span><Settings2 size={15} /></div>
          <div className="lc-actions-label">Điều khiển phần cứng</div>
          <div className="lc-actions-row">
            <button className="lc-btn-ghost" type="button" disabled title="Cần giao thức lệnh MQTT, backend chưa khả dụng"><RotateCcw size={14} /> Khởi động lại (Restart)</button>
            <button
              className="lc-btn-danger"
              type="button"
              disabled={busy === 'DECOMMISSIONED' || retired}
              title={retired ? 'Thiết bị đã kết thúc vòng đời' : 'Ngừng sử dụng thiết bị này'}
              onClick={() => changeStatus('DECOMMISSIONED', `Ngừng sử dụng "${selected.name || selected.deviceId}"?`)}
            >
              <CircleX size={14} /> {busy === 'DECOMMISSIONED' ? 'Đang gửi...' : 'Ngừng sử dụng'}
            </button>
          </div>
          {retired && <div className="lc-conn-note">Thiết bị đã kết thúc vòng đời.</div>}
        </article>
      </section>
    </>}
  </>;
}

const userStatusMeta = {
  ACTIVE: { label: 'Đang hoạt động', tone: 'status-online' },
  INACTIVE: { label: 'Ngừng hoạt động', tone: 'status-neutral' },
  LOCKED: { label: 'Đã khóa', tone: 'status-danger' },
};

// Only these roles exist in practice; ADMIN accounts are locked for editing.
const editableRoles = ['OPERATOR', 'VIEWER'];

// Presence comes from live login sessions (backend `online` flag). Older backends
// without the flag fall back to the raw account status.
function userPresenceMeta(user) {
  if (user.status === 'LOCKED') return { label: 'Đã khóa', tone: 'status-danger' };
  if (user.status === 'INACTIVE') return { label: 'Ngừng hoạt động', tone: 'status-neutral' };
  if (user.online === undefined) return userStatusMeta[user.status] || { label: user.status || '—', tone: 'status-neutral' };
  return user.online
    ? { label: 'Đang trực tuyến', tone: 'status-online' }
    : { label: 'Ngoại tuyến', tone: 'status-neutral' };
}

export function UsersPage() {
  const [query, setQuery] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const [status, setStatus] = useState('ALL');
  const [page, setPage] = useState(0);
  const [busyId, setBusyId] = useState(null);
  const [toasts, setToasts] = useState([]);
  const sessionUser = useMemo(getStoredSession, []);
  const myAvatar = useAvatar(sessionUser);

  useEffect(() => {
    const id = window.setTimeout(() => setDebouncedQuery(query), 300);
    return () => window.clearTimeout(id);
  }, [query]);

  useEffect(() => { setPage(0); }, [debouncedQuery, status]);

  const { data, loading, error, meta, refresh } = useApiCollection('/api/users', {
    status, search: debouncedQuery, page, size: 20,
  });

  async function changeRole(user, role) {
    if (!role || user.roles?.[0] === role) return;
    const toastId = Date.now() + Math.random();
    setToasts((list) => [...list, {
      id: toastId,
      kind: 'confirm',
      text: `Đổi vai trò của @${user.username} sang ${role}?`,
      actions: [
        {
          label: 'Xác nhận',
          primary: true,
          onClick: () => {
            dismissToast(toastId);
            doRoleChange(user, role);
          },
        },
        { label: 'Hủy', onClick: () => dismissToast(toastId) },
      ],
    }]);
  }

  function dismissToast(id) {
    setToasts((list) => list.filter((toast) => toast.id !== id));
  }

  function pushToast(kind, text) {
    const id = Date.now() + Math.random();
    setToasts((list) => [...list, { id, kind, text }]);
    window.setTimeout(() => dismissToast(id), 4500);
  }

  async function doRoleChange(user, role) {
    setBusyId(user.id);
    try {
      const updated = await apiRequest(`/api/users/${user.id}/role`, { method: 'PATCH', body: JSON.stringify({ role }) });
      pushToast('success', `Đã đổi vai trò của @${user.username} sang ${updated.roles?.[0] || role}.`);
      refresh();
    } catch (requestError) {
      const raw = requestError.message || 'Không thể đổi vai trò.';
      pushToast('error', raw.includes('(404)') ? `${raw} — hãy restart backend để nhận API đổi vai trò mới.` : raw);
    } finally {
      setBusyId(null);
    }
  }

  return <>
    <ModuleHeading eyebrow="QUẢN TRỊ / TRUY CẬP" title="Người dùng" description="Tra cứu tài khoản, vai trò và trạng thái trong cơ sở dữ liệu." actions={<><Link className="button button-secondary" to="/invite-user"><MailPlus size={15} /> Tạo tài khoản</Link><button className="button button-secondary" onClick={refresh}><RefreshCw size={15} /></button></>} />
    <ErrorNotice>{error}</ErrorNotice>
    <section className="table-panel"><div className="table-toolbar filter-toolbar"><SearchBox value={query} onChange={setQuery} placeholder="Tìm theo tên, email hoặc vai trò" /><label className="filter-select"><select value={status} onChange={(event) => setStatus(event.target.value)}><option value="ALL">Mọi trạng thái</option><option value="ACTIVE">ACTIVE</option><option value="INACTIVE">INACTIVE</option><option value="LOCKED">LOCKED</option></select></label><span className="toolbar-count">{meta.totalElements} tài khoản</span></div>
      <TableFrame loading={loading} empty={data.length ? null : 'Chưa có tài khoản phù hợp.'}><thead><tr><th>TÀI KHOẢN</th><th>EMAIL</th><th>VAI TRÒ</th><th>TRẠNG THÁI</th><th>NGÀY TẠO</th></tr></thead><tbody>{data.map((user) => {
        const isAdmin = user.roles?.some((role) => String(role).replace(/^ROLE_/i, '').toUpperCase() === 'ADMIN');
        const primaryRole = user.roles?.[0];
        const presence = userPresenceMeta(user);
        const isMe = (sessionUser?.userId != null && user.id === sessionUser.userId)
          || (sessionUser?.username && user.username && String(user.username).toLowerCase() === String(sessionUser.username).toLowerCase());
        return <tr key={user.id}>
          <td><div className="user-cell"><span className="user-initial">{isMe && myAvatar ? <img src={myAvatar} alt="" className="avatar-img" /> : (user.fullName || user.username || '?').slice(0, 1).toUpperCase()}</span><span><strong>{user.fullName || user.username}</strong><small>@{user.username}</small></span></div></td>
          <td>{user.email}</td>
          <td>{isAdmin
            ? <div className="tag-list">{user.roles.length ? user.roles.map((role) => <span className="role-tag" key={role}>{role}</span>) : <span className="cell-secondary">Chưa gán vai trò</span>}</div>
            : <select className="inline-select" aria-label={`Đổi vai trò của ${user.username}`} title={isMe ? 'Đang chỉnh vai trò của chính bạn' : `Đổi vai trò của ${user.username}`} disabled={busyId === user.id} value={editableRoles.includes(primaryRole) ? primaryRole : ''} onChange={(event) => changeRole(user, event.target.value)}>
              <option value="" disabled>{primaryRole || 'Chọn vai trò'}</option>
              {editableRoles.map((role) => <option value={role} key={role}>{role}</option>)}
            </select>}</td>
          <td><span className={`status-pill ${presence.tone}`} title={`Tài khoản: ${user.status || '—'}${user.online === undefined ? '' : user.online ? ' · Có phiên đăng nhập' : ' · Không có phiên đăng nhập'}`}><span />{presence.label}</span></td>
          <td>{formatDate(user.createdAt)}</td>
        </tr>;
      })}</tbody></TableFrame>
      <Pager meta={meta} onPage={setPage} />
    </section>
    {toasts.length > 0 && (
      <div className="toast-stack" role="status" aria-live="polite">
        {toasts.map((toast) => (
          <div key={toast.id} className={`toast toast-${toast.kind}`}>
            <span className="toast-text">{toast.text}</span>
            {toast.actions ? (
              <span className="toast-actions">
                {toast.actions.map((action) => (
                  <button
                    key={action.label}
                    type="button"
                    className={action.primary ? 'button button-small button-primary' : 'button button-small button-secondary'}
                    onClick={action.onClick}
                  >
                    {action.label}
                  </button>
                ))}
              </span>
            ) : (
              <button type="button" className="icon-button toast-close" aria-label="Đóng thông báo" onClick={() => dismissToast(toast.id)}><X size={14} /></button>
            )}
          </div>
        ))}
      </div>
    )}
  </>;
}

const auditActionLabels = {
  LOGIN: 'Đăng nhập hệ thống',
  LOGIN_FAILED: 'Đăng nhập thất bại',
  LOGOUT: 'Đăng xuất hệ thống',
  USER_CREATE: 'Tạo tài khoản người dùng',
  DEVICE_REGISTER: 'Đăng ký thiết bị mới',
  DEVICE_STATUS_CHANGE: 'Đổi trạng thái thiết bị',
  DEVICE_CONFIG_UPDATE: 'Cập nhật cấu hình thiết bị',
  ALERT_ACKNOWLEDGE: 'Xác nhận cảnh báo',
  ALERT_RESOLVE: 'Xử lý cảnh báo',
};

// Backend stores no category column; it is derived deterministically from the action code.
function auditCategory(action) {
  const code = String(action || '').toUpperCase();
  if (code === 'DEVICE_CONFIG_UPDATE') return 'CẤU HÌNH';
  if (code.includes('DEVICE') || code.includes('COMMAND') || code.includes('FIRMWARE') || code.includes('TELEMETRY')) return 'THIẾT BỊ';
  if (code.includes('LOGIN') || code.includes('LOGOUT') || code.includes('USER') || code.includes('ROLE') || code.includes('AUTH') || code.includes('PASSWORD')) return 'BẢO MẬT';
  if (code.includes('ALERT')) return 'CẢNH BÁO';
  if (code.includes('MAINTENANCE') || code.includes('CALIBRAT')) return 'BẢO TRÌ';
  return 'HỆ THỐNG';
}

// Backend stores no result column either: only failure-type actions count as warnings.
function auditFailed(action) {
  return /FAIL|ERROR|DENIED|REJECT|LOCKED/.test(String(action || '').toUpperCase());
}

const auditCategoryOptions = ['THIẾT BỊ', 'CẤU HÌNH', 'BẢO MẬT', 'CẢNH BÁO', 'BẢO TRÌ', 'HỆ THỐNG'];

const AUDIT_FETCH_SIZE = 200;
const AUDIT_PAGE_SIZE = 10;

function auditDayLabel(value) {
  if (!value) return '';
  const time = new Date(value);
  if (Number.isNaN(time.getTime())) return '';
  const today = new Date();
  const day = (date) => `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
  if (day(time) === day(today)) return 'Hôm nay';
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  if (day(time) === day(yesterday)) return 'Hôm qua';
  return time.toLocaleDateString('vi-VN');
}

function auditClock(value) {
  if (!value) return '—';
  const time = new Date(value);
  return Number.isNaN(time.getTime()) ? '—' : time.toLocaleTimeString('vi-VN', { hour12: false });
}

export function AuditLogsPage() {
  const [rows, setRows] = useState([]);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState('ALL');
  const [result, setResult] = useState('ALL');
  const [range, setRange] = useState('ALL');
  const [page, setPage] = useState(0);
  const sessionUser = useMemo(getStoredSession, []);
  const myAvatar = useAvatar(sessionUser);

  useEffect(() => { setPage(0); }, [query, category, result, range]);

  useEffect(() => {
    let active = true;
    async function load(showLoader) {
      if (showLoader) setLoading(true);
      try {
        // Category / result / date filters don't exist server-side, so the newest
        // block is fetched once and filtered client-side (see scope note below).
        const payload = await apiRequest(`/api/audit-logs?page=0&size=${AUDIT_FETCH_SIZE}`);
        if (!active) return;
        const content = Array.isArray(payload) ? payload : payload.content || [];
        setRows(content);
        setTotalElements(Array.isArray(payload) ? content.length : payload.totalElements ?? 0);
        setError('');
      } catch (requestError) {
        if (active) setError(requestError.message || 'Không thể tải nhật ký.');
      } finally {
        if (active && showLoader) setLoading(false);
      }
    }
    load(true);
    const intervalId = window.setInterval(() => load(false), refreshMs);
    return () => {
      active = false;
      window.clearInterval(intervalId);
    };
  }, []);

  const filtered = rows.filter((row) => {
    if (category !== 'ALL' && auditCategory(row.action) !== category) return false;
    if (result !== 'ALL' && (auditFailed(row.action) ? 'WARN' : 'OK') !== result) return false;
    if (range !== 'ALL') {
      const time = new Date(row.createdAt).getTime();
      if (!Number.isFinite(time)) return false;
      const hours = (Date.now() - time) / 3600000;
      if (range === '24H' && hours > 24) return false;
      if (range === '7D' && hours > 24 * 7) return false;
      if (range === '30D' && hours > 24 * 30) return false;
    }
    if (query) {
      const haystack = [row.username, row.action, auditActionLabels[row.action], row.resourceType, row.resourceId, row.ipAddress, formatDetails(row.details)]
        .filter(Boolean).join(' ').toLowerCase();
      if (!haystack.includes(query.toLowerCase())) return false;
    }
    return true;
  });

  const hasActiveFilter = query || category !== 'ALL' || result !== 'ALL' || range !== 'ALL';

  function clearFilters() {
    setQuery('');
    setCategory('ALL');
    setResult('ALL');
    setRange('ALL');
    setPage(0);
  }

  const pageCount = Math.max(1, Math.ceil(filtered.length / AUDIT_PAGE_SIZE));
  const safePage = Math.min(page, pageCount - 1);
  const visible = filtered.slice(safePage * AUDIT_PAGE_SIZE, (safePage + 1) * AUDIT_PAGE_SIZE);
  const from = filtered.length === 0 ? 0 : safePage * AUDIT_PAGE_SIZE + 1;
  const to = Math.min((safePage + 1) * AUDIT_PAGE_SIZE, filtered.length);

  const dayAgo = Date.now() - 24 * 3600000;
  const last24h = rows.filter((row) => {
    const time = new Date(row.createdAt).getTime();
    return Number.isFinite(time) && time >= dayAgo;
  });
  const security24h = last24h.filter((row) => auditCategory(row.action) === 'BẢO MẬT');
  const anomalies24h = security24h.filter((row) => auditFailed(row.action)).length;
  const securityOkPct = security24h.length
    ? Math.round((100 * (security24h.length - anomalies24h)) / security24h.length)
    : 100;
  const manual24h = last24h.filter(
    (row) => !['LOGIN', 'LOGOUT', 'LOGIN_FAILED'].includes(String(row.action).toUpperCase()),
  ).length;

  return <>
    <ErrorNotice>{error}</ErrorNotice>
    <section className="au-stats" aria-label="Tổng quan sự kiện">
      <article className="au-stat">
        <div><div className="au-stat-label">TOTAL RECORDED EVENTS</div><div className="au-stat-main">{last24h.length} <small>Past 24h</small></div></div>
        <span className="au-stat-icon"><FileClock size={16} /></span>
      </article>
      <article className="au-stat">
        <div><div className="au-stat-label">SECURITY &amp; AUTH STATE</div><div className="au-stat-main">{securityOkPct}% <small>{anomalies24h} Anomalies</small></div></div>
        <span className="au-stat-icon au-stat-icon-green"><ShieldCheck size={16} /></span>
      </article>
      <article className="au-stat">
        <div><div className="au-stat-label">ACTUATOR OVERRIDES</div><div className="au-stat-main">{manual24h} <small>Manual Pulse</small></div></div>
        <span className="au-stat-icon"><SlidersHorizontal size={16} /></span>
      </article>
    </section>
    <section className="au-panel">
      <div className="au-toolbar">
        <label className="au-search"><Search size={14} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Tìm kiếm hành động, người thực hiện..." aria-label="Tìm kiếm nhật ký" /></label>
        <label className="filter-select"><select value={category} onChange={(event) => setCategory(event.target.value)} aria-label="Lọc danh mục"><option value="ALL">Tất cả danh mục</option>{auditCategoryOptions.map((option) => <option value={option} key={option}>{option.charAt(0) + option.slice(1).toLowerCase()}</option>)}</select></label>
        <label className="filter-select"><select value={result} onChange={(event) => setResult(event.target.value)} aria-label="Lọc trạng thái"><option value="ALL">Tất cả trạng thái</option><option value="OK">Thành công</option><option value="WARN">Cảnh báo</option></select></label>
        <label className="filter-select"><FileClock size={14} /><select value={range} onChange={(event) => setRange(event.target.value)} aria-label="Lọc thời gian"><option value="ALL">Mọi thời điểm</option><option value="24H">Hôm nay (24h)</option><option value="7D">7 ngày qua</option><option value="30D">30 ngày qua</option></select></label>
        <button type="button" className="au-clear" onClick={clearFilters} disabled={!hasActiveFilter} title="Xóa toàn bộ bộ lọc"><RotateCcw size={13} /> Xóa lọc</button>
      </div>
      {loading ? <div className="table-state"><LoaderCircle className="spin" size={21} /> Đang tải nhật ký...</div> : !visible.length ? <div className="table-state"><FileClock size={22} /><span>Không có bản ghi phù hợp với bộ lọc.</span></div> : (
        <div className="table-scroll au-table-scroll">
          <table className="au-table">
            <thead><tr><th>THỜI GIAN</th><th>NGƯỜI THỰC HIỆN</th><th>PHÂN LOẠI</th><th>HÀNH ĐỘNG THỰC HIỆN</th></tr></thead>
            <tbody>
              {visible.map((row) => {
                const actor = row.username || 'Hệ thống tự động';
                const actorInitials = actor.split(/[\s_@.]+/).filter(Boolean).map((part) => part[0]).join('').slice(0, 2).toUpperCase();
                const isMe = sessionUser?.username
                  && row.username
                  && String(row.username).toLowerCase() === String(sessionUser.username).toLowerCase();
                return (
                  <tr key={row.id}>
                    <td><div className="au-time">{auditClock(row.createdAt)}</div><div className="au-day">{auditDayLabel(row.createdAt)}</div></td>
                    <td>
                      <div className="au-actor"><span className="au-avatar">{isMe && myAvatar ? <img src={myAvatar} alt="" className="avatar-img" /> : actorInitials}</span><span><strong>{actor}</strong><small>{row.ipAddress ? `IP ${row.ipAddress}` : '—'}</small></span></div>
                    </td>
                    <td><span className="au-cat">{auditCategory(row.action)}</span></td>
                    <td>
                      <div className="au-action">{auditActionLabels[row.action] || row.action}</div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      <div className="au-footer">
        <span className="au-range">Hiển thị {from} - {to} trong {filtered.length} sự kiện{totalElements > rows.length ? ` · Lọc trong ${rows.length} sự kiện mới nhất (tổng ${totalElements})` : ''}</span>
        <div className="au-pager">
          <button type="button" disabled={safePage <= 0} onClick={() => setPage((current) => current - 1)}>‹ Trước</button>
          <span className="au-page-current">Trang {safePage + 1} / {pageCount}</span>
          <button type="button" disabled={safePage >= pageCount - 1} onClick={() => setPage((current) => current + 1)}>Sau ›</button>
        </div>
      </div>
    </section>
  </>;
}

export function SettingsPage() {
  const defaults = {
    greenhouseName: 'Smart Greenhouse Unit 01',
    timezone: 'Asia/Ho_Chi_Minh',
    refreshSeconds: '5',
    temperatureUnit: 'C',
    themeMode: 'light',
    autoReconnect: true,
    dashboardNotifications: true,
    temperatureAlerts: true,
    nodeAlerts: true,
  };
  const [settings, setSettings] = useState(() => {
    try {
      return { ...defaults, ...JSON.parse(localStorage.getItem('flora_settings') || '{}') };
    } catch {
      return defaults;
    }
  });
  const [systemStatus, setSystemStatus] = useState(null);
  const [statusError, setStatusError] = useState(false);

  useEffect(() => {
    try { localStorage.setItem('flora_settings', JSON.stringify(settings)); } catch {}
  }, [settings]);

  useEffect(() => {
    apiRequest('/api/system/status')
      .then(setSystemStatus)
      .catch(() => setStatusError(true));
  }, []);

  function updateSetting(name, value) {
    setSettings((current) => ({ ...current, [name]: value }));
    if (name === 'themeMode') document.documentElement.dataset.theme = value;
  }

  function ToggleRow({ name, title, description }) {
    return <label className="preference-row">
      <span className="preference-copy"><strong>{title}</strong><small>{description}</small></span>
      <input className="switch-input" type="checkbox" checked={settings[name]} onChange={(event) => updateSetting(name, event.target.checked)} />
    </label>;
  }

  return <>
    <ModuleHeading eyebrow="QUẢN TRỊ / HỆ THỐNG" title="Cài đặt" description="Thông tin nhà kính và tùy chọn vận hành." />
    <div className="preferences-page">
      <section className="preference-section" aria-labelledby="general-settings-title">
        <div className="preference-heading"><span className="preference-icon"><Settings2 size={18} /></span><div><h2 id="general-settings-title">Cài đặt chung</h2><p>Thông tin nhận diện và đơn vị hiển thị</p></div></div>
        <div className="preference-body">
          <label className="preference-field preference-field-full"><span>Tên nhà kính</span><input className="preference-control" value={settings.greenhouseName} onChange={(event) => updateSetting('greenhouseName', event.target.value)} /></label>
          <div className="preference-field-grid">
            <label className="preference-field"><span>Múi giờ vận hành</span><select className="preference-control" value={settings.timezone} onChange={(event) => updateSetting('timezone', event.target.value)}><option value="Asia/Ho_Chi_Minh">UTC+07:00 (Hà Nội, TP.HCM)</option><option value="Asia/Bangkok">UTC+07:00 (Bangkok)</option><option value="Asia/Tokyo">UTC+09:00 (Tokyo)</option><option value="UTC">UTC+00:00 (UTC)</option></select></label>
            <label className="preference-field"><span>Tần suất cập nhật dữ liệu</span><span className="preference-select-wrap"><select className="preference-control" value={settings.refreshSeconds} onChange={(event) => updateSetting('refreshSeconds', event.target.value)}><option value="5">5 giây (Thời gian thực)</option><option value="10">10 giây</option><option value="30">30 giây</option><option value="60">1 phút</option></select><Timer size={15} /></span></label>
          </div>
          <div className="theme-setting"><span className="unit-copy"><strong>Chế độ giao diện</strong><small>Chọn giao diện sáng hoặc tối cho toàn ứng dụng</small></span><div className="theme-options" role="group" aria-label="Chế độ giao diện"><button type="button" className={settings.themeMode === 'light' ? 'theme-option-active' : ''} aria-pressed={settings.themeMode === 'light'} onClick={() => updateSetting('themeMode', 'light')}><Sun size={14} /> Sáng</button><button type="button" className={settings.themeMode === 'dark' ? 'theme-option-active' : ''} aria-pressed={settings.themeMode === 'dark'} onClick={() => updateSetting('themeMode', 'dark')}><Moon size={14} /> Tối</button></div></div>
          <div className="unit-setting"><span className="unit-copy"><strong>Đơn vị nhiệt độ</strong><small>Áp dụng hiển thị trên toàn bộ biểu đồ và chỉ số sensor</small></span><div className="unit-switch" role="group" aria-label="Đơn vị nhiệt độ"><button type="button" className={settings.temperatureUnit === 'C' ? 'unit-active' : ''} aria-pressed={settings.temperatureUnit === 'C'} onClick={() => updateSetting('temperatureUnit', 'C')}>°C</button><button type="button" className={settings.temperatureUnit === 'F' ? 'unit-active' : ''} aria-pressed={settings.temperatureUnit === 'F'} onClick={() => updateSetting('temperatureUnit', 'F')}>°F</button></div></div>
        </div>
      </section>

      <section className="preference-section" aria-labelledby="connection-settings-title">
        <div className="preference-heading"><span className="preference-icon"><Wifi size={18} /></span><div><h2 id="connection-settings-title">Kết nối &amp; MQTT</h2><p>Thông số truyền thông dữ liệu đám mây</p></div></div>
        <div className="preference-body">
          <div className="connection-status"><span className={`connection-indicator ${systemStatus?.mqttEnabled ? 'is-enabled' : ''}`} /><div className="connection-copy"><strong>Trạng thái MQTT</strong><small>{statusError ? 'Không thể tải trạng thái từ máy chủ' : systemStatus?.mqttEnabled ? 'Dịch vụ MQTT đã được bật trên máy chủ' : systemStatus ? 'Dịch vụ MQTT chưa được bật' : 'Đang kiểm tra trạng thái...'}</small></div><span className={`connection-badge ${systemStatus?.mqttEnabled ? 'badge-enabled' : ''}`}>{statusError ? 'KHÔNG RÕ' : systemStatus?.mqttEnabled ? 'ĐÃ BẬT' : systemStatus ? 'ĐÃ TẮT' : 'ĐANG TẢI'}</span></div>
          <div className="broker-field"><label htmlFor="mqtt-broker">Máy chủ MQTT (Broker Host)</label><div className="broker-value"><span id="mqtt-broker">Được quản lý bởi máy chủ</span><LockKeyhole size={15} aria-label="Chỉ đọc" /></div></div>
          <ToggleRow name="autoReconnect" title="Tự động kết nối lại khi mất mạng" description="Tự khởi tạo phiên kết nối socket nếu bị ngắt quãng" />
        </div>
      </section>

      <section className="preference-section" aria-labelledby="alert-settings-title">
        <div className="preference-heading"><span className="preference-icon"><BellRing size={18} /></span><div><h2 id="alert-settings-title">Thông báo &amp; Cảnh báo</h2><p>Thiết lập bộ lọc và kích hoạt cảnh báo thông minh</p></div></div>
        <div className="preference-body preference-body-compact">
          <ToggleRow name="dashboardNotifications" title="Nhận cảnh báo trực tiếp trên Web Dashboard" description="Hiển thị thông báo tức thời trên thanh công cụ" />
          <ToggleRow name="temperatureAlerts" title="Cảnh báo nhiệt độ & độ ẩm vượt ngưỡng" description="Báo động ngay khi nhiệt độ > 34°C hoặc ẩm rễ < 45%" />
          <ToggleRow name="nodeAlerts" title="Cảnh báo mất kết nối Node cảm biến" description="Phát hiện và báo động khi node không phản hồi quá 30 giây" />
        </div>
      </section>
    </div>
  </>;
}

export function ProfilePage() {
  const [profile, setProfile] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [phone, setPhoneState] = useState('');
  const [passwords, setPasswords] = useState({ current: '', next: '', confirm: '' });
  const [showPw, setShowPw] = useState({ current: false, next: false, confirm: false });
  const [uploading, setUploading] = useState(false);
  const fileRef = useRef(null);
  const avatar = useAvatar(profile);

  useEffect(() => {
    apiRequest('/api/auth/me')
      .then((data) => {
        setProfile(data);
        setPhoneState(getPhone(data));
      })
      .catch((requestError) => setError(requestError.message || 'Không thể tải hồ sơ.'))
      .finally(() => setLoading(false));
  }, []);

  async function handleAvatarFile(event) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      window.alert('Vui lòng chọn file ảnh.');
      return;
    }
    if (file.size > 5 * 1024 * 1024) {
      window.alert('Ảnh vượt quá 5MB.');
      return;
    }
    setUploading(true);
    try {
      setAvatar(profile, await fileToAvatarDataUrl(file));
    } catch {
      window.alert('Không đọc được file ảnh.');
    } finally {
      setUploading(false);
    }
  }

  function handlePhoneChange(value) {
    setPhoneState(value);
    setPhone(profile, value);
  }

  if (loading) {
    return <>
      <ErrorNotice>{error}</ErrorNotice>
      <LoadingState label="Đang tải hồ sơ..." />
    </>;
  }
  if (!profile) {
    return <>
      <ErrorNotice>{error}</ErrorNotice>
      <EmptyState label="Không tải được hồ sơ." />
    </>;
  }

  const displayName = profile.fullName || profile.username || '—';
  const initials = displayName.split(' ').map((part) => part[0]).join('').slice(0, 2).toUpperCase();
  const primaryRole = profile.roles?.[0];
  const userCode = `USR-${String(profile.userId ?? profile.id ?? 0).padStart(4, '0')}`;

  const passwordFields = [
    { key: 'current', label: 'Mật khẩu hiện tại', placeholder: '••••••••••••' },
    { key: 'next', label: 'Mật khẩu mới', placeholder: 'Tối thiểu 8 ký tự' },
    { key: 'confirm', label: 'Xác nhận mật khẩu mới', placeholder: 'Nhập lại mật khẩu mới' },
  ];

  return <>
    <ErrorNotice>{error}</ErrorNotice>
    <section className="pf-hero">
      <span className="pf-avatar">
        {avatar ? <img src={avatar} alt={displayName} /> : <span className="pf-avatar-fallback">{initials}</span>}
        <i className="pf-dot" title="Đang hoạt động" />
      </span>
      <div className="pf-hero-copy">
        <div className="pf-name-row">
          <strong>{displayName}</strong>
          <span className="lc-badge lc-badge-done pf-role-pill"><ShieldCheck size={11} /> {roleLabel(primaryRole)}</span>
        </div>
        <div className="pf-id-row">
          <span><UserRound size={13} /> ID: {userCode}</span>
          <span><Mail size={13} /> {profile.email || '—'}</span>
        </div>
      </div>
      <div className="pf-hero-actions">
        <input ref={fileRef} type="file" accept="image/*" hidden aria-label="Chọn ảnh đại diện" onChange={handleAvatarFile} />
        <button className="pf-upload" type="button" disabled={uploading} onClick={() => fileRef.current?.click()}>
          <Pencil size={14} /> {uploading ? 'Đang xử lý...' : 'Đổi ảnh đại diện'}
        </button>
      </div>
    </section>

    <div className="pf-grid">
      <section className="pf-card">
        <div className="pf-card-head">
          <span className="pf-head-icon"><UserRound size={17} /></span>
          <div><h2>Thông tin cá nhân</h2><p>Cập nhật thông tin định danh và liên hệ cơ bản</p></div>
        </div>
        <label className="pf-field">
          <span>Họ và tên</span>
          <span className="pf-input"><input value={profile.fullName || ''} disabled title="Do hệ thống quản lý" /><Pencil size={14} /></span>
        </label>
        <label className="pf-field">
          <span>Địa chỉ Email</span>
          <span className="pf-input"><input value={profile.email || ''} disabled title="Do hệ thống quản lý" /><Mail size={14} /></span>
        </label>
        <label className="pf-field">
          <span>Số điện thoại liên hệ</span>
          <span className="pf-input"><input value={phone} onChange={(event) => handlePhoneChange(event.target.value)} placeholder="Chưa cập nhật" maxLength={20} /><Phone size={14} /></span>
        </label>
        <label className="pf-field">
          <span>Vai trò / Chức vụ</span>
          <span className="pf-input"><input value={roleLabel(primaryRole)} disabled title="Do quản trị viên phân quyền" /><ShieldCheck size={14} /></span>
        </label>
      </section>

      <section className="pf-card">
        <div className="pf-card-head">
          <span className="pf-head-icon"><ShieldCheck size={17} /></span>
          <div><h2>Bảo mật &amp; Đổi mật khẩu</h2><p>Cập nhật mật khẩu định kỳ để nâng cao tính an toàn</p></div>
        </div>
        {passwordFields.map(({ key, label, placeholder }) => (
          <label className="pf-field" key={key}>
            <span>{label}</span>
            <span className="pf-input">
              <input
                type={showPw[key] ? 'text' : 'password'}
                value={passwords[key]}
                onChange={(event) => setPasswords((current) => ({ ...current, [key]: event.target.value }))}
                placeholder={placeholder}
                autoComplete="new-password"
              />
              <button type="button" aria-label={showPw[key] ? `Ẩn ${label}` : `Hiện ${label}`} onClick={() => setShowPw((current) => ({ ...current, [key]: !current[key] }))}>
                {showPw[key] ? <EyeOff size={15} /> : <Eye size={15} />}
              </button>
            </span>
          </label>
        ))}
        <button className="pf-submit" type="button" disabled title="Backend chưa có API đổi mật khẩu">
          <LockKeyhole size={14} /> Cập nhật mật khẩu
        </button>
      </section>
    </div>
  </>;
}

export function InviteUserPage() {
  const [form, setForm] = useState({ username: '', email: '', fullName: '', password: '', role: 'OPERATOR' });
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
      setForm({ username: '', email: '', fullName: '', password: '', role: 'OPERATOR' });
    } catch (requestError) {
      setError(requestError.message || 'Không thể tạo tài khoản.');
    } finally {
      setSaving(false);
    }
  }

  return <>
    <ModuleHeading eyebrow="QUẢN TRỊ / TRUY CẬP" title="Tạo tài khoản" description="Tạo thông tin đăng nhập mới và chọn vai trò phù hợp." actions={<Link className="button button-secondary" to="/users"><UsersRound size={15} /> Danh sách người dùng</Link>} />
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
        <fieldset className="role-picker">
          <legend>Chọn vai trò &amp; phân quyền</legend>
          <div className="role-option-list">
            {accountRoles.map((role) => <label className={`role-option ${form.role === role.value ? 'role-option-selected' : ''}`} key={role.value}>
              <input type="radio" name="account-role" value={role.value} checked={form.role === role.value} onChange={() => setForm({ ...form, role: role.value })} />
              <span className="role-option-content"><span className="role-option-heading"><strong>{role.title}</strong><span className={`role-option-badge role-badge-${role.value.toLowerCase()}`}>{role.badge}</span></span><small>{role.description}</small></span>
            </label>)}
          </div>
        </fieldset>
        <div className="form-footer form-footer-end"><button className="button button-primary" disabled={saving}>{saving ? <LoaderCircle className="spin" size={15} /> : <Check size={15} />}{saving ? 'Đang tạo...' : 'Tạo tài khoản'}</button></div>
      </form>
      {created && <div className="inline-success"><Check size={15} /> Đã tạo tài khoản @{created.username} với vai trò {accountRoles.find((role) => role.value === created.roles?.[0])?.title || created.roles?.[0]}.</div>}
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