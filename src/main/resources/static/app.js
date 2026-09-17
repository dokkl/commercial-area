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

// 팝업이 열려 있는 동안엔 renderMap()의 clearLayers()가 그 팝업이 달린 마커까지 지워버린다.
// map.on('popupopen'/'popupclose', ...)에서 갱신한다(아래쪽 초기화 절 참고).
// moveend 시점에 스케줄링을 막는 것과 별개로, 이미 날아간 fetch가 뒤늦게 돌아왔을 때도
// 아래 refresh()의 렌더링 직전에 다시 한번 확인해야 한다 — 요청이 나간 "뒤" 팝업이 열릴 수도 있다.
let popupOpen = false;

// Task 12에서 목록 갱신을 합치기 위해 재할당하므로 let으로 선언한다.
let refresh = async function () {
  const seq = ++refreshSeq;
  const params = currentParams();
  try {
    const data = await fetch('/api/map?' + params).then(r => r.json());
    if (seq !== refreshSeq) return; // 더 최신 refresh가 이미 시작됐다 — 이 응답은 버린다.
    if (popupOpen) return; // 팝업이 열려 있다 — 지금 렌더링하면 그 팝업이 달린 마커가 지워진다. popupclose가 따라잡는다.
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

/* ---------- 필터 드롭다운 ---------- */

const el = id => document.getElementById(id);

const CASCADE = [
  { id: 'sido',   child: 'sgg',    url: () => '/api/regions/sido' },
  { id: 'sgg',    child: 'dong',   url: v => '/api/regions/sgg?sido=' + encodeURIComponent(v) },
  { id: 'dong',   child: null,     url: v => '/api/regions/dong?sgg=' + encodeURIComponent(v) },
  { id: 'large',  child: 'medium', url: () => '/api/industries/large' },
  { id: 'medium', child: 'small',  url: v => '/api/industries/medium?large=' + encodeURIComponent(v) },
  { id: 'small',  child: null,     url: v => '/api/industries/small?medium=' + encodeURIComponent(v) }
];

const PLACEHOLDER = {
  sido: '시도 전체', sgg: '시군구 전체', dong: '행정동 전체',
  large: '대분류 전체', medium: '중분류 전체', small: '소분류 전체'
};

function resetSelect(id) {
  const select = el(id);
  select.innerHTML = `<option value="">${PLACEHOLDER[id]}</option>`;
  select.disabled = true;
  filters[id] = '';
}

async function fillSelect(id, url) {
  const select = el(id);
  try {
    const items = await fetch(url).then(r => r.json());
    select.innerHTML = `<option value="">${PLACEHOLDER[id]}</option>` + items.map(item => {
      const bbox = item.minLat != null
        ? ` data-bbox="${item.minLat},${item.minLon},${item.maxLat},${item.maxLon}"`
        : '';
      return `<option value="${escapeHtml(item.code)}"${bbox}>`
           + `${escapeHtml(item.name)} (${item.count.toLocaleString()})</option>`;
    }).join('');
    select.disabled = items.length === 0;
  } catch (e) {
    console.error('옵션 로딩 실패: ' + id, e);
    select.disabled = true;
  }
}

function fitToSelected(select) {
  const bbox = select.selectedOptions[0]?.dataset.bbox;
  if (!bbox) return;
  const [minLat, minLon, maxLat, maxLon] = bbox.split(',').map(Number);
  // 한 점뿐인 구역은 bounds가 0 넓이라 지도가 최대 줌으로 튄다. 약간 넓혀준다.
  const pad = 0.002;
  map.fitBounds([[minLat - pad, minLon - pad], [maxLat + pad, maxLon + pad]]);
}

for (const level of CASCADE) {
  el(level.id).addEventListener('change', async event => {
    const value = event.target.value;
    filters[level.id] = value;

    // 하위 단계를 모두 초기화한다.
    let child = level.child;
    while (child) {
      resetSelect(child);
      child = CASCADE.find(l => l.id === child)?.child;
    }

    if (value && level.child) {
      const childLevel = CASCADE.find(l => l.id === level.child);
      await fillSelect(childLevel.id, childLevel.url(value));
    }

    page = 0;
    // 지역을 고르면 지도를 그쪽으로 옮긴다. moveend가 refresh를 부른다.
    if (value && event.target.selectedOptions[0]?.dataset.bbox) {
      fitToSelected(event.target);
    } else {
      refresh();
    }
  });
}

let searchTimer;
el('q').addEventListener('input', event => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    filters.q = event.target.value.trim();
    page = 0;
    refresh();
  }, 300);
});

el('reset').addEventListener('click', () => {
  for (const level of CASCADE) {
    if (level.id === 'sido' || level.id === 'large') {
      el(level.id).value = '';
      filters[level.id] = '';
    } else {
      resetSelect(level.id);
    }
  }
  el('q').value = '';
  filters.q = '';
  page = 0;
  map.setView(SEOUL, 11);   // moveend가 refresh를 부른다
});

/* ---------- 결과 목록 ---------- */

const PAGE_SIZE = 20;
let page = 0;
let totalPages = 0;

function renderList(data) {
  const body = el('list-body');
  totalPages = Math.ceil(data.total / PAGE_SIZE);

  if (data.items.length === 0) {
    body.innerHTML = '<tr class="empty"><td colspan="3">조건에 맞는 상가가 없습니다.</td></tr>';
  } else {
    body.innerHTML = data.items.map(item => `
      <tr data-id="${escapeHtml(item.id)}" data-lat="${item.lat}" data-lon="${item.lon}">
        <td>${escapeHtml(item.name)}${item.branchName ? ' ' + escapeHtml(item.branchName) : ''}</td>
        <td>${escapeHtml(item.smallName)}</td>
        <td class="addr">${escapeHtml(item.roadAddress || '')}</td>
      </tr>
    `).join('');
  }

  el('page-info').textContent = totalPages === 0 ? '0 / 0' : `${page + 1} / ${totalPages}`;
  el('prev').disabled = page <= 0;
  el('next').disabled = page >= totalPages - 1;
}

el('list-body').addEventListener('click', event => {
  const row = event.target.closest('tr[data-id]');
  if (!row) return;
  const lat = Number(row.dataset.lat);
  const lon = Number(row.dataset.lon);
  map.setView([lat, lon], Math.max(map.getZoom(), 17));
  const marker = L.marker([lat, lon]).addTo(map);
  openDetail(marker, row.dataset.id);
  marker.on('popupclose', () => map.removeLayer(marker));
});

el('prev').addEventListener('click', () => { if (page > 0) { page--; refreshList(); } });
el('next').addEventListener('click', () => { if (page < totalPages - 1) { page++; refreshList(); } });

// 목록 응답도 지도 응답과 마찬가지로 순서가 뒤바뀔 수 있다 (필터를 빠르게 바꾸는 경우 등).
// 지도의 refreshSeq와는 별개의 순번을 쓴다 — 두 요청은 서로 다른 시점에 발생하므로
// 하나의 카운터를 공유하면 유효한 응답까지 오탐으로 버리게 된다.
let listSeq = 0;

async function refreshList() {
  const seq = ++listSeq;
  const params = currentParams();
  params.set('page', page);
  params.set('size', PAGE_SIZE);
  try {
    const data = await fetch('/api/stores?' + params).then(r => r.json());
    if (seq !== listSeq) return; // 더 최신 refreshList가 이미 시작됐다 — 이 응답은 버린다.
    if (data.error) {
      console.warn('목록 조회 실패', data);
      return;
    }
    renderList(data);
  } catch (e) {
    if (seq !== listSeq) return;
    console.error('목록 조회 실패', e);
  }
}

/* ---------- 초기화 ---------- */

// Task 11의 refresh()를 감싸 목록까지 함께 갱신한다.
// refresh는 위에서 이미 let으로 선언되어 있으므로 재할당이 그대로 동작한다.
const refreshMapOnly = refresh;
refresh = async function () {
  await Promise.all([refreshMapOnly(), refreshList()]);
};

// popupOpen 플래그(위쪽 refreshSeq 옆에 선언)를 여기서 갱신한다.
// openDetail()이 연 팝업이 화면 가장자리에 가까우면 Leaflet의 기본 popup autoPan이
// 지도를 살짝 옮긴다 → moveend → scheduleRefresh → 300ms 뒤 refresh() →
// renderMap()의 markerLayer.clearLayers()가 방금 연 팝업이 달린 마커까지 지워버린다.
// (또는 반대로, moveend가 먼저 발생해 fetch가 이미 날아간 뒤에 팝업이 열릴 수도 있다 —
// 그 경우는 refresh() 안의 렌더링 직전 popupOpen 확인이 막아준다.)
// autoPan은 그대로 두고(끄면 가장자리 팝업이 잘린다), 팝업이 열려 있는 동안만
// 갱신을 미루고 닫힐 때 한 번 따라잡는다.
map.on('popupopen', () => {
  popupOpen = true;
  clearTimeout(refreshTimer); // 이미 예약된 갱신이 있다면 취소한다.
});
map.on('popupclose', () => {
  popupOpen = false;
  refresh(); // 팝업이 열려 있는 동안 놓쳤을 이동을 한 번에 반영한다.
});

map.on('moveend', () => {
  page = 0;
  if (popupOpen) return;
  scheduleRefresh();
});

(async function init() {
  await Promise.all([
    fillSelect('sido', '/api/regions/sido'),
    fillSelect('large', '/api/industries/large')
  ]);
  el('sido').disabled = false;
  el('large').disabled = false;
  refresh();
})();
