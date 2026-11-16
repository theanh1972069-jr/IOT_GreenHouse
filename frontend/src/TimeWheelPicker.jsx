import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import './TimeWheelPicker.css';

const ITEM_HEIGHT = 40; // Chiều cao mỗi hàng: 40px
const VISIBLE_COUNT = 5; // Đúng 5 hàng hiển thị, hàng giữa (hàng 3) là hàng được chọn

/**
 * Cột cuộn bánh xe vô hạn (WheelColumn)
 * Hỗ trợ: touch swipe, mouse wheel, mouse drag, click chọn, phím mũi tên.
 */
function WheelColumn({ count, value, onChange, label }) {
  const colRef = useRef(null);
  const debounceTimer = useRef(null);
  const isDragging = useRef(false);
  const startY = useRef(0);
  const startScrollTop = useRef(0);

  // Số bản sao để tạo hiệu ứng vòng lặp vô hạn mượt mà
  const COPIES = 7;
  const cycleHeight = count * ITEM_HEIGHT;
  const baseOffset = Math.floor(COPIES / 2) * count;

  // Tạo danh sách lặp lại
  const items = useMemo(() => {
    const list = [];
    for (let c = 0; c < COPIES; c++) {
      for (let i = 0; i < count; i++) {
        list.push({
          rawIndex: c * count + i,
          val: i,
          label: String(i).padStart(2, '0'),
        });
      }
    }
    return list;
  }, [count, COPIES]);

  const numVal = parseInt(value, 10) || 0;

  // Khởi tạo vị trí cuộn ban đầu về khối giữa
  useEffect(() => {
    if (!colRef.current) return;
    const targetIdx = baseOffset + numVal;
    const targetTop = targetIdx * ITEM_HEIGHT;
    // Nếu độ lệch lớn hơn 1 nửa chu kỳ thì cuộn tức thì
    if (Math.abs(colRef.current.scrollTop - targetTop) > cycleHeight) {
      colRef.current.scrollTop = targetTop;
    }
  }, [value, baseOffset, numVal, cycleHeight]);

  // Xử lý cuộn, vòng lặp vô hạn và tự động snap
  const handleScroll = useCallback(() => {
    if (!colRef.current) return;
    const st = colRef.current.scrollTop;

    // Vòng lặp vô hạn: nếu cuộn quá gần đỉnh hoặc đáy, dịch chuyển không giật
    if (st < cycleHeight * 2) {
      colRef.current.scrollTop += cycleHeight * 2;
    } else if (st > cycleHeight * 4) {
      colRef.current.scrollTop -= cycleHeight * 2;
    }

    // Debounce snap dừng đúng giữa một số và gọi onChange
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    debounceTimer.current = setTimeout(() => {
      if (!colRef.current) return;
      const currentST = colRef.current.scrollTop;
      const nearestIdx = Math.round(currentST / ITEM_HEIGHT);
      const snappedTop = nearestIdx * ITEM_HEIGHT;

      // Snap chuẩn xác vào giữa hàng
      if (Math.abs(currentST - snappedTop) > 1) {
        colRef.current.scrollTo({ top: snappedTop, behavior: 'smooth' });
      }

      // Tính giá trị thực (0..count-1)
      const actualVal = ((nearestIdx % count) + count) % count;
      const formatted = String(actualVal).padStart(2, '0');
      if (formatted !== String(numVal).padStart(2, '0')) {
        onChange(formatted);
      }
    }, 80);
  }, [cycleHeight, count, numVal, onChange]);

  // Hỗ trợ kéo thả chuột trên desktop (Mouse Drag)
  const handlePointerDown = (e) => {
    isDragging.current = true;
    startY.current = e.clientY;
    startScrollTop.current = colRef.current.scrollTop;
    try {
      e.currentTarget.setPointerCapture(e.pointerId);
    } catch {}
  };

  const handlePointerMove = (e) => {
    if (!isDragging.current || !colRef.current) return;
    const delta = e.clientY - startY.current;
    colRef.current.scrollTop = startScrollTop.current - delta;
  };

  const handlePointerUp = (e) => {
    if (!isDragging.current || !colRef.current) return;
    isDragging.current = false;
    try {
      e.currentTarget.releasePointerCapture(e.pointerId);
    } catch {}
    const nearestIdx = Math.round(colRef.current.scrollTop / ITEM_HEIGHT);
    colRef.current.scrollTo({ top: nearestIdx * ITEM_HEIGHT, behavior: 'smooth' });
  };

  // Bấm vào số bất kỳ thì cuộn mượt tới số đó
  const handleItemClick = (idx) => {
    if (!colRef.current) return;
    colRef.current.scrollTo({ top: idx * ITEM_HEIGHT, behavior: 'smooth' });
  };

  // Hỗ trợ bàn phím mũi tên Lên / Xuống
  const handleKeyDown = (e) => {
    if (!colRef.current) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      const nextIdx = Math.round(colRef.current.scrollTop / ITEM_HEIGHT) + 1;
      colRef.current.scrollTo({ top: nextIdx * ITEM_HEIGHT, behavior: 'smooth' });
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      const prevIdx = Math.round(colRef.current.scrollTop / ITEM_HEIGHT) - 1;
      colRef.current.scrollTo({ top: prevIdx * ITEM_HEIGHT, behavior: 'smooth' });
    }
  };

  return (
    <div
      className="time-wheel-col"
      ref={colRef}
      onScroll={handleScroll}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerCancel={handlePointerUp}
      onKeyDown={handleKeyDown}
      tabIndex={0}
      role="listbox"
      aria-label={label}
    >
      {items.map((item) => {
        const isSelected = item.val === numVal;
        return (
          <div
            key={item.rawIndex}
            className={`time-wheel-item ${isSelected ? 'selected' : ''}`}
            onClick={() => handleItemClick(item.rawIndex)}
            role="option"
            aria-selected={isSelected}
          >
            {item.label}
          </div>
        );
      })}
    </div>
  );
}

/**
 * Component chính: TimeWheelPicker
 * Props:
 *  - value: chuỗi "HH:mm" (ví dụ: "19:00")
 *  - onChange: hàm nhận chuỗi "HH:mm" mới
 */
export function TimeWheelPicker({ value = '19:00', onChange }) {
  const [hStr, mStr] = (value || '00:00').split(':');
  const hour = hStr ? hStr.padStart(2, '0') : '00';
  const minute = mStr ? mStr.padStart(2, '0') : '00';

  const handleHourChange = useCallback(
    (newH) => {
      onChange(`${newH}:${minute}`);
    },
    [minute, onChange]
  );

  const handleMinuteChange = useCallback(
    (newM) => {
      onChange(`${hour}:${newM}`);
    },
    [hour, onChange]
  );

  return (
    <div className="time-wheel-container">
      {/* Lớp phủ mờ dần ở mép trên và dưới (Fade mask iOS) */}
      <div className="time-wheel-mask" />

      {/* Dải highlight ngang xám nhạt kéo dài qua 2 cột ở hàng giữa */}
      <div className="time-wheel-highlight" />

      {/* Hai cột cuộn dọc: Giờ (00–23) và Phút (00–59) */}
      <div className="time-wheel-columns">
        <WheelColumn count={24} value={hour} onChange={handleHourChange} label="Chọn giờ" />
        <div className="time-wheel-separator">:</div>
        <WheelColumn count={60} value={minute} onChange={handleMinuteChange} label="Chọn phút" />
      </div>
    </div>
  );
}

/**
 * Component tích hợp: TimeWheelInput
 * Hiển thị ô input có thể gõ trực tiếp ("19:00") kèm icon đồng hồ.
 * Khi click vào ô hoặc icon, mở picker dạng popover/dropdown ngay dưới ô đó, bấm ra ngoài thì đóng.
 */
export function TimeWheelInput({
  value = '19:00',
  onChange,
  label = 'Chọn thời gian',
  icon = '🕒',
  placeholder = '19:00',
  align = 'left',
}) {
  const [open, setOpen] = useState(false);
  const wrapperRef = useRef(null);

  // Đóng popover khi bấm ra ngoài
  useEffect(() => {
    function handleClickOutside(event) {
      if (wrapperRef.current && !wrapperRef.current.contains(event.target)) {
        setOpen(false);
      }
    }
    if (open) {
      document.addEventListener('mousedown', handleClickOutside);
      document.addEventListener('touchstart', handleClickOutside);
    }
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('touchstart', handleClickOutside);
    };
  }, [open]);

  // Xử lý khi người dùng gõ phím trực tiếp vào ô input
  const handleInputChange = (e) => {
    let val = e.target.value.replace(/[^0-9:]/g, '');
    if (val.length === 2 && !val.includes(':') && e.nativeEvent.inputType !== 'deleteContentBackward') {
      val = val + ':';
    }
    onChange(val);
  };

  // Chuẩn hóa định dạng khi rời ô input (blur)
  const handleInputBlur = () => {
    const val = (value || '').trim();
    if (/^([01]?[0-9]|2[0-3]):[0-5][0-9]$/.test(val)) {
      const [h, m] = val.split(':');
      onChange(`${h.padStart(2, '0')}:${m.padStart(2, '0')}`);
    } else if (/^\d{1,2}$/.test(val)) {
      const h = Math.min(23, Math.max(0, parseInt(val, 10)));
      onChange(`${String(h).padStart(2, '0')}:00`);
    } else if (/^\d{3,4}$/.test(val)) {
      const h = Math.min(23, parseInt(val.slice(0, val.length - 2), 10));
      const m = Math.min(59, parseInt(val.slice(-2), 10));
      onChange(`${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`);
    }
  };

  return (
    <div className="time-wheel-input-wrapper" ref={wrapperRef}>
      <div className="time-wheel-input-box">
        <span style={{ fontSize: 14 }}>{icon}</span>
        <input
          type="text"
          className="time-wheel-input-text"
          placeholder={placeholder}
          maxLength={5}
          value={value}
          onChange={handleInputChange}
          onBlur={handleInputBlur}
          onClick={() => setOpen(true)}
          aria-label={label}
        />
        <button
          type="button"
          className="time-wheel-trigger-btn"
          onClick={() => setOpen((prev) => !prev)}
          title={open ? 'Đóng bánh xe cuộn' : 'Mở bánh xe cuộn chọn giờ (hướng lên)'}
          aria-expanded={open}
        >
          {open ? '▾' : '▴'}
        </button>
      </div>

      {/* Popover bánh xe cuộn xuất hiện hướng lên trên ô input */}
      {open && (
        <div className={`time-wheel-popover ${align === 'right' ? 'popover-right' : ''}`}>
          <div className="time-wheel-popover-header">
            <span className="time-wheel-popover-title">{label}</span>
            <button type="button" className="time-wheel-done-btn" onClick={() => setOpen(false)}>
              Xong
            </button>
          </div>
          <TimeWheelPicker value={value} onChange={onChange} />
        </div>
      )}
    </div>
  );
}

export default TimeWheelPicker;

/* ==========================================================================
   VÍ DỤ CÁCH DÙNG TRONG FORM LỊCH CHIẾU SÁNG:
   ==========================================================================
   
   import { useState } from 'react';
   import { TimeWheelInput } from './TimeWheelPicker.jsx';

   function LightScheduleExample() {
     const [startTime, setStartTime] = useState('19:00');
     const [endTime, setEndTime] = useState('07:00');

     return (
       <div className="schedule-form">
         <label>Giờ Bật Đèn (24h):</label>
         <TimeWheelInput
           value={startTime}
           onChange={setStartTime}
           label="Giờ bật đèn"
           icon="🌙"
         />

         <label>Giờ Tắt Đèn (24h):</label>
         <TimeWheelInput
           value={endTime}
           onChange={setEndTime}
           label="Giờ tắt đèn"
           icon="☀️"
         />
       </div>
     );
   }
*/
