// SmartShip Dashboard - Live Statistics
const REFRESH_INTERVAL = 30000; // 30 seconds

// API Endpoints
const ENDPOINTS = {
    shipments: '/api/shipments/status/all',
    lateShipments: '/api/shipments/late',
    vehicles: '/api/vehicles/state',
    warehouses: '/api/warehouses/metrics/all',
    warehouseReference: '/api/reference/warehouses',
    orders: '/api/orders/state',
    slaRisk: '/api/orders/sla-risk'
};

// Initialize dashboard
document.addEventListener('DOMContentLoaded', () => {
    // Initial load
    refreshDashboard();

    // Auto-refresh every 30 seconds
    setInterval(refreshDashboard, REFRESH_INTERVAL);

    // Manual refresh button
    document.getElementById('refresh-btn').addEventListener('click', refreshDashboard);
});

// Fetch all statistics in parallel
async function refreshDashboard() {
    try {
        const [shipments, lateShipments, vehicles, warehouseResult, orders, slaRisk] = await Promise.all([
            fetchJSON(ENDPOINTS.shipments),
            fetchJSON(ENDPOINTS.lateShipments),
            fetchJSON(ENDPOINTS.vehicles),
            fetchWarehouseData(),
            fetchJSON(ENDPOINTS.orders),
            fetchJSON(ENDPOINTS.slaRisk)
        ]);

        renderShipmentStats(shipments, lateShipments);
        renderVehicleStats(vehicles);
        renderWarehouseStats(warehouseResult.data, warehouseResult.isReferenceData);
        renderOrderStats(orders, slaRisk);
        renderAlerts(lateShipments, slaRisk);
        updateTimestamp();

    } catch (error) {
        console.error('Dashboard refresh failed:', error);
    }
}

// Fetch JSON from API
async function fetchJSON(url) {
    try {
        const response = await fetch(url);
        if (!response.ok) {
            console.warn(`Failed to fetch ${url}: ${response.status}`);
            return null;
        }
        return await response.json();
    } catch (error) {
        console.warn(`Error fetching ${url}:`, error);
        return null;
    }
}

// Fetch warehouse data with fallback to reference data
async function fetchWarehouseData() {
    // First try real-time metrics
    const metrics = await fetchJSON(ENDPOINTS.warehouses);

    // Check if metrics has data
    const hasMetrics = metrics && (
        (Array.isArray(metrics) && metrics.length > 0) ||
        (metrics.warehouses && Object.keys(metrics.warehouses).length > 0) ||
        (typeof metrics === 'object' && !metrics.warehouses && Object.keys(metrics).length > 0)
    );

    if (hasMetrics) {
        return { data: metrics, isReferenceData: false };
    }

    // Fallback to reference data from PostgreSQL
    const refData = await fetchJSON(ENDPOINTS.warehouseReference);
    if (refData && refData.warehouses) {
        return { data: refData.warehouses, isReferenceData: true };
    }

    return { data: null, isReferenceData: false };
}

// Render shipment statistics
function renderShipmentStats(data, lateData) {
    if (!data) return;

    const counts = data.counts || data.status_counts || data;

    // Update stat values
    setValue('shipments-in-transit', counts.IN_TRANSIT || counts.in_transit || 0);
    setValue('shipments-delivered', counts.DELIVERED || counts.delivered || 0);
    setValue('shipments-created', counts.CREATED || counts.created || 0);

    // Late shipments count
    const lateCount = lateData ? (Array.isArray(lateData) ? lateData.length : (lateData.count || 0)) : 0;
    setValue('shipments-late', lateCount);
}

// Render vehicle statistics
function renderVehicleStats(data) {
    if (!data) return;

    const vehicles = data.vehicles || data;
    if (!Array.isArray(vehicles)) return;

    let active = 0, idle = 0, maintenance = 0;
    let totalLoad = 0, loadCount = 0;

    vehicles.forEach(v => {
        const status = v.status || v.vehicle_status;
        if (status === 'IN_TRANSIT' || status === 'LOADING' || status === 'UNLOADING') {
            active++;
        } else if (status === 'IDLE' || status === 'AVAILABLE') {
            idle++;
        } else if (status === 'MAINTENANCE' || status === 'OUT_OF_SERVICE') {
            maintenance++;
        }

        // Calculate load percentage
        const load = v.current_load || v.currentLoad;
        if (load && load.weight_kg && load.capacity_kg) {
            totalLoad += (load.weight_kg / load.capacity_kg) * 100;
            loadCount++;
        }
    });

    setValue('vehicles-active', active);
    setValue('vehicles-idle', idle);
    setValue('vehicles-maintenance', maintenance);
    setValue('vehicles-avg-load', loadCount > 0 ? Math.round(totalLoad / loadCount) + '%' : 'N/A');
}

// Render warehouse statistics
function renderWarehouseStats(data, isReferenceData = false) {
    const container = document.getElementById('warehouse-stats');

    if (!data) {
        container.innerHTML = '<div class="warehouse-item"><span class="warehouse-name">No data</span></div>';
        return;
    }

    const warehouses = data.warehouses || data;

    if (!warehouses || typeof warehouses !== 'object') {
        container.innerHTML = '<div class="warehouse-item"><span class="warehouse-name">No data</span></div>';
        return;
    }

    let html = '';

    // Handle reference data (static warehouse info from PostgreSQL)
    if (isReferenceData && Array.isArray(warehouses)) {
        warehouses.forEach(wh => {
            const id = wh.warehouse_id || wh.warehouseId || 'Unknown';
            const name = wh.name || id;
            const city = wh.city || '';
            const displayName = city ? `${id} - ${city}` : name;
            html += `<div class="warehouse-item">
                <span class="warehouse-name">${displayName}</span>
                <span class="warehouse-ops awaiting">Awaiting data</span>
            </div>`;
        });
    } else if (Array.isArray(warehouses)) {
        // Real-time metrics as array
        warehouses.forEach(wh => {
            const id = wh.warehouse_id || wh.warehouseId || 'Unknown';
            const ops = wh.total_operations || wh.totalOperations || 0;
            html += `<div class="warehouse-item">
                <span class="warehouse-name">${id}</span>
                <span class="warehouse-ops">${ops} ops</span>
            </div>`;
        });
    } else {
        // Real-time metrics as object (may be windowed or flat)
        Object.entries(warehouses).forEach(([id, data]) => {
            let ops = 0;

            // Handle windowed data: [{window_start: ..., metrics: {...}}, ...]
            if (Array.isArray(data) && data.length > 0) {
                const latestWindow = data[data.length - 1]; // Get latest window
                const metrics = latestWindow.metrics || latestWindow;
                ops = metrics.total_operations || metrics.totalOperations ||
                      (metrics.picks || 0) + (metrics.packs || 0) + (metrics.ships || 0) || 0;
            } else if (typeof data === 'object' && data !== null) {
                // Handle flat metrics object
                ops = data.total_operations || data.totalOperations ||
                      (data.picks || 0) + (data.packs || 0) + (data.ships || 0) || 0;
            }

            html += `<div class="warehouse-item">
                <span class="warehouse-name">${id}</span>
                <span class="warehouse-ops">${ops} ops</span>
            </div>`;
        });
    }

    container.innerHTML = html || '<div class="warehouse-item"><span class="warehouse-name">No data</span></div>';
}

// Render order statistics
function renderOrderStats(orderData, slaData) {
    if (!orderData) return;

    const orders = orderData.orders || orderData;

    // Count by status
    let pending = 0, confirmed = 0, shipped = 0;

    if (Array.isArray(orders)) {
        orders.forEach(o => {
            const status = o.status || o.order_status;
            if (status === 'PENDING') pending++;
            else if (status === 'CONFIRMED') confirmed++;
            else if (status === 'SHIPPED' || status === 'IN_TRANSIT') shipped++;
        });
    } else if (typeof orders === 'object') {
        pending = orders.PENDING || orders.pending || 0;
        confirmed = orders.CONFIRMED || orders.confirmed || 0;
        shipped = orders.SHIPPED || orders.shipped || 0;
    }

    setValue('orders-pending', pending);
    setValue('orders-confirmed', confirmed);
    setValue('orders-shipped', shipped);

    // SLA at-risk count
    const atRiskCount = slaData ? (Array.isArray(slaData) ? slaData.length : (slaData.count || 0)) : 0;
    setValue('orders-at-risk', atRiskCount);
}

// Render alerts (late shipments + SLA at-risk)
function renderAlerts(lateShipments, slaRisk) {
    const container = document.getElementById('alerts-list');
    let html = '';

    // Late shipments - handle both array and object with late_shipments property
    const lateList = lateShipments ? (Array.isArray(lateShipments) ? lateShipments : lateShipments.late_shipments) : [];
    if (lateList && Array.isArray(lateList)) {
        lateList.slice(0, 5).forEach(shipment => {
            const id = shipment.shipment_id || shipment.shipmentId || 'Unknown';
            const delay = shipment.delay_minutes || shipment.delayMinutes || '?';
            html += `<div class="alert-item late-shipment">
                <span class="alert-icon">&#x26A0;</span>
                <span class="alert-text">Late: ${id} (${delay} min overdue)</span>
            </div>`;
        });
    }

    // SLA at-risk - handle both array and object with at_risk_orders property
    const riskList = slaRisk ? (Array.isArray(slaRisk) ? slaRisk : slaRisk.at_risk_orders) : [];
    if (riskList && Array.isArray(riskList)) {
        riskList.slice(0, 5).forEach(order => {
            const id = order.order_id || order.orderId || 'Unknown';
            const remaining = order.time_remaining_minutes || order.timeRemainingMinutes || '?';
            html += `<div class="alert-item sla-risk">
                <span class="alert-icon">&#x23F0;</span>
                <span class="alert-text">SLA Risk: ${id} (${remaining} min remaining)</span>
            </div>`;
        });
    }

    if (!html) {
        html = '<div class="alert-item success"><span class="alert-icon">&#x2705;</span><span class="alert-text">No active alerts</span></div>';
    }

    container.innerHTML = html;
}

// Helper: Set element value
function setValue(id, value) {
    const el = document.getElementById(id);
    if (el) {
        el.textContent = value;
    }
}

// Update last updated timestamp
function updateTimestamp() {
    const now = new Date();
    const timeStr = now.toLocaleTimeString();
    document.getElementById('last-updated').textContent = `Last updated: ${timeStr}`;
}
