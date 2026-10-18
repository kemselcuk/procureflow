package com.procureflow.app.requests;

import com.procureflow.app.identity.ActorAccess;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RequestQueries {
    private final ActorAccess access;
    private final RequestRepository requests;
    RequestQueries(ActorAccess access, RequestRepository requests) { this.access = access; this.requests = requests; }
    public RequestData.View detail(UUID id) {
        var actor = access.current(); var request = requests.find(actor.companyId(), id, false);
        requests.requireRead(actor, request); return request.view();
    }
    public List<RequestData.History> history(UUID id) {
        var actor = access.current(); var request = requests.find(actor.companyId(), id, false);
        requests.requireRead(actor, request); return requests.history(actor.companyId(), id);
    }
    public RequestData.Page list(String view, RequestStatus status, int page, int size) {
        return requests.page(access.current(), view, status, page, size);
    }
}
