package com.pricemonitor.push;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PushSubscriptionRequest(
        @NotBlank @Size(max = 2048) String endpoint,
        @Valid Keys keys
) {
    public record Keys(@NotBlank @Size(max = 256) String p256dh, @NotBlank @Size(max = 128) String auth) { }
}
