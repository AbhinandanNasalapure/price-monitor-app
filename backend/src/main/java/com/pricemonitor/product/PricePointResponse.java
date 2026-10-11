package com.pricemonitor.product;

import java.math.BigDecimal;
import java.time.Instant;

public record PricePointResponse(BigDecimal amount, String currency, Instant checkedAt) {
    static PricePointResponse from(PriceSnapshot snapshot) {
        return new PricePointResponse(snapshot.getAmount(), snapshot.getCurrency(), snapshot.getCheckedAt());
    }
}
