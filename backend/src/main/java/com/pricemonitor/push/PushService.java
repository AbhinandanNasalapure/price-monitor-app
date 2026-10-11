package com.pricemonitor.push;

import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

@Service
public class PushService {
    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    private static final String CHROME_PUSH_HOST = "fcm.googleapis.com";

    private final PushSubscriptionRepository subscriptions;
    private final String publicKey;
    private final boolean configured;
    private final nl.martijndwars.webpush.PushService webPush;

    public PushService(PushSubscriptionRepository subscriptions,
                       @Value("${monitor.push.public-key:}") String publicKey,
                       @Value("${monitor.push.private-key:}") String privateKey,
                       @Value("${monitor.push.subject:mailto:admin@example.com}") String subject) {
        this.subscriptions = subscriptions;
        this.publicKey = publicKey;
        this.configured = !publicKey.isBlank() && !privateKey.isBlank();
        if (configured) {
            java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
            try {
                this.webPush = new nl.martijndwars.webpush.PushService()
                        .setPublicKey(publicKey).setPrivateKey(privateKey).setSubject(subject);
            } catch (java.security.GeneralSecurityException exception) {
                throw new IllegalStateException("The configured VAPID key pair is invalid.", exception);
            }
        } else {
            this.webPush = null;
        }
    }

    public Map<String, Object> configuration() {
        return Map.of("enabled", configured, "publicKey", configured ? publicKey : "");
    }

    public void save(PushSubscriptionRequest request) {
        URI endpoint;
        try { endpoint = URI.create(request.endpoint()); }
        catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid push endpoint."); }
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || !CHROME_PUSH_HOST.equalsIgnoreCase(endpoint.getHost())
                || request.keys() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only valid HTTPS Chrome push subscriptions are accepted.");
        }
        if (!configured) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Set VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY in .env first.");
        subscriptions.findByEndpoint(request.endpoint()).ifPresentOrElse(existing -> {
            subscriptions.delete(existing);
            subscriptions.save(new PushSubscription(request.endpoint(), request.keys().p256dh(), request.keys().auth()));
        }, () -> subscriptions.save(new PushSubscription(request.endpoint(), request.keys().p256dh(), request.keys().auth())));
    }

    public void remove(String endpoint) {
        subscriptions.deleteByEndpoint(endpoint);
    }

    public void sendTargetReached(String productName, String productUrl, String currency, String price) {
        send(Map.of("title", "Price target reached", "body", productName + " is now " + currency + " " + price,
                "url", productUrl));
    }

    public void sendTest() {
        send(Map.of("title", "Price Monitor", "body", "Browser notifications are working.", "url", "/"));
    }

    private void send(Map<String, String> payload) {
        if (!configured) return;
        String message = "{\"title\":\"" + escapeJson(payload.get("title")) + "\",\"body\":\""
                + escapeJson(payload.get("body")) + "\",\"url\":\"" + escapeJson(payload.get("url")) + "\"}";

        for (PushSubscription saved : subscriptions.findAll()) {
            try {
                Subscription.Keys keys = new Subscription.Keys(saved.getPublicKey(), saved.getAuthSecret());
                Subscription subscription = new Subscription(saved.getEndpoint(), keys);
                webPush.send(new Notification(subscription, message));
            } catch (Exception exception) {
                log.warn("Could not deliver browser push notification: {}", exception.getMessage());
            }
        }
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }
}
