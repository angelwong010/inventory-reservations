package com.store.inventory;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.alerts.LowStockAlerts;
import com.store.inventory.category.CategoryPolicies;
import com.store.inventory.reservation.InMemoryInventoryService;
import java.time.Clock;

/**
 * Entry point used by our automated tests. Keep this signature exactly as it is,
 * and build your implementation here.
 */
public final class Inventory {

    private Inventory() {
    }

    public static InventoryService create(Clock clock, StockAlertListener alertListener) {
        return new InMemoryInventoryService(
                clock,
                CategoryPolicies.defaults(),
                new LowStockAlerts(alertListener, LowStockAlerts.DEFAULT_THRESHOLD));
    }
}
