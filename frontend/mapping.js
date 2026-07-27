const config = window.POC_CONFIG;

const mappingStatus = document.getElementById("mapping-status");
const mappingRegistry = document.getElementById("mapping-registry");

let expandedMappingId = null;

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll("\"", "&quot;");
}

function countEntries(map) {
    return Object.keys(map || {}).length;
}

function renderDetailTable(title, columns, rows, emptyText) {
    if (!rows || rows.length === 0) {
        return `
            <div class="mapping-detail-block">
                <h4>${escapeHtml(title)}</h4>
                <p class="empty">${escapeHtml(emptyText)}</p>
            </div>
        `;
    }

    const head = columns.map((col) => `<th>${escapeHtml(col.title)}</th>`).join("");
    const body = rows.map((row) => {
        const cells = columns.map((col) => `<td>${col.render(row)}</td>`).join("");
        return `<tr>${cells}</tr>`;
    }).join("");

    return `
        <div class="mapping-detail-block">
            <h4>${escapeHtml(title)}</h4>
            <table>
                <thead><tr>${head}</tr></thead>
                <tbody>${body}</tbody>
            </table>
        </div>
    `;
}

function renderMappingDetail(mapping) {
    const headerRows = Object.entries(mapping.headerMapping || {}).map(([source, target]) => ({ source, target }));
    const eventTypeRows = Object.entries(mapping.eventTypeMapping || {}).map(([source, target]) => ({ source, target }));

    return `
        <div class="mapping-detail">
            <p class="mapping-detail-description">${escapeHtml(mapping.description || "—")}</p>
            <div class="mapping-detail-meta">
                <span>Config: <code>${escapeHtml(mapping.configFile)}</code></span>
                ${mapping.bindingId ? `<span>Binding: <code>${escapeHtml(mapping.bindingId)}</code></span>` : ""}
                ${mapping.topicAlias ? `<span>Topic: <code>${escapeHtml(mapping.topicAlias)}</code></span>` : ""}
                ${mapping.consumerGroup ? `<span>Consumer group: <code>${escapeHtml(mapping.consumerGroup)}</code></span>` : ""}
            </div>
            ${renderDetailTable(
                "Headers",
                [
                    { title: "Source", render: (r) => `<code>${escapeHtml(r.source)}</code>` },
                    { title: "", render: () => "→" },
                    { title: "Target", render: (r) => `<code>${escapeHtml(r.target)}</code>` }
                ],
                headerRows,
                "Header mapping не задан"
            )}
            ${renderDetailTable(
                "Event types",
                [
                    { title: "Source", render: (r) => `<code>${escapeHtml(r.source)}</code>` },
                    { title: "", render: () => "→" },
                    { title: "Target", render: (r) => `<code>${escapeHtml(r.target)}</code>` }
                ],
                eventTypeRows,
                "Event type mapping не задан"
            )}
            ${renderDetailTable(
                "Body fields",
                [
                    { title: "Source field", render: (r) => `<code>${escapeHtml(r.sourceField)}</code>` },
                    { title: "", render: () => "→" },
                    { title: "Target field", render: (r) => `<code>${escapeHtml(r.targetField)}</code>` },
                    { title: "Transform", render: (r) => escapeHtml(r.transform || "—") }
                ],
                mapping.bodyMappings || [],
                "Body field mapping не задан"
            )}
        </div>
    `;
}

function renderRegistry(mappings) {
    if (!mappings || mappings.length === 0) {
        mappingRegistry.innerHTML = `<p class="empty">В Gateway не зарегистрировано ни одного event mapping</p>`;
        return;
    }

    const rows = mappings.map((mapping) => {
        const isExpanded = expandedMappingId === mapping.mappingId;
        const eventTypesPreview = Object.keys(mapping.eventTypeMapping || {})
            .slice(0, 3)
            .map((key) => `${key} → ${mapping.eventTypeMapping[key]}`)
            .join(", ");

        return `
            <tr class="mapping-row ${isExpanded ? "expanded" : ""}" data-mapping-id="${escapeHtml(mapping.mappingId)}" tabindex="0" role="button" aria-expanded="${isExpanded}">
                <td><code>${escapeHtml(mapping.mappingId)}</code></td>
                <td>${escapeHtml(mapping.sourceSystem || "—")}</td>
                <td>${mapping.bindingId ? `<code>${escapeHtml(mapping.bindingId)}</code>` : "—"}</td>
                <td>${mapping.topicAlias ? `<code>${escapeHtml(mapping.topicAlias)}</code>` : "—"}</td>
                <td>${countEntries(mapping.headerMapping)} / ${countEntries(mapping.eventTypeMapping)} / ${(mapping.bodyMappings || []).length}</td>
                <td class="small">${escapeHtml(eventTypesPreview || "—")}</td>
                <td><code>${escapeHtml(mapping.configFile)}</code></td>
            </tr>
            ${isExpanded ? `
                <tr class="mapping-detail-row">
                    <td colspan="7">${renderMappingDetail(mapping)}</td>
                </tr>
            ` : ""}
        `;
    }).join("");

    mappingRegistry.innerHTML = `
        <table class="mapping-registry-table">
            <thead>
                <tr>
                    <th>Mapping ID</th>
                    <th>Source system</th>
                    <th>Binding</th>
                    <th>Topic</th>
                    <th>H / E / B</th>
                    <th>Event types</th>
                    <th>Config file</th>
                </tr>
            </thead>
            <tbody>${rows}</tbody>
        </table>
    `;

    mappingRegistry.querySelectorAll(".mapping-row").forEach((row) => {
        row.addEventListener("click", () => toggleMapping(row.dataset.mappingId));
        row.addEventListener("keydown", (event) => {
            if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                toggleMapping(row.dataset.mappingId);
            }
        });
    });
}

function toggleMapping(mappingId) {
    expandedMappingId = expandedMappingId === mappingId ? null : mappingId;
    renderRegistry(currentMappings);
}

let currentMappings = [];

async function loadMappings() {
    mappingStatus.textContent = "Загрузка...";
    try {
        const response = await fetch(`${config.gatewayUrl}/internal/admin/config`);
        if (!response.ok) {
            throw new Error(`Gateway returned ${response.status}`);
        }
        const data = await response.json();
        currentMappings = data.eventMappings || [];
        if (currentMappings.length === 0 && data.eventMapping) {
            currentMappings = [{
                mappingId: "processor",
                configFile: "processor-event-mapping.yaml",
                sourceSystem: data.eventMapping.sourceSystem,
                description: data.eventMapping.description,
                bindingId: null,
                topicAlias: null,
                consumerGroup: null,
                headerMapping: data.eventMapping.headerMapping,
                eventTypeMapping: data.eventMapping.eventTypeMapping,
                bodyMappings: data.eventMapping.bodyMappings
            }];
        }
        renderRegistry(currentMappings);
        mappingStatus.textContent = `${currentMappings.length} mapping(s) · обновлено: ${new Date().toLocaleTimeString()}`;
    } catch (error) {
        mappingStatus.textContent = `Ошибка: ${error.message}`;
        mappingRegistry.innerHTML = `<p class="empty">Не удалось загрузить маппинги</p>`;
    }
}

loadMappings();
