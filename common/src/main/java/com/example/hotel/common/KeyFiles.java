package com.example.hotel.common;

import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.Base64;

public final class KeyFiles {
  private KeyFiles() {}

  private static byte[] read(String file, String env) throws Exception {
    String value = System.getenv(env);
    Path p = Path.of(value == null ? "config/keys/" + file : value).toAbsolutePath();
    if (value == null) {
      for (Path base = Path.of("").toAbsolutePath(); base != null; base = base.getParent()) {
        Path candidate = base.resolve("config/keys/" + file);
        if (Files.exists(candidate)) {
          p = candidate;
          break;
        }
      }
    }
    return Base64.getMimeDecoder().decode(Files.readString(p).replaceAll("-----[^-]+-----", ""));
  }

  public static RSAPublicKey publicKey() {
    try {
      return (RSAPublicKey)
          KeyFactory.getInstance("RSA")
              .generatePublic(new X509EncodedKeySpec(read("public.pem", "JWT_PUBLIC_KEY")));
    } catch (Exception e) {
      throw new IllegalStateException("Generate RSA keys: java scripts/GenerateKeys.java", e);
    }
  }

  public static RSAPrivateKey privateKey() {
    try {
      return (RSAPrivateKey)
          KeyFactory.getInstance("RSA")
              .generatePrivate(new PKCS8EncodedKeySpec(read("private.pem", "JWT_PRIVATE_KEY")));
    } catch (Exception e) {
      throw new IllegalStateException("Missing private RSA key", e);
    }
  }
}
