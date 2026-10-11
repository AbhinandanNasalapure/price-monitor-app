package com.pricemonitor.product;

import com.pricemonitor.scrape.PriceReader;
import com.pricemonitor.scrape.PriceReading;
import com.pricemonitor.push.PushService;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

@Service
public class ProductService {
    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository products;
    private final PriceReader priceReader;
    private final PushService pushService;

    public ProductService(ProductRepository products, PriceReader priceReader, PushService pushService) {
        this.products = products;
        this.priceReader = priceReader;
        this.pushService = pushService;
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> list() {
        return products.findAllByOrderByCreatedAtDesc().stream().map(ProductResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse get(long id) {
        return ProductResponse.from(requireProduct(id));
    }

    public ProductResponse create(ProductRequest request) {
        validatePublicUrl(request.url());
        log.info("Creating price monitor name='{}', host={}, target={} {}", request.name(), hostOf(request.url()), request.targetPrice(), request.currency());
        Product product = products.save(new Product(request.name(), request.url(), request.targetPrice(), request.currency(), request.priceSelector()));
        checkAndSave(product);
        return ProductResponse.from(products.findWithSnapshotsById(product.getId()).orElse(product));
    }

    @Transactional
    public ProductResponse update(long id, ProductRequest request) {
        validatePublicUrl(request.url());
        Product product = requireProduct(id);
        product.updateDetails(request.name(), request.url(), request.targetPrice(), request.currency(), request.priceSelector());
        return ProductResponse.from(product);
    }

    @Transactional
    public ProductResponse check(long id) {
        Product product = requireProduct(id);
        checkAndSave(product);
        return ProductResponse.from(product);
    }

    @Transactional
    public void delete(long id) {
        products.delete(requireProduct(id));
    }

    @Transactional
    public void checkActiveProducts() {
        for (Product product : products.findAllByOrderByCreatedAtDesc()) {
            if (product.isActive()) checkAndSave(product);
        }
    }

    private void checkAndSave(Product product) {
        String host = hostOf(product.getUrl());
        log.info("Checking price productId={}, name='{}', host={}", product.getId(), product.getName(), host);
        try {
            PriceReading reading = priceReader.read(product.getUrl(), product.getPriceSelector());
            var previous = product.getSnapshots().isEmpty() ? null : product.getSnapshots().get(0);
            boolean targetCrossed = product.getTargetPrice() != null && previous != null
                    && previous.getCurrency().equalsIgnoreCase(reading.currency() == null ? product.getCurrency() : reading.currency())
                    && previous.getAmount().compareTo(product.getTargetPrice()) > 0
                    && reading.amount().compareTo(product.getTargetPrice()) <= 0;
            product.record(new PriceSnapshot(reading.amount(), reading.currency() == null ? product.getCurrency() : reading.currency()));
            if (reading.imageUrl() != null) product.setImageUrl(reading.imageUrl());
            products.save(product);
            log.info("Price check succeeded productId={}, name='{}', price={} {}", product.getId(), product.getName(), reading.amount(), reading.currency() == null ? product.getCurrency() : reading.currency());
            if (targetCrossed) {
                pushService.sendTargetReached(product.getName(), product.getUrl(),
                        reading.currency() == null ? product.getCurrency() : reading.currency(), reading.amount().toPlainString());
            }
        } catch (RuntimeException exception) {
            product.failedCheck(exception.getMessage());
            products.save(product);
            log.warn("Price check failed productId={}, name='{}', host={}, reason={}", product.getId(), product.getName(), host, exception.getMessage());
        }
    }

    private String hostOf(String value) {
        try { return URI.create(value.trim()).getHost(); }
        catch (RuntimeException exception) { return "unknown"; }
    }

    private Product requireProduct(long id) {
        return products.findWithSnapshotsById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked product not found"));
    }

    private void validatePublicUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("Use a public http or https product URL without embedded credentials.");
            }
            String normalizedHost = host.toLowerCase(java.util.Locale.ROOT);
            String path = uri.getPath();
            boolean amazonHost = normalizedHost.startsWith("amazon.") || normalizedHost.contains(".amazon.");
            if (amazonHost && (path == null || !path.matches("(?i).*/(?:dp|gp/product)/[A-Z0-9]{10}(?:/.*)?$"))) {
                throw new IllegalArgumentException("Use the direct Amazon product page URL containing /dp/ and the product ASIN, not the Amazon home or search page.");
            }
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("Local and private network URLs are not allowed.");
                }
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The product URL could not be resolved.");
        }
    }
}
