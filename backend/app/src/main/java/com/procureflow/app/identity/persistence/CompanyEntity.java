package com.procureflow.app.identity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "company")
class CompanyEntity {
    @Id
    private UUID id;

    @Column(nullable = false, length = 63)
    private String slug;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CompanyEntity() {}
}
