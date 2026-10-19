package com.store.inventory.reservation;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Mutable state of one SKU. Not thread-safe: callers synchronize.
 */
final class Product {

    private final String sku;
    private final ProductCategory category;
    private int stock;
    private final Map<String, Reservation> pendingByOrder = new HashMap<>();
    private boolean lowStockAlerted;

    Product(String sku, ProductCategory category) {
        this.sku = sku;
        this.category = category;
    }

    String sku() {
        return sku;
    }

    ProductCategory category() {
        return category;
    }

    /** Units that can still be reserved. Assumes expired reservations were already released. */
    int available() {
        int reserved = 0;
        for (Reservation reservation : pendingByOrder.values()) {
            reserved += reservation.quantity();
        }
        return stock - reserved;
    }

    void addStock(int quantity) {
        stock += quantity;
        lowStockAlerted = false;
    }

    void reserve(Reservation reservation) {
        pendingByOrder.put(reservation.orderId(), reservation);
    }

    Reservation pendingReservation(String orderId) {
        return pendingByOrder.get(orderId);
    }

    /** Sold units leave the stock for good. */
    void confirm(String orderId) {
        Reservation reservation = pendingByOrder.remove(orderId);
        stock -= reservation.quantity();
    }

    /** Releases every reservation whose payment window closed and returns their order ids. */
    List<String> releaseExpired(Instant now) {
        List<String> released = new ArrayList<>();
        Iterator<Reservation> it = pendingByOrder.values().iterator();
        while (it.hasNext()) {
            Reservation reservation = it.next();
            if (!now.isBefore(reservation.expiresAt())) {
                released.add(reservation.orderId());
                it.remove();
            }
        }
        return released;
    }

    boolean lowStockAlerted() {
        return lowStockAlerted;
    }

    void markLowStockAlerted(boolean alerted) {
        lowStockAlerted = alerted;
    }
}
