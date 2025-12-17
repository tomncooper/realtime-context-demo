package com.smartship.api.config;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.Map;

/**
 * Exception mappers for consistent JSON error responses.
 */
public class ExceptionMappers {

    @ServerExceptionMapper
    public Response handleNotFoundException(NotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of(
                "error", exception.getMessage() != null ? exception.getMessage() : "Resource not found",
                "status", 404,
                "timestamp", System.currentTimeMillis()
            ))
            .build();
    }
}
