package com.rydex.ride.security;

import com.nimbusds.jose.jwk.RSAKey;
import com.rydex.ride.user.User;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    public static final String ROLE_CLAIM = "role";

    private final JwtEncoder encoder;
    private final JwtProperties props;
    private final String keyId;
    private final Clock clock;

    TokenService(JwtEncoder encoder, JwtProperties props, RSAKey signingKey, Clock clock) {
        this.encoder = encoder;
        this.props = props;
        this.keyId = signingKey.getKeyID();
        this.clock = clock;
    }

    public String issueAccessToken(User user) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTokenTtl()))
                .claim(ROLE_CLAIM, user.getRole().name())
                .claim("name", user.getName())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long accessTokenTtlSeconds() {
        return props.accessTokenTtl().toSeconds();
    }
}
