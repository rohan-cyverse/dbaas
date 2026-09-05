package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.dto.OrganizationResponse;
import com.cyfuture.dbaas.dto.UpdateOrganizationRequest;
import com.cyfuture.dbaas.entity.OrganizationMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.ResourceStatus;
import com.cyfuture.dbaas.repository.OrganizationMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class OrganizationService {
    /**
     * The organization is backend managed. It is deliberately not supplied by
     * a client request or derived from an editable display name.
     */
    public static final String DEFAULT_ORGANIZATION_ID = "org-000000000000";

    private final OrganizationMetadataRepository organizationRepository;
    private final FriendlyNameGenerator friendlyNameGenerator;

    /** Returns the backend-created organization exposed to the current API surface. */
    @Transactional
    public OrganizationResponse getDefault() {
        return response(requireDefaultOrganization());
    }

    /**
     * Used internally by project creation. The fallback keeps a fresh or
     * manually bootstrapped environment usable even before its seed migration
     * has run; clients have no endpoint that can create an organization.
     */
    @Transactional
    public OrganizationMetadata requireDefaultOrganization() {
        OrganizationMetadata organization = organizationRepository
                .findById(DEFAULT_ORGANIZATION_ID)
                .orElseGet(this::createDefaultOrganization);
        if (organization.getStatus() != ResourceStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "The backend-managed organization is not active");
        }
        return organization;
    }

    /** Only the friendly display details are mutable through the public API. */
    @Transactional
    public OrganizationResponse updateDefault(UpdateOrganizationRequest request) {
        OrganizationMetadata organization = requireDefaultOrganization();
        organization.setDisplayName(request.displayName().trim());
        organization.setDescription(request.description());
        organization.setUpdatedAt(Instant.now());
        return response(organizationRepository.save(organization));
    }

    private OrganizationMetadata createDefaultOrganization() {
        Instant now = Instant.now();
        OrganizationMetadata organization = new OrganizationMetadata();
        organization.setOrganizationId(DEFAULT_ORGANIZATION_ID);
        organization.setDisplayName(friendlyNameGenerator.next());
        organization.setDescription("Backend-managed default organization");
        organization.setStatus(ResourceStatus.ACTIVE);
        organization.setCreatedAt(now);
        organization.setUpdatedAt(now);
        return organizationRepository.save(organization);
    }

    private OrganizationResponse response(OrganizationMetadata organization) {
        return new OrganizationResponse(organization.getOrganizationId(), organization.getDisplayName(),
                organization.getDescription(), organization.getStatus());
    }
}
