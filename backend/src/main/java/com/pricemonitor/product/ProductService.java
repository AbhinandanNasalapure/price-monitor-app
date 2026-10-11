package com.pricemonitor.product;

import com.pricemonitor.scrape.PriceReader;
import com.pricemonitor.scrape.PriceReading;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

@Service
public class ProductService {
    private final ProductRepository products;
    private final PriceReader priceReader;

    public ProductService(ProductRepository products, PriceReader priceReader) {
        this.products = products;
        this.priceReader = priceReader;
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> list() {
        return products.findAllByOrderByCreatedAtDesc().stream().map(ProductResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse get(long id) {
        return ProductResponse.from(requireProduct(id));
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        validatePublicUrl(request.url());
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
        try {
            PriceReading reading = priceReader.read(product.getUrl(), product.getPriceSelector());
            product.record(new PriceSnapshot(reading.amount(), reading.currency() == null ? product.getCurrency() : reading.currency()));
            if (reading.imageUrl() != null) product.setImageUrl(reading.imageUrl());
            products.save(product);
        } catch (RuntimeException exception) {
            product.failedCheck(exception.getMessage());
        }
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
