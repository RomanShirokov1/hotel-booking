package com.example.hotel.booking;

import com.example.hotel.common.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.*;
import java.util.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
  private final JwtEncoder encoder;

  public TokenService() {
    var rsa =
        new RSAKey.Builder(KeyFiles.publicKey())
            .privateKey(KeyFiles.privateKey())
            .keyID("local")
            .build();
    encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsa)));
  }

  public String user(User u) {
    return encode(
        u.getId().toString(),
        JwtSupport.USER_AUDIENCE,
        3600,
        Map.of("roles", List.of(u.getRole().name())));
  }

  public String service() {
    return encode(
        "booking-service", JwtSupport.INTERNAL_AUDIENCE, 60, Map.of("scope", "room:reserve"));
  }

  private String encode(String sub, String audience, int seconds, Map<String, Object> claims) {
    Instant now = Instant.now();
    var builder =
        JwtClaimsSet.builder()
            .issuer(JwtSupport.ISSUER)
            .subject(sub)
            .audience(List.of(audience))
            .issuedAt(now)
            .expiresAt(now.plusSeconds(seconds))
            .id(UUID.randomUUID().toString());
    claims.forEach(builder::claim);
    return encoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(
                        org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                    .build(),
                builder.build()))
        .getTokenValue();
  }
}
