import java.nio.file.*;
import java.security.*;
import java.util.Base64;
class GenerateKeys {
 public static void main(String[] args) throws Exception {
  Path dir=Path.of("config/keys"); Files.createDirectories(dir);
  if(Files.exists(dir.resolve("private.pem")) && Files.exists(dir.resolve("public.pem"))) return;
  var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var keys=generator.generateKeyPair();
  write(dir.resolve("private.pem"),"PRIVATE KEY",keys.getPrivate().getEncoded());
  write(dir.resolve("public.pem"),"PUBLIC KEY",keys.getPublic().getEncoded());
 }
 static void write(Path p,String type,byte[] value) throws Exception {Files.writeString(p,"-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(value)+"\n-----END "+type+"-----\n");}
}