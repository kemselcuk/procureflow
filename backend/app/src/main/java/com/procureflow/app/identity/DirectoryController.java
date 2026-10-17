package com.procureflow.app.identity;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class DirectoryController {
    private final DirectoryService service;
    public DirectoryController(DirectoryService service) { this.service = service; }

    @PostMapping("/api/identities/self")
    DirectoryService.IdentityView register() { return service.registerSelf(); }

    @GetMapping("/api/me")
    Actor me() { return service.me(); }

    @GetMapping("/api/departments")
    List<DirectoryService.DepartmentView> departments() { return service.departments(); }

    @PostMapping("/api/admin/departments")
    @ResponseStatus(HttpStatus.CREATED)
    DirectoryService.DepartmentView createDepartment(@Valid @RequestBody DirectoryService.NewDepartment input) {
        return service.createDepartment(input);
    }

    @GetMapping("/api/admin/memberships")
    List<DirectoryService.MembershipView> members() { return service.memberships(); }

    @PostMapping("/api/admin/memberships")
    @ResponseStatus(HttpStatus.CREATED)
    DirectoryService.MembershipView createMember(@Valid @RequestBody DirectoryService.NewMembership input) {
        return service.createMembership(input);
    }

    @PutMapping("/api/admin/memberships/{id}")
    DirectoryService.MembershipView updateMember(@PathVariable UUID id, @Valid @RequestBody DirectoryService.UpdateMembership input) {
        return service.updateMembership(id, input);
    }
}
