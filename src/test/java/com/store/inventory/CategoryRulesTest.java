package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CategoryRulesTest {

    private MutableClock clock;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        service = Inventory.create(clock, (sku, available) -> { });
        for (ProductCategory category : ProductCategory.values()) {
            service.registerProduct(category.name(), category);
            service.addStock(category.name(), 100);
        }
    }

    @ParameterizedTest(name = "{0} expires after {1} minutes")
    @CsvSource({
            "STANDARD, 15",
            "PRE_ORDER, 1440",
            "FLASH_SALE, 5",
    })
    void paymentWindowDependsOnCategory(ProductCategory category, long minutes) {
        service.reserve("ORDER-1", category.name(), 1);

        clock.advance(Duration.ofMinutes(minutes).minusSeconds(1));
        assertEquals(99, service.available(category.name()));

        clock.advance(Duration.ofSeconds(1));
        assertEquals(100, service.available(category.name()));
    }

    @Test
    void standardAndPreOrderHaveNoOrderLimit() {
        service.reserve("ORDER-1", "STANDARD", 100);
        service.reserve("ORDER-2", "PRE_ORDER", 100);

        assertEquals(0, service.available("STANDARD"));
        assertEquals(0, service.available("PRE_ORDER"));
    }

    @Test
    void flashSaleAllowsAtMostTwoUnitsPerOrder() {
        service.reserve("ORDER-1", "FLASH_SALE", 2);

        OrderLimitExceededException error = assertThrows(OrderLimitExceededException.class,
                () -> service.reserve("ORDER-2", "FLASH_SALE", 3));

        assertEquals("Order limit for FLASH_SALE is 2 units, requested 3", error.getMessage());
        assertEquals(98, service.available("FLASH_SALE"));
    }

    @Test
    void orderLimitIsCheckedBeforeStock() {
        service.registerProduct("SOLD-OUT", ProductCategory.FLASH_SALE);

        assertThrows(OrderLimitExceededException.class, () -> service.reserve("ORDER-1", "SOLD-OUT", 3));
    }
}
