package com.pricemonitor.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ProductResponse(
        Long id,
        String name,
        String url,
        String priceSelector,
        String imageUrl,
        BigDecimal targetPrice,
        String currency,
        BigDecimal currentPrice,
        Instant lastCheckedAt,
        Availability availability,
        Instant availabilityCheckedAt,
        String lastCheckError,
        boolean active,
        List<PricePointResponse> history
) {
    static ProductResponse from(Product product) {
        List<PriceSnapshot> snapshots = product.getSnapshots();
        PriceSnapshot latest = snapshots.stream().findFirst().orElse(null);
        return new ProductResponse(
                product.getId(), product.getName(), product.getUrl(), product.getPriceSelector(), product.getImageUrl(), product.getTargetPrice(),
                product.getCurrency(), latest == null ? null : latest.getAmount(), product.getLastCheckedAt(),
                product.getAvailability(), product.getAvailabilityCheckedAt(), product.getLastCheckError(), product.isActive(),
                snapshots.stream().limit(60).map(PricePointResponse::from).toList()
        );
    }
}
