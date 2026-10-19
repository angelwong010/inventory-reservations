package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReservationLifecycleTest {

    private MutableClock clock;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        service = Inventory.create(clock, (sku, available) -> { });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
    }

    @Nested
    class Expiration {

        @Test
        void reservationCarriesTheCategoryPaymentWindow() {
            Reservation reservation = service.reserve("ORDER-1", "SKU-1", 3);

            assertEquals("ORDER-1", reservation.orderId());
            assertEquals("SKU-1", reservation.sku());
            assertEquals(3, reservation.quantity());
            assertEquals(MutableClock.START.plus(Duration.ofMinutes(15)), reservation.expiresAt());
        }

        @Test
        void unitsStayReservedUntilTheWindowCloses() {
            service.reserve("ORDER-1", "SKU-1", 3);
            clock.advance(Duration.ofMinutes(14).plusSeconds(59));

            assertEquals(7, service.available("SKU-1"));
        }

        @Test
        void unitsReturnWhenTheWindowCloses() {
            service.reserve("ORDER-1", "SKU-1", 3);
            clock.advance(Duration.ofMinutes(15));

            assertEquals(10, service.available("SKU-1"));
        }

        @Test
        void releasedUnitsCanBeReservedByAnotherOrder() {
            service.reserve("ORDER-1", "SKU-1", 10);
            assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-2", "SKU-1", 1));

            clock.advance(Duration.ofMinutes(15));
            service.reserve("ORDER-2", "SKU-1", 10);

            assertEquals(0, service.available("SKU-1"));
        }

        @Test
        void expiredReservationCannotBeConfirmed() {
            service.reserve("ORDER-1", "SKU-1", 3);
            clock.advance(Duration.ofMinutes(15));

            assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-1"));
            assertEquals(10, service.available("SKU-1"));
        }
    }

    @Nested
    class Confirmation {

        @Test
        void confirmedUnitsNeverReturnToStock() {
            service.reserve("ORDER-1", "SKU-1", 4);
            service.confirm("ORDER-1");
            clock.advance(Duration.ofDays(30));

            assertEquals(6, service.available("SKU-1"));
        }

        @Test
        void unknownOrderCannotBeConfirmed() {
            assertThrows(IllegalStateException.class, () -> service.confirm("NOPE"));
        }

        @Test
        void orderCannotBeConfirmedTwice() {
            service.reserve("ORDER-1", "SKU-1", 4);
            service.confirm("ORDER-1");

            assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-1"));
            assertEquals(6, service.available("SKU-1"));
        }
    }

    @Nested
    class Retries {

        @Test
        void resendingTheSameOrderDoesNotReserveTwice() {
            Reservation first = service.reserve("ORDER-1", "SKU-1", 3);
            Reservation retry = service.reserve("ORDER-1", "SKU-1", 3);

            assertEquals(first, retry);
            assertEquals(7, service.available("SKU-1"));
        }

        @Test
        void retryAfterConfirmationStillAnswersTheOriginalReservation() {
            Reservation first = service.reserve("ORDER-1", "SKU-1", 3);
            service.confirm("ORDER-1");

            assertSame(first, service.reserve("ORDER-1", "SKU-1", 3));
            assertEquals(7, service.available("SKU-1"));
        }

        @Test
        void sameOrderWithDifferentContentIsRejected() {
            service.reserve("ORDER-1", "SKU-1", 3);

            assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-1", 4));
            service.registerProduct("SKU-2", ProductCategory.STANDARD);
            service.addStock("SKU-2", 5);
            assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-2", 3));
            assertEquals(7, service.available("SKU-1"));
        }

        @Test
        void retryAfterExpirationStartsANewReservation() {
            Reservation first = service.reserve("ORDER-1", "SKU-1", 3);
            clock.advance(Duration.ofMinutes(15));

            Reservation second = service.reserve("ORDER-1", "SKU-1", 3);

            assertNotEquals(first.expiresAt(), second.expiresAt());
            assertEquals(7, service.available("SKU-1"));
        }
    }

    @Nested
    class Validation {

        @Test
        void unknownProductHasNothingAvailable() {
            assertEquals(0, service.available("UNKNOWN"));
            assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "UNKNOWN", 1));
        }

        @Test
        void quantityMustBePositive() {
            assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-1", 0));
            assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-1", -1));
            assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-1", 0));
            assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-1", -5));
        }

        @Test
        void stockRequiresARegisteredProduct() {
            assertThrows(IllegalArgumentException.class, () -> service.addStock("UNKNOWN", 5));
        }

        @Test
        void identifiersMustNotBeBlank() {
            assertThrows(IllegalArgumentException.class, () -> service.reserve(" ", "SKU-1", 1));
            assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", null, 1));
            assertThrows(IllegalArgumentException.class, () -> service.registerProduct("", ProductCategory.STANDARD));
        }

        @Test
        void registeringTwiceWithTheSameCategoryIsHarmless() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);
            assertEquals(10, service.available("SKU-1"));
        }

        @Test
        void changingTheCategoryOfARegisteredProductIsRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.registerProduct("SKU-1", ProductCategory.FLASH_SALE));
        }
    }
}
