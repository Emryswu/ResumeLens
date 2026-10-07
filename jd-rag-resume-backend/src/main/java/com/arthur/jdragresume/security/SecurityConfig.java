package com.arthur.jdragresume.security;

import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            SecurityProblemSupport securityProblemSupport
    ) throws Exception {
        return http
                // Business endpoints take a Bearer header, which browsers never send on their own;
                // the refresh-cookie endpoints under /api/auth are covered by FetchMetadataGuard below.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Where the JWT filter saves the context, and where async/error dispatches of
                // the same request load it from; nothing is kept across requests.
                .securityContext(context -> context.securityContextRepository(jwtAuthenticationFilter.securityContextRepository()))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(securityProblemSupport)
                        .accessDeniedHandler(securityProblemSupport)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/**",
                                "/api/ai/status",
                                "/actuator/health",
                                "/actuator/health/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * Maps the CSRF guard onto the path instead of having each endpoint call it.
     * {@code /api/auth/*} is servlet syntax and covers every descendant, i.e. Spring's
     * {@code /api/auth/**}. Ordered just ahead of Spring Security, so it also runs
     * before the controller's rate limiting.
     */
    @Bean
    public FilterRegistrationBean<FetchMetadataGuard> fetchMetadataGuard(
            @Value("${app.security.trusted-origins}") List<String> trustedOrigins,
            ObjectMapper objectMapper
    ) {
        FilterRegistrationBean<FetchMetadataGuard> registration =
                new FilterRegistrationBean<>(new FetchMetadataGuard(trustedOrigins, objectMapper));
        registration.addUrlPatterns("/api/auth/*");
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 1);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(AppUserRepository appUserRepository) {
        return username -> {
            AppUser user = appUserRepository.findByUsername(username)
                    .orElseThrow(() -> new UsernameNotFoundException("user not found: " + username));
            return User.withUsername(user.getUsername())
                    .password(user.getPasswordHash())
                    .authorities("ROLE_USER")
                    .build();
        };
    }
}
