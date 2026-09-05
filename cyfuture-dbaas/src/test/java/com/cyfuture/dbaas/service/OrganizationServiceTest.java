package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.dto.UpdateOrganizationRequest;
import com.cyfuture.dbaas.entity.OrganizationMetadata;
import com.cyfuture.dbaas.model.ResourceStatus;
import com.cyfuture.dbaas.repository.OrganizationMetadataRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrganizationServiceTest {
    @Test
    void backendCreatesTheDefaultOrganizationWithAnImmutableIdAndFriendlyName() {
        OrganizationMetadataRepository repository = mock(OrganizationMetadataRepository.class);
        FriendlyNameGenerator friendlyNames = mock(FriendlyNameGenerator.class);
        when(repository.findById(OrganizationService.DEFAULT_ORGANIZATION_ID)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(friendlyNames.next()).thenReturn("amber-river");
        OrganizationService service = new OrganizationService(repository, friendlyNames);

        var response = service.getDefault();

        assertEquals(OrganizationService.DEFAULT_ORGANIZATION_ID, response.organizationId());
        assertEquals("amber-river", response.displayName());
        assertEquals("Backend-managed default organization", response.description());
        assertEquals(ResourceStatus.ACTIVE, response.status());
    }

    @Test
    void onlyUpdatesTheDefaultOrganizationDisplayDetails() {
        OrganizationMetadataRepository repository = mock(OrganizationMetadataRepository.class);
        FriendlyNameGenerator friendlyNames = mock(FriendlyNameGenerator.class);
        OrganizationMetadata organization = new OrganizationMetadata();
        organization.setOrganizationId(OrganizationService.DEFAULT_ORGANIZATION_ID);
        organization.setDisplayName("amber-river");
        organization.setDescription("Original description");
        organization.setStatus(ResourceStatus.ACTIVE);
        when(repository.findById(OrganizationService.DEFAULT_ORGANIZATION_ID))
                .thenReturn(Optional.of(organization));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        OrganizationService service = new OrganizationService(repository, friendlyNames);

        var response = service.updateDefault(new UpdateOrganizationRequest("Customer Platform", "Updated details"));

        assertEquals("Customer Platform", response.displayName());
        assertEquals("Updated details", response.description());
    }
}
