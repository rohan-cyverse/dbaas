package com.cyfuture.dbaas.service;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientIpResolverTest {

    @Test
    void trustsForwardedClientIpFromConfiguredPublicProxy() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("49.50.116.159");
        when(request.getHeader("X-Forwarded-For"))
                .thenReturn("49.50.73.146, 49.50.116.159");

        ClientIpResolver resolver = new ClientIpResolver(false, "49.50.116.159/32");

        assertEquals("49.50.73.146", resolver.resolve(request));
    }

    @Test
    void ignoresSpoofedForwardedHeaderFromUntrustedPublicCaller() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.20");
        when(request.getHeader("X-Forwarded-For")).thenReturn("49.50.73.146");

        ClientIpResolver resolver = new ClientIpResolver(false, "49.50.116.159/32");

        assertEquals("203.0.113.20", resolver.resolve(request));
    }

    @Test
    void supportsTrustedProxyNetworks() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("49.50.116.159");
        when(request.getHeader("X-Forwarded-For")).thenReturn("49.50.73.146");

        ClientIpResolver resolver = new ClientIpResolver(false, "49.50.116.0/24");

        assertEquals("49.50.73.146", resolver.resolve(request));
    }
}
