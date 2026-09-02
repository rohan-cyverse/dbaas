package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class ClientIpResolver {
    private final boolean allowEgressFallback;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public ClientIpResolver(
            @Value("${dbaas.client-ip.allow-egress-fallback:true}")
            boolean allowEgressFallback) {
        this.allowEgressFallback = allowEgressFallback;
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddress = clean(request.getRemoteAddr());

        if (isLocalOrPrivate(remoteAddress)) {
            String forwarded = firstAddress(request.getHeader("X-Forwarded-For"));
            if (isPublicIpv4(forwarded)) return forwarded;

            String realIp = clean(request.getHeader("X-Real-IP"));
            if (isPublicIpv4(realIp)) return realIp;

            // During local development the API and client are on the same
            // laptop/LAN. Resolve the laptop's current public egress address so
            // Postman never needs a manually supplied forwarding header.
            if (allowEgressFallback) {
                String egressIp = lookupLocalMachinePublicIp();
                if (isPublicIpv4(egressIp)) return egressIp;
            }
        }

        if (isPublicIpv4(remoteAddress)) return remoteAddress;

        throw new ApiException(HttpStatus.BAD_REQUEST,
                "Could not detect a public client IP. The production reverse proxy must send X-Forwarded-For");
    }

    private String lookupLocalMachinePublicIp() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.ipify.org"))
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();
            return clean(httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstAddress(String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank()) return null;
        return clean(forwardedFor.split(",")[0]);
    }

    private String clean(String value) {
        return value == null ? null : value.trim();
    }

    private boolean isPublicIpv4(String value) {
        if (value == null || !value.matches("^(\\d{1,3}\\.){3}\\d{1,3}$")) return false;
        try {
            InetAddress address = InetAddress.getByName(value);
            return address instanceof Inet4Address
                    && !address.isAnyLocalAddress()
                    && !address.isLoopbackAddress()
                    && !address.isLinkLocalAddress()
                    && !address.isSiteLocalAddress()
                    && !address.isMulticastAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isLocalOrPrivate(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            InetAddress address = InetAddress.getByName(value);
            return address.isLoopbackAddress() || address.isSiteLocalAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

}
