package com.example.hotel.hotel;

import com.example.hotel.common.*;
import com.example.hotel.common.web.Errors;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class InternalSecurity {
  @Bean
  @Order(1)
  SecurityFilterChain internal(HttpSecurity h, ObjectMapper m) throws Exception {
    return h.securityMatcher("/api/rooms/*/confirm-availability", "/api/rooms/*/release")
        .csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a.anyRequest().hasAuthority("SCOPE_room:reserve"))
        .oauth2ResourceServer(
            o ->
                o.jwt(
                        j ->
                            j.decoder(JwtSupport.decoder(JwtSupport.INTERNAL_AUDIENCE))
                                .jwtAuthenticationConverter(JwtSupport.converter()))
                    .authenticationEntryPoint(
                        (q, s, e) -> Errors.write(q, s, m, 401, "UNAUTHORIZED"))
                    .accessDeniedHandler((q, s, e) -> Errors.write(q, s, m, 403, "FORBIDDEN")))
        .build();
  }
}
