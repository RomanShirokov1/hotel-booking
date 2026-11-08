package com.example.hotel.api;

import com.example.hotel.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.cloud.gateway.route.*;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

@Configuration
public class GatewayConfig {
  @Bean
  RouteLocator routes(RouteLocatorBuilder b) {
    return b.routes()
        .route(
            "booking-alias-create",
            r ->
                r.path("/api/bookings")
                    .and()
                    .method("POST")
                    .filters(f -> f.setPath("/booking"))
                    .uri("lb://booking-service"))
        .route(
            "booking-alias-list",
            r ->
                r.path("/api/bookings")
                    .and()
                    .method("GET")
                    .filters(f -> f.setPath("/bookings"))
                    .uri("lb://booking-service"))
        .route(
            "booking-alias-item",
            r ->
                r.path("/api/bookings/{id}")
                    .filters(f -> f.rewritePath("/api/bookings/(?<id>[^/]+)", "/booking/${id}"))
                    .uri("lb://booking-service"))
        .route(
            "booking",
            r ->
                r.path("/booking", "/booking/**", "/bookings", "/user", "/user/**")
                    .uri("lb://booking-service"))
        .route(
            "hotel",
            r ->
                r.path("/api/hotels", "/api/hotels/**", "/api/rooms", "/api/rooms/**")
                    .uri("lb://hotel-service"))
        .route(
            "booking-docs",
            r ->
                r.path("/docs/booking")
                    .filters(f -> f.setPath("/v3/api-docs"))
                    .uri("lb://booking-service"))
        .route(
            "hotel-docs",
            r ->
                r.path("/docs/hotel")
                    .filters(f -> f.setPath("/v3/api-docs"))
                    .uri("lb://hotel-service"))
        .build();
  }

  @Bean
  SecurityWebFilterChain security(ServerHttpSecurity h, ObjectMapper mapper) {
    var d = NimbusReactiveJwtDecoder.withPublicKey(KeyFiles.publicKey()).build();
    d.setJwtValidator(JwtSupport.validator(JwtSupport.USER_AUDIENCE));
    return h.csrf(c -> c.disable())
        .authorizeExchange(
            a ->
                a.pathMatchers("/api/rooms/*/confirm-availability", "/api/rooms/*/release")
                    .denyAll()
                    .pathMatchers("/user/register", "/user/auth", "/actuator/health/**", "/docs/**")
                    .permitAll()
                    .anyExchange()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(
                        j ->
                            j.jwtDecoder(d)
                                .jwtAuthenticationConverter(
                                    new ReactiveJwtAuthenticationConverterAdapter(
                                        JwtSupport.converter())))
                    .authenticationEntryPoint((x, e) -> error(x, mapper, 401, "UNAUTHORIZED"))
                    .accessDeniedHandler((x, e) -> error(x, mapper, 403, "FORBIDDEN")))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint((x, t) -> error(x, mapper, 401, "UNAUTHORIZED"))
                    .accessDeniedHandler((x, t) -> error(x, mapper, 403, "FORBIDDEN")))
        .build();
  }

  @Bean
  @Order(-200)
  WebFilter trace() {
    return (x, chain) -> {
      String incoming = x.getRequest().getHeaders().getFirst("X-Correlation-Id");
      String id =
          incoming != null && incoming.matches("[a-zA-Z0-9-]{1,64}")
              ? incoming
              : UUID.randomUUID().toString();
      x.getAttributes().put("traceId", id);
      x.getResponse().getHeaders().set("X-Correlation-Id", id);
      return chain.filter(
          x.mutate()
              .request(x.getRequest().mutate().header("X-Correlation-Id", id).build())
              .build());
    };
  }

  static Mono<Void> error(ServerWebExchange x, ObjectMapper m, int status, String code) {
    x.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
    x.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
    try {
      byte[] bytes =
          m.writeValueAsBytes(
              new ApiError(
                  Instant.now(),
                  status,
                  code,
                  code,
                  x.getRequest().getPath().value(),
                  x.getAttribute("traceId"),
                  Map.of(),
                  null));
      return x.getResponse().writeWith(Mono.just(x.getResponse().bufferFactory().wrap(bytes)));
    } catch (Exception e) {
      return Mono.error(e);
    }
  }

  @Bean
  @Order(-2)
  WebExceptionHandler errors(ObjectMapper mapper) {
    return (x, e) -> {
      if (x.getResponse().isCommitted()) return Mono.error(e);
      int status =
          e instanceof org.springframework.web.server.ResponseStatusException r
              ? r.getStatusCode().value()
              : 503;
      return error(x, mapper, status, "GATEWAY_ERROR");
    };
  }
}
