package com.store.inventory.alerts;

import com.store.inventory.api.StockAlertListener;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;

/**
 * Low stock policy for purchasing: when a product counts as low and how the alert is delivered.
 * Delivery failures are logged and reported, never thrown: an alert must not break a sale.
 */
public final class LowStockAlerts {

    public static final int DEFAULT_THRESHOLD = 5;

    private static final Logger LOG = System.getLogger(LowStockAlerts.class.getName());

    private final StockAlertListener listener;
    private final int threshold;

    public LowStockAlerts(StockAlertListener listener, int threshold) {
        this.listener = Objects.requireNonNull(listener, "listener");
        if (threshold < 0) {
            throw new IllegalArgumentException("threshold must not be negative: " + threshold);
        }
        this.threshold = threshold;
    }

    public int threshold() {
        return threshold;
    }

    public boolean isLow(int availableUnits) {
        return availableUnits <= threshold;
    }

    /** Hands the alert to the channel (email today). Returns whether it was delivered. */
    public boolean deliver(String sku, int availableUnits) {
        try {
            listener.onLowStock(sku, availableUnits);
            return true;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Low stock alert for " + sku + " failed; will retry on next change", e);
            return false;
        }
    }
}
