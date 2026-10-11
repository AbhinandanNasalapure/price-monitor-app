package com.pricemonitor.product;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 2048) String url,
        @DecimalMin(value = "0.00", inclusive = false) BigDecimal targetPrice,
        @Pattern(regexp = "(?i)^[A-Z]{3}$", message = "Currency must be a 3-letter code") String currency,
        @Size(max = 128) String priceSelector
) { }
