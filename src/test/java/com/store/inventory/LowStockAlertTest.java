package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.support.MutableClock;
import com.store.inventory.support.RecordingAlertListener;
import com.store.inventory.support.RecordingAlertListener.Alert;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LowStockAlertTest {

    private MutableClock clock;
    private RecordingAlertListener purchasing;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        purchasing = new RecordingAlertListener();
        service = Inventory.create(clock, purchasing);
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
    }

    @Test
    void noAlertWhileMoreThanFiveUnitsRemain() {
        service.reserve("ORDER-1", "SKU-1", 4);

        assertEquals(0, purchasing.count());
    }

    @Test
    void alertFiresWhenFiveOrFewerUnitsRemain() {
        service.reserve("ORDER-1", "SKU-1", 5);

        assertEquals(List.of(new Alert("SKU-1", 5)), purchasing.alerts());
    }

    @Test
    void alertIsNotRepeatedWhileStockKeepsFalling() {
        service.reserve("ORDER-1", "SKU-1", 5);
        service.reserve("ORDER-2", "SKU-1", 2);
        service.reserve("ORDER-3", "SKU-1", 3);

        assertEquals(1, purchasing.count());
    }

    @Test
    void alertIsNotRepeatedWhenExpirationsFreeUnitsWithoutRestocking() {
        service.reserve("ORDER-1", "SKU-1", 6);
        service.reserve("ORDER-2", "SKU-1", 1);
        clock.advance(Duration.ofMinutes(15));
        assertEquals(10, service.available("SKU-1"));

        service.reserve("ORDER-3", "SKU-1", 7);

        assertEquals(1, purchasing.count());
    }

    @Test
    void restockingArmsTheAlertAgain() {
        service.reserve("ORDER-1", "SKU-1", 6);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-2", "SKU-1", 10);

        assertEquals(List.of(new Alert("SKU-1", 4), new Alert("SKU-1", 4)), purchasing.alerts());
    }

    @Test
    void restockingAloneDoesNotAlert() {
        service.reserve("ORDER-1", "SKU-1", 6);
        service.addStock("SKU-1", 1);

        assertEquals(1, purchasing.count());
    }

    @Test
    void confirmingDoesNotChangeAvailabilityNorAlert() {
        service.reserve("ORDER-1", "SKU-1", 4);
        service.confirm("ORDER-1");

        assertEquals(6, service.available("SKU-1"));
        assertEquals(0, purchasing.count());
    }

    @Test
    void alertsAreTrackedPerProduct() {
        service.registerProduct("SKU-2", ProductCategory.STANDARD);
        service.addStock("SKU-2", 10);

        service.reserve("ORDER-1", "SKU-1", 6);
        service.reserve("ORDER-2", "SKU-2", 7);

        assertEquals(List.of(new Alert("SKU-1", 4), new Alert("SKU-2", 3)), purchasing.alerts());
    }

    @Test
    void aFailingChannelDoesNotBreakTheSaleAndIsRetriedOnTheNextChange() {
        AtomicInteger attempts = new AtomicInteger();
        InventoryService flaky = Inventory.create(clock, (sku, available) -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("mail server down");
            }
        });
        flaky.registerProduct("SKU-1", ProductCategory.STANDARD);
        flaky.addStock("SKU-1", 10);

        flaky.reserve("ORDER-1", "SKU-1", 6);
        assertEquals(4, flaky.available("SKU-1"));

        flaky.reserve("ORDER-2", "SKU-1", 1);
        assertEquals(2, attempts.get());

        flaky.reserve("ORDER-3", "SKU-1", 1);
        assertTrue(attempts.get() == 2, "delivered alert must not repeat");
    }
}
