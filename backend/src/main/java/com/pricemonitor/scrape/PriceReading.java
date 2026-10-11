package com.pricemonitor.scrape;

import java.math.BigDecimal;

public record PriceReading(BigDecimal amount, String currency, String imageUrl) { }
