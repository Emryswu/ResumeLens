package com.arthur.jdragresume.controller;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backend only trusts X-BFF-Client-IP from addresses listed in TRUSTED_PROXIES. If the default
 * Compose deployment leaves that list empty, or the frontend container's address is not pinned, every
 * request is counted under the BFF container's IP and all users share one rate-limit bucket.
 */
class ComposeTrustedProxyTests {

    private static final Pattern DEFAULTED = Pattern.compile("\\$\\{[A-Z0-9_]+:-([^}]*)}");

    @Test
    @SuppressWarnings("unchecked")
    void defaultComposeDeploymentTrustsThePinnedFrontendAddress() throws IOException {
        Map<String, Object> compose = new Yaml().load(Files.readString(Path.of("../docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");

        Map<String, Object> frontend = (Map<String, Object>) services.get("frontend");
        Map<String, Object> frontendNetworks = (Map<String, Object>) frontend.get("networks");
        assertNotNull(frontendNetworks, "frontend must join the default network with a pinned address");
        Map<String, Object> attachment = (Map<String, Object>) frontendNetworks.get("default");
        assertNotNull(attachment, "frontend must pin its address on the default network");
        String frontendIp = expand((String) attachment.get("ipv4_address"));
        assertFalse(frontendIp.isBlank(), "frontend ipv4_address needs a non-empty default");

        Map<String, Object> backend = (Map<String, Object>) services.get("backend");
        Map<String, Object> env = (Map<String, Object>) backend.get("environment");
        List<String> trusted = Arrays.stream(expand((String) env.get("TRUSTED_PROXIES")).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        assertTrue(trusted.contains(frontendIp),
                "backend TRUSTED_PROXIES default " + trusted + " must include the frontend address " + frontendIp);

        Map<String, Object> network = (Map<String, Object>) ((Map<String, Object>) compose.get("networks")).get("default");
        Map<String, Object> ipam = (Map<String, Object>) network.get("ipam");
        String subnet = expand((String) ((List<Map<String, Object>>) ipam.get("config")).get(0).get("subnet"));
        assertTrue(inSubnet(frontendIp, subnet), frontendIp + " must lie inside the default network " + subnet);
    }

    /** Resolves ${VAR:-default} to its default, the value an unconfigured deployment gets. */
    private static String expand(String value) {
        assertNotNull(value);
        Matcher matcher = DEFAULTED.matcher(value);
        return matcher.replaceAll(result -> Matcher.quoteReplacement(result.group(1))).trim();
    }

    private static boolean inSubnet(String ip, String cidr) throws IOException {
        String[] parts = cidr.split("/");
        int prefix = Integer.parseInt(parts[1]);
        BigInteger address = new BigInteger(1, InetAddress.getByName(ip).getAddress());
        BigInteger network = new BigInteger(1, InetAddress.getByName(parts[0]).getAddress());
        int bits = InetAddress.getByName(parts[0]).getAddress().length * 8;
        return address.shiftRight(bits - prefix).equals(network.shiftRight(bits - prefix));
    }
}
