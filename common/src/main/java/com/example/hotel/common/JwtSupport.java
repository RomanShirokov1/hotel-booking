package com.example.hotel.common;

import java.util.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;

public final class JwtSupport {
  public static final String ISSUER = "hotel-booking",
      USER_AUDIENCE = "hotel-api",
      INTERNAL_AUDIENCE = "hotel-internal";

  private JwtSupport() {}

  public static NimbusJwtDecoder decoder(String audience) {
    var d = NimbusJwtDecoder.withPublicKey(KeyFiles.publicKey()).build();
    d.setJwtValidator(validator(audience));
    return d;
  }

  public static OAuth2TokenValidator<Jwt> validator(String audience) {
    return new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(ISSUER),
        jwt ->
            jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Wrong audience", null)));
  }

  public static JwtAuthenticationConverter converter() {
    var c = new JwtAuthenticationConverter();
    c.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          var authorities = new ArrayList<org.springframework.security.core.GrantedAuthority>();
          var roles = jwt.getClaimAsStringList("roles");
          if (roles != null) {
            for (String role : roles) authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            if (roles.contains("ADMIN")) authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
          }
          String scope = jwt.getClaimAsString("scope");
          if (scope != null)
            for (String s : scope.split(" "))
              authorities.add(new SimpleGrantedAuthority("SCOPE_" + s));
          return authorities;
        });
    return c;
  }
}
