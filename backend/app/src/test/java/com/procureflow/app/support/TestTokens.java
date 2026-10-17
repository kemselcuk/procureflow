package com.procureflow.app.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.function.Consumer;

public final class TestTokens implements AutoCloseable {
    private final RSAKey key;
    private final HttpServer server;

    public TestTokens() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-signing-key").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                var body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    public String issuer() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/test"; }
    public String jwks() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks"; }
    public String token(String subject) { return token(subject, ignored -> {}, false); }

    public String token(String subject, Consumer<JWTClaimsSet.Builder> customize, boolean wrongSignature) {
        try {
            var claims = new JWTClaimsSet.Builder().issuer(issuer()).subject(subject).audience("procureflow-api")
                    .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(600)))
                    .claim("typ", "Bearer")
                    .claim("realm_access", Map.of("roles", java.util.List.of("COMPANY_ADMIN", "DEPARTMENT_MANAGER")));
            customize.accept(claims);
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
            jwt.sign(new RSASSASigner(wrongSignature ? new RSAKeyGenerator(2048).generate() : key));
            return jwt.serialize();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    @Override public void close() { server.stop(0); }
}
