package com.example.hotel.common.web;

import com.example.hotel.common.JwtSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
@Import({TraceFilter.class, ApiAdvice.class})
public class UserSecurityConfig {
  @Bean
  @Order(2)
  SecurityFilterChain userSecurity(HttpSecurity http, ObjectMapper mapper) throws Exception {
    http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/user/register",
                        "/user/auth",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                        "/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(
                        j ->
                            j.decoder(JwtSupport.decoder(JwtSupport.USER_AUDIENCE))
                                .jwtAuthenticationConverter(JwtSupport.converter()))
                    .authenticationEntryPoint(
                        (q, s, e) -> Errors.write(q, s, mapper, 401, "UNAUTHORIZED"))
                    .accessDeniedHandler((q, s, e) -> Errors.write(q, s, mapper, 403, "FORBIDDEN")))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (q, s, x) -> Errors.write(q, s, mapper, 401, "UNAUTHORIZED"))
                    .accessDeniedHandler(
                        (q, s, x) -> Errors.write(q, s, mapper, 403, "FORBIDDEN")));
    return http.build();
  }
}
