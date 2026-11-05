package com.arthur.jdragresume.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpResolverTests {

    private final ClientIpResolver resolver = new ClientIpResolver("127.0.0.1,172.18.0.4");

    @Test
    void usesBffAddressFromExplicitlyConfiguredPeers() {
        assertEquals("203.0.113.20", resolver.resolve(request("127.0.0.1", "203.0.113.20")));
        assertEquals("198.51.100.21", resolver.resolve(request("172.18.0.4", "198.51.100.21")));
    }

    @Test
    void privatePeerOutsideConfiguredProxyListCannotStampAnAddress() {
        assertEquals("10.0.0.8", resolver.resolve(request("10.0.0.8", "203.0.113.99")));
    }

    @Test
    void ignoresSpoofedOrMalformedBffAddressOutsideTrustedNetwork() {
        assertEquals("198.51.100.30", resolver.resolve(request("198.51.100.30", "203.0.113.99")));
        assertEquals("127.0.0.1", resolver.resolve(request("127.0.0.1", "not-an-ip")));
    }

    private static MockHttpServletRequest request(String remoteAddress, String forwardedAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader(ClientIpResolver.BFF_CLIENT_IP_HEADER, forwardedAddress);
        return request;
    }
}
