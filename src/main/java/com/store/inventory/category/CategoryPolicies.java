package com.store.inventory.category;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

/**
 * Rule table per {@link ProductCategory}. Adding a category means adding one row here;
 * the constructor refuses tables that leave a category without rules.
 */
public final class CategoryPolicies {

    private final Map<ProductCategory, CategoryPolicy> policies;

    public CategoryPolicies(Map<ProductCategory, CategoryPolicy> policies) {
        Objects.requireNonNull(policies, "policies");
        EnumSet<ProductCategory> missing = EnumSet.allOf(ProductCategory.class);
        missing.removeAll(policies.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Categories without policy: " + missing);
        }
        this.policies = new EnumMap<>(policies);
    }

    public static CategoryPolicies defaults() {
        return new CategoryPolicies(Map.of(
                ProductCategory.STANDARD, CategoryPolicy.of(Duration.ofMinutes(15)),
                ProductCategory.PRE_ORDER, CategoryPolicy.of(Duration.ofHours(24)),
                ProductCategory.FLASH_SALE, CategoryPolicy.of(Duration.ofMinutes(5), 2)));
    }

    public CategoryPolicy policyFor(ProductCategory category) {
        return policies.get(Objects.requireNonNull(category, "category"));
    }
}
