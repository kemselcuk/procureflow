package com.procureflow.app.notifications;

import com.procureflow.app.identity.ActorAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class NotificationQueries {
    private final ActorAccess access;
    private final NotificationRepository notifications;

    NotificationQueries(ActorAccess access, NotificationRepository notifications) {
        this.access = access;
        this.notifications = notifications;
    }

    /** Current company/identity only; a spoofed company or identity scope is never accepted. */
    public NotificationData.Page list(int page, int size) {
        return notifications.page(access.current(), page, size);
    }

    public NotificationData.Backlog backlog() {
        var actor = access.current();
        actor.requireAdmin();
        return notifications.backlog(actor.companyId());
    }
}
