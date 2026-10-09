package com.parkview.ruleengine.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.config.Customizer;

/**
 * Defence in depth: every service validates the Supabase JWT itself (ES256 via JWKS, issuer and
 * audience checked) instead of trusting the gateway. Coarse rules live here, fine-grained ones
 * on controllers with {@code @PreAuthorize} and {@link AccessPolicy}.
 *
 * <ul>
 *   <li>stateless, CSRF off (bearer tokens only), no CORS (browsers only talk to the gateway)</li>
 *   <li>{@code /actuator/health/**}, {@code /actuator/info} and {@code parkview.security.public-paths} are public</li>
 *   <li>{@code /actuator/prometheus} and the rest of actuator: internal key only</li>
 *   <li>{@code /internal/**}: {@code X-Internal-Api-Key} (denied entirely when no key is configured)</li>
 *   <li>OpenAPI/Swagger is public only when {@code springdoc.api-docs.enabled=true}</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class JwtSecurityConfig {

    @Bean
    JwtDecoder jwtDecoder(SecurityProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.resolvedJwksUri())
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();

        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null
                && jwt.getAudience().contains(props.audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token", "Required audience '" + props.audience() + "' is missing", null));

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.issuer()), audience));
        return decoder;
    }

    /** ADMIN implicitly holds every OPERATOR permission. */
    @Bean
    RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("ROLE_ADMIN > ROLE_OPERATOR");
    }

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            SecurityProperties props,
            ObjectMapper objectMapper,
            ObjectProvider<SecurityPathCustomizer> pathCustomizer) throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .cacheControl(Customizer.withDefaults()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(ProblemJsonErrorHandlers.authenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(ProblemJsonErrorHandlers.accessDeniedHandler(objectMapper)))
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(new ParkviewJwtAuthenticationConverter()))
                        .authenticationEntryPoint(ProblemJsonErrorHandlers.authenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(ProblemJsonErrorHandlers.accessDeniedHandler(objectMapper)));

        if (props.internalApiEnabled()) {
            http.addFilterBefore(new InternalApiKeyFilter(props.internalApiKey()), BearerTokenAuthenticationFilter.class);
        }

        http.authorizeHttpRequests(auth -> {
            auth.requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/info").permitAll();
            auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
            if (!props.publicPaths().isEmpty()) {
                auth.requestMatchers(props.publicPaths().toArray(String[]::new)).permitAll();
            }
            if (props.internalApiEnabled()) {
                auth.requestMatchers("/internal/**", "/actuator/**").hasAuthority(InternalApiKeyFilter.AUTHORITY);
            } else {
                auth.requestMatchers("/internal/**", "/actuator/**").denyAll();
            }
            pathCustomizer.ifAvailable(c -> c.customize(auth));
            auth.anyRequest().authenticated();
        });
        return http.build();
    }

    /** Lets a service add its own matcher rules before the catch-all {@code authenticated()}. */
    @FunctionalInterface
    public interface SecurityPathCustomizer {
        void customize(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry);
    }
}
