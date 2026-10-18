package com.procureflow.app.notifications;

import com.procureflow.app.identity.ActorAccess;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationCommands {
    private final ActorAccess access;
    private final NotificationRepository notifications;

    NotificationCommands(ActorAccess access, NotificationRepository notifications) {
        this.access = access;
        this.notifications = notifications;
    }

    /**
     * Uses the shared company authorization barrier (company SHARE) before touching the delivery
     * row, exactly like other commands; ownership stays the current identity, not the current role.
     */
    @Transactional
    public NotificationData.Read markRead(UUID id) {
        return notifications.markRead(access.command(false), id);
    }
}
