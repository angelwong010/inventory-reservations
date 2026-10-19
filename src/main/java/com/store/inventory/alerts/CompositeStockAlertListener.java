package com.store.inventory.alerts;

import com.store.inventory.api.StockAlertListener;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;

/**
 * Fans one alert out to several channels. A failing channel does not stop the others;
 * the first failure is rethrown so the caller can schedule a retry.
 */
public final class CompositeStockAlertListener implements StockAlertListener {

    private static final Logger LOG = System.getLogger(CompositeStockAlertListener.class.getName());

    private final List<StockAlertListener> channels;

    public CompositeStockAlertListener(List<StockAlertListener> channels) {
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("at least one channel is required");
        }
        this.channels = List.copyOf(channels);
    }

    public static StockAlertListener of(StockAlertListener... channels) {
        return new CompositeStockAlertListener(List.of(channels));
    }

    @Override
    public void onLowStock(String sku, int availableUnits) {
        RuntimeException first = null;
        for (StockAlertListener channel : channels) {
            try {
                channel.onLowStock(sku, availableUnits);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Alert channel " + channel.getClass().getSimpleName() + " failed for " + sku, e);
                if (first == null) {
                    first = e;
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
