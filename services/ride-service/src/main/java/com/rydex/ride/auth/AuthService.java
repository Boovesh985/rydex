package com.rydex.ride.auth;

import com.rydex.ride.auth.AuthDtos.AuthResponse;
import com.rydex.ride.auth.AuthDtos.LoginRequest;
import com.rydex.ride.auth.AuthDtos.RegisterRequest;
import com.rydex.ride.security.JwtProperties;
import com.rydex.ride.security.TokenService;
import com.rydex.ride.user.Role;
import com.rydex.ride.user.User;
import com.rydex.ride.user.UserRepository;
import com.rydex.ride.user.UserView;
import com.rydex.ride.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final JwtProperties jwtProps;
    private final Clock clock;
    /** Compared against when the email is unknown so both paths cost one bcrypt check. */
    private final String dummyHash;

    AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                TokenService tokenService, JwtProperties jwtProps, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.jwtProps = jwtProps;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("timing-equaliser");
    }

    @Transactional
    AuthResponse register(RegisterRequest req) {
        if (req.role() == Role.ADMIN) {
            throw ApiException.forbidden("Admin accounts cannot be self-registered");
        }
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("An account with this email already exists");
        }
        var user = new User(req.name().trim(), email, req.phone(), passwordEncoder.encode(req.password()),
                req.role(), clock.instant());
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("An account with this email already exists");
        }
        return issueTokens(user);
    }

    @Transactional
    AuthResponse login(LoginRequest req) {
        var user = users.findByEmail(req.email().trim()).orElse(null);
        String hash = user != null ? user.getPasswordHash() : dummyHash;
        boolean matches = passwordEncoder.matches(req.password(), hash);
        if (user == null || !matches) {
            throw ApiException.unauthorized("Invalid email or password");
        }
        return issueTokens(user);
    }

    /**
     * Rotates the refresh token. Presenting an already-rotated token means it was copied,
     * so every session of that user is revoked.
     */
    @Transactional(noRollbackFor = ApiException.class)
    AuthResponse refresh(String presentedToken) {
        Instant now = clock.instant();
        var token = refreshTokens.findByTokenHash(sha256(presentedToken))
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));
        if (token.isRevoked()) {
            int revoked = refreshTokens.revokeAllForUser(token.getUserId(), now);
            log.warn("Refresh token reuse detected for user {}; revoked {} active tokens", token.getUserId(), revoked);
            throw ApiException.unauthorized("Refresh token has been revoked");
        }
        if (token.isExpired(now)) {
            throw ApiException.unauthorized("Refresh token has expired");
        }
        token.revoke(now);
        var user = users.findById(token.getUserId())
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));
        return issueTokens(user);
    }

    @Transactional
    void logout(String presentedToken) {
        refreshTokens.findByTokenHash(sha256(presentedToken)).ifPresent(t -> t.revoke(clock.instant()));
    }

    private AuthResponse issueTokens(User user) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = clock.instant();
        refreshTokens.save(new RefreshToken(user.getId(), sha256(refresh), now, now.plus(jwtProps.refreshTokenTtl())));
        return new AuthResponse(tokenService.issueAccessToken(user), refresh, "Bearer",
                tokenService.accessTokenTtlSeconds(), UserView.of(user));
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
