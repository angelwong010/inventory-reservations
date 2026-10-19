package com.store.inventory.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CategoryPoliciesTest {

    @Test
    void everyCategoryHasADefaultPolicy() {
        CategoryPolicies policies = CategoryPolicies.defaults();
        for (ProductCategory category : ProductCategory.values()) {
            assertNotNull(policies.policyFor(category), category + " has no policy");
        }
    }

    @Test
    void defaultsMatchTheBusinessTable() {
        CategoryPolicies policies = CategoryPolicies.defaults();

        assertEquals(CategoryPolicy.of(Duration.ofMinutes(15)), policies.policyFor(ProductCategory.STANDARD));
        assertEquals(CategoryPolicy.of(Duration.ofHours(24)), policies.policyFor(ProductCategory.PRE_ORDER));
        assertEquals(CategoryPolicy.of(Duration.ofMinutes(5), 2), policies.policyFor(ProductCategory.FLASH_SALE));
    }

    @Test
    void aTableThatForgetsACategoryIsRejected() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new CategoryPolicies(Map.of(ProductCategory.STANDARD, CategoryPolicy.of(Duration.ofMinutes(1)))));

        assertTrue(error.getMessage().contains("PRE_ORDER"));
        assertTrue(error.getMessage().contains("FLASH_SALE"));
    }

    @Test
    void policyWithoutLimitAllowsAnyQuantity() {
        CategoryPolicy policy = CategoryPolicy.of(Duration.ofMinutes(1));

        assertFalse(policy.hasOrderLimit());
        assertTrue(policy.allowsPerOrder(Integer.MAX_VALUE));
    }

    @Test
    void policyRejectsNonsenseValues() {
        assertThrows(IllegalArgumentException.class, () -> CategoryPolicy.of(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> CategoryPolicy.of(Duration.ofMinutes(-1)));
        assertThrows(IllegalArgumentException.class, () -> CategoryPolicy.of(Duration.ofMinutes(1), 0));
    }
}
