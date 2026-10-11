package com.pricemonitor.product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PriceCheckScheduler {
    private static final Logger log = LoggerFactory.getLogger(PriceCheckScheduler.class);
    private final ProductService productService;

    public PriceCheckScheduler(ProductService productService) {
        this.productService = productService;
    }

    @Scheduled(fixedDelayString = "${monitor.interval-ms:1800000}", initialDelay = 30000)
    public void refreshPrices() {
        try { productService.checkActiveProducts(); }
        catch (Exception exception) { log.warn("Scheduled price refresh did not finish: {}", exception.getMessage()); }
    }
}
