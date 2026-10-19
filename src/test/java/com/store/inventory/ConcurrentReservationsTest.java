package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.support.RecordingAlertListener;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class ConcurrentReservationsTest {

    private static final int STOCK = 50;
    private static final int CUSTOMERS = 400;

    @RepeatedTest(5)
    void hundredsOfCustomersNeverOversellTheSameProduct() throws Exception {
        RecordingAlertListener purchasing = new RecordingAlertListener();
        InventoryService service = Inventory.create(Clock.systemUTC(), purchasing);
        service.registerProduct("HOT", ProductCategory.STANDARD);
        service.addStock("HOT", STOCK);

        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        runConcurrently(CUSTOMERS, i -> {
            try {
                service.reserve("ORDER-" + i, "HOT", 1);
                reserved.incrementAndGet();
            } catch (InsufficientStockException e) {
                rejected.incrementAndGet();
            }
        });

        assertEquals(STOCK, reserved.get());
        assertEquals(CUSTOMERS - STOCK, rejected.get());
        assertEquals(0, service.available("HOT"));
        assertEquals(1, purchasing.count(), "exactly one low stock alert");
    }

    @Test
    void retriesOfTheSameOrderInParallelReserveOnce() throws Exception {
        InventoryService service = Inventory.create(Clock.systemUTC(), (sku, available) -> { });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 100);

        runConcurrently(50, i -> service.reserve("ORDER-1", "SKU-1", 3));

        assertEquals(97, service.available("SKU-1"));
    }

    private static void runConcurrently(int tasks, java.util.function.IntConsumer task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < tasks; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    task.accept(n);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
