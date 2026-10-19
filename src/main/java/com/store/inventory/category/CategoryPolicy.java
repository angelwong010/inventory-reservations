package com.store.inventory.category;

import java.time.Duration;
import java.util.Objects;

/**
 * Business rules of a product category: how long the customer has to pay
 * and how many units a single order may reserve.
 */
public record CategoryPolicy(Duration paymentWindow, int maxUnitsPerOrder) {

    public static final int NO_LIMIT = Integer.MAX_VALUE;

    public CategoryPolicy {
        Objects.requireNonNull(paymentWindow, "paymentWindow");
        if (paymentWindow.isZero() || paymentWindow.isNegative()) {
            throw new IllegalArgumentException("paymentWindow must be positive: " + paymentWindow);
        }
        if (maxUnitsPerOrder <= 0) {
            throw new IllegalArgumentException("maxUnitsPerOrder must be positive: " + maxUnitsPerOrder);
        }
    }

    public static CategoryPolicy of(Duration paymentWindow) {
        return new CategoryPolicy(paymentWindow, NO_LIMIT);
    }

    public static CategoryPolicy of(Duration paymentWindow, int maxUnitsPerOrder) {
        return new CategoryPolicy(paymentWindow, maxUnitsPerOrder);
    }

    public boolean hasOrderLimit() {
        return maxUnitsPerOrder != NO_LIMIT;
    }

    public boolean allowsPerOrder(int quantity) {
        return quantity <= maxUnitsPerOrder;
    }
}
