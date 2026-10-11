package com.pricemonitor.product;

import com.pricemonitor.push.PushService;
import com.pricemonitor.scrape.PriceReader;
import com.pricemonitor.scrape.PriceReading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductCheckWorker {
    private static final Logger log = LoggerFactory.getLogger(ProductCheckWorker.class);

    private final ProductRepository products;
    private final PriceReader priceReader;
    private final PushService pushService;

    public ProductCheckWorker(ProductRepository products, PriceReader priceReader, PushService pushService) {
        this.products = products;
        this.priceReader = priceReader;
        this.pushService = pushService;
    }

    @Async("priceCheckExecutor")
    @Transactional
    public void checkInBackground(long id) {
        products.findWithSnapshotsById(id).ifPresent(this::checkAndSave);
    }

    @Transactional
    public ProductResponse checkNow(long id) {
        Product product = products.findWithSnapshotsById(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Tracked product not found"));
        checkAndSave(product);
        return ProductResponse.from(product);
    }

    @Transactional
    public void checkActiveProducts() {
        for (Product product : products.findAllByOrderByCreatedAtDesc()) {
            if (product.isActive()) checkAndSave(product);
        }
    }

    private void checkAndSave(Product product) {
        String host = hostOf(product.getUrl());
        log.info("Checking productId={}, name='{}', host={}", product.getId(), product.getName(), host);
        try {
            PriceReading reading = priceReader.read(product.getUrl(), product.getPriceSelector());
            product.setAvailability(reading.availability());
            if (reading.amount() == null) {
                product.failedCheck("No current price is available; the last successful price is retained.");
                products.save(product);
                log.info("Availability updated productId={}, name='{}', status={}; no new price was recorded",
                        product.getId(), product.getName(), reading.availability());
                return;
            }

            var previous = product.getSnapshots().isEmpty() ? null : product.getSnapshots().get(0);
            String currency = reading.currency() == null ? product.getCurrency() : reading.currency();
            boolean targetCrossed = product.getTargetPrice() != null && previous != null
                    && previous.getCurrency().equalsIgnoreCase(currency)
                    && previous.getAmount().compareTo(product.getTargetPrice()) > 0
                    && reading.amount().compareTo(product.getTargetPrice()) <= 0;
            product.record(new PriceSnapshot(reading.amount(), currency));
            if (reading.imageUrl() != null) product.setImageUrl(reading.imageUrl());
            products.save(product);
            log.info("Check succeeded productId={}, name='{}', price={} {}, availability={}",
                    product.getId(), product.getName(), reading.amount(), currency, reading.availability());
            if (targetCrossed) {
                pushService.sendTargetReached(product.getName(), product.getUrl(), currency, reading.amount().toPlainString());
            }
        } catch (RuntimeException exception) {
            product.failedCheck(exception.getMessage());
            products.save(product);
            log.warn("Check failed productId={}, name='{}', host={}, reason={}",
                    product.getId(), product.getName(), host, exception.getMessage());
        }
    }

    private String hostOf(String url) {
        try { return java.net.URI.create(url).getHost(); }
        catch (RuntimeException ignored) { return "unknown"; }
    }
}
