/* =============================================================================
 * Content Recommendation Feedback Dashboard - dashboard client
 * Plain ES2020, no bundler: keeps the deployable artefact a single JAR.
 * ========================================================================== */
(() => {
    'use strict';

    const API = '/api/v1';

    const STATUSES = ['RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED', 'QUARANTINED'];
    const TYPES = ['IMPRESSION', 'CLICK', 'ADD_TO_CART', 'PURCHASE', 'DISMISS', 'RATING'];
    const SOURCES = ['COLLABORATIVE_FILTER', 'CONTENT_BASED', 'TRENDING', 'EDITORIAL', 'HYBRID'];
    const SURFACES = ['HOME_FEED', 'PRODUCT_PAGE', 'SEARCH_RESULTS', 'EMAIL', 'PUSH_NOTIFICATION'];

    const STATUS_COLORS = {
        PROCESSED: '#2dd4bf',
        RECEIVED: '#93c5fd',
        PROCESSING: '#fbbf24',
        FAILED: '#f87171',
        QUARANTINED: '#e879f9'
    };

    /** Single configured operator identity - avoids browser prompts in automated runs. */
    const OPERATOR = { owner: 'duty-engineer@crfd.example.com', actor: 'duty-engineer' };

    const state = {
        page: 0,
        size: 25,
        sort: 'createdAt,desc',
        filters: {},
        granularity: 'hour',
        alertStatus: 'OPEN',
        selectedEventId: null,
        timer: null
    };

    // ------------------------------------------------------------------ helpers

    const $ = (id) => document.getElementById(id);
    const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => (
        { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    const num = (v) => (v === null || v === undefined ? '0' : Number(v).toLocaleString());
    const pct = (v) => `${((Number(v) || 0) * 100).toFixed(1)}%`;
    const ms = (v) => (v === null || v === undefined ? '–' : `${Math.round(v)} ms`);
    const when = (iso) => (iso ? new Date(iso).toLocaleString() : '–');
    const timeOnly = (iso) => (iso ? new Date(iso).toLocaleTimeString() : '–');
    const pretty = (v) => String(v ?? '–').replace(/_/g, ' ').toLowerCase();

    async function api(path, options = {}) {
        const { headers, body, ...rest } = options;
        const response = await fetch(path, {
            ...rest,
            headers: { 'Content-Type': 'application/json', ...(headers || {}) },
            body: body === undefined ? undefined : JSON.stringify(body)
        });
        const text = await response.text();
        let payload = null;
        if (text) {
            try {
                payload = JSON.parse(text);
            } catch (e) {
                payload = text;
            }
        }
        if (!response.ok) {
            const message = (payload && payload.message) ? payload.message : `${response.status} ${response.statusText}`;
            const error = new Error(message);
            error.status = response.status;
            error.body = payload;
            throw error;
        }
        return payload;
    }

    function toast(message, kind = '') {
        const host = $('toast-container');
        const node = document.createElement('div');
        node.className = `toast ${kind}`;
        node.textContent = message;
        host.appendChild(node);
        setTimeout(() => node.remove(), 4200);
    }

    function fillSelect(select, values, placeholder) {
        if (!select) {
            return;
        }
        const current = select.value;
        select.innerHTML = '';
        if (placeholder !== undefined) {
            select.appendChild(new Option(placeholder, ''));
        }
        values.forEach((value) => select.appendChild(new Option(pretty(value), value)));
        select.value = current;
    }

    // ------------------------------------------------------------------ bootstrap

    function bootstrap() {
        fillSelect($('filter-status'), STATUSES, 'All statuses');
        fillSelect($('filter-type'), TYPES, 'All types');
        fillSelect($('filter-source'), SOURCES, 'All recommenders');
        fillSelect($('filter-surface'), SURFACES, 'All surfaces');
        fillSelect($('field-eventType'), TYPES);
        fillSelect($('field-source'), SOURCES);
        fillSelect($('field-surface'), SURFACES);
        fillSelect($('detail-new-status'), STATUSES);
        $('field-eventType').value = 'IMPRESSION';
        $('field-source').value = 'HYBRID';
        $('field-surface').value = 'HOME_FEED';

        wireEvents();
        refreshAll();
        state.timer = setInterval(() => {
            if ($('auto-refresh').checked) {
                refreshAll();
            }
        }, 15000);
    }

    function wireEvents() {
        $('filter-form').addEventListener('submit', (e) => {
            e.preventDefault();
            state.page = 0;
            readFilters();
            loadEvents();
        });

        $('btn-reset-filters').addEventListener('click', () => {
            $('filter-form').reset();
            state.filters = {};
            state.page = 0;
            state.sort = 'createdAt,desc';
            loadEvents();
        });

        $('btn-refresh').addEventListener('click', refreshAll);
        $('btn-prev').addEventListener('click', () => { if (state.page > 0) { state.page -= 1; loadEvents(); } });
        $('btn-next').addEventListener('click', () => { state.page += 1; loadEvents(); });
        $('btn-export').addEventListener('click', () => {
            window.open(`${API}/events/export?${buildQuery({ export: true })}`, '_blank');
        });

        $('chart-granularity').addEventListener('change', (e) => {
            state.granularity = e.target.value;
            loadTimeseries();
        });

        $('alert-status-filter').addEventListener('change', (e) => {
            state.alertStatus = e.target.value;
            loadAlerts();
        });

        document.querySelectorAll('.data-table thead th[data-sort]').forEach((th) => {
            th.addEventListener('click', () => {
                const field = th.dataset.sort;
                const [currentField, currentDir] = state.sort.split(',');
                const dir = currentField === field && currentDir === 'desc' ? 'asc' : 'desc';
                state.sort = `${field},${dir}`;
                state.page = 0;
                loadEvents();
            });
        });

        $('btn-generate-traffic').addEventListener('click', async () => {
            const button = $('btn-generate-traffic');
            button.disabled = true;
            try {
                const result = await api(`${API}/dev/simulate?count=40`, { method: 'POST' });
                toast(`Generated ${result.generated} feedback events`, '');
                setTimeout(refreshAll, 1400);
            } catch (error) {
                toast(`Traffic generation failed: ${error.message}`, 'error');
            } finally {
                button.disabled = false;
            }
        });

        $('btn-add-event').addEventListener('click', () => {
            $('event-form-message').textContent = '';
            $('event-form-message').className = 'form-message';
            $('event-modal').hidden = false;
            $('field-itemId').focus();
        });
        document.querySelectorAll('[data-close-modal]').forEach((node) => {
            node.addEventListener('click', () => { $('event-modal').hidden = true; });
        });
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                $('event-modal').hidden = true;
                closeDrawer();
            }
        });

        $('event-form').addEventListener('submit', submitEvent);

        $('btn-submit-event').dataset.defaultLabel = $('btn-submit-event').textContent;

        $('detail-close').addEventListener('click', closeDrawer);
        $('btn-change-status').addEventListener('click', changeStatus);
        $('btn-reprocess').addEventListener('click', reprocess);

        // Delegated actions for the alert lists (dashboard panel + drill-down)
        document.addEventListener('click', async (event) => {
            const target = event.target.closest('[data-action]');
            if (!target) {
                return;
            }
            const action = target.dataset.action;
            const ref = target.dataset.alertRef;
            if (!ref) {
                return;
            }
            target.disabled = true;
            try {
                const verb = action === 'ack' ? 'acknowledge' : 'resolve';
                const note = action === 'ack'
                    ? 'Acknowledged from the dashboard exception view.'
                    : 'Resolved from the dashboard exception view.';
                await api(`${API}/alerts/${encodeURIComponent(ref)}/${verb}`, {
                    method: 'POST',
                    body: { owner: OPERATOR.owner, note }
                });
                toast(`Alert ${ref} ${verb}d`, '');
                loadAlerts();
                if (state.selectedEventId) {
                    openDrawer(state.selectedEventId);
                }
            } catch (error) {
                toast(`${action} failed: ${error.message}`, 'error');
            } finally {
                target.disabled = false;
            }
        });
    }

    // ------------------------------------------------------------------ data loading

    async function refreshAll() {
        await Promise.allSettled([
            loadSummary(),
            loadTimeseries(),
            loadEvents(),
            loadAlerts(),
            loadHealth()
        ]);
        $('last-refreshed').textContent = `Last refreshed ${new Date().toLocaleTimeString()} · window ${timeOnlyLabel()}`;
    }

    function timeOnlyLabel() {
        return `${state.granularity === 'day' ? '7 days' : '24 hours'}`;
    }

    async function loadHealth() {
        try {
            const response = await fetch(`${API}/health`);
            const body = await response.json();
            const pill = $('health-pill');
            pill.dataset.status = body.status;
            $('health-text').textContent = `${body.status} · ${body.database}`;
            $('env-badge').textContent = `env: ${body.environment}`;
            pill.title = `version ${body.version} · uptime ${body.uptimeSeconds}s · open alerts ${body.openAlerts}`;
        } catch (error) {
            $('health-pill').dataset.status = 'DOWN';
            $('health-text').textContent = 'unreachable';
        }
    }

    async function loadSummary() {
        try {
            const summary = await api(`${API}/metrics/summary`);
            renderKpis(summary);
            renderDonut(summary.byStatus || []);
            const total = (summary.bySource || []).reduce((acc, m) => acc + m.count, 0);
            renderBars('dist-source', summary.bySource || [], total);
            renderBars('dist-surface', summary.bySurface || [], total);
            renderBars('dist-type', summary.byEventType || [], (summary.byEventType || []).reduce((a, m) => a + m.count, 0));
        } catch (error) {
            toast(`Summary unavailable: ${error.message}`, 'error');
        }
    }

    async function loadTimeseries() {
        try {
            const points = await api(`${API}/metrics/timeseries?granularity=${state.granularity}`);
            renderTimeseries(points);
        } catch (error) {
            toast(`Chart unavailable: ${error.message}`, 'error');
        }
    }

    function readFilters() {
        const toIso = (value) => (value ? new Date(value).toISOString() : '');
        state.filters = {
            q: $('search-input').value.trim(),
            status: $('filter-status').value,
            eventType: $('filter-type').value,
            source: $('filter-source').value,
            surface: $('filter-surface').value,
            from: toIso($('filter-from').value),
            to: toIso($('filter-to').value),
            onlyWithAlerts: $('filter-alerts-only').checked ? 'true' : ''
        };
    }

    function buildQuery(extra = {}) {
        const params = new URLSearchParams();
        if (!extra.export) {
            params.set('page', state.page);
            params.set('size', state.size);
            params.set('sort', state.sort);
        }
        Object.entries(state.filters).forEach(([key, value]) => {
            if (value !== '' && value !== null && value !== undefined && value !== 'false') {
                params.set(key, value);
            }
        });
        return params.toString();
    }

    async function loadEvents() {
        const tbody = $('events-body');
        try {
            const page = await api(`${API}/events?${buildQuery()}`);
            renderTable(page);
        } catch (error) {
            tbody.innerHTML = `<tr class="empty"><td colspan="9">Failed to load events: ${esc(error.message)}</td></tr>`;
        }
    }

    async function loadAlerts() {
        try {
            const params = new URLSearchParams({ size: '12' });
            if (state.alertStatus) {
                params.set('status', state.alertStatus);
            }
            const page = await api(`${API}/alerts?${params.toString()}`);
            renderAlerts($('alerts-list'), page.content, false);
        } catch (error) {
            $('alerts-list').innerHTML = `<li class="empty">Failed to load alerts: ${esc(error.message)}</li>`;
        }
    }

    // ------------------------------------------------------------------ rendering

    function renderKpis(summary) {
        setKpi('kpi-total', num(summary.totalEvents), `last ${summary.windowHours}h · ${num(summary.impressions)} impressions`);
        setKpi('kpi-processed', num(summary.processedEvents), `success rate ${pct(summary.successRate)}`);
        setKpi('kpi-failed', num(summary.failedEvents + summary.quarantinedEvents),
            `failure rate ${pct(summary.failureRate)}`);
        setKpi('kpi-open-alerts', num(summary.openAlerts), `${num(summary.criticalOpenAlerts)} critical`);
        setKpi('kpi-latency', summary.averageLatencyMs === null ? '–' : Math.round(summary.averageLatencyMs), 'ms (pipeline)');
        setKpi('kpi-ctr', pct(summary.clickThroughRate), `${num(summary.clicks)} clicks / ${num(summary.impressions)} impressions`);
    }

    function setKpi(id, value, sub) {
        const card = $(id);
        if (!card) {
            return;
        }
        card.querySelector('[data-value]').textContent = value;
        const subNode = card.querySelector('[data-sub]');
        if (subNode && sub !== undefined) {
            subNode.textContent = sub;
        }
    }

    function renderBars(containerId, metrics, total) {
        const host = $(containerId);
        if (!metrics || metrics.length === 0) {
            host.innerHTML = '<div class="bar-row"><span>no data</span><div class="bar-track"></div><b>0</b></div>';
            return;
        }
        const max = Math.max(...metrics.map((m) => m.count), 1);
        host.innerHTML = metrics
            .slice()
            .sort((a, b) => b.count - a.count)
            .map((m) => `
                <div class="bar-row" title="${esc(m.key)}: ${num(m.count)} of ${num(total)}">
                    <span>${esc(pretty(m.key))}</span>
                    <div class="bar-track"><div class="bar-fill" style="width:${(m.count / max * 100).toFixed(1)}%"></div></div>
                    <b>${num(m.count)}</b>
                </div>`)
            .join('');
    }

    function renderDonut(metrics) {
        const host = $('status-donut');
        const legend = $('status-legend');
        const total = metrics.reduce((acc, m) => acc + m.count, 0);
        if (!total) {
            host.innerHTML = '<svg viewBox="0 0 42 42"><circle cx="21" cy="21" r="15.9" fill="none" stroke="#223350" stroke-width="6"/></svg>';
            legend.innerHTML = '<li>No events in window</li>';
            return;
        }
        const radius = 15.9155;
        const circumference = 2 * Math.PI * radius;
        let offset = 0;
        const segments = metrics
            .slice()
            .sort((a, b) => b.count - a.count)
            .map((m) => {
                const fraction = m.count / total;
                const length = fraction * circumference;
                const segment = `
                    <circle cx="21" cy="21" r="${radius}" fill="none"
                            stroke="${STATUS_COLORS[m.key] || '#64748b'}"
                            stroke-width="6"
                            stroke-dasharray="${length.toFixed(3)} ${(circumference - length).toFixed(3)}"
                            stroke-dashoffset="${(-offset).toFixed(3)}"></circle>`;
                offset += length;
                return segment;
            })
            .join('');
        host.innerHTML = `
            <svg viewBox="0 0 42 42" role="img" aria-label="Status distribution">
                <circle cx="21" cy="21" r="${radius}" fill="none" stroke="#1d2b45" stroke-width="6"></circle>
                ${segments}
                <text x="21" y="20.4" text-anchor="middle" fill="#e6edf7" font-size="6" font-weight="700">${num(total)}</text>
                <text x="21" y="25.4" text-anchor="middle" fill="#6b809d" font-size="2.9">events</text>
            </svg>`;
        legend.innerHTML = metrics
            .slice()
            .sort((a, b) => b.count - a.count)
            .map((m) => `<li><span><i style="background:${STATUS_COLORS[m.key] || '#64748b'}"></i>${esc(pretty(m.key))}</span><b>${num(m.count)}</b></li>`)
            .join('');
    }

    function renderTimeseries(points) {
        const host = $('timeseries-chart');
        if (!points || points.length === 0) {
            host.innerHTML = '<div class="empty" style="color:#6b809d;padding:60px 0;text-align:center">No data in window</div>';
            return;
        }
        const width = host.clientWidth || 800;
        const height = 210;
        const padLeft = 42;
        const padBottom = 26;
        const padTop = 12;
        const plotW = width - padLeft - 12;
        const plotH = height - padTop - padBottom;

        const maxTotal = Math.max(...points.map((p) => p.total), 1);
        const maxAlerts = Math.max(...points.map((p) => p.alerts), 1);
        const slot = plotW / points.length;
        const barW = Math.max(4, Math.min(20, slot * 0.5));
        const alertW = Math.max(2, barW * 0.28);

        const y = (value, max) => padTop + plotH - (value / max) * plotH;

        let bars = '';
        let labels = '';
        let alerts = '';
        const labelStep = Math.ceil(points.length / 12);

        points.forEach((point, index) => {
            const cx = padLeft + slot * index + slot / 2;
            const x = cx - barW / 2;

            const totalTop = y(point.total, maxTotal);
            bars += `<rect x="${x.toFixed(1)}" y="${totalTop.toFixed(1)}" width="${barW.toFixed(1)}"
                        height="${(padTop + plotH - totalTop).toFixed(1)}" rx="2" fill="#334155">
                        <title>${esc(point.bucket)} — total ${point.total}</title></rect>`;

            const processedTop = y(point.processed, maxTotal);
            if (point.processed > 0) {
                bars += `<rect x="${x.toFixed(1)}" y="${processedTop.toFixed(1)}" width="${barW.toFixed(1)}"
                            height="${(padTop + plotH - processedTop).toFixed(1)}" rx="2" fill="#2dd4bf">
                            <title>${esc(point.bucket)} — processed ${point.processed}</title></rect>`;
            }
            if (point.failed > 0) {
                const failedTop = y(point.processed + point.failed, maxTotal);
                bars += `<rect x="${x.toFixed(1)}" y="${failedTop.toFixed(1)}" width="${barW.toFixed(1)}"
                            height="${Math.max(1.5, processedTop - failedTop).toFixed(1)}" rx="2" fill="#f87171">
                            <title>${esc(point.bucket)} — failed ${point.failed}</title></rect>`;
            }
            if (point.alerts > 0) {
                const alertTop = y(point.alerts, maxAlerts);
                alerts += `<rect x="${(cx + barW / 2 + 2).toFixed(1)}" y="${alertTop.toFixed(1)}" width="${alertW.toFixed(1)}"
                             height="${Math.max(1.5, padTop + plotH - alertTop).toFixed(1)}" rx="1.5" fill="#fbbf24">
                             <title>${esc(point.bucket)} — ${point.alerts} alerts</title></rect>`;
            }
            if (index % labelStep === 0) {
                const label = state.granularity === 'day'
                    ? point.bucket.slice(5)
                    : point.bucket.slice(11);
                labels += `<text x="${cx.toFixed(1)}" y="${height - 8}" text-anchor="middle"
                             fill="#6b809d" font-size="9.5">${esc(label)}</text>`;
            }
        });

        const grid = [0, 0.25, 0.5, 0.75, 1].map((fraction) => {
            const gy = padTop + plotH - fraction * plotH;
            return `<line x1="${padLeft}" y1="${gy.toFixed(1)}" x2="${width - 12}" y2="${gy.toFixed(1)}"
                        stroke="#1b2942" stroke-width="1"/>
                    <text x="${padLeft - 7}" y="${(gy + 3).toFixed(1)}" text-anchor="end"
                          fill="#6b809d" font-size="9.5">${Math.round(maxTotal * fraction)}</text>`;
        }).join('');

        host.innerHTML = `<svg viewBox="0 0 ${width} ${height}" width="100%" height="${height}" role="img"
                               aria-label="Feedback volume over time">${grid}${bars}${alerts}${labels}</svg>`;
    }

    function renderTable(page) {
        const tbody = $('events-body');
        if (!page.content || page.content.length === 0) {
            tbody.innerHTML = '<tr class="empty"><td colspan="9">No events match the current filters</td></tr>';
        } else {
            tbody.innerHTML = page.content.map((event) => `
                <tr data-event-id="${esc(event.eventId)}" class="${event.eventId === state.selectedEventId ? 'selected' : ''}">
                    <td>${esc(when(event.createdAt))}</td>
                    <td class="mono">${esc(event.eventId)}</td>
                    <td>${esc(event.userId)}</td>
                    <td class="truncate" title="${esc(event.itemId)} · ${esc(event.itemTitle)}">
                        <div>${esc(event.itemId)}</div>
                        <div class="mono truncate">${esc(event.itemTitle ?? '')}</div>
                    </td>
                    <td>${esc(pretty(event.eventType))}</td>
                    <td>${esc(pretty(event.source))}</td>
                    <td class="num ${scoreClass(event.score)}">${event.score === null || event.score === undefined ? '–' : Number(event.score).toFixed(2)}</td>
                    <td><span class="chip chip-${esc(event.status)}">${esc(event.status)}</span></td>
                    <td class="num">${esc(ms(event.latencyMs))}</td>
                </tr>`).join('');

            tbody.querySelectorAll('tr[data-event-id]').forEach((row) => {
                row.addEventListener('click', () => openDrawer(row.dataset.eventId));
            });
        }

        const from = page.totalElements === 0 ? 0 : page.page * page.size + 1;
        const to = page.page * page.size + page.content.length;
        $('pagination-info').textContent =
            `${num(from)}–${num(to)} of ${num(page.totalElements)} · page ${page.page + 1} of ${Math.max(1, page.totalPages)}`;
        $('btn-prev').disabled = page.first;
        $('btn-next').disabled = page.last;
    }

    function scoreClass(score) {
        if (score === null || score === undefined) {
            return '';
        }
        if (score < 0.30) {
            return 'score-low';
        }
        return score < 0.60 ? 'score-mid' : 'score-ok';
    }

    function renderAlerts(host, alerts, compact) {
        if (!alerts || alerts.length === 0) {
            host.innerHTML = `<li class="empty">No ${state.alertStatus ? state.alertStatus.toLowerCase() : ''} exceptions</li>`;
            return;
        }
        host.innerHTML = alerts.map((alert) => `
            <li class="alert-item" data-severity="${esc(alert.severity)}" data-alert-ref="${esc(alert.alertRef)}">
                <div class="alert-top">
                    <span class="sev sev-${esc(alert.severity)}">${esc(alert.severity)}</span>
                    <span class="alert-code">${esc(alert.code)}</span>
                    <span class="chip chip-${esc(alert.status)}">${esc(alert.status)}</span>
                    <span class="alert-ref">${esc(alert.alertRef)}</span>
                </div>
                <div class="alert-message">${esc(alert.message)}</div>
                <div class="alert-meta">
                    ${alert.eventRef ? `<span>event ${esc(alert.eventRef)}</span>` : ''}
                    <span>${esc(when(alert.createdAt))}</span>
                    ${alert.owner ? `<span>owner ${esc(alert.owner)}</span>` : ''}
                </div>
                ${compact ? '' : `
                <div class="alert-actions">
                    ${alert.status === 'OPEN' ? `<button class="btn btn-ghost btn-sm" data-action="ack" data-alert-ref="${esc(alert.alertRef)}">Acknowledge</button>` : ''}
                    ${alert.status !== 'RESOLVED' ? `<button class="btn btn-ghost btn-sm" data-action="resolve" data-alert-ref="${esc(alert.alertRef)}">Resolve</button>` : ''}
                </div>`}
            </li>`).join('');
    }

    // ------------------------------------------------------------------ drill-down drawer

    async function openDrawer(eventId) {
        try {
            const detail = await api(`${API}/events/${encodeURIComponent(eventId)}`);
            state.selectedEventId = eventId;
            const event = detail.event;

            $('detail-event-id').textContent = event.eventId;
            $('detail-status-badges').innerHTML = `
                <span class="chip chip-${esc(event.status)}">${esc(event.status)}</span>
                <span class="chip">${esc(pretty(event.eventType))}</span>
                <span class="chip">${esc(pretty(event.source))}</span>
                <span class="chip">${esc(pretty(event.surface))}</span>
                ${detail.alerts.length ? `<span class="sev sev-${esc(detail.alerts[0].severity)}">${detail.alerts.length} alert(s)</span>` : ''}`;

            const fields = [
                ['Correlation id', event.correlationId],
                ['User', event.userId],
                ['Item', `${event.itemId ?? '–'} · ${event.itemTitle ?? ''}`],
                ['Confidence score', event.score === null || event.score === undefined ? '–' : Number(event.score).toFixed(2)],
                ['Rating', event.rating ?? '–'],
                ['Latency', ms(event.latencyMs)],
                ['Created at', when(event.createdAt)],
                ['Processed at', when(event.processedAt)],
                ['Client timestamp', when(event.clientTimestamp)],
                ['Outcome message', event.message],
                ['User comment', event.comment]
            ];
            $('detail-fields').innerHTML = fields
                .map(([key, value]) => `<dt>${esc(key)}</dt><dd>${esc(value ?? '–')}</dd>`)
                .join('');

            $('detail-timeline').innerHTML = detail.timeline.length
                ? detail.timeline.map((step) => `
                    <li>
                        <div class="tl-head">
                            <span class="chip chip-${esc(step.toStatus)}">${esc(step.fromStatus)} → ${esc(step.toStatus)}</span>
                            <span class="tl-meta">${esc(when(step.at))}</span>
                            <span class="tl-meta">${esc(step.actor)}</span>
                        </div>
                        <div class="tl-note">${esc(step.note ?? '')}</div>
                    </li>`).join('')
                : '<li class="tl-note">No transitions recorded</li>';

            renderAlerts($('detail-alerts'), detail.alerts, true);
            $('detail-new-status').value = event.status;
            $('detail-note').value = '';
            $('detail-drawer').hidden = false;
            $('btn-reprocess').disabled = !(event.status === 'FAILED' || event.status === 'QUARANTINED');

            document.querySelectorAll('#events-body tr').forEach((row) => {
                row.classList.toggle('selected', row.dataset.eventId === eventId);
            });
        } catch (error) {
            toast(`Drill-down failed: ${error.message}`, 'error');
        }
    }

    function closeDrawer() {
        $('detail-drawer').hidden = true;
        state.selectedEventId = null;
        document.querySelectorAll('#events-body tr').forEach((row) => row.classList.remove('selected'));
    }

    async function changeStatus() {
        const eventId = state.selectedEventId;
        if (!eventId) {
            return;
        }
        try {
            await api(`${API}/events/${encodeURIComponent(eventId)}/status`, {
                method: 'PATCH',
                body: {
                    status: $('detail-new-status').value,
                    note: $('detail-note').value || 'Manual status change from the drill-down view',
                    actor: OPERATOR.actor
                }
            });
            toast(`Event ${eventId} moved to ${$('detail-new-status').value}`, '');
            await openDrawer(eventId);
            refreshAll();
        } catch (error) {
            toast(`Status change failed: ${error.message}`, 'error');
        }
    }

    async function reprocess() {
        const eventId = state.selectedEventId;
        if (!eventId) {
            return;
        }
        try {
            await api(`${API}/events/${encodeURIComponent(eventId)}/reprocess`, { method: 'POST' });
            toast(`Event ${eventId} requeued through the pipeline`, '');
            setTimeout(() => openDrawer(eventId), 700);
            setTimeout(refreshAll, 900);
        } catch (error) {
            toast(`Reprocess failed: ${error.message}`, 'error');
        }
    }

    // ------------------------------------------------------------------ event entry

    async function submitEvent(submitEvent_) {
        submitEvent_.preventDefault();
        const button = $('btn-submit-event');
        const message = $('event-form-message');
        const payload = {
            userId: $('field-userId').value.trim(),
            itemId: $('field-itemId').value.trim(),
            itemTitle: $('field-itemTitle').value.trim() || null,
            eventType: $('field-eventType').value,
            source: $('field-source').value,
            surface: $('field-surface').value,
            score: $('field-score').value === '' ? null : Number($('field-score').value),
            rating: $('field-rating').value === '' ? null : Number($('field-rating').value)
        };

        button.disabled = true;
        message.className = 'form-message';
        message.textContent = 'Submitting…';
        try {
            const created = await api(`${API}/events`, { method: 'POST', body: payload });
            message.className = 'form-message success';
            message.textContent = `Accepted · ${created.eventId} (${created.status})`;
            toast(`Event ${created.eventId} accepted`, '');
            $('field-itemId').value = '';
            $('field-itemTitle').value = '';
            setTimeout(() => {
                $('event-modal').hidden = true;
                $('search-input').value = payload.itemId;
                readFilters();
                state.page = 0;
                loadEvents();
                refreshAll();
            }, 700);
        } catch (error) {
            message.className = 'form-message error';
            const details = error.body && error.body.fieldErrors && error.body.fieldErrors.length
                ? ` (${error.body.fieldErrors.map((f) => `${f.field}: ${f.detail}`).join('; ')})`
                : '';
            message.textContent = `${error.message}${details}`;
            toast(`Event rejected: ${error.message}`, 'error');
        } finally {
            button.disabled = false;
        }
    }

    document.addEventListener('DOMContentLoaded', bootstrap);
})();
