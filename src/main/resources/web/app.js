const state = {
  status: null,
  snapshots: [],
  snapshotDetails: {},
  itemHistoryCache: {},
  itemHistoryPending: {},
  selectedIndex: -1,
  selectedItemId: null,
  filterText: '',
  livePriceMap: {},
  wikiMappingById: {},
  wikiMappingByName: {},
  wikiMappingByLooseName: {},
  wikiTimeseriesCache: {},
  activeWikiRange: '1d',
  activeTabIndex: 'all',
  activeSection: 'overview',
  requestToken: 0,
  itemHistoryRequestToken: 0
};

const els = {
  statusText: document.getElementById('statusText'),
  profileTitle: document.getElementById('profileTitle'),
  openDataDirBtn: document.getElementById('openDataDirBtn'),
  refreshBtn: document.getElementById('refreshBtn'),
  snapshotSelect: document.getElementById('snapshotSelect'),
  itemSearch: document.getElementById('itemSearch'),
  summaryGrid: document.getElementById('summaryGrid'),
  browserItemDetails: document.getElementById('browserItemDetails'),
  bankTabButtons: document.getElementById('bankTabButtons'),
  bankRecreation: document.getElementById('bankRecreation'),
  gainersTable: document.getElementById('gainersTable'),
  losersTable: document.getElementById('losersTable'),
  holdingsTable: document.getElementById('holdingsTable'),
  topHoldingsTable: document.getElementById('topHoldingsTable'),
  marketEstimateBox: document.getElementById('marketEstimateBox'),
  compareASelect: document.getElementById('compareASelect'),
  compareBSelect: document.getElementById('compareBSelect'),
  compareSummaryGrid: document.getElementById('compareSummaryGrid'),
  totalChart: document.getElementById('totalChart'),
  itemValueChart: document.getElementById('itemValueChart'),
  itemQuantityChart: document.getElementById('itemQuantityChart'),
  navButtons: Array.from(document.querySelectorAll('.nav-btn')),
  sections: Array.from(document.querySelectorAll('.page-section'))
};

function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function formatNumber(value) {
  return new Intl.NumberFormat().format(Math.round(Number(value || 0)));
}

function formatGp(value) {
  const num = Number(value || 0);
  const sign = num < 0 ? '-' : '';
  return `${sign}${formatNumber(Math.abs(num))} gp`;
}

function trimTrailingZeros(value) {
  return String(value)
    .replace(/\.0+$/, '')
    .replace(/(\.\d*[1-9])0+$/, '$1');
}

function formatCompactGp(value) {
  const num = Number(value || 0);
  const abs = Math.abs(num);
  const sign = num < 0 ? '-' : '';
  if (abs >= 1_000_000_000) {
    const decimals = abs >= 100_000_000_000 ? 0 : abs >= 10_000_000_000 ? 1 : 2;
    return `${sign}${trimTrailingZeros((abs / 1_000_000_000).toFixed(decimals))}B gp`;
  }
  if (abs >= 1_000_000) {
    const decimals = abs >= 100_000_000 ? 0 : abs >= 10_000_000 ? 1 : 2;
    return `${sign}${trimTrailingZeros((abs / 1_000_000).toFixed(decimals))}M gp`;
  }
  if (abs >= 1_000) {
    const decimals = abs >= 100_000 ? 0 : abs >= 10_000 ? 1 : 2;
    return `${sign}${trimTrailingZeros((abs / 1_000).toFixed(decimals))}K gp`;
  }
  return `${sign}${formatNumber(abs)} gp`;
}


function formatCompactNumber(value) {
  const num = Number(value || 0);
  const abs = Math.abs(num);
  const sign = num < 0 ? '-' : '';
  if (abs >= 1_000_000_000) {
    const decimals = abs >= 100_000_000_000 ? 0 : abs >= 10_000_000_000 ? 1 : 2;
    return `${sign}${trimTrailingZeros((abs / 1_000_000_000).toFixed(decimals))}B`;
  }
  if (abs >= 1_000_000) {
    const decimals = abs >= 100_000_000 ? 0 : abs >= 10_000_000 ? 1 : 2;
    return `${sign}${trimTrailingZeros((abs / 1_000_000).toFixed(decimals))}M`;
  }
  if (abs >= 1_000) {
    const decimals = abs >= 100_000 ? 0 : abs >= 10_000 ? 1 : 2;
    return `${sign}${trimTrailingZeros((abs / 1_000).toFixed(decimals))}K`;
  }
  return `${sign}${formatNumber(abs)}`;
}

function formatChartShortTimestamp(value) {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return String(value || '');
  return date.toLocaleString([], { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function formatChartTooltipTimestamp(value) {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return String(value || '');
  return date.toLocaleString();
}

function gpSpan(value, className = '') {
  const cls = ['gp-value', className].filter(Boolean).join(' ');
  return `<span class="${cls}">${formatGp(value)}</span>`;
}

function numberSpan(value) {
  return `<span class="gp-value">${formatNumber(value)}</span>`;
}

function positiveNegativeClass(value) {
  return Number(value || 0) >= 0 ? 'positive' : 'negative';
}

function normalizeWikiName(name) {
  return String(name || '')
    .toLowerCase()
    .replace(/&/g, 'and')
    .replace(/[^a-z0-9()+\- ]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function looseWikiName(name) {
  return normalizeWikiName(name)
    .replace(/\s*\((?:uncharged|charged|broken|inactive|active|or|nz|l|d|full|light|dark|normal|echoes)\)\s*/g, ' ')
    .replace(/\s*\(\d+\)\s*/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function safeAssetPath(path, fallback = '') {
  const value = String(path || '');
  if (/^\/icons\/[A-Za-z0-9._-]+\.png(?:\?.*)?$/.test(value)) return value;
  if (/^\/data\/[A-Za-z0-9 _-]+\/images\/[A-Za-z0-9._-]+\.(?:png|jpg|jpeg|webp)(?:\?.*)?$/.test(value)) return value;
  return fallback;
}

async function fetchJson(url, options = {}) {
  const response = await fetch(url, { cache: 'no-store', ...options });
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}));
    throw new Error(payload.error || `Request failed (${response.status})`);
  }
  return response.json();
}

function snapshotHasDetails(snapshot) {
  return Boolean(snapshot && Array.isArray(snapshot.items));
}

async function ensureSnapshotLoaded(index) {
  if (!Number.isInteger(index) || index < 0 || index >= state.snapshots.length) return null;
  const snapshot = state.snapshots[index];
  if (!snapshot) return null;
  if (snapshotHasDetails(snapshot)) return snapshot;
  const key = String(snapshot.capturedAt || '');
  if (!key) return snapshot;
  if (state.snapshotDetails[key]) {
    state.snapshots[index] = state.snapshotDetails[key];
    return state.snapshotDetails[key];
  }
  const detail = await fetchJson(`/api/snapshot?capturedAt=${encodeURIComponent(key)}`);
  state.snapshotDetails[key] = detail;
  state.snapshots[index] = detail;
  return detail;
}

async function ensureSnapshotsLoaded(indexes) {
  const unique = [...new Set((indexes || []).filter(index => Number.isInteger(index) && index >= 0))];
  const results = await Promise.all(unique.map(index => ensureSnapshotLoaded(index)));
  const map = new Map();
  unique.forEach((index, idx) => map.set(index, results[idx]));
  return map;
}

async function loadItemHistory(itemId) {
  const key = String(itemId || '');
  if (!key) return [];
  if (state.itemHistoryCache[key]) return state.itemHistoryCache[key];
  if (state.itemHistoryPending[key]) return state.itemHistoryPending[key];
  state.itemHistoryPending[key] = fetchJson(`/api/item-history?canonicalId=${encodeURIComponent(itemId)}`)
    .then(history => {
      state.itemHistoryCache[key] = Array.isArray(history) ? history : [];
      delete state.itemHistoryPending[key];
      return state.itemHistoryCache[key];
    })
    .catch(error => {
      delete state.itemHistoryPending[key];
      throw error;
    });
  return state.itemHistoryPending[key];
}

function currentSnapshot() {
  return state.snapshots[state.selectedIndex] || null;
}

function previousSnapshot() {
  return state.selectedIndex > 0 ? state.snapshots[state.selectedIndex - 1] : null;
}


function snapshotAtOrBefore(targetIso) {
  const target = new Date(targetIso).getTime();
  let best = null;
  state.snapshots.forEach(snapshot => {
    const t = new Date(snapshot.capturedAt).getTime();
    if (!Number.isFinite(t) || t > target) return;
    if (!best || t > new Date(best.capturedAt).getTime()) best = snapshot;
  });
  return best;
}

function daysAgo(iso, days) {
  const d = new Date(iso);
  d.setDate(d.getDate() - days);
  return d.toISOString();
}

function findDynamicDelta(snapshot) {
  const ranges = [
    { days: 30, label: 'Delta vs 1 month' },
    { days: 14, label: 'Delta vs 2 weeks' },
    { days: 7, label: 'Delta vs 1 week' },
    { days: 1, label: 'Delta vs 1 day' }
  ];
  for (const range of ranges) {
    const reference = snapshotAtOrBefore(daysAgo(snapshot.capturedAt, range.days));
    if (reference && reference.capturedAt !== snapshot.capturedAt) {
      return {
        label: range.label,
        valueHtml: gpSpan(Number(snapshot.totalGe || 0) - Number(reference.totalGe || 0), positiveNegativeClass(Number(snapshot.totalGe || 0) - Number(reference.totalGe || 0)))
      };
    }
  }
  const oldest = state.snapshots[0] || null;
  if (oldest && oldest.capturedAt !== snapshot.capturedAt) {
    const delta = Number(snapshot.totalGe || 0) - Number(oldest.totalGe || 0);
    return {
      label: 'Delta vs first snapshot',
      valueHtml: gpSpan(delta, positiveNegativeClass(delta))
    };
  }
  return {
    label: 'Delta vs history',
    valueHtml: '<span class="muted">Need more history</span>'
  };
}

function updateHeader(snapshot) {
  const profile = snapshot?.profileKey || state.status?.currentProfile || '';
  els.profileTitle.textContent = profile ? `— ${profile}` : '';
  if (!snapshot) {
    return;
  }
  const url = state.status?.dashboardUrl || '';
  els.statusText.textContent = `Loaded ${state.snapshots.length} snapshot${state.snapshots.length === 1 ? '' : 's'}${url ? ` · ${url}` : ''}`;
}

function renderCompareControls(snapshot) {
  if (!els.compareASelect || !els.compareBSelect || !els.compareSummaryGrid) return;
  const snapshots = state.snapshots;
  els.compareASelect.innerHTML = '';
  els.compareBSelect.innerHTML = '';
  snapshots.forEach((entry, index) => {
    const label = `${entry.day} ${new Date(entry.capturedAt).toLocaleTimeString()}`;
    const optA = document.createElement('option');
    optA.value = String(index);
    optA.textContent = label;
    const optB = document.createElement('option');
    optB.value = String(index);
    optB.textContent = label;
    els.compareASelect.appendChild(optA);
    els.compareBSelect.appendChild(optB);
  });

  if (els.compareASelect.dataset.bound !== '1') {
    els.compareASelect.dataset.bound = '1';
    els.compareASelect.addEventListener('change', () => {
      applyComparisonForIndexes(Number(els.compareASelect.value), Number(els.compareBSelect.value));
    });
    els.compareBSelect.dataset.bound = '1';
    els.compareBSelect.addEventListener('change', () => {
      applyComparisonForIndexes(Number(els.compareASelect.value), Number(els.compareBSelect.value));
    });
  }

  const aIndex = state.selectedIndex;
  const bIndex = Math.max(0, state.selectedIndex - 1);
  els.compareASelect.value = String(aIndex);
  els.compareBSelect.value = String(bIndex);
  applyComparisonForIndexes(aIndex, bIndex);
}

async function applyComparisonForIndexes(aIndex, bIndex) {
  if (!Number.isInteger(aIndex) || !Number.isInteger(bIndex)) return;
  const loaded = await ensureSnapshotsLoaded([aIndex, bIndex]);
  applyComparison(loaded.get(aIndex) || state.snapshots[aIndex], loaded.get(bIndex) || state.snapshots[bIndex]);
}

function applyComparison(current, previous) {
  if (!current || !previous || !snapshotHasDetails(current) || !snapshotHasDetails(previous)) return;
  const geDelta = Number(current.totalGe || 0) - Number(previous.totalGe || 0);
  const haDelta = Number(current.totalHa || 0) - Number(previous.totalHa || 0);
  els.compareSummaryGrid.innerHTML = [
    ['Compare A', escapeHtml(new Date(current.capturedAt).toLocaleString())],
    ['Compare B', escapeHtml(new Date(previous.capturedAt).toLocaleString())],
    ['GE delta', gpSpan(geDelta, positiveNegativeClass(geDelta))],
    ['HA delta', gpSpan(haDelta, positiveNegativeClass(haDelta))]
  ].map(([label, value]) => `
    <div class="summary-box">
      <div class="label">${label}</div>
      <div class="value">${value}</div>
    </div>`).join('');

  const movers = computeMovers(current, previous);
  renderMoverRows(els.gainersTable, movers.gainers, 'Rise');
  renderMoverRows(els.losersTable, movers.losers, 'Drop');
}

function orderedSnapshotItems(snapshot) {
  const items = (snapshot?.items || []).slice();
  items.sort((a, b) => {
    const ax = Number(a.slotIndex ?? a.slot ?? 0);
    const bx = Number(b.slotIndex ?? b.slot ?? 0);
    return ax - bx;
  });
  return items.map(item => ({
    ...item,
    iconPath: safeAssetPath(item.iconPath, `/icons/${item.canonicalItemId}.png`)
  }));
}

function getExplicitTabIndex(item) {
  const candidates = [item.tabIndex, item.capturedTabIndex, item.effectiveTabIndex, item.bankTabIndex, item.tab];
  for (const value of candidates) {
    if (value !== undefined && value !== null && value !== '') return Number(value);
  }
  return null;
}

function getTabName(snapshot, idx) {
  const tab = (snapshot?.bankTabs || []).find(t => Number(t.index) === Number(idx));
  if (tab?.name) return tab.name;
  if (Number(idx) === 0) return 'Main';
  if (idx === 'all') return 'All';
  return `Tab ${idx}`;
}

function heuristicTabDecorate(snapshot) {
  const ordered = orderedSnapshotItems(snapshot);
  const tabs = (snapshot?.bankTabs || []).slice();
  const main = tabs.find(tab => Number(tab.index) === 0) || { index: 0, name: 'Main', count: 0 };
  const others = tabs.filter(tab => Number(tab.index) !== 0 && Number(tab.count) > 0);
  const plan = [main, ...others];
  const decorated = [];
  let cursor = 0;
  plan.forEach(tab => {
    const count = Number(tab.count || 0);
    for (let i = 0; i < count && cursor < ordered.length; i += 1) {
      decorated.push({ ...ordered[cursor], effectiveTabIndex: Number(tab.index), effectiveTabName: tab.name || getTabName(snapshot, tab.index) });
      cursor += 1;
    }
  });
  while (cursor < ordered.length) {
    decorated.push({ ...ordered[cursor], effectiveTabIndex: 0, effectiveTabName: 'Main' });
    cursor += 1;
  }
  return decorated;
}

function decorateItems(snapshot) {
  const ordered = orderedSnapshotItems(snapshot);
  const hasExplicit = ordered.some(item => getExplicitTabIndex(item) !== null);
  if (!hasExplicit) return heuristicTabDecorate(snapshot);
  return ordered.map(item => {
    const idx = getExplicitTabIndex(item);
    return {
      ...item,
      effectiveTabIndex: idx,
      effectiveTabName: getTabName(snapshot, idx)
    };
  });
}

function visibleItems(snapshot) {
  let items = decorateItems(snapshot);
  if (state.activeTabIndex !== 'all') {
    items = items.filter(item => String(item.effectiveTabIndex) === String(state.activeTabIndex));
  }
  const term = state.filterText.trim().toLowerCase();
  if (term) {
    items = items.filter(item => String(item.name || '').toLowerCase().includes(term));
  }
  return items;
}

function currentBrowserItem(snapshot = currentSnapshot()) {
  if (!snapshot) return null;
  const all = decorateItems(snapshot);
  let item = all.find(entry => entry.canonicalItemId === state.selectedItemId && !entry.placeholder);
  if (!item) {
    item = visibleItems(snapshot).find(entry => !entry.placeholder) || visibleItems(snapshot)[0] || null;
    if (item) state.selectedItemId = item.canonicalItemId;
  }
  return item;
}

function currentItemFromLatest() {
  const snapshot = currentSnapshot();
  if (!snapshot) return null;
  return currentBrowserItem(snapshot);
}

function byCanonical(snapshot) {
  const map = new Map();
  decorateItems(snapshot).forEach(item => map.set(item.canonicalItemId, item));
  return map;
}

function computeMovers(current, previous, limit = 10) {
  const prevMap = previous ? byCanonical(previous) : new Map();
  const currentMap = byCanonical(current);
  const ids = new Set([...currentMap.keys(), ...prevMap.keys()]);
  const rows = [];
  ids.forEach(id => {
    const now = currentMap.get(id);
    const prev = prevMap.get(id);
    const item = now || prev;
    if (!item || item.placeholder) return;
    const delta = (now?.geTotal || 0) - (prev?.geTotal || 0);
    rows.push({
      canonicalItemId: id,
      name: item.name || 'Unknown',
      iconPath: item.iconPath || `/icons/${id}.png`,
      delta,
      quantityNow: now?.quantity || 0,
      quantityDelta: (now?.quantity || 0) - (prev?.quantity || 0),
      unitDelta: ((now?.geUnitPrice || 0) - (prev?.geUnitPrice || 0)),
      currentTab: now?.effectiveTabName || item.effectiveTabName || 'Main'
    });
  });
  rows.sort((a, b) => b.delta - a.delta);
  return {
    gainers: rows.filter(r => r.delta > 0).slice(0, limit),
    losers: rows.filter(r => r.delta < 0).sort((a, b) => a.delta - b.delta).slice(0, limit)
  };
}

function renderMoverRows(table, rows, valueTitle) {
  table.innerHTML = `
    <thead><tr><th>Item</th><th>${valueTitle}</th><th>Qty</th><th>Qty Δ</th><th>GP/item Δ</th></tr></thead>
    <tbody></tbody>
  `;
  const tbody = table.querySelector('tbody');
  if (!rows.length) {
    tbody.innerHTML = '<tr><td colspan="5" class="muted">No data</td></tr>';
    return;
  }
  rows.forEach(row => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><button class="row-link icon-cell" data-item-id="${row.canonicalItemId}"><img loading="lazy" src="${row.iconPath}" alt=""><span>${escapeHtml(row.name)}</span></button></td>
      <td>${gpSpan(row.delta, positiveNegativeClass(row.delta))}</td>
      <td>${numberSpan(row.quantityNow)}</td>
      <td>${row.quantityDelta >= 0 ? '+' : ''}${numberSpan(row.quantityDelta)}</td>
      <td>${gpSpan(row.unitDelta, positiveNegativeClass(row.unitDelta))}</td>
    `;
    tbody.appendChild(tr);
  });
  tbody.querySelectorAll('[data-item-id]').forEach(btn => btn.addEventListener('click', event => {
    event.preventDefault();
    selectItem(Number(btn.dataset.itemId));
  }));
}

function renderSummary(snapshot, previous) {
  const delta = previous ? Number(snapshot.totalGe || 0) - Number(previous.totalGe || 0) : 0;
  const dynamicDelta = findDynamicDelta(snapshot);
  const items = decorateItems(snapshot).filter(item => !item.placeholder);
  const cards = [
    ['Captured', escapeHtml(new Date(snapshot.capturedAt).toLocaleString())],
    ['Captured GE', gpSpan(snapshot.totalGe)],
    ['Captured HA', gpSpan(snapshot.totalHa)],
    ['Real items', numberSpan(items.length)],
    ['Total quantity', numberSpan(items.reduce((sum, item) => sum + Number(item.quantity || 0), 0))],
    ['Delta vs previous', previous ? gpSpan(delta, positiveNegativeClass(delta)) : '<span class="muted">No previous snapshot</span>'],
    [dynamicDelta.label, dynamicDelta.valueHtml]
  ];
  els.summaryGrid.innerHTML = cards.map(([label, value]) => `
    <div class="summary-box">
      <div class="label">${label}</div>
      <div class="value">${value}</div>
    </div>
  `).join('');
}

function ensureChartTooltip(canvas) {
  const host = canvas.closest('.chart-card') || canvas.parentElement;
  if (!host) return null;
  let tooltip = host.querySelector('.chart-tooltip');
  if (!tooltip) {
    tooltip = document.createElement('div');
    tooltip.className = 'chart-tooltip';
    host.appendChild(tooltip);
  }
  return tooltip;
}

function hideChartTooltip(canvas) {
  const tooltip = ensureChartTooltip(canvas);
  if (!tooltip) return;
  tooltip.classList.remove('is-visible', 'is-below');
  tooltip.innerHTML = '';
}

function positionChartTooltip(canvas, tooltip, pointX, pointY) {
  const host = canvas.closest('.chart-card') || canvas.parentElement;
  if (!host) return;
  const canvasRect = canvas.getBoundingClientRect();
  const hostRect = host.getBoundingClientRect();
  const leftBase = canvasRect.left - hostRect.left + ((pointX / canvas.width) * canvasRect.width);
  const topBase = canvasRect.top - hostRect.top + ((pointY / canvas.height) * canvasRect.height);
  tooltip.style.left = `${leftBase}px`;
  tooltip.style.top = `${topBase}px`;
  tooltip.classList.add('is-visible');
  tooltip.classList.remove('is-below');

  const pad = 10;
  const tooltipRect = tooltip.getBoundingClientRect();
  let finalLeft = leftBase;
  const half = tooltipRect.width / 2;
  const minLeft = pad + half;
  const maxLeft = Math.max(minLeft, hostRect.width - pad - half);
  finalLeft = Math.max(minLeft, Math.min(maxLeft, finalLeft));

  const aboveTop = topBase - tooltipRect.height - 14;
  const useBelow = aboveTop < pad;
  tooltip.classList.toggle('is-below', useBelow);
  let finalTop = useBelow ? Math.min(hostRect.height - pad, topBase + 18) : topBase;

  tooltip.style.left = `${finalLeft}px`;
  tooltip.style.top = `${finalTop}px`;
}

function bindChartInteractions(canvas) {
  if (!canvas || canvas.dataset.chartBound === '1') return;
  canvas.dataset.chartBound = '1';

  const resolveHoverIndex = event => {
    const chartState = canvas._chartState;
    const coords = chartState?.coords || [];
    if (!coords.length) return null;
    const rect = canvas.getBoundingClientRect();
    const x = (event.clientX - rect.left) * (canvas.width / rect.width);
    const y = (event.clientY - rect.top) * (canvas.height / rect.height);
    let bestIndex = null;
    let bestDistance = Infinity;
    coords.forEach((coord, index) => {
      const dx = coord.x - x;
      const dy = coord.y - y;
      const distance = Math.sqrt((dx * dx) + (dy * dy));
      if (distance < bestDistance) {
        bestDistance = distance;
        bestIndex = index;
      }
    });
    return bestDistance <= 28 ? bestIndex : null;
  };

  const updateHover = event => {
    const chartState = canvas._chartState;
    if (!chartState || !chartState.coords?.length) {
      hideChartTooltip(canvas);
      canvas.style.cursor = 'default';
      return;
    }
    const hoverIndex = resolveHoverIndex(event);
    if (hoverIndex === null) {
      if (chartState.hoverIndex !== null) {
        chartState.hoverIndex = null;
        drawLineChart(canvas, chartState.points, chartState.color, chartState.options);
      }
      hideChartTooltip(canvas);
      canvas.style.cursor = 'default';
      return;
    }

    if (chartState.hoverIndex !== hoverIndex) {
      chartState.hoverIndex = hoverIndex;
      drawLineChart(canvas, chartState.points, chartState.color, chartState.options);
    }

    const coord = (canvas._chartState?.coords || [])[hoverIndex];
    const point = coord?.point;
    const tooltip = ensureChartTooltip(canvas);
    if (!coord || !point || !tooltip) return;
    const valueFormatter = chartState.options?.tooltipValueFormatter || chartState.options?.valueFormatter || formatGp;
    const title = point.tooltipLabel || point.label || '';
    tooltip.innerHTML = `
      <div class="chart-tooltip-title">${escapeHtml(title)}</div>
      <div class="chart-tooltip-value">${escapeHtml(valueFormatter(point.value))}</div>
    `;
    positionChartTooltip(canvas, tooltip, coord.x, coord.y);
    canvas.style.cursor = chartState.options?.onPointClick ? 'pointer' : 'crosshair';
  };

  canvas.addEventListener('mousemove', updateHover);
  canvas.addEventListener('mouseleave', () => {
    const chartState = canvas._chartState;
    if (chartState && chartState.hoverIndex !== null) {
      chartState.hoverIndex = null;
      drawLineChart(canvas, chartState.points, chartState.color, chartState.options);
    }
    hideChartTooltip(canvas);
    canvas.style.cursor = 'default';
  });
  canvas.addEventListener('click', event => {
    const chartState = canvas._chartState;
    if (!chartState?.options?.onPointClick) return;
    const hoverIndex = resolveHoverIndex(event);
    if (hoverIndex === null) return;
    const point = chartState.points[hoverIndex];
    chartState.options.onPointClick(point, hoverIndex);
  });
}

function drawLineChart(canvas, points, color = '#7cc5ff', options = {}) {
  if (!canvas) return;
  bindChartInteractions(canvas);
  const ctx = canvas.getContext('2d');
  const width = canvas.width;
  const height = canvas.height;
  const axisFormatter = options.axisFormatter || options.valueFormatter || formatGp;
  const valueFormatter = options.valueFormatter || formatGp;
  const xLabelFormatter = options.xLabelFormatter || (point => point.label || '');
  const hoverIndex = canvas._chartState?.hoverIndex ?? null;

  ctx.clearRect(0, 0, width, height);
  ctx.fillStyle = '#141b24';
  ctx.fillRect(0, 0, width, height);

  if (!points || !points.length) {
    canvas._chartState = { points: [], color, options, hoverIndex: null, coords: [] };
    ctx.fillStyle = '#9ab0c7';
    ctx.font = '16px Arial';
    ctx.fillText('No data available yet.', 20, 30);
    hideChartTooltip(canvas);
    return;
  }

  const padding = { left: 76, right: 24, top: 20, bottom: 42 };
  const min = Math.min(...points.map(p => Number(p.value || 0)));
  const max = Math.max(...points.map(p => Number(p.value || 0)));
  const span = Math.max(1, max - min);
  const plotWidth = Math.max(1, width - padding.left - padding.right);
  const plotHeight = Math.max(1, height - padding.top - padding.bottom);
  const xStep = points.length > 1 ? plotWidth / (points.length - 1) : 0;

  ctx.strokeStyle = '#22354d';
  ctx.lineWidth = 1;
  [0, 0.25, 0.5, 0.75, 1].forEach(ratio => {
    const y = padding.top + (plotHeight * ratio);
    ctx.beginPath();
    ctx.moveTo(padding.left, y);
    ctx.lineTo(width - padding.right, y);
    ctx.stroke();
  });

  ctx.strokeStyle = '#2b4260';
  ctx.beginPath();
  ctx.moveTo(padding.left, padding.top);
  ctx.lineTo(padding.left, height - padding.bottom);
  ctx.lineTo(width - padding.right, height - padding.bottom);
  ctx.stroke();

  const coords = [];
  points.forEach((point, idx) => {
    const x = padding.left + (idx * xStep);
    const y = height - padding.bottom - (((Number(point.value || 0) - min) / span) * plotHeight);
    coords.push({ x, y, point, index: idx });
  });

  ctx.save();
  ctx.beginPath();
  coords.forEach(({ x, y }, idx) => {
    if (idx === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
  });
  ctx.lineTo(coords[coords.length - 1].x, height - padding.bottom);
  ctx.lineTo(coords[0].x, height - padding.bottom);
  ctx.closePath();
  ctx.fillStyle = 'rgba(124, 197, 255, 0.12)';
  ctx.fill();
  ctx.restore();

  ctx.strokeStyle = color;
  ctx.lineWidth = 2.25;
  ctx.beginPath();
  coords.forEach(({ x, y }, idx) => {
    if (idx === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
  });
  ctx.stroke();

  if (hoverIndex !== null && coords[hoverIndex]) {
    const hover = coords[hoverIndex];
    ctx.strokeStyle = 'rgba(214, 231, 248, 0.35)';
    ctx.setLineDash([4, 4]);
    ctx.beginPath();
    ctx.moveTo(hover.x, padding.top);
    ctx.lineTo(hover.x, height - padding.bottom);
    ctx.stroke();
    ctx.setLineDash([]);
  }

  coords.forEach(({ x, y }, idx) => {
    const isHover = idx === hoverIndex;
    ctx.beginPath();
    ctx.arc(x, y, isHover ? 6 : 3.5, 0, Math.PI * 2);
    ctx.fillStyle = isHover ? '#ffffff' : color;
    ctx.fill();
    if (isHover) {
      ctx.beginPath();
      ctx.arc(x, y, 9, 0, Math.PI * 2);
      ctx.strokeStyle = 'rgba(255,255,255,0.35)';
      ctx.lineWidth = 2;
      ctx.stroke();
    }
  });

  if (options.showPointLabels) {
    ctx.fillStyle = '#d7e7f8';
    ctx.font = '11px Arial';
    const labelFormatter = options.compactPointLabels ? formatCompactGp : valueFormatter;
    const placedLabels = [];
    coords.forEach(({ x, y, point }, idx) => {
      const text = labelFormatter(point.value);
      const tw = ctx.measureText(text).width;
      const baseX = Math.max(4, Math.min(width - tw - 4, x - (tw / 2)));
      const candidateYs = [Math.max(14, y - 10), Math.min(height - padding.bottom - 6, y + 16), Math.max(14, y - 24), Math.min(height - padding.bottom - 6, y + 30)];
      const boxHeight = 12;
      let placed = false;
      for (const ty of candidateYs) {
        const box = { left: baseX - 2, right: baseX + tw + 2, top: ty - boxHeight, bottom: ty + 2 };
        const overlaps = placedLabels.some(other => !(box.right < other.left || box.left > other.right || box.bottom < other.top || box.top > other.bottom));
        if (overlaps) continue;
        ctx.fillText(text, baseX, ty);
        placedLabels.push(box);
        placed = true;
        break;
      }
      if (!placed && options.forceEdgeLabels && (idx === 0 || idx === coords.length - 1)) {
        const ty = Math.max(14, y - 10);
        ctx.fillText(text, baseX, ty);
      }
    });
  }

  ctx.fillStyle = '#d7e7f8';
  ctx.font = '12px Arial';
  ctx.fillText(axisFormatter(max), 10, padding.top + 4);
  ctx.fillText(axisFormatter(min), 10, height - padding.bottom);
  const firstLabel = xLabelFormatter(points[0]);
  ctx.fillText(firstLabel, padding.left, height - 12);
  if (points.length > 1) {
    const lastLabel = xLabelFormatter(points[points.length - 1]);
    const labelWidth = ctx.measureText(lastLabel).width;
    ctx.fillText(lastLabel, width - padding.right - labelWidth, height - 12);
  }

  canvas._chartState = { points, color, options, hoverIndex, coords };
  if (hoverIndex !== null && coords[hoverIndex]) {
    const tooltip = ensureChartTooltip(canvas);
    if (tooltip) {
      const point = coords[hoverIndex].point;
      tooltip.innerHTML = `
        <div class="chart-tooltip-title">${escapeHtml(point.tooltipLabel || point.label || '')}</div>
        <div class="chart-tooltip-value">${escapeHtml((options.tooltipValueFormatter || valueFormatter)(point.value))}</div>
      `;
      positionChartTooltip(canvas, tooltip, coords[hoverIndex].x, coords[hoverIndex].y);
    }
  } else {
    hideChartTooltip(canvas);
  }
}


function derivedTabs(snapshot) {
  const items = decorateItems(snapshot);
  const counts = new Map();
  items.forEach(item => {
    const key = String(item.effectiveTabIndex ?? 0);
    const current = counts.get(key) || { index: item.effectiveTabIndex ?? 0, name: item.effectiveTabName || getTabName(snapshot, item.effectiveTabIndex ?? 0), count: 0 };
    current.count += 1;
    counts.set(key, current);
  });
  const ordered = Array.from(counts.values()).sort((a, b) => Number(a.index) - Number(b.index));
  return [{ index: 'all', name: 'All', count: items.length }, ...ordered];
}

function getTabMeta(snapshot, tabIndex) {
  return (snapshot?.bankTabs || []).find(tab => String(tab.index) === String(tabIndex)) || null;
}

function getTabImagePath(snapshot, tabIndex) {
  const tab = getTabMeta(snapshot, tabIndex);
  const candidates = [
    tab?.imagePath, tab?.screenshotPath, tab?.renderPath, tab?.pngPath,
    snapshot?.bankImagePath, snapshot?.imagePath, snapshot?.screenshotPath
  ];
  return safeAssetPath(candidates.find(Boolean) || '', '');
}

function getCapturedBankTabIndex(snapshot) {
  const value = Number(snapshot?.capturedBankTabIndex ?? snapshot?.currentBankTab ?? -1);
  return Number.isFinite(value) ? value : -1;
}

function shouldUseTabOverlay(snapshot, items) {
  if (state.activeTabIndex === 'all') return false;
  const activeTab = Number(state.activeTabIndex);
  const capturedTab = getCapturedBankTabIndex(snapshot);
  if (!Number.isFinite(activeTab) || activeTab <= 0) return false;
  if (capturedTab !== activeTab) return false;
  return Boolean(getTabImagePath(snapshot, activeTab)) && hasOverlayData(items);
}

function itemOverlayLeft(item) {
  const value = Number(item?.x ?? item?.layoutX ?? -1);
  return Number.isFinite(value) ? value : -1;
}

function itemOverlayTop(item) {
  const value = Number(item?.y ?? item?.layoutY ?? -1);
  return Number.isFinite(value) ? value : -1;
}

function itemOverlayWidth(item) {
  const value = Number(item?.width ?? item?.w ?? item?.layoutW ?? 36);
  return Number.isFinite(value) && value > 0 ? value : 36;
}

function itemOverlayHeight(item) {
  const value = Number(item?.height ?? item?.h ?? item?.layoutH ?? 32);
  return Number.isFinite(value) && value > 0 ? value : 32;
}

function hasOverlayData(items) {
  return items.some(item => itemOverlayLeft(item) >= 0 && itemOverlayTop(item) >= 0);
}

function renderAllTabGroups(snapshot, items) {
  const tabCounts = derivedTabs(snapshot).filter(tab => String(tab.index) !== 'all');
  const grouped = new Map();
  tabCounts.forEach(tab => grouped.set(String(tab.index), []));
  items.forEach(item => {
    const key = String(item.effectiveTabIndex ?? 0);
    if (!grouped.has(key)) grouped.set(key, []);
    grouped.get(key).push(item);
  });

  const sections = tabCounts
    .filter(tab => (grouped.get(String(tab.index)) || []).length > 0)
    .map(tab => {
      const sectionItems = grouped.get(String(tab.index)) || [];
      return `
        <div class="bank-tab-group">
          <div class="bank-tab-group-header">${escapeHtml(tab.name)} (${formatNumber(sectionItems.length)})</div>
          <div class="bank-grid-wrap">
            <div class="bank-grid">
              ${sectionItems.map(item => `
                <button class="bank-slot ${item.placeholder ? 'placeholder' : ''} ${state.selectedItemId === item.canonicalItemId ? 'is-selected' : ''}" data-item-id="${item.canonicalItemId}" title="${escapeHtml(item.name)} | ${escapeHtml(item.effectiveTabName || 'Main')} | Qty ${formatNumber(item.quantity)} | GE ${formatGp(item.geTotal)} | HA ${formatGp(item.haTotal)}">
                  <img loading="lazy" src="${item.iconPath}" alt="${escapeHtml(item.name)}">
                  ${item.placeholder ? '' : `<span class="qty">${formatNumber(item.quantity)}</span>`}
                </button>
              `).join('')}
            </div>
          </div>
        </div>
      `;
    });

  els.bankRecreation.innerHTML = `<div class="bank-all-groups">${sections.join('')}</div>`;
  els.bankRecreation.querySelectorAll('[data-item-id]').forEach(btn => btn.addEventListener('click', () => {
    state.selectedItemId = Number(btn.dataset.itemId);
    renderBrowser(snapshot);
    renderSelectedItemCharts();
  }));
}

function renderBankTabButtons(snapshot) {
  const tabs = derivedTabs(snapshot);
  els.bankTabButtons.innerHTML = tabs.map(tab => `
    <button class="tab-btn ${String(tab.index) === String(state.activeTabIndex) ? 'is-active' : ''}" data-tab="${tab.index}">${escapeHtml(tab.name)} (${formatNumber(tab.count)})</button>
  `).join('');
  els.bankTabButtons.querySelectorAll('[data-tab]').forEach(btn => btn.addEventListener('click', () => {
    state.activeTabIndex = btn.dataset.tab;
    renderBrowser(snapshot);
  }));
}

function renderBankOverlay(snapshot, items) {
  const imagePath = getTabImagePath(snapshot, state.activeTabIndex === 'all' ? 0 : state.activeTabIndex);
  if (!imagePath || !hasOverlayData(items)) return false;
  const width = Math.max(...items.map(i => itemOverlayLeft(i) + itemOverlayWidth(i)), 0) + 8;
  const height = Math.max(...items.map(i => itemOverlayTop(i) + itemOverlayHeight(i)), 0) + 8;
  els.bankRecreation.innerHTML = `
    <div class="bank-overlay-scroll">
      <div class="bank-overlay-wrap" style="width:${width}px;height:${height}px;">
        <img class="bank-overlay-image" src="${imagePath}" alt="Bank tab screenshot">
        ${items.map(item => {
          const left = itemOverlayLeft(item);
          const top = itemOverlayTop(item);
          const w = itemOverlayWidth(item);
          const h = itemOverlayHeight(item);
          return `<button class="bank-hotspot ${state.selectedItemId === item.canonicalItemId ? 'is-selected' : ''}" data-item-id="${item.canonicalItemId}" style="left:${left}px;top:${top}px;width:${w}px;height:${h}px;" title="${escapeHtml(item.name)} | Qty ${formatNumber(item.quantity)} | GE ${formatGp(item.geTotal)} | HA ${formatGp(item.haTotal)}"></button>`;
        }).join('')}
      </div>
    </div>
  `;
  els.bankRecreation.querySelectorAll('[data-item-id]').forEach(btn => btn.addEventListener('click', () => {
    state.selectedItemId = Number(btn.dataset.itemId);
    renderBrowser(snapshot);
    renderSelectedItemCharts();
  }));
  return true;
}

function renderBankGrid(snapshot) {
  const items = visibleItems(snapshot);
  if (shouldUseTabOverlay(snapshot, items) && renderBankOverlay(snapshot, items)) {
    return;
  }

  if (state.activeTabIndex === 'all') {
    renderAllTabGroups(snapshot, items);
    return;
  }

  els.bankRecreation.innerHTML = `
    <div class="bank-grid-wrap">
      <div class="bank-grid">
        ${items.map(item => `
          <button class="bank-slot ${item.placeholder ? 'placeholder' : ''} ${state.selectedItemId === item.canonicalItemId ? 'is-selected' : ''}" data-item-id="${item.canonicalItemId}" title="${escapeHtml(item.name)} | ${escapeHtml(item.effectiveTabName || 'Main')} | Qty ${formatNumber(item.quantity)} | GE ${formatGp(item.geTotal)} | HA ${formatGp(item.haTotal)}">
            <img loading="lazy" src="${item.iconPath}" alt="${escapeHtml(item.name)}">
            ${item.placeholder ? '' : `<span class="qty">${formatNumber(item.quantity)}</span>`}
          </button>
        `).join('')}
      </div>
    </div>
  `;
  els.bankRecreation.querySelectorAll('[data-item-id]').forEach(btn => btn.addEventListener('click', () => {
    state.selectedItemId = Number(btn.dataset.itemId);
    renderBrowser(snapshot);
    renderSelectedItemCharts();
  }));
}

function resolveWikiEntry(item) {
  if (!item) return null;
  const rawId = Number(item.itemId || 0);
  const canonicalId = Number(item.canonicalItemId || 0);
  const exactName = normalizeWikiName(item.name);
  const simplifiedName = looseWikiName(item.name);

  if (rawId > 0 && (state.livePriceMap[rawId] || state.wikiMappingById[rawId])) {
    return { id: rawId, live: state.livePriceMap[rawId] || null, meta: state.wikiMappingById[rawId] || null, matchLabel: 'captured item id' };
  }
  if (canonicalId > 0 && (state.livePriceMap[canonicalId] || state.wikiMappingById[canonicalId])) {
    return { id: canonicalId, live: state.livePriceMap[canonicalId] || null, meta: state.wikiMappingById[canonicalId] || null, matchLabel: 'canonical item id' };
  }
  const exactMeta = state.wikiMappingByName[exactName];
  if (exactMeta) {
    const id = Number(exactMeta.id || 0);
    return { id, live: state.livePriceMap[id] || null, meta: exactMeta, matchLabel: 'exact item name' };
  }
  const looseMeta = state.wikiMappingByLooseName[simplifiedName];
  if (looseMeta) {
    const id = Number(looseMeta.id || 0);
    return { id, live: state.livePriceMap[id] || null, meta: looseMeta, matchLabel: 'simplified item name (likely charged/uncharged or base variant)' };
  }
  return null;
}

function wikiPriceRow(label, value) {
  return `<div class="mini-card"><div class="label">${label}</div><div class="value">${value}</div></div>`;
}

function formatTimestamp(seconds) {
  if (!seconds) return '-';
  return new Date(Number(seconds) * 1000).toLocaleString();
}

async function fetchWikiTimeseries(itemId, timestep) {
  const key = `${itemId}:${timestep}`;
  if (state.wikiTimeseriesCache[key]) return state.wikiTimeseriesCache[key];
  const payload = await fetchJson(`/api/item-timeseries?id=${itemId}&timestep=${encodeURIComponent(timestep)}`);
  state.wikiTimeseriesCache[key] = payload;
  return payload;
}

function rangeConfig(range) {
  switch (range) {
    case '1h': return { timestep: '5m', hours: 1 };
    case '12h': return { timestep: '5m', hours: 12 };
    case '1d': return { timestep: '1h', hours: 24 };
    case '1w': return { timestep: '24h', hours: 24 * 7 };
    case '1m': return { timestep: '24h', hours: 24 * 30 };
    case '1y': default: return { timestep: '24h', hours: 24 * 365 };
  }
}

function parseWikiPoints(payload, timestep, hours) {
  const entries = Array.isArray(payload?.data) ? payload.data : [];
  const points = entries.map(entry => {
    const timestamp = Number(entry.timestamp || entry.ts || 0);
    const avgHigh = Number(entry.avgHighPrice ?? entry.avgHigh ?? entry.high ?? 0);
    const avgLow = Number(entry.avgLowPrice ?? entry.avgLow ?? entry.low ?? 0);
    const value = avgHigh && avgLow ? Math.round((avgHigh + avgLow) / 2) : (avgHigh || avgLow || 0);
    const date = timestamp ? new Date(timestamp * 1000) : null;
    const label = date ? (timestep === '24h' ? date.toLocaleDateString() : date.toLocaleString([], { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })) : '';
    return { label, value, timestampMs: timestamp * 1000 };
  }).filter(p => p.value > 0);
  if (!points.length) return points;
  const latest = Math.max(...points.map(p => p.timestampMs));
  const cutoff = latest - (hours * 60 * 60 * 1000);
  return points.filter(p => p.timestampMs >= cutoff);
}

async function renderWikiChart(itemId, range) {
  const canvas = document.getElementById('browserWikiChart');
  if (!canvas || !itemId) return;
  const token = ++state.requestToken;
  try {
    const cfg = rangeConfig(range);
    const payload = await fetchWikiTimeseries(itemId, cfg.timestep);
    if (token !== state.requestToken) return;
    const points = parseWikiPoints(payload, cfg.timestep, cfg.hours);
    drawLineChart(canvas, points, '#89c4ff', { valueFormatter: formatGp, axisFormatter: formatCompactGp, xLabelFormatter: point => point.label || '' });
  } catch (e) {
    if (token !== state.requestToken) return;
    drawLineChart(canvas, []);
  }
}

function itemSeriesFromSnapshots(itemId) {
  const history = state.itemHistoryCache[String(itemId || '')] || [];
  return history.map((entry, index) => ({
    label: formatChartShortTimestamp(entry.capturedAt),
    tooltipLabel: formatChartTooltipTimestamp(entry.capturedAt),
    value: Number(entry.geTotal || 0),
    quantity: Number(entry.quantity || 0),
    tabName: entry.present ? (entry.tabName || 'Main') : 'Missing',
    snapshotIndex: index,
    item: entry.present ? entry : null
  }));
}

function renderBrowserItemDetails(snapshot) {
  const item = currentBrowserItem(snapshot);
  if (!item) {
    els.browserItemDetails.innerHTML = '<div class="muted">No item selected in this view.</div>';
    return;
  }
  const cachedHistory = state.itemHistoryCache[String(item.canonicalItemId || '')] || null;
  const series = (cachedHistory ? itemSeriesFromSnapshots(item.canonicalItemId) : []).filter(point => point.item).slice(-5).reverse();
  const wiki = resolveWikiEntry(item);

  const historyRows = cachedHistory
    ? (series.map(point => `
        <div class="history-row">
          <span>${escapeHtml(point.label)}</span>
          <span>${escapeHtml(point.tabName)}</span>
          <span>${gpSpan(point.item.geTotal)}</span>
        </div>
      `).join('') || '<div class="muted">No snapshot history for this item.</div>')
    : '<div class="muted">Loading snapshot history…</div>';

  let wikiHtml = '<div class="note-box">This item does not have a matching OSRS Wiki market entry. The captured item id, canonical id, exact item name, and simplified item name were checked.</div>';
  if (wiki) {
    const live = wiki.live || {};
    const meta = wiki.meta || {};
    const margin = (live.high && live.low) ? live.high - live.low : 0;
    const roi = live.low && margin ? ((margin / live.low) * 100) : 0;
    wikiHtml = `
      <div class="wiki-panel">
        <div class="item-subhead">
          <div>
            <strong>OSRS Wiki market data</strong>
            <div class="muted small">Matched by ${escapeHtml(wiki.matchLabel)} · Wiki item id ${wiki.id}</div>
          </div>
          <div class="inline-controls compact-controls wiki-chart-controls">
            <label for="wikiRangeSelect">Graph</label>
            <select id="wikiRangeSelect">
              <option value="1h" ${state.activeWikiRange === '1h' ? 'selected' : ''}>1h</option>
              <option value="12h" ${state.activeWikiRange === '12h' ? 'selected' : ''}>12h</option>
              <option value="1d" ${state.activeWikiRange === '1d' ? 'selected' : ''}>1d</option>
              <option value="1w" ${state.activeWikiRange === '1w' ? 'selected' : ''}>1 week</option>
              <option value="1m" ${state.activeWikiRange === '1m' ? 'selected' : ''}>1 month</option>
              <option value="1y" ${state.activeWikiRange === '1y' ? 'selected' : ''}>1 year</option>
            </select>
          </div>
        </div>
        <div class="wiki-grid">
          ${wikiPriceRow('Buy price', live.high ? gpSpan(live.high) : '-')}
          ${wikiPriceRow('Sell price', live.low ? gpSpan(live.low) : '-')}
          ${wikiPriceRow('Margin', margin ? gpSpan(margin, positiveNegativeClass(margin)) : '-')}
          ${wikiPriceRow('ROI', roi ? `${roi.toFixed(2)}%` : '-')}
          ${wikiPriceRow('Buy limit', meta.limit ? numberSpan(meta.limit) : '-')}
          ${wikiPriceRow('High alch', meta.highalch ? gpSpan(meta.highalch) : '-')}
          ${wikiPriceRow('Low alch', meta.lowalch ? gpSpan(meta.lowalch) : '-')}
          ${wikiPriceRow('Members', meta.members ? 'Yes' : 'No')}
        </div>
        <div class="chart-card compact-chart-card"><canvas id="browserWikiChart" width="820" height="220"></canvas></div>
        <div class="note-box">
          <div><strong>High price updated:</strong> ${escapeHtml(formatTimestamp(live.highTime))}</div>
          <div><strong>Low price updated:</strong> ${escapeHtml(formatTimestamp(live.lowTime))}</div>
          ${meta.examine ? `<div><strong>Examine:</strong> ${escapeHtml(meta.examine)}</div>` : ''}
        </div>
      </div>
    `;
  }

  els.browserItemDetails.innerHTML = `
    <div class="item-head">
      <img src="${item.iconPath}" alt="${escapeHtml(item.name)}">
      <div>
        <div><strong>${escapeHtml(item.name)}</strong></div>
        <div class="muted small">Raw ID: ${item.itemId} · Canonical ID: ${item.canonicalItemId}</div>
      </div>
    </div>
    <div class="item-subgrid compact-item-layout">
      <div class="item-grid compact-item-grid">
        ${wikiPriceRow('Quantity', item.placeholder ? 'Placeholder' : numberSpan(item.quantity))}
        ${wikiPriceRow('Tab', escapeHtml(item.effectiveTabName || 'Main'))}
        ${wikiPriceRow('Captured GE unit', item.placeholder ? '-' : gpSpan(item.geUnitPrice))}
        ${wikiPriceRow('Captured GE total', item.placeholder ? '-' : gpSpan(item.geTotal))}
        ${wikiPriceRow('Captured HA unit', item.placeholder ? '-' : gpSpan(item.haUnitPrice))}
        ${wikiPriceRow('Captured HA total', item.placeholder ? '-' : gpSpan(item.haTotal))}
      </div>
      <div>
        <div class="muted small">Last 5 tab/value history rows</div>
        <div class="history-list">${historyRows}</div>
      </div>
    </div>
    ${wikiHtml}
  `;

  const select = document.getElementById('wikiRangeSelect');
  if (select && wiki) {
    select.addEventListener('change', event => {
      state.activeWikiRange = event.target.value;
      renderWikiChart(wiki.id, state.activeWikiRange);
    });
    renderWikiChart(wiki.id, state.activeWikiRange);
  }

  if (!cachedHistory) {
    loadItemHistory(item.canonicalItemId)
      .then(() => {
        const latestSnapshot = currentSnapshot();
        const currentItem = currentBrowserItem(latestSnapshot);
        if (latestSnapshot && currentItem && Number(currentItem.canonicalItemId) === Number(item.canonicalItemId)) {
          renderBrowserItemDetails(latestSnapshot);
          renderSelectedItemCharts();
        }
      })
      .catch(() => {});
  }
}

function renderBrowser(snapshot) {
  renderBankTabButtons(snapshot);
  renderBankGrid(snapshot);
  const rows = visibleItems(snapshot).map(item => ({
    canonicalItemId: item.canonicalItemId,
    name: item.name,
    iconPath: item.iconPath,
    quantity: item.quantity,
    geTotal: item.geTotal,
    haTotal: item.haTotal,
    currentTab: item.effectiveTabName,
    placeholder: item.placeholder
  }));
  els.holdingsTable.innerHTML = `
    <thead><tr><th>Item</th><th>Qty</th><th>GE total</th><th>HA total</th><th>Tab</th></tr></thead>
    <tbody>
      ${rows.map(item => `
        <tr>
          <td><button class="row-link icon-cell" data-item-id="${item.canonicalItemId}"><img loading="lazy" src="${item.iconPath}" alt=""><span>${escapeHtml(item.name)}</span></button></td>
          <td>${item.placeholder ? '-' : numberSpan(item.quantity)}</td>
          <td>${item.placeholder ? '-' : gpSpan(item.geTotal)}</td>
          <td>${item.placeholder ? '-' : gpSpan(item.haTotal)}</td>
          <td>${escapeHtml(item.currentTab || 'Main')}</td>
        </tr>`).join('')}
    </tbody>
  `;
  els.holdingsTable.querySelectorAll('[data-item-id]').forEach(btn => btn.addEventListener('click', event => {
    event.preventDefault();
    state.selectedItemId = Number(btn.dataset.itemId);
    renderBrowser(snapshot);
    renderSelectedItemCharts();
  }));
  renderBrowserItemDetails(snapshot);
}

function renderCompactRows(table, rows, valueTitle, extraTitle = 'Tab') {
  table.innerHTML = `
    <thead><tr><th>Item</th><th>${valueTitle}</th><th>${extraTitle}</th></tr></thead>
    <tbody></tbody>
  `;
  const tbody = table.querySelector('tbody');
  if (!rows.length) {
    tbody.innerHTML = '<tr><td colspan="3" class="muted">No data</td></tr>';
    return;
  }
  rows.forEach(row => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><button class="row-link icon-cell" data-item-id="${row.canonicalItemId}"><img loading="lazy" src="${row.iconPath}" alt=""><span>${escapeHtml(row.name)}</span></button></td>
      <td>${gpSpan(row.total ?? row.delta ?? 0, positiveNegativeClass(row.delta ?? row.total ?? 0))}</td>
      <td>${escapeHtml(row.currentTab || '')}</td>
    `;
    tbody.appendChild(tr);
  });
  tbody.querySelectorAll('[data-item-id]').forEach(btn => btn.addEventListener('click', event => {
    event.preventDefault();
    selectItem(Number(btn.dataset.itemId));
  }));
}

function renderMarket(snapshot) {
  const items = decorateItems(snapshot).filter(item => !item.placeholder).slice().sort((a, b) => b.geTotal - a.geTotal).slice(0, 15).map(item => ({
    canonicalItemId: item.canonicalItemId,
    name: item.name,
    iconPath: item.iconPath,
    total: item.geTotal,
    currentTab: item.effectiveTabName
  }));
  renderCompactRows(els.topHoldingsTable, items, 'Value', 'Tab');

  if (!items.length || !Object.keys(state.livePriceMap || {}).length) {
    els.marketEstimateBox.innerHTML = '<div class="muted">Live wiki estimate not loaded.</div>';
  } else {
    const estimate = decorateItems(snapshot).reduce((sum, item) => {
      if (item.placeholder) return sum;
      const wiki = resolveWikiEntry(item);
      const live = wiki?.live || null;
      const mid = live?.high && live?.low ? Math.round((live.high + live.low) / 2) : (live?.high || live?.low || item.geUnitPrice);
      return sum + (mid * Number(item.quantity || 0));
    }, 0);
    const diff = estimate - snapshot.totalGe;
    els.marketEstimateBox.innerHTML = `
      <div class="estimate-row"><span>Captured total</span><strong>${formatGp(snapshot.totalGe)}</strong></div>
      <div class="estimate-row"><span>Wiki midpoint estimate</span><strong>${formatGp(estimate)}</strong></div>
      <div class="estimate-row"><span>Difference</span><strong class="${positiveNegativeClass(diff)}">${formatGp(diff)}</strong></div>
      <div class="muted small">Estimate uses OSRS Wiki latest high/low midpoint per item when available.</div>
    `;
  }
  renderSelectedItemCharts();
}

function renderSelectedItemCharts() {
  const item = currentItemFromLatest();
  if (!item) {
    drawLineChart(els.itemValueChart, []);
    drawLineChart(els.itemQuantityChart, []);
    return;
  }

  const key = String(item.canonicalItemId || '');
  const cached = state.itemHistoryCache[key];
  if (!cached) {
    drawLineChart(els.itemValueChart, []);
    drawLineChart(els.itemQuantityChart, []);
    const token = ++state.itemHistoryRequestToken;
    loadItemHistory(item.canonicalItemId)
      .then(() => {
        const latestSnapshot = currentSnapshot();
        const currentItem = currentItemFromLatest();
        if (token !== state.itemHistoryRequestToken) return;
        if (latestSnapshot && currentItem && Number(currentItem.canonicalItemId) === Number(item.canonicalItemId)) {
          renderSelectedItemCharts();
        }
      })
      .catch(() => {
        if (token !== state.itemHistoryRequestToken) return;
        drawLineChart(els.itemValueChart, []);
        drawLineChart(els.itemQuantityChart, []);
      });
    return;
  }

  const series = itemSeriesFromSnapshots(item.canonicalItemId);
  drawLineChart(els.itemValueChart, series.map(point => ({
    label: point.label,
    tooltipLabel: point.tooltipLabel,
    value: point.value,
    snapshotIndex: point.snapshotIndex
  })), '#ffd166', {
    valueFormatter: formatGp,
    axisFormatter: formatCompactGp,
    xLabelFormatter: point => point.label
  });
  drawLineChart(els.itemQuantityChart, series.map(point => ({
    label: point.label,
    tooltipLabel: point.tooltipLabel,
    value: point.quantity,
    snapshotIndex: point.snapshotIndex
  })), '#7bd389', {
    valueFormatter: value => formatNumber(value),
    axisFormatter: formatCompactNumber,
    xLabelFormatter: point => point.label
  });
}

function switchSection(section) {
  state.activeSection = section;
  els.navButtons.forEach(btn => btn.classList.toggle('is-active', btn.dataset.section === section));
  els.sections.forEach(sectionEl => sectionEl.classList.toggle('is-active', sectionEl.id === `section-${section}`));
}

function selectItem(itemId) {
  state.selectedItemId = itemId;
  switchSection('browser');
  const snapshot = currentSnapshot();
  if (snapshot) renderBrowser(snapshot);
  renderSelectedItemCharts();
}

function render() {
  const snapshot = currentSnapshot();
  if (!snapshot) {
    els.profileTitle.textContent = '';
    els.statusText.textContent = 'No bank snapshots found yet. Open the bank in RuneLite and capture one.';
    els.summaryGrid.innerHTML = '';
    els.bankRecreation.innerHTML = '';
    els.holdingsTable.innerHTML = '';
    els.marketEstimateBox.innerHTML = '';
    els.browserItemDetails.innerHTML = '<div class="muted">No data available.</div>';
    drawLineChart(els.totalChart, []);
    drawLineChart(els.itemValueChart, []);
    drawLineChart(els.itemQuantityChart, []);
    return;
  }
  updateHeader(snapshot);
  renderSummary(snapshot, previousSnapshot());
  renderCompareControls(snapshot);
  renderBrowser(snapshot);
  renderMarket(snapshot);
  drawLineChart(els.totalChart, state.snapshots.map((s, index) => ({
    label: formatChartShortTimestamp(s.capturedAt),
    tooltipLabel: formatChartTooltipTimestamp(s.capturedAt),
    value: s.totalGe,
    snapshotIndex: index
  })), '#7cc5ff', {
    valueFormatter: formatGp,
    axisFormatter: formatCompactGp,
    xLabelFormatter: point => point.label,
    onPointClick: point => {
      if (!Number.isInteger(point?.snapshotIndex)) return;
      state.selectedIndex = point.snapshotIndex;
      els.snapshotSelect.value = String(point.snapshotIndex);
      state.activeTabIndex = 'all';
      ensureSnapshotLoaded(point.snapshotIndex)
        .then(() => loadLivePrices())
        .then(() => render())
        .catch(error => {
          els.statusText.textContent = `Failed to load snapshot: ${error}`;
        });
    }
  });
}

function populateSnapshotSelect() {
  els.snapshotSelect.innerHTML = '';
  if (!state.snapshots.length) {
    const option = document.createElement('option');
    option.textContent = 'No snapshots';
    els.snapshotSelect.appendChild(option);
    return;
  }
  state.snapshots.forEach((snapshot, index) => {
    const option = document.createElement('option');
    option.value = index;
    option.textContent = `${snapshot.day} ${new Date(snapshot.capturedAt).toLocaleTimeString()}`;
    els.snapshotSelect.appendChild(option);
  });
  state.selectedIndex = state.snapshots.length - 1;
  els.snapshotSelect.value = String(state.selectedIndex);
}

function buildMappingIndexes(entries) {
  const byId = {};
  const byName = {};
  const byLooseName = {};
  (entries || []).forEach(entry => {
    const id = Number(entry.id || 0);
    if (id > 0) byId[id] = entry;
    const exact = normalizeWikiName(entry.name || '');
    const loose = looseWikiName(entry.name || '');
    if (exact && !byName[exact]) byName[exact] = entry;
    if (loose && !byLooseName[loose]) byLooseName[loose] = entry;
  });
  return { byId, byName, byLooseName };
}

async function loadWikiMapping() {
  const payload = await fetchJson('/api/wiki-mapping');
  const idx = buildMappingIndexes(payload);
  state.wikiMappingById = idx.byId;
  state.wikiMappingByName = idx.byName;
  state.wikiMappingByLooseName = idx.byLooseName;
}

async function loadLivePrices() {
  const snapshot = currentSnapshot();
  if (!snapshot?.items?.length) {
    state.livePriceMap = {};
    return;
  }
  const idSet = new Set();
  snapshot.items.filter(item => !item.placeholder).forEach(item => {
    if (item.itemId > 0) idSet.add(item.itemId);
    if (item.canonicalItemId > 0) idSet.add(item.canonicalItemId);
  });
  const ids = [...idSet].join(',');
  if (!ids) {
    state.livePriceMap = {};
    return;
  }
  const payload = await fetchJson(`/api/wiki-prices?ids=${ids}`);
  state.livePriceMap = payload.data || {};
}

async function loadData() {
  const [status, summaries] = await Promise.all([
    fetchJson('/api/status'),
    fetchJson('/api/snapshots')
  ]);
  state.status = status;
  state.snapshots = Array.isArray(summaries) ? summaries : [];
  state.snapshotDetails = {};
  state.itemHistoryCache = {};
  state.itemHistoryPending = {};
  state.snapshots.sort((a, b) => String(a.capturedAt).localeCompare(String(b.capturedAt)));
  populateSnapshotSelect();
  await ensureSnapshotLoaded(state.selectedIndex);
  await Promise.all([loadWikiMapping(), loadLivePrices()]);
  render();
}

els.refreshBtn.addEventListener('click', loadData);
els.openDataDirBtn.addEventListener('click', async () => {
  try {
    await fetchJson('/api/open-data-dir', {
      method: 'POST',
      headers: { 'X-Requested-With': 'XMLHttpRequest' }
    });
  } catch (error) {
    els.statusText.textContent = `Failed to open data folder: ${error}`;
  }
});
els.snapshotSelect.addEventListener('change', async event => {
  state.selectedIndex = Number(event.target.value);
  state.activeTabIndex = 'all';
  await ensureSnapshotLoaded(state.selectedIndex);
  await loadLivePrices();
  render();
});
let searchTimer = null;
els.itemSearch.addEventListener('input', event => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    state.filterText = event.target.value || '';
    const snapshot = currentSnapshot();
    if (snapshot) renderBrowser(snapshot);
  }, 120);
});
els.navButtons.forEach(btn => btn.addEventListener('click', () => switchSection(btn.dataset.section)));

loadData().catch(error => {
  els.profileTitle.textContent = '';
  els.statusText.textContent = `Failed to load dashboard data: ${error}`;
});
