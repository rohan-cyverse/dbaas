package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.dto.OperationResponse;
import com.cyfuture.dbaas.dto.PageResponse;
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

    public PageResponse<OperationResponse> listForDatabase(String project,
                                                           String databaseId,
                                                           int page,
                                                           int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        List<OperationResponse> filtered = operationRepository
                .findByDatabaseIdAndProjectNameOrderByCreatedAtDesc(databaseId, project)
                .stream()
                .map(operationMapper::toResponse)
                .toList();
        int from = Math.min(safePage * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        int totalPages = filtered.isEmpty() ? 0
                : (int) Math.ceil((double) filtered.size() / safeSize);
        return new PageResponse<>(filtered.subList(from, to), safePage, safeSize,
                filtered.size(), totalPages);
    }

}
