const config = window.POC_CONFIG;
const statsGrid = document.getElementById("stats-grid");
const runtimeConfig = document.getElementById("runtime-config");
const ingressRoutes = document.getElementById("ingress-routes");
const capabilities = document.getElementById("capabilities");
const messagingBindings = document.getElementById("messaging-bindings");
const requestLog = document.getElementById("request-log");
const refreshStatus = document.getElementById("refresh-status");
const autoRefreshToggle = document.getElementById("auto-refresh");

let refreshTimer = null;

autoRefreshToggle.addEventListener("change", () => {
    if (autoRefreshToggle.checked) {
        startAutoRefresh();
    } else {
        stopAutoRefresh();
    }
});

function startAutoRefresh() {
    stopAutoRefresh();
    refreshTimer = setInterval(refreshOverview, 3000);
}

function stopAutoRefresh() {
    if (refreshTimer) {
        clearInterval(refreshTimer);
        refreshTimer = null;
    }
}

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll("\"", "&quot;");
}

function statusClass(status) {
    if (!status) return "status-neutral";
    if (status === "DEDUP" || status === "IN_PROGRESS") return "status-neutral";
    if (status === "RATE_LIMIT") return "status-neutral";
    if (status.startsWith("2") || status === "SUCCESS") return "status-ok";
    if (status.startsWith("4") || status.startsWith("5") || status === "ERROR") return "status-error";
    return "status-neutral";
}

function planeLabel(plane) {
    const labels = {
        ingress: "Ingress",
        capability: "Capability",
        messaging: "Messaging"
    };
    return labels[plane] || plane;
}

function renderStats(stats) {
    const byPlane = stats.byPlane || {};
    statsGrid.innerHTML = `
        <div class="stat-card">
            <div class="stat-value">${stats.totalRecorded ?? 0}</div>
            <div class="stat-label">Всего записей</div>
        </div>
        <div class="stat-card">
            <div class="stat-value">${stats.successCount ?? 0}</div>
            <div class="stat-label">Успешных</div>
        </div>
        <div class="stat-card">
            <div class="stat-value">${stats.errorCount ?? 0}</div>
            <div class="stat-label">Ошибок</div>
        </div>
        <div class="stat-card">
            <div class="stat-value">${stats.dedupCount ?? 0}</div>
            <div class="stat-label">Dedup (Kafka)</div>
        </div>
        <div class="stat-card">
            <div class="stat-value">${stats.rateLimitedCount ?? 0}</div>
            <div class="stat-label">Rate limited</div>
        </div>
        <div class="stat-card">
            <div class="stat-value">${byPlane.ingress ?? 0}</div>
            <div class="stat-label">Ingress</div>
        </div>
        <div class="stat-card">
            <div class="stat-value">${byPlane.capability ?? 0}</div>
            <div class="stat-label">Capabilities</div>
        </div>
        <div class="stat-card">
            <div class="stat-value">${byPlane.messaging ?? 0}</div>
            <div class="stat-label">Messaging</div>
        </div>
        <div class="stat-card">
            <div class="stat-value">${Math.round(stats.avgDurationMs ?? 0)} ms</div>
            <div class="stat-label">Средняя latency</div>
        </div>
    `;
}

function renderTable(container, columns, rows, emptyText) {
    if (!rows || rows.length === 0) {
        container.innerHTML = `<p class="empty">${emptyText}</p>`;
        return;
    }

    const head = columns.map((col) => `<th>${escapeHtml(col.title)}</th>`).join("");
    const body = rows.map((row) => {
        const cells = columns.map((col) => `<td>${col.render(row)}</td>`).join("");
        return `<tr>${cells}</tr>`;
    }).join("");

    container.innerHTML = `
        <table>
            <thead><tr>${head}</tr></thead>
            <tbody>${body}</tbody>
        </table>
    `;
}

function renderConfig(configData) {
    runtimeConfig.innerHTML = `<pre>${escapeHtml(JSON.stringify(configData.runtime, null, 2))}</pre>`;

    renderTable(
        ingressRoutes,
        [
            { title: "Route ID", render: (r) => escapeHtml(r.routeId) },
            { title: "Method", render: (r) => `<code>${escapeHtml(r.method)}</code>` },
            { title: "Inbound", render: (r) => `<code>${escapeHtml(r.inboundPath)}</code>` },
            { title: "Target service", render: (r) => escapeHtml(r.targetService) },
            { title: "Target path / URL", render: (r) => `<code>${escapeHtml(r.targetPath)}</code>` },
            { title: "Note", render: (r) => escapeHtml(r.note) }
        ],
        configData.ingressRoutes,
        "Маршруты не найдены"
    );

    renderTable(
        capabilities,
        [
            { title: "Capability ID", render: (c) => escapeHtml(c.capabilityId) },
            { title: "Method", render: (c) => `<code>${escapeHtml(c.method)}</code>` },
            { title: "Path", render: (c) => `<code>${escapeHtml(c.path)}</code>` },
            { title: "Provider", render: (c) => escapeHtml(c.providerSet) },
            { title: "Note", render: (c) => escapeHtml(c.note) }
        ],
        configData.capabilities,
        "Capabilities не найдены"
    );

    renderTable(
        messagingBindings,
        [
            { title: "Binding ID", render: (b) => escapeHtml(b.bindingId) },
            { title: "Direction", render: (b) => escapeHtml(b.direction) },
            { title: "Topic alias", render: (b) => `<code>${escapeHtml(b.topicAlias)}</code>` },
            { title: "Consumer group", render: (b) => `<code>${escapeHtml(b.consumerGroup || "—")}</code>` },
            { title: "Targets", render: (b) => (b.targets || []).map((t) => `<code>${escapeHtml(t)}</code>`).join("<br>") || "—" }
        ],
        configData.messagingBindings,
        "Messaging bindings не найдены"
    );
}

function renderRequests(items) {
    const grouped = groupByCorrelation(items);
    const html = grouped.map((group) => {
        const header = group.correlationId
            ? `<div class="trace-header"><code>${escapeHtml(group.correlationId)}</code> · ${group.items.length} hop(s) · ${Math.round(group.totalMs)} ms</div>`
            : `<div class="trace-header trace-standalone">${escapeHtml(group.label)}</div>`;

        const rows = group.items.map((r) => `
            <tr class="trace-row trace-${escapeHtml(r.plane)}">
                <td>${escapeHtml(new Date(r.timestamp).toLocaleTimeString())}</td>
                <td><span class="pill">${escapeHtml(planeLabel(r.plane))}</span></td>
                <td><code>${escapeHtml(r.method)} ${escapeHtml(r.inboundPath)}</code></td>
                <td>
                    <div>${escapeHtml(r.targetService || "—")}</div>
                    <code class="small">${escapeHtml(r.targetUrl || "")}</code>
                </td>
                <td><span class="pill ${statusClass(r.status)}">${escapeHtml(r.status)}</span></td>
                <td>${escapeHtml(r.durationMs)}</td>
                <td><span class="small">${escapeHtml(r.detail || "")}</span></td>
            </tr>
        `).join("");

        return `
            <div class="trace-group">
                ${header}
                <table>
                    <thead>
                        <tr>
                            <th>Time</th>
                            <th>Plane</th>
                            <th>Inbound</th>
                            <th>Target</th>
                            <th>Status</th>
                            <th>ms</th>
                            <th>Detail</th>
                        </tr>
                    </thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>
        `;
    }).join("");

    requestLog.innerHTML = html || `<p class="empty">Запросов пока нет — выполните поиск offers или опубликуйте событие processor</p>`;
}

function groupByCorrelation(items) {
    const planeOrder = { ingress: 0, capability: 1, messaging: 2 };
    const groups = new Map();

    for (const item of items) {
        const key = item.correlationId || `standalone:${item.id}`;
        if (!groups.has(key)) {
            groups.set(key, {
                correlationId: item.correlationId,
                label: item.correlationId ? null : `${planeLabel(item.plane)} · ${item.inboundPath}`,
                items: [],
                latestTs: 0,
                totalMs: 0
            });
        }
        const group = groups.get(key);
        group.items.push(item);
        const ts = new Date(item.timestamp).getTime();
        if (ts > group.latestTs) {
            group.latestTs = ts;
        }
        group.totalMs += Number(item.durationMs || 0);
    }

    return Array.from(groups.values())
        .map((group) => {
            group.items.sort((a, b) => {
                const byPlane = (planeOrder[a.plane] ?? 9) - (planeOrder[b.plane] ?? 9);
                if (byPlane !== 0) {
                    return byPlane;
                }
                return new Date(a.timestamp) - new Date(b.timestamp);
            });
            return group;
        })
        .sort((a, b) => b.latestTs - a.latestTs);
}

async function refreshResilience() {
    const resilienceStats = document.getElementById("resilience-stats");
    const testProcessors = document.getElementById("test-processors");
    if (!resilienceStats) {
        return;
    }
    try {
        const [statsResponse, processorsResponse] = await Promise.all([
            fetch(`${config.gatewayUrl}/internal/admin/test/resilience/stats`),
            fetch(`${config.gatewayUrl}/internal/admin/test/processors`)
        ]);
        if (statsResponse.ok) {
            const stats = await statsResponse.json();
            resilienceStats.textContent = JSON.stringify(stats, null, 2);
        }
        if (processorsResponse.ok) {
            const processors = await processorsResponse.json();
            testProcessors.innerHTML = `
                <h3 class="subheading">Registered test processors</h3>
                <table>
                    <thead><tr><th>ID</th><th>Binding</th><th>Topic</th><th>Demo URL</th></tr></thead>
                    <tbody>
                        ${processors.map((p) => `
                            <tr>
                                <td><code>${escapeHtml(p.processorId)}</code></td>
                                <td><code>${escapeHtml(p.bindingId)}</code></td>
                                <td><code>${escapeHtml(p.physicalTopic)}</code></td>
                                <td><code>${escapeHtml(p.demoBaseUrl)}</code></td>
                            </tr>
                        `).join("")}
                    </tbody>
                </table>
            `;
        }
    } catch (error) {
        resilienceStats.textContent = `Error: ${error.message}`;
    }
}

async function runScenario(path, scenarioStatus) {
    scenarioStatus.textContent = "Running...";
    try {
        const response = await fetch(`${config.gatewayUrl}${path}`, { method: "POST" });
        const data = await response.json();
        if (!response.ok) {
            throw new Error(JSON.stringify(data));
        }
        scenarioStatus.textContent = `OK: ${data.scenario} · ${JSON.stringify(data)}`;
        await refreshOverview();
        await refreshResilience();
    } catch (error) {
        scenarioStatus.textContent = `Error: ${error.message}`;
    }
}

const scenarioStatus = document.getElementById("scenario-status");
document.getElementById("btn-scenario-dedup")?.addEventListener("click", () =>
    runScenario("/internal/admin/test/scenarios/dedup", scenarioStatus));
document.getElementById("btn-scenario-retry")?.addEventListener("click", () =>
    runScenario("/internal/admin/test/scenarios/retry?failCount=2", scenarioStatus));
document.getElementById("btn-scenario-rate")?.addEventListener("click", () =>
    runScenario("/internal/admin/test/scenarios/rate-limit?burstCount=8", scenarioStatus));

async function refreshOverview() {
    refreshStatus.textContent = "Обновление...";
    try {
        const response = await fetch(`${config.gatewayUrl}/internal/admin/overview?limit=100`);
        if (!response.ok) {
            throw new Error(`Gateway returned ${response.status}`);
        }
        const data = await response.json();
        renderStats(data.stats);
        renderConfig(data.config);
        renderRequests(data.recentRequests);
        await refreshResilience();
        refreshStatus.textContent = `Обновлено: ${new Date().toLocaleTimeString()}`;
    } catch (error) {
        refreshStatus.textContent = `Ошибка: ${error.message}`;
    }
}

refreshOverview();
startAutoRefresh();
