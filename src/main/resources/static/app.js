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
  return Math.min(38, 7 + Math.sqrt(count) * 0.75);
}

function renderMap(data) {
  cellLayer.clearLayers();
  markerLayer.clearLayers();

  if (data.mode === 'cluster') {
    for (const cell of data.cells) {
      L.circleMarker([cell.lat, cell.lon], {
        radius: cellRadius(cell.count),
        color: '#1c5ed6',
        weight: 1,
        fillColor: '#3b82f6',
        fillOpacity: 0.55
      })
        .bindTooltip(cell.count.toLocaleString(), {
          permanent: true, direction: 'center', className: 'cell-label'
        })
        .on('click', () => map.setView([cell.lat, cell.lon], Math.min(19, map.getZoom() + 2)))
        .addTo(cellLayer);
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

// Task 12에서 목록 갱신을 합치기 위해 재할당하므로 let으로 선언한다.
let refresh = async function () {
  const params = currentParams();
  try {
    const data = await fetch('/api/map?' + params).then(r => r.json());
    if (data.error) {
      console.warn('지도 조회 실패', data);
      return;
    }
    renderMap(data);
    document.getElementById('total').textContent = data.total.toLocaleString();
  } catch (e) {
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
