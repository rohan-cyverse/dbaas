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
import java.util.Arrays;
import java.util.List;

@Service
public class ClientIpResolver {
    private final boolean allowEgressFallback;
    private final List<String> trustedProxyCidrs;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public ClientIpResolver(
            @Value("${dbaas.client-ip.allow-egress-fallback:true}") boolean allowEgressFallback,
            @Value("${dbaas.client-ip.trusted-proxies:}") String trustedProxies) {
        this.allowEgressFallback = allowEgressFallback;
        this.trustedProxyCidrs = Arrays.stream(trustedProxies.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddress = clean(request.getRemoteAddr());

        if (isLocalOrPrivate(remoteAddress) || isTrustedProxy(remoteAddress)) {
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

    private boolean isTrustedProxy(String address) {
        if (!isPublicIpv4(address)) return false;
        long candidate = ipv4Value(address);
        return trustedProxyCidrs.stream().anyMatch(cidr -> {
            String[] parts = cidr.split("/", 2);
            if (!isPublicIpv4(parts[0])) return false;
            int prefix;
            try {
                prefix = parts.length == 1 ? 32 : Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                return false;
            }
            if (prefix < 0 || prefix > 32) return false;
            long mask = prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
            return (candidate & mask) == (ipv4Value(parts[0]) & mask);
        });
    }

    private long ipv4Value(String address) {
        long value = 0;
        for (String octet : address.split("\\.")) {
            value = (value << 8) | Integer.parseInt(octet);
        }
        return value;
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
