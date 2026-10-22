package com.procureflow.app.requests;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused tests for the producer's envelope-size guard. The 16 KiB single-recipient throw is
 * structurally unreachable with the frozen schema-v1 recipient shape (two UUIDs and two channels
 * are about 134 bytes, so one recipient cannot approach 16 KiB), so these tests exercise the same
 * branch with a deliberately tiny bound. The command transaction rollback on that throw is the
 * existing {@code RequestLifecycleIT.downstreamFailureRollsBack...} path, not re-tested here.
 */
class RequestEventsBoundTest {

    @Test
    void candidateThatFitsIsKept() {
        assertThat(RequestEvents.boundedBatchSize(40, 8_000, size -> size * 200)).isEqualTo(40);
    }

    @Test
    void overflowHalvesUntilTheBodyFits() {
        assertThat(RequestEvents.boundedBatchSize(40, 7_000, size -> size * 200)).isEqualTo(20);
    }

    @Test
    void singleRecipientThatFitsTheBoundIsAllowed() {
        assertThat(RequestEvents.boundedBatchSize(1, 100, size -> 100)).isEqualTo(1);
    }

    @Test
    void singleRecipientOverTheBoundFailsLoudly() {
        assertThatThrownBy(() -> RequestEvents.boundedBatchSize(1, 100, size -> 101))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("single-recipient")
                .hasMessageContaining("100-byte");
    }
}
