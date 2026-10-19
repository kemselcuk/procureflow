package com.procureflow.worker.email;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Test-only in-process seam that records send/save attempts and can simulate a worker crash. */
public final class CapturingEmailInstrumentation implements EmailInstrumentation {
    private final List<UUID> sends = new CopyOnWriteArrayList<>();
    private final List<UUID> sentSaves = new CopyOnWriteArrayList<>();
    private volatile RuntimeException saveFailure;
    private volatile Consumer<ClaimedEmailJob> beforeSendCheck;

    public void failBeforeSave(String message) {
        this.saveFailure = new IllegalStateException(message);
    }

    public void onBeforeSend(Consumer<ClaimedEmailJob> check) {
        this.beforeSendCheck = check;
    }

    @Override
    public void beforeSend(ClaimedEmailJob job) {
        sends.add(job.jobId());
        Consumer<ClaimedEmailJob> check = beforeSendCheck;
        if (check != null) {
            check.accept(job);
        }
    }

    @Override
    public void beforeSentStateSave(UUID jobId) {
        sentSaves.add(jobId);
        if (saveFailure != null) {
            throw saveFailure;
        }
    }

    public List<UUID> sends() {
        return List.copyOf(sends);
    }

    public List<UUID> sentSaves() {
        return List.copyOf(sentSaves);
    }
}
