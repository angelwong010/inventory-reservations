package com.store.inventory.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.StockAlertListener;
import com.store.inventory.support.RecordingAlertListener;
import com.store.inventory.support.RecordingAlertListener.Alert;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompositeStockAlertListenerTest {

    @Test
    void everyChannelReceivesTheAlert() {
        RecordingAlertListener email = new RecordingAlertListener();
        RecordingAlertListener chat = new RecordingAlertListener();

        CompositeStockAlertListener.of(email, chat).onLowStock("SKU-1", 3);

        assertEquals(List.of(new Alert("SKU-1", 3)), email.alerts());
        assertEquals(List.of(new Alert("SKU-1", 3)), chat.alerts());
    }

    @Test
    void aFailingChannelDoesNotStarveTheOthersButIsReported() {
        StockAlertListener broken = (sku, available) -> {
            throw new IllegalStateException("smtp down");
        };
        RecordingAlertListener chat = new RecordingAlertListener();
        StockAlertListener composite = CompositeStockAlertListener.of(broken, chat);

        assertThrows(IllegalStateException.class, () -> composite.onLowStock("SKU-1", 3));
        assertEquals(1, chat.count());
    }

    @Test
    void requiresAtLeastOneChannel() {
        assertThrows(IllegalArgumentException.class, CompositeStockAlertListener::of);
    }
}
