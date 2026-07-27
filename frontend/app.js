const config = window.POC_CONFIG;

const searchForm = document.getElementById("search-form");
const searchStatus = document.getElementById("search-status");
const resultsContainer = document.getElementById("results");
const processorStatus = document.getElementById("processor-status");

let demoVersion = 1;

searchForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    await runSearch();
});

document.getElementById("btn-created").addEventListener("click", () => publishProcessorEvent("DepositOfferCreated", true));
document.getElementById("btn-updated").addEventListener("click", () => publishProcessorEvent("DepositOfferUpdated", false));
document.getElementById("btn-closed").addEventListener("click", () => publishProcessorEvent("DepositOfferClosed", false));

async function runSearch() {
    searchStatus.textContent = "Запрос через Internal Gateway...";
    resultsContainer.innerHTML = "";

    const organizationId = document.getElementById("organizationId").value;
    const accountId = document.getElementById("accountId").value;
    const amount = Number(document.getElementById("amount").value);
    const termMonths = Number(document.getElementById("termMonths").value);

    try {
        const response = await fetch(`${config.gatewayUrl}/deposit-offers/search`, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "X-Demo-Subject-Id": "demo-user-001",
                "X-Demo-Organization-Id": organizationId
            },
            body: JSON.stringify({ organizationId, accountId, amount, termMonths })
        });

        if (!response.ok) {
            throw new Error(`Gateway returned ${response.status}`);
        }

        const data = await response.json();
        renderResults(data);
        searchStatus.textContent = `Найдено ${data.offers.length} предложений (${data.calculatedAt})`;
    } catch (error) {
        searchStatus.textContent = `Ошибка: ${error.message}`;
    }
}

function renderResults(data) {
    if (!data.offers || data.offers.length === 0) {
        resultsContainer.innerHTML = "<p class=\"empty\">Нет подходящих предложений</p>";
        return;
    }

    const rows = data.offers.map((offer) => `
        <tr>
            <td>${offer.offerId}</td>
            <td>${offer.source}</td>
            <td>${offer.productCode}</td>
            <td>${(offer.rate * 100).toFixed(2)}%</td>
            <td>${offer.termMonths}</td>
            <td>${offer.minAmount} – ${offer.maxAmount} ${offer.currency}</td>
        </tr>
    `).join("");

    resultsContainer.innerHTML = `
        <p><strong>${data.organizationDisplayName}</strong> · счёт ${data.accountId} (${data.accountCurrency})</p>
        <table>
            <thead>
                <tr>
                    <th>Offer ID</th>
                    <th>Source</th>
                    <th>Product</th>
                    <th>Rate</th>
                    <th>Term</th>
                    <th>Amount range</th>
                </tr>
            </thead>
            <tbody>${rows}</tbody>
        </table>
    `;
}

async function publishProcessorEvent(eventType, incrementVersion) {
    if (incrementVersion) {
        demoVersion = 1;
    } else if (eventType === "DepositOfferUpdated") {
        demoVersion += 1;
    }

    const payload = {
        eventType,
        processorOfferId: document.getElementById("processorOfferId").value,
        processorOfferVersion: demoVersion,
        productCode: document.getElementById("productCode").value,
        rate: Number(document.getElementById("rate").value),
        termMonths: Number(document.getElementById("procTermMonths").value),
        minAmount: 10000,
        maxAmount: 5000000,
        currency: "RUB"
    };

    processorStatus.textContent = `Публикация ${eventType}...`;

    try {
        const response = await fetch(`${config.processorUrl}/demo/publish-offer-event`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });

        if (!response.ok) {
            throw new Error(`Processor returned ${response.status}`);
        }

        const result = await response.json();
        processorStatus.textContent = `Опубликовано: ${result.eventType} (${result.eventId}). Ожидание fan-out...`;

        setTimeout(async () => {
            document.getElementById("termMonths").value = String(payload.termMonths);
            await runSearch();
        }, 1500);
    } catch (error) {
        processorStatus.textContent = `Ошибка: ${error.message}`;
    }
}
