package com.arthur.jdragresume.security;

import com.arthur.jdragresume.common.ApiResponse;
import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CSRF defence for everything under {@code /api/auth}, where the refresh cookie lives.
 *
 * <p>Everything else authenticates with a Bearer header, which a browser never
 * attaches on its own. register / login / refresh / logout are different: the
 * refresh cookie rides along automatically. {@code SameSite=Lax} keeps cross-site
 * POSTs out, but it is decided per <em>site</em> (registrable domain), so a page on
 * a sibling subdomain is same-site and its POST still carries the cookie.
 *
 * <p>It is a filter mapped onto the path (see {@link SecurityConfig}), not a call each
 * endpoint has to remember, so an endpoint added under {@code /api/auth} is covered the
 * moment it exists. It runs ahead of Spring Security and the controller: a rejected
 * request is never parsed, never spends rate-limit budget and never reaches a service.
 * Every method is checked, not only writes, because {@code SameSite=Lax} still sends
 * the cookie on a cross-site top-level GET.
 *
 * <p>So a third-party login callback (an identity provider redirecting the browser back,
 * which arrives as a cross-site GET, or as a cross-site POST with {@code form_post}) must
 * not live under {@code /api/auth}, or it will be rejected here; give it its own path and
 * protect it with the OAuth {@code state} parameter instead.
 *
 * <ol>
 *   <li>{@code Sec-Fetch-Site} present: only {@code same-origin} (our own frontend)
 *       and {@code none} (typed or bookmarked navigation) pass. Page script cannot
 *       forge it.</li>
 *   <li>Absent, but {@code Origin} present: an older browser. Every cross-origin POST
 *       carries Origin, so its <em>full</em> origin (scheme, host, port) must be one
 *       of {@code app.security.trusted-origins}. {@code null} and anything that is
 *       not a bare origin are rejected.</li>
 *   <li>Neither header: not a browser (scripts, health checks), not a CSRF vector.</li>
 * </ol>
 *
 * <p>The backend cannot derive its public origin from the request: behind the BFF
 * the Host it sees is the BFF's upstream address. So the trusted list is
 * configuration, shared with the BFF through the {@code TRUSTED_ORIGINS} variable.
 * {@code Sec-Fetch-Mode} is never consulted: Node's fetch in the BFF adds
 * {@code sec-fetch-mode: cors} on its own when forwarding.
 */
public class FetchMetadataGuard extends OncePerRequestFilter {

    private static final String SITE_HEADER = "Sec-Fetch-Site";
    private static final String ORIGIN_HEADER = "Origin";

    private final Set<String> trustedOrigins;
    private final ObjectMapper objectMapper;

    public FetchMetadataGuard(List<String> trustedOrigins, ObjectMapper objectMapper) {
        this.trustedOrigins = trustedOrigins.stream()
                .map(FetchMetadataGuard::normalise)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            requireSameOrigin(request.getHeader(SITE_HEADER), request.getHeader(ORIGIN_HEADER));
        } catch (BusinessException blocked) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(), ApiResponse.error(blocked.getCode(), blocked.getMessage()));
            return;
        }
        filterChain.doFilter(request, response);
    }

    public void requireSameOrigin(String secFetchSite, String origin) {
        if (secFetchSite != null && !secFetchSite.isBlank()) {
            String site = secFetchSite.trim();
            if ("same-origin".equalsIgnoreCase(site) || "none".equalsIgnoreCase(site)) {
                return;
            }
            throw blocked();
        }
        if (origin == null || origin.isBlank()) {
            return;
        }
        String normalised = normalise(origin);
        if (normalised == null || !trustedOrigins.contains(normalised)) {
            throw blocked();
        }
    }

    /**
     * {@code scheme://host[:port]} in lower case with the scheme's default port
     * dropped, the way a browser serialises Origin. Returns null for {@code null},
     * unparseable values, and anything carrying a path, query or credentials.
     */
    static String normalise(String origin) {
        if (origin == null) {
            return null;
        }
        String value = origin.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            URI uri = new URI(value);
            if (uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))) {
                return null;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
                port = -1;
            }
            return scheme + "://" + host + (port == -1 ? "" : ":" + port);
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private static BusinessException blocked() {
        return new BusinessException(
                "CROSS_SITE_REQUEST_BLOCKED",
                "cross-site request rejected, please use this site's own pages"
        );
    }
}
