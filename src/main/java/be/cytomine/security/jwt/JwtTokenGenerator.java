/*
 * This file is used to generate a JWT token with a payload, secret key, algorithm, and expiry time.
 * 
 * The main purpose is generate temporary access keys for the IMS through a shared secret key.
 */

package be.cytomine.security.jwt;

import be.cytomine.config.properties.ApplicationProperties;

import java.util.Map;  
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;  


public class JwtTokenGenerator {

    public static Map<String, Object> generateJwtToken(ApplicationProperties applicationProperties,
                                                      Map<String, Object> payload, 
                                                      SignatureAlgorithm algorithm, long expiryMinutes) {
        
        // Get the current time in UTC
        Instant nowUtc = Instant.now();
        String secret = applicationProperties.getAuthentication().getJwt().getSecret();
        // Calculate the expiry time in UTC
        Instant expiryUtc = nowUtc.plus(expiryMinutes, ChronoUnit.MINUTES);
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        Key key = Keys.hmacShaKeyFor(keyBytes);
        // Generate the JWT token
        String token = Jwts.builder()
                .setClaims(payload)
                .setExpiration(Date.from(expiryUtc))
                .signWith(key, algorithm)
                .compact();

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("expiryTime", expiryUtc);
        return result;
    }

    public static Map<String, Object> generateJwtToken(ApplicationProperties applicationProperties, Map<String, Object> payload) {
        return generateJwtToken(applicationProperties, payload, SignatureAlgorithm.HS256, 30);
    }
}