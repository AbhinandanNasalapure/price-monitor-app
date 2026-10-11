package com.pricemonitor.product;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tracked_products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 160)
    @Column(nullable = false, length = 160)
    private String name;

    @NotBlank
    @Size(max = 2048)
    @Column(nullable = false, length = 2048)
    private String url;

    @Size(max = 512)
    @Column(length = 512)
    private String imageUrl;

    @Size(max = 128)
    @Column(length = 128)
    private String priceSelector;

    @DecimalMin(value = "0.00", inclusive = false)
    @Column(precision = 14, scale = 2)
    private BigDecimal targetPrice;

    @NotBlank
    @Size(min = 3, max = 3)
    @Column(nullable = false, length = 3)
    private String currency = "USD";

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant lastCheckedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Availability availability = Availability.UNKNOWN;

    private Instant availabilityCheckedAt;

    @Size(max = 500)
    @Column(length = 500)
    private String lastCheckError;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("checkedAt DESC")
    private List<PriceSnapshot> snapshots = new ArrayList<>();

    protected Product() { }

    public Product(String name, String url, BigDecimal targetPrice, String currency, String priceSelector) {
        this.name = name.trim();
        this.url = url.trim();
        this.targetPrice = targetPrice;
        this.currency = currency == null || currency.isBlank() ? "USD" : currency.trim().toUpperCase();
        this.priceSelector = priceSelector == null || priceSelector.isBlank() ? null : priceSelector.trim();
    }

    public void updateDetails(String name, String url, BigDecimal targetPrice, String currency, String priceSelector) {
        this.name = name.trim();
        this.url = url.trim();
        this.targetPrice = targetPrice;
        this.currency = currency == null || currency.isBlank() ? "USD" : currency.trim().toUpperCase();
        this.priceSelector = priceSelector == null || priceSelector.isBlank() ? null : priceSelector.trim();
    }

    public void record(PriceSnapshot snapshot) {
        snapshots.add(snapshot);
        snapshot.setProduct(this);
        lastCheckedAt = snapshot.getCheckedAt();
        lastCheckError = null;
    }

    public void setAvailability(Availability availability) {
        this.availability = availability == null ? Availability.UNKNOWN : availability;
        this.availabilityCheckedAt = Instant.now();
    }

    public void markChecking() {
        this.availability = Availability.CHECKING;
    }

    public void failedCheck(String message) {
        lastCheckedAt = Instant.now();
        if (availability == Availability.CHECKING) setAvailability(Availability.UNKNOWN);
        lastCheckError = message == null ? "Price could not be read from this page." : message.substring(0, Math.min(message.length(), 500));
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getUrl() { return url; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getPriceSelector() { return priceSelector; }
    public BigDecimal getTargetPrice() { return targetPrice; }
    public String getCurrency() { return currency; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public Availability getAvailability() { return availability == null ? Availability.UNKNOWN : availability; }
    public Instant getAvailabilityCheckedAt() { return availabilityCheckedAt; }
    public String getLastCheckError() { return lastCheckError; }
    public List<PriceSnapshot> getSnapshots() { return snapshots; }
}
