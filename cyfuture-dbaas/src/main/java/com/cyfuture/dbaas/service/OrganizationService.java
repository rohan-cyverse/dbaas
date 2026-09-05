package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.dto.CreateOrganizationRequest;
import com.cyfuture.dbaas.dto.OrganizationResponse;
import com.cyfuture.dbaas.dto.UpdateOrganizationRequest;
import com.cyfuture.dbaas.entity.OrganizationMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.ResourceStatus;
import com.cyfuture.dbaas.repository.OrganizationMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrganizationService {
    private static final String[] ADJECTIVES = {
            "amber", "bright", "calm", "cobalt", "coral", "golden", "indigo", "jade",
            "lunar", "misty", "north", "quiet", "silver", "solar", "swift", "velvet"
    };
    private static final String[] NOUNS = {
            "birch", "comet", "harbor", "mango", "meadow", "orchid", "otter", "river",
            "summit", "tiger", "valley", "willow", "zephyr", "cedar", "lagoon", "maple"
    };

    private final OrganizationMetadataRepository organizationRepository;
    private final SecureRandom random = new SecureRandom();

    public OrganizationResponse create(CreateOrganizationRequest request) {
        Instant now = Instant.now();
        OrganizationMetadata organization = new OrganizationMetadata();
        organization.setOrganizationId("org-" + shortId());
        organization.setDisplayName(blank(request.displayName()) ? friendlyName() : request.displayName().trim());
        organization.setDescription(request.description());
        organization.setStatus(ResourceStatus.ACTIVE);
        organization.setCreatedAt(now);
        organization.setUpdatedAt(now);
        return response(organizationRepository.save(organization));
    }

    public List<OrganizationResponse> list() {
        return organizationRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::response).toList();
    }

    public OrganizationResponse get(String organizationId) {
        return response(requireActive(organizationId));
    }

    public OrganizationResponse update(String organizationId, UpdateOrganizationRequest request) {
        OrganizationMetadata organization = requireActive(organizationId);
        organization.setDisplayName(request.displayName().trim());
        organization.setDescription(request.description());
        organization.setUpdatedAt(Instant.now());
        return response(organizationRepository.save(organization));
    }

    public OrganizationMetadata requireActive(String organizationId) {
        OrganizationMetadata organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Organization " + organizationId + " was not found"));
        if (organization.getStatus() != ResourceStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Organization " + organizationId + " is not active");
        }
        return organization;
    }

    private String friendlyName() {
        return ADJECTIVES[random.nextInt(ADJECTIVES.length)] + "-"
                + NOUNS[random.nextInt(NOUNS.length)];
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private OrganizationResponse response(OrganizationMetadata organization) {
        return new OrganizationResponse(organization.getOrganizationId(), organization.getDisplayName(),
                organization.getDescription(), organization.getStatus(), organization.getCreatedAt(),
                organization.getUpdatedAt());
    }
}
