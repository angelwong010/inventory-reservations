package com.store.inventory.reservation;

import com.store.inventory.alerts.LowStockAlerts;
import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.category.CategoryPolicies;
import com.store.inventory.category.CategoryPolicy;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory implementation. Expiration is evaluated lazily against the {@link Clock}:
 * a reservation is active while {@code now < expiresAt}, no background threads involved.
 * A single lock guards all state: operations are short and this stage is in-memory only.
 */
public final class InMemoryInventoryService implements InventoryService {

    private final Clock clock;
    private final CategoryPolicies policies;
    private final LowStockAlerts alerts;

    private final Object lock = new Object();
    private final Map<String, Product> productsBySku = new HashMap<>();
    private final Map<String, Order> ordersById = new HashMap<>();

    public InMemoryInventoryService(Clock clock, CategoryPolicies policies, LowStockAlerts alerts) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
    }

    @Override
    public void registerProduct(String sku, ProductCategory category) {
        requireText(sku, "sku");
        Objects.requireNonNull(category, "category");
        synchronized (lock) {
            Product existing = productsBySku.get(sku);
            if (existing == null) {
                productsBySku.put(sku, new Product(sku, category));
            } else if (existing.category() != category) {
                throw new IllegalArgumentException(
                        "Product " + sku + " is already registered as " + existing.category());
            }
        }
    }

    @Override
    public void addStock(String sku, int quantity) {
        requireText(sku, "sku");
        requirePositive(quantity);
        synchronized (lock) {
            Product product = productsBySku.get(sku);
            if (product == null) {
                throw new IllegalArgumentException("Product not registered: " + sku);
            }
            product.addStock(quantity);
        }
    }

    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        requireText(orderId, "orderId");
        requireText(sku, "sku");
        requirePositive(quantity);

        Product product;
        Reservation reservation;
        int availableAfter;
        boolean alertClaimed = false;
        synchronized (lock) {
            Instant now = clock.instant();
            Order existing = ordersById.get(orderId);
            if (existing != null) {
                releaseExpired(existing.product, now);
                existing = ordersById.get(orderId);
            }
            if (existing != null) {
                return existing.sameRequest(sku, quantity);
            }

            product = productsBySku.get(sku);
            if (product == null) {
                throw new InsufficientStockException(sku, quantity, 0);
            }
            releaseExpired(product, now);

            CategoryPolicy policy = policies.policyFor(product.category());
            if (!policy.allowsPerOrder(quantity)) {
                throw new OrderLimitExceededException(sku, quantity, policy.maxUnitsPerOrder());
            }
            int available = product.available();
            if (quantity > available) {
                throw new InsufficientStockException(sku, quantity, available);
            }

            reservation = new Reservation(orderId, sku, quantity, now.plus(policy.paymentWindow()));
            product.reserve(reservation);
            ordersById.put(orderId, new Order(product, reservation));

            availableAfter = product.available();
            if (alerts.isLow(availableAfter) && !product.lowStockAlerted()) {
                product.markLowStockAlerted(true);
                alertClaimed = true;
            }
        }
        if (alertClaimed) {
            deliverAlert(product, availableAfter);
        }
        return reservation;
    }

    @Override
    public void confirm(String orderId) {
        requireText(orderId, "orderId");
        synchronized (lock) {
            Order order = ordersById.get(orderId);
            if (order != null) {
                releaseExpired(order.product, clock.instant());
                order = ordersById.get(orderId);
            }
            if (order == null || order.confirmed) {
                throw new IllegalStateException("Order " + orderId + " has no active reservation");
            }
            order.product.confirm(orderId);
            order.confirmed = true;
        }
    }

    @Override
    public int available(String sku) {
        requireText(sku, "sku");
        synchronized (lock) {
            Product product = productsBySku.get(sku);
            if (product == null) {
                return 0;
            }
            releaseExpired(product, clock.instant());
            return product.available();
        }
    }

    /**
     * Runs outside the lock: the channel may be slow. The alert was claimed under the lock, so
     * concurrent reservations cannot duplicate it; if delivery fails, the claim is given back.
     */
    private void deliverAlert(Product product, int availableUnits) {
        if (!alerts.deliver(product.sku(), availableUnits)) {
            synchronized (lock) {
                product.markLowStockAlerted(false);
            }
        }
    }

    private void releaseExpired(Product product, Instant now) {
        for (String orderId : product.releaseExpired(now)) {
            ordersById.remove(orderId);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
    }

    private static final class Order {
        final Product product;
        final Reservation reservation;
        boolean confirmed;

        Order(Product product, Reservation reservation) {
            this.product = product;
            this.reservation = reservation;
        }

        /** A retried order returns its original reservation; a different payload is a client bug. */
        Reservation sameRequest(String sku, int quantity) {
            if (!reservation.sku().equals(sku) || reservation.quantity() != quantity) {
                throw new IllegalArgumentException("Order " + reservation.orderId()
                        + " already reserved " + reservation.quantity() + " x " + reservation.sku()
                        + ", got " + quantity + " x " + sku);
            }
            return reservation;
        }
    }
}
