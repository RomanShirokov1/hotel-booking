package com.example.hotel.common;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;

class JwtSupportTest {
  String token(String issuer, String audience, Instant expires) {
    var rsa =
        new RSAKey.Builder(KeyFiles.publicKey())
            .privateKey(KeyFiles.privateKey())
            .keyID("test")
            .build();
    var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsa)));
    return encoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(
                        org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                    .build(),
                JwtClaimsSet.builder()
                    .issuer(issuer)
                    .subject("1")
                    .audience(List.of(audience))
                    .issuedAt(Instant.now().minusSeconds(7200))
                    .expiresAt(expires)
                    .claim("roles", List.of("ADMIN"))
                    .build()))
        .getTokenValue();
  }

  @Test
  void validSignatureIssuerAudienceAndExpiryAreRequired() {
    var decoder = JwtSupport.decoder(JwtSupport.USER_AUDIENCE);
    var valid =
        decoder.decode(
            token(JwtSupport.ISSUER, JwtSupport.USER_AUDIENCE, Instant.now().plusSeconds(100)));
    assertThat(valid.getSubject()).isEqualTo("1");
    assertThatThrownBy(
            () ->
                decoder.decode(
                    token(
                        JwtSupport.ISSUER,
                        JwtSupport.USER_AUDIENCE,
                        Instant.now().minusSeconds(120))))
        .isInstanceOf(JwtException.class);
    assertThatThrownBy(
            () ->
                decoder.decode(
                    token("other", JwtSupport.USER_AUDIENCE, Instant.now().plusSeconds(100))))
        .isInstanceOf(JwtException.class);
    assertThatThrownBy(
            () ->
                decoder.decode(
                    token(
                        JwtSupport.ISSUER,
                        JwtSupport.INTERNAL_AUDIENCE,
                        Instant.now().plusSeconds(100))))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void adminsReceiveUserAuthority() {
    var jwt =
        JwtSupport.decoder(JwtSupport.USER_AUDIENCE)
            .decode(
                token(JwtSupport.ISSUER, JwtSupport.USER_AUDIENCE, Instant.now().plusSeconds(100)));
    assertThat(JwtSupport.converter().convert(jwt).getAuthorities())
        .extracting(Object::toString)
        .contains("ROLE_ADMIN", "ROLE_USER");
  }
}
