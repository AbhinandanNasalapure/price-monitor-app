package com.pricemonitor.scrape;

import java.math.BigDecimal;
import com.pricemonitor.product.Availability;

public record PriceReading(BigDecimal amount, String currency, String imageUrl, Availability availability) { }
