package com.pricemonitor.product;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
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
        Instant cutoff = Instant.now().minus(Duration.ofDays(90));
        List<PriceSnapshot> snapshots = product.getSnapshots().stream()
                .sorted(Comparator.comparing(PriceSnapshot::getCheckedAt).reversed())
                .toList();
        PriceSnapshot latest = snapshots.stream().findFirst().orElse(null);
        List<PricePointResponse> recentHistory = snapshots.stream()
                .filter(snapshot -> !snapshot.getCheckedAt().isBefore(cutoff))
                .map(PricePointResponse::from)
                .toList();
        return new ProductResponse(
                product.getId(), product.getName(), product.getUrl(), product.getPriceSelector(), product.getImageUrl(), product.getTargetPrice(),
                product.getCurrency(), latest == null ? null : latest.getAmount(), product.getLastCheckedAt(),
                product.getAvailability(), product.getAvailabilityCheckedAt(), product.getLastCheckError(), product.isActive(),
                recentHistory
        );
    }
}
