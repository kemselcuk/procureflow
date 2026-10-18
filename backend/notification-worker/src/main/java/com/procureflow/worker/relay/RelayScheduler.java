package com.procureflow.worker.relay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic driver; can be disabled for tests or a future externally triggered mode. */
@Component
@ConditionalOnProperty(name = "worker.relay.scheduled", havingValue = "true", matchIfMissing = true)
class RelayScheduler {
    private final NotificationOutboxRelay relay;

    RelayScheduler(NotificationOutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${worker.relay.poll-interval}")
    void poll() {
        relay.runOnce();
    }
}
