'use strict';

// webjars 경로에서는 Leaflet이 기본 마커 이미지 경로를 스스로 찾지 못한다.
L.Icon.Default.imagePath = '/webjars/leaflet/dist/images/';

const SEOUL = [37.5665, 126.9780];

const map = L.map('map').setView(SEOUL, 11);

L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19,
  attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
}).addTo(map);

const cellLayer = L.layerGroup().addTo(map);
const markerLayer = L.markerClusterGroup({ chunkedLoading: true });
map.addLayer(markerLayer);

/** Task 12에서 필터 값이 채워진다. */
const filters = {
  sido: '', sgg: '', dong: '',
  large: '', medium: '', small: '',
  q: ''
};

function currentParams() {
  const b = map.getBounds();
  const params = new URLSearchParams({
    minLat: b.getSouth(),
    maxLat: b.getNorth(),
    minLon: b.getWest(),
    maxLon: b.getEast(),
    zoom: map.getZoom()
  });
  for (const [key, value] of Object.entries(filters)) {
    if (value) params.set(key, value);
  }
  return params;
}

function cellRadius(count) {
  // 선형으로 키우면 대도시 셀 하나가 화면을 덮는다.
  // 격자 한 칸은 모든 줌 레벨에서 항상 화면 32px 폭이므로(360/2^(z+3) 도 * 256*2^z/360 px/도 = 32px),
  // 반지름 상한은 그 칸에 들어가도록 16px(지름 32px)을 넘지 않게 잡는다.
  return Math.min(16, 4 + Math.sqrt(count) * 0.6);
}

/**
 * 32px 원 안에 들어가도록 건수를 간략 표기한다.
 * 1,000 미만은 그대로, 1,000 이상은 "N.N천", 10,000 이상은 "N.N만" — 소수점 첫째 자리,
 * 정수면 ".0"을 뗀다. 예: 847 -> "847", 1,562 -> "1.6천", 16,799 -> "1.7만".
 */
function formatCellCount(count) {
  if (count < 1000) return String(count);

  let unit = count >= 10000 ? '만' : '천';
  let divisor = unit === '만' ? 10000 : 1000;
  let rounded = Math.round((count / divisor) * 10) / 10;

  // 반올림으로 "10.0천"처럼 자릿수가 넘어가면 "1.0만"으로 올린다.
  if (unit === '천' && rounded >= 10) {
    unit = '만';
    rounded = Math.round((count / 10000) * 10) / 10;
  }

  return (Number.isInteger(rounded) ? rounded : rounded.toFixed(1)) + unit;
}

function renderMap(data) {
  cellLayer.clearLayers();
  markerLayer.clearLayers();

  if (data.mode === 'cluster') {
    for (const cell of data.cells) {
      const circle = L.circleMarker([cell.lat, cell.lon], {
        radius: cellRadius(cell.count),
        color: '#1c5ed6',
        weight: 1,
        fillColor: '#3b82f6',
        fillOpacity: 0.55
      })
        .bindTooltip(formatCellCount(cell.count), {
          permanent: true, direction: 'center', className: 'cell-label'
        })
        .on('click', () => map.setView([cell.lat, cell.lon], Math.min(19, map.getZoom() + 2)))
        .addTo(cellLayer);
      // 정확한 건수는 네이티브 title 속성으로 남겨 둔다 (마우스 오버 시 브라우저 기본 툴팁, 별도 상호작용 없음).
      const el = circle.getElement();
      if (el) el.setAttribute('title', cell.count.toLocaleString());
    }
    return;
  }

  const markers = data.points.map(point => {
    const marker = L.marker([point.lat, point.lon]);
    marker.on('click', () => openDetail(marker, point.id));
    return marker;
  });
  markerLayer.addLayers(markers);
}

async function openDetail(marker, id) {
  marker.bindPopup('불러오는 중…').openPopup();
  try {
    const response = await fetch('/api/stores/' + encodeURIComponent(id));
    if (!response.ok) throw new Error('not found');
    const s = await response.json();
    marker.setPopupContent(`
      <div class="popup">
        <strong>${escapeHtml(s.storeName)}</strong>${s.branchName ? ' ' + escapeHtml(s.branchName) : ''}
        <div>${escapeHtml(s.largeName)} &gt; ${escapeHtml(s.mediumName)} &gt; ${escapeHtml(s.smallName)}</div>
        <div>${escapeHtml(s.roadAddress || s.lotAddress || '')}</div>
        ${s.buildingName ? `<div>${escapeHtml(s.buildingName)}${s.floorInfo ? ' ' + escapeHtml(s.floorInfo) + '층' : ''}</div>` : ''}
      </div>
    `);
  } catch (e) {
    marker.setPopupContent('정보를 불러오지 못했습니다.');
  }
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));
}

// 느린 응답이 그 사이 사용자가 떠난 뷰포트의 데이터로 화면을 덮어쓰는 것을 막기 위한 순번 토큰.
// (좁은 bbox는 ~150ms, 전국 bbox는 ~900ms까지 걸려 응답이 요청 순서대로 돌아오지 않을 수 있다.)
let refreshSeq = 0;

// Task 12에서 목록 갱신을 합치기 위해 재할당하므로 let으로 선언한다.
let refresh = async function () {
  const seq = ++refreshSeq;
  const params = currentParams();
  try {
    const data = await fetch('/api/map?' + params).then(r => r.json());
    if (seq !== refreshSeq) return; // 더 최신 refresh가 이미 시작됐다 — 이 응답은 버린다.
    if (data.error) {
      console.warn('지도 조회 실패', data);
      return;
    }
    renderMap(data);
    document.getElementById('total').textContent = data.total.toLocaleString();
  } catch (e) {
    if (seq !== refreshSeq) return;
    console.error('지도 조회 실패', e);
  }
};

let refreshTimer;
function scheduleRefresh() {
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(refresh, 300);
}

map.on('moveend', scheduleRefresh);
refresh();
