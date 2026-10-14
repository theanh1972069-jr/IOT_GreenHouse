import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  AlertTriangle,
  ArrowDownToLine,
  BellRing,
  Bell,
  Check,
  CircleAlert,
  Clock3,
  Droplet,
  Fan,
  FileClock,
  Info,
  Lightbulb,
  LoaderCircle,
  LockKeyhole,
  MailPlus,
  Moon,
  Play,
  RefreshCw,
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
  X,
} from 'lucide-react';
import { apiRequest } from './api.js';

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

function ControlOverview({ devices, capability }) {
  const [searchParams] = useSearchParams();
  const requestedDeviceId = searchParams.get('deviceId');
  const [selectedDeviceId, setSelectedDeviceId] = useState('');
  const [telemetry, setTelemetry] = useState([]);
  const [configuration, setConfiguration] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [series, setSeries] = useState('temperature');
  const [range, setRange] = useState('auto');

  useEffect(() => {
    if (requestedDeviceId && devices.some((device) => device.deviceId === requestedDeviceId)) {
      setSelectedDeviceId(requestedDeviceId);
    } else if (!selectedDeviceId && devices.length) {
      setSelectedDeviceId(devices[0].deviceId);
    }
  }, [requestedDeviceId, selectedDeviceId, devices]);

  useEffect(() => {
    if (!selectedDeviceId) {
      setTelemetry([]);
      setConfiguration(null);
      return undefined;
    }

    let active = true;
    const loadTelemetry = async () => {
      setLoading(true);
      try {
        const encodedId = encodeURIComponent(selectedDeviceId);
        const [records, deviceConfiguration] = await Promise.all([
          apiRequest(`/api/telemetry?deviceId=${encodedId}`),
          apiRequest(`/api/devices/${encodedId}/configuration`).catch(() => null),
        ]);
        if (active) {
          setTelemetry(records);
          setConfiguration(deviceConfiguration);
          setError('');
        }
      } catch (requestError) {
        if (active) setError(requestError.message || 'Không thể tải dữ liệu cảm biến.');
      } finally {
        if (active) setLoading(false);
      }
    };

    loadTelemetry();
    const intervalId = window.setInterval(loadTelemetry, refreshMs);
    return () => {
      active = false;
      window.clearInterval(intervalId);
    };
  }, [selectedDeviceId]);

  const latest = telemetry[0];
  const temperatureLimit = Number(configuration?.temperatureHigh) || 35;
  const soilLimit = Number(configuration?.soilMoistureLow) || 35;
  const metrics = [
    { label: 'Nhiệt độ không khí', value: latest?.temperature, unit: '°C', target: `< ${temperatureLimit}°C`, good: (value) => value < temperatureLimit, tone: 'metric-temperature' },
    { label: 'Độ ẩm không khí', value: latest?.humidity, unit: '% RH', target: '65% – 75%', good: (value) => value >= 65 && value <= 75, tone: 'metric-humidity' },
    { label: 'Độ ẩm đất', value: latest?.soilMoisture, unit: '%', target: `> ${soilLimit}%`, good: (value) => value > soilLimit, tone: 'metric-soil' },
    { label: 'Cường độ ánh sáng', value: latest?.light, unit: 'lux', target: '500 – 1000 lux', good: (value) => value >= 500 && value <= 1000, tone: 'metric-light' },
  ];
  const unavailableReason = !capability
    ? 'Đang kiểm tra khả năng điều khiển.'
    : !capability.mqttEnabled
      ? 'MQTT đang tắt trên máy chủ.'
      : !capability.commandProtocolConfigured
        ? 'Firmware chưa khai báo giao thức nhận lệnh.'
        : 'Backend chưa có API gửi lệnh đến thiết bị.';

  return <>
    <div className="control-overview-toolbar">
      <label className="control-device-select"><span>Node đang theo dõi</span><select value={selectedDeviceId} onChange={(event) => setSelectedDeviceId(event.target.value)} disabled={!devices.length}><option value="">{devices.length ? 'Chọn thiết bị' : 'Chưa có thiết bị'}</option>{devices.map((device) => <option key={device.deviceId} value={device.deviceId}>{device.name} · {device.deviceId}</option>)}</select></label>
      <span className={`control-live-state ${latest ? 'control-live-on' : ''}`}><span />{loading ? 'Đang đồng bộ' : latest?.timestamp ? `Cập nhật ${formatDate(latest.timestamp)}` : 'Chưa có dữ liệu cảm biến'}</span>
    </div>
    {error && <ErrorNotice>{error}</ErrorNotice>}

    <section className="control-metric-grid" aria-label="Chỉ số môi trường hiện tại">
      {metrics.map(({ label, value, unit, target, good, tone }) => {
        const isGood = value != null && good(Number(value));
        return <article className="control-metric-card" key={label}>
          <div className="control-metric-top"><span>{label}</span><span className={`control-metric-badge ${value == null ? 'badge-unknown' : isGood ? 'badge-good' : 'badge-warn'}`}>{value == null ? 'Chưa có dữ liệu' : isGood ? 'Bình thường' : 'Chú ý'}</span></div>
          <div className="control-metric-reading">{value == null ? '—' : Number(value).toLocaleString('vi-VN', { maximumFractionDigits: 1 })}<small>{unit}</small><span className={tone} /></div>
          <div className="control-metric-target"><span>Mục tiêu an toàn</span><strong>{target}</strong></div>
        </article>;
      })}
    </section>

    <section className="actuator-grid" aria-label="Thiết bị chấp hành">
      <article className="actuator-card">
        <header className="actuator-heading"><span className="actuator-icon actuator-fan"><Fan size={17} /></span><span><strong>Quạt thông gió</strong><small>Lưu thông không khí vòm cây</small></span><span className="actuator-state">Chưa khả dụng</span></header>
        <div className="actuator-control-row"><span><strong>Nguồn thiết bị</strong><small>Chế độ tự động</small></span><input type="checkbox" role="switch" aria-label="Bật quạt thông gió" disabled title={unavailableReason} /></div>
        <footer className="actuator-footer"><span>Tự động khi nhiệt độ vượt ngưỡng</span><button type="button" disabled title={unavailableReason}>Cấu hình</button></footer>
      </article>
      <article className="actuator-card">
        <header className="actuator-heading"><span className="actuator-icon actuator-pump"><Droplet size={17} /></span><span><strong>Máy bơm tưới</strong><small>Hệ thống cấp nước nhỏ giọt</small></span><span className="actuator-state">Chưa khả dụng</span></header>
        <div className="actuator-control-row"><span><strong>Nguồn thiết bị</strong><small>Tự động theo lịch</small></span><input type="checkbox" role="switch" aria-label="Bật máy bơm tưới" disabled title={unavailableReason} /></div>
        <button className="actuator-action" type="button" disabled title={unavailableReason}><Play size={14} /> Tưới nhanh 5 phút</button>
      </article>
      <article className="actuator-card">
        <header className="actuator-heading"><span className="actuator-icon actuator-light"><Lightbulb size={17} /></span><span><strong>Đèn sinh trưởng LED</strong><small>Dải quang phổ cho cây con</small></span><span className="actuator-state">Chưa khả dụng</span></header>
        <label className="brightness-control"><span><strong>Độ sáng</strong><b>80%</b></span><input type="range" min="0" max="100" defaultValue="80" disabled aria-label="Độ sáng đèn sinh trưởng" title={unavailableReason} /></label>
        <footer className="actuator-footer"><span>Tự động tắt lúc 22:00</span><input type="checkbox" role="switch" aria-label="Hẹn giờ đèn sinh trưởng" disabled title={unavailableReason} /></footer>
      </article>
    </section>
    <div className="notice notice-info control-notice"><LockKeyhole size={16} /><span>{unavailableReason} Các lệnh phần cứng đang bị khóa; số liệu và lịch sử lệnh vẫn lấy từ backend.</span></div>

    <section className="control-chart-section">
      <div className="control-chart-heading">
        <div>
          <h2>Biểu đồ biến thiên môi trường</h2>
          <p>Dữ liệu đo đạc cảm biến theo mốc thời gian thực</p>
        </div>
        <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap', alignItems: 'center' }}>
          <div className="control-chart-tabs" role="group" aria-label="Khung thời gian">
            <button type="button" className={range === 'auto' ? 'selected' : ''} onClick={() => setRange('auto')}>Tự động</button>
            <button type="button" className={range === '15m' ? 'selected' : ''} onClick={() => setRange('15m')}>15 phút</button>
            <button type="button" className={range === '1h' ? 'selected' : ''} onClick={() => setRange('1h')}>1 giờ</button>
            <button type="button" className={range === '24h' ? 'selected' : ''} onClick={() => setRange('24h')}>24 giờ</button>
          </div>
          <div className="control-chart-tabs" role="group" aria-label="Chỉ số biểu đồ">
            <button type="button" className={series === 'temperature' ? 'selected' : ''} aria-pressed={series === 'temperature'} onClick={() => setSeries('temperature')}>Nhiệt độ (°C)</button>
            <button type="button" className={series === 'humidity' ? 'selected' : ''} aria-pressed={series === 'humidity'} onClick={() => setSeries('humidity')}>Độ ẩm KK (%)</button>
            <button type="button" className={series === 'soilMoisture' ? 'selected' : ''} aria-pressed={series === 'soilMoisture'} onClick={() => setSeries('soilMoisture')}>Độ ẩm đất (%)</button>
          </div>
        </div>
      </div>
      <ControlTrendChart telemetry={telemetry} series={series} range={range} loading={loading} />
    </section>
  </>;
}

function ControlTrendChart({ telemetry, series, range = 'auto', loading }) {
  const key = series === 'temperature' ? 'temperature' : series === 'soilMoisture' ? 'soilMoisture' : 'humidity';
  const unit = series === 'temperature' ? '°C' : '%';
  const label = series === 'temperature' ? 'Nhiệt độ' : series === 'soilMoisture' ? 'Độ ẩm đất' : 'Độ ẩm không khí';
  const now = Date.now();

  const rangeDurations = {
    '15m': 15 * 60 * 1000,
    '1h': 60 * 60 * 1000,
    '6h': 6 * 60 * 60 * 1000,
    '24h': 24 * 60 * 60 * 1000,
  };

  const parsedRecords = (telemetry || [])
    .map((record) => ({
      ...record,
      time: new Date(record.timestamp).getTime(),
      val: Number(record[key]),
    }))
    .filter((record) => Number.isFinite(record.time) && Number.isFinite(record.val))
    .sort((a, b) => a.time - b.time);

  let startTime;
  let endTime;
  let records;

  if (range !== 'auto' && rangeDurations[range]) {
    const duration = rangeDurations[range];
    endTime = now;
    startTime = now - duration;
    records = parsedRecords.filter((r) => r.time >= startTime && r.time <= endTime);
  } else {
    // Auto: adapt to the actual data span
    if (parsedRecords.length >= 2) {
      const first = parsedRecords[0].time;
      const last = parsedRecords[parsedRecords.length - 1].time;
      const span = Math.max(last - first, 30000);
      startTime = first - span * 0.05;
      endTime = last + span * 0.05;
      records = parsedRecords;
    } else if (parsedRecords.length === 1) {
      startTime = parsedRecords[0].time - 5 * 60 * 1000;
      endTime = parsedRecords[0].time + 60 * 1000;
      records = parsedRecords;
    } else {
      startTime = now - 15 * 60 * 1000;
      endTime = now;
      records = [];
    }
  }

  const values = records.map((r) => r.val);
  const minimum = values.length ? Math.min(...values) : 0;
  const maximum = values.length ? Math.max(...values) : 1;
  const padding = maximum === minimum ? Math.max(Math.abs(maximum) * 0.1, 1) : (maximum - minimum) * 0.15;
  const low = minimum - padding;
  const high = maximum + padding;
  const timeSpan = Math.max(endTime - startTime, 10000);

  const points = records.map((record) => ({
    x: 20 + Math.max(0, Math.min(1, (record.time - startTime) / timeSpan)) * 760,
    y: 145 - ((record.val - low) / (high - low)) * 116,
    value: record.val,
    time: record.time,
  }));

  const path = points.map((point, index) => `${index === 0 ? 'M' : 'L'}${point.x.toFixed(1)},${point.y.toFixed(1)}`).join(' ');
  const lastPoint = points[points.length - 1];
  const firstPoint = points[0];
  const area = lastPoint && firstPoint ? `${path} L${lastPoint.x.toFixed(1)},160 L${firstPoint.x.toFixed(1)},160 Z` : '';

  const formatClock = (ts) => {
    if (!ts || !Number.isFinite(ts)) return '';
    return new Date(ts).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  };

  const durationSec = Math.round(timeSpan / 1000);
  const durationDesc = durationSec >= 3600
    ? `${(durationSec / 3600).toFixed(1)} giờ`
    : durationSec >= 60
      ? `${Math.round(durationSec / 60)} phút`
      : `${durationSec} giây`;

  return (
    <div className="control-chart-frame">
      {records.length ? (
        <>
          <svg className="control-trend-chart" viewBox="0 0 800 190" role="img" aria-label={`Biểu đồ ${label}`}>
            <defs>
              <linearGradient id="control-chart-fill" x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="#139876" stopOpacity=".2" />
                <stop offset="100%" stopColor="#139876" stopOpacity="0" />
              </linearGradient>
            </defs>
            {[38, 76, 114, 152].map((y) => (
              <line key={y} x1="16" x2="784" y1={y} y2={y} className="control-chart-gridline" />
            ))}
            {area && <path d={area} fill="url(#control-chart-fill)" />}
            {path && <path d={path} className="control-chart-line" />}
            {points.map((pt, i) => (
              <circle
                key={i}
                cx={pt.x}
                cy={pt.y}
                r={i === points.length - 1 ? 4.5 : 2.5}
                className={i === points.length - 1 ? 'control-chart-current' : ''}
                fill="#087b66"
              >
                <title>{`${formatClock(pt.time)}: ${pt.value.toLocaleString('vi-VN', { maximumFractionDigits: 1 })} ${unit}`}</title>
              </circle>
            ))}
          </svg>
          <div className="control-chart-axis">
            <span>Từ: <strong>{formatClock(startTime)}</strong></span>
            <span>Khoảng đo: <strong>{durationDesc}</strong> ({records.length} điểm)</span>
            <span>
              Đến: <strong>{formatClock(endTime)}</strong> · Hiện tại: <strong>{values[values.length - 1].toLocaleString('vi-VN', { maximumFractionDigits: 1 })} {unit}</strong>
            </span>
          </div>
        </>
      ) : (
        <div className="control-chart-empty">
          {loading ? 'Đang tải dữ liệu cảm biến...' : 'Chưa có dữ liệu cảm biến trong khoảng thời gian này.'}
        </div>
      )}
    </div>
  );
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
    <ModuleHeading eyebrow="VẬN HÀNH / ĐIỀU KHIỂN" title="Điều khiển nhà kính" description="Theo dõi môi trường và trạng thái các thiết bị trong nhà kính." actions={<button className="button button-secondary" onClick={refresh}><RefreshCw size={15} /> Làm mới</button>} />
    <ControlOverview devices={devices} capability={capability} />
    <ErrorNotice>{error}</ErrorNotice>
    <section className="table-panel">
      <div className="section-heading command-history-heading"><div><div className="section-kicker">NHẬT KÝ THIẾT BỊ</div><h2>Lịch sử lệnh <span className="history-count">{meta.totalElements}</span></h2></div></div>
      <div className="table-toolbar filter-toolbar"><label className="filter-select"><select value={deviceId} onChange={(event) => setDeviceId(event.target.value)}><option value="ALL">Mọi thiết bị</option>{devices.map((device) => <option value={device.deviceId} key={device.deviceId}>{device.name} · {device.deviceId}</option>)}</select></label><label className="filter-select"><select value={status} onChange={(event) => setStatus(event.target.value)}><option value="ALL">Mọi trạng thái</option>{['PENDING', 'SENT', 'SUCCESS', 'FAILED', 'TIMEOUT', 'REJECTED'].map((value) => <option key={value}>{value}</option>)}</select></label></div>
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
      <p className="preferences-note"><Info size={14} /> Tùy chọn giao diện được lưu trên trình duyệt này.</p>
    </div>
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
        <div className="form-footer"><span>Yêu cầu mật khẩu tối thiểu 8 ký tự.</span><button className="button button-primary" disabled={saving}>{saving ? <LoaderCircle className="spin" size={15} /> : <Check size={15} />}{saving ? 'Đang tạo...' : 'Tạo tài khoản'}</button></div>
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