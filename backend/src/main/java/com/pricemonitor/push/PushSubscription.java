package com.pricemonitor.push;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "push_subscriptions")
public class PushSubscription {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 2048)
    private String endpoint;

    @Column(nullable = false, length = 256)
    private String publicKey;

    @Column(nullable = false, length = 128)
    private String authSecret;

    protected PushSubscription() { }

    public PushSubscription(String endpoint, String publicKey, String authSecret) {
        this.endpoint = endpoint;
        this.publicKey = publicKey;
        this.authSecret = authSecret;
    }

    public Long getId() { return id; }
    public String getEndpoint() { return endpoint; }
    public String getPublicKey() { return publicKey; }
    public String getAuthSecret() { return authSecret; }
}
