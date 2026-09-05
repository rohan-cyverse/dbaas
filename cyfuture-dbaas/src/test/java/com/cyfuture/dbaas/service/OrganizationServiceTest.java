package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.dto.CreateOrganizationRequest;
import com.cyfuture.dbaas.entity.OrganizationMetadata;
import com.cyfuture.dbaas.repository.OrganizationMetadataRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrganizationServiceTest {
    @Test
    void generatesFriendlyRandomNameWhenDisplayNameIsOmitted() {
        OrganizationMetadataRepository repository = mock(OrganizationMetadataRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        OrganizationService service = new OrganizationService(repository, new FriendlyNameGenerator());

        var response = service.create(new CreateOrganizationRequest(null, "Development tenant"));

        assertTrue(response.organizationId().matches("org-[a-f0-9]{12}"));
        assertTrue(response.displayName().matches("[a-z]+-[a-z]+"));
        assertEquals("Development tenant", response.description());
    }

    @Test
    void preservesExplicitFriendlyDisplayName() {
        OrganizationMetadataRepository repository = mock(OrganizationMetadataRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        OrganizationService service = new OrganizationService(repository, new FriendlyNameGenerator());

        var response = service.create(new CreateOrganizationRequest("Cyfuture Noida", null));

        assertEquals("Cyfuture Noida", response.displayName());
    }
}
