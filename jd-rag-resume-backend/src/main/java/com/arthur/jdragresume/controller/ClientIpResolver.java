package com.arthur.jdragresume.controller;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

@Component
final class ClientIpResolver {
    static final String BFF_CLIENT_IP_HEADER = "X-BFF-Client-IP";
    private final Set<String> trustedProxies;

    ClientIpResolver(@Value("${TRUSTED_PROXIES:}") String configured) {
        trustedProxies = Arrays.stream(configured.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> {
                    String ip = normalize(s);
                    if (ip == null) throw new IllegalArgumentException("TRUSTED_PROXIES must contain IP addresses");
                    return ip;
                }).collect(Collectors.toUnmodifiableSet());
    }

    String resolve(HttpServletRequest request) {
        String remote = normalize(request.getRemoteAddr());
        if (remote == null) throw new IllegalArgumentException("Missing or invalid TCP peer address");
        if (trustedProxies.contains(remote)) {
            String forwarded = normalize(request.getHeader(BFF_CLIENT_IP_HEADER));
            if (forwarded != null) return forwarded;
        }
        return remote;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (candidate.isEmpty() || candidate.length() > 45) {
            return null;
        }
        if (candidate.indexOf(':') >= 0) {
            if (!candidate.matches("[0-9a-fA-F:.]+")) {
                return null;
            }
            try {
                return InetAddress.getByName(candidate).getHostAddress();
            } catch (Exception ignored) {
                return null;
            }
        }

        String[] octets = candidate.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        StringBuilder normalized = new StringBuilder();
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || !octet.chars().allMatch(Character::isDigit)) {
                return null;
            }
            int number = Integer.parseInt(octet);
            if (number > 255) {
                return null;
            }
            if (!normalized.isEmpty()) {
                normalized.append('.');
            }
            normalized.append(number);
        }
        return normalized.toString();
    }
}
