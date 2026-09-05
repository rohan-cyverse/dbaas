package com.cyfuture.dbaas.entity;

import com.cyfuture.dbaas.model.ResourceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
public class ProjectMetadata {
    @Id
    @Column(length = 32)
    private String projectId;

    @Column(nullable = false, length = 32)
    private String organizationId;

    @Column(nullable = false, length = 64)
    private String displayName;

    @Column(length = 250)
    private String description;

    @Column(nullable = false, length = 63)
    private String namespaceName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ResourceStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;
}
