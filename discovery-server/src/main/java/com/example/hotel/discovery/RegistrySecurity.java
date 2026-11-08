package com.example.hotel.discovery;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class RegistrySecurity {
  @Bean
  UserDetailsService users() {
    String password = System.getenv().getOrDefault("EUREKA_PASSWORD", "local-registry-password");
    return new InMemoryUserDetailsManager(
        User.withUsername("registry").password("{noop}" + password).roles("REGISTRY").build());
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.csrf(c -> c.ignoringRequestMatchers("/eureka/**"))
        .authorizeHttpRequests(
            a -> a.requestMatchers("/actuator/health/**").permitAll().anyRequest().authenticated())
        .httpBasic(b -> {})
        .build();
  }
}
