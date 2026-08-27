package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.dto.OperationResponse;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.mapper.OperationMapper;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OperationService {
    private final OperationMetadataRepository operationRepository;
    private final OperationMapper operationMapper;

    public OperationResponse get(String project, String operationId) {
        return operationMapper.toResponse(operationRepository
                .findByOperationIdAndProjectName(operationId, project)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Operation " + operationId + " was not found in project " + project)));
    }

    public OperationResponse getForDatabase(String project,
                                            String databaseId, String operationId) {
        return operationMapper.toResponse(operationRepository
                .findByOperationIdAndDatabaseIdAndProjectName(
                        operationId, databaseId, project)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Operation " + operationId + " was not found for database "
                                + databaseId + " in project " + project)));
    }

    public List<OperationResponse> listForDatabase(String project,
                                                   String databaseId) {
        return operationRepository
                .findByDatabaseIdAndProjectNameOrderByCreatedAtDesc(databaseId, project)
                .stream().map(operationMapper::toResponse).toList();
    }
}
