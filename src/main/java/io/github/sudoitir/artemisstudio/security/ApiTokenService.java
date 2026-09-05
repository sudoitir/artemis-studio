package io.github.sudoitir.artemisstudio.security;

import io.github.sudoitir.artemisstudio.persist.ApiTokenEntity;
import io.github.sudoitir.artemisstudio.persist.ApiTokenGrantEntity;
import io.github.sudoitir.artemisstudio.persist.ApiTokenGrantRepository;
import io.github.sudoitir.artemisstudio.persist.ApiTokenRepository;
import io.github.sudoitir.artemisstudio.persist.AppUserRepository;
import io.github.sudoitir.artemisstudio.service.NotFoundException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints, authenticates, and revokes personal API tokens (api-tokens spec,
 * ADR-0039, design.md decision 5). A minted secret is 256 bits of entropy — its
 * hash is looked up by an indexed plaintext prefix and compared in constant
 * time, never through a slow password KDF.
 */
@Service
@RequiredArgsConstructor
public class ApiTokenService {

    private static final String PREFIX_TAG = "as_";
    private static final int PREFIX_BYTES = 8;
    private static final int SECRET_BYTES = 32;
    // Base64url-without-padding length of PREFIX_BYTES random bytes: ceil(8 * 4 / 3) = 11.
    private static final int PREFIX_LENGTH = PREFIX_TAG.length() + 11;

    private final ApiTokenRepository tokens;
    private final ApiTokenGrantRepository tokenGrants;
    private final AppUserRepository users;
    private final GrantLoader grantLoader;
    private final SecureRandom random = new SecureRandom();

    /** token id -> last-flushed instant, batched at most once a minute (design.md decision 5, task 8.4). */
    private final Map<UUID, Instant> pendingLastUsed = new ConcurrentHashMap<>();

    public record Minted(ApiTokenEntity entity, String plaintext) {}

    @Transactional
    public Minted mint(UUID userId, String name, Instant expiresAt, List<Grant> requestedGrants) {
        String prefix = PREFIX_TAG + randomToken(PREFIX_BYTES);
        String secret = randomToken(SECRET_BYTES);
        String plaintext = prefix + "_" + secret;
        ApiTokenEntity entity = tokens.save(new ApiTokenEntity(userId, name, prefix, sha256(secret), expiresAt));
        for (Grant g : requestedGrants) {
            for (String action : g.permissions()) {
                tokenGrants.save(new ApiTokenGrantEntity(
                        entity.getId(), action, g.scopeType().name(), g.scopeId()));
            }
        }
        return new Minted(entity, plaintext);
    }

    public List<ApiTokenEntity> listFor(UUID userId) {
        return tokens.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public void revoke(UUID userId, UUID tokenId) {
        ApiTokenEntity token = tokens.findById(tokenId).orElseThrow(() -> new NotFoundException("token", tokenId));
        if (!token.getUserId().equals(userId)) {
            throw new NotFoundException("token", tokenId);
        }
        token.setRevokedAt(Instant.now());
        tokens.save(token);
    }

    /**
     * Authenticates a presented token, intersecting its configured grants with
     * its owner's current live grants (design.md decision 5) so demoting or
     * disabling the owner immediately narrows or disables the token.
     */
    public StudioPrincipal authenticate(String presented) {
        // Fixed-length prefix, not underscore-delimited: base64url's alphabet includes
        // '_', so searching for a separator character would be ambiguous.
        if (presented.length() <= PREFIX_LENGTH + 1
                || !presented.startsWith(PREFIX_TAG)
                || presented.charAt(PREFIX_LENGTH) != '_') {
            return null;
        }
        String prefix = presented.substring(0, PREFIX_LENGTH);
        String secret = presented.substring(PREFIX_LENGTH + 1);
        ApiTokenEntity token = tokens.findByPrefix(prefix).orElse(null);
        if (token == null
                || !token.isActive(Instant.now())
                || !MessageDigest.isEqual(sha256(secret), token.getTokenHash())) {
            return null;
        }
        var owner = users.findById(token.getUserId()).orElse(null);
        if (owner == null || owner.isDisabled()) {
            return null;
        }
        Set<Grant> ownerGrants = grantLoader.loadFor(owner.getId());
        Set<Grant> tokenGrantSet = new HashSet<>();
        for (ApiTokenGrantEntity g : tokenGrants.findByIdTokenId(token.getId())) {
            tokenGrantSet.add(
                    new Grant(Grant.ScopeType.valueOf(g.getScopeType()), g.getScopeId(), Set.of(g.getAction())));
        }
        Set<Grant> intersected = intersect(tokenGrantSet, ownerGrants);
        pendingLastUsed.put(token.getId(), Instant.now());
        return new StudioPrincipal(owner.getId(), owner.getUsername(), intersected, false);
    }

    /** At most one row-write per token per minute, however many requests it authenticates in that window. */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void flushLastUsed() {
        var snapshot = Map.copyOf(pendingLastUsed);
        pendingLastUsed.clear();
        snapshot.forEach((tokenId, at) -> tokens.findById(tokenId).ifPresent(t -> {
            t.setLastUsedAt(at);
            tokens.save(t);
        }));
    }

    private static Set<Grant> intersect(Set<Grant> tokenGrants, Set<Grant> ownerGrants) {
        Set<Grant> result = new HashSet<>();
        for (Grant tg : tokenGrants) {
            for (Grant og : ownerGrants) {
                if (og.scopeType() != tg.scopeType() || !java.util.Objects.equals(og.scopeId(), tg.scopeId())) {
                    continue;
                }
                for (String action : tg.permissions()) {
                    if (og.grants(action)) {
                        result.add(new Grant(tg.scopeType(), tg.scopeId(), Set.of(action)));
                    }
                }
            }
        }
        return result;
    }

    private String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
