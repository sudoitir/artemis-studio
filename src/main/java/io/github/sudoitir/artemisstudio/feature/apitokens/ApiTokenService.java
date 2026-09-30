package io.github.sudoitir.artemisstudio.feature.apitokens;

import io.github.sudoitir.artemisstudio.feature.apitokens.internal.persistence.ApiTokenEntity;
import io.github.sudoitir.artemisstudio.feature.apitokens.internal.persistence.ApiTokenGrantEntity;
import io.github.sudoitir.artemisstudio.feature.apitokens.internal.persistence.ApiTokenGrantRepository;
import io.github.sudoitir.artemisstudio.feature.apitokens.internal.persistence.ApiTokenRepository;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.security.Actor;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.GrantLoader;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.security.PersonalTokens;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeHierarchy;
import io.github.sudoitir.artemisstudio.kernel.security.SecondFactorRequiredException;
import io.github.sudoitir.artemisstudio.kernel.security.SecondFactors;
import io.github.sudoitir.artemisstudio.kernel.security.TokenPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints, authenticates, rotates and revokes personal API tokens (api-tokens spec,
 * ADR-0039, ADR-0136). A minted secret is 256 bits of entropy — its hash is looked
 * up by an indexed plaintext prefix and compared in constant time, never through a
 * slow password KDF. A token's effective expiry is capped live by the installation's
 * maximum lifetime.
 *
 * <p>A token records whether the session it was minted from had verified a second factor
 * (ADR-0143, D7), and stops authenticating once its owner is required to hold one and it was
 * minted without.
 */
@Service
@RequiredArgsConstructor
public class ApiTokenService implements PersonalTokens {

    private static final String RESOURCE = "token";
    private static final String PREFIX_TAG = "as_";
    private static final int PREFIX_BYTES = 8;
    private static final int SECRET_BYTES = 32;
    // Base64url-without-padding length of PREFIX_BYTES random bytes: ceil(8 * 4 / 3) = 11.
    private static final int PREFIX_LENGTH = PREFIX_TAG.length() + 11;

    private final ApiTokenRepository tokens;
    private final ApiTokenGrantRepository tokenGrants;
    private final UserAccounts accounts;
    private final GrantLoader grantLoader;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final ScopeHierarchy environments;
    private final SettingsService settings;
    private final TokenUsage usage;
    /** Absent when the module holding the factors is off, when nobody is required to hold one. */
    private final Optional<SecondFactors> secondFactors;

    private final SecureRandom random = new SecureRandom();

    /** token id -> last use, batched at most once a minute (ADR-0039). */
    private final Map<UUID, Instant> pendingLastUsed = new ConcurrentHashMap<>();

    /** token id -> when a late use of its replaced secret was last audited; one row a minute at most. */
    private final Map<UUID, Instant> rejectionAudited = new ConcurrentHashMap<>();

    public record Minted(ApiTokenEntity entity, String plaintext) {}

    private record Secret(String prefix, String secret) {
        String plaintext() {
            return prefix + "_" + secret;
        }

        byte[] hash() {
            return sha256(secret);
        }
    }

    /** The maximum lifetime currently in force. */
    public Duration maxLifetime() {
        return settings.duration(ApiTokensSettings.MAX_LIFETIME);
    }

    public Duration staleAfter() {
        return settings.duration(ApiTokensSettings.STALE_AFTER);
    }

    public Instant effectiveExpiry(ApiTokenEntity token) {
        return token.effectiveExpiry(maxLifetime());
    }

    /** Unused, or never used since creation, for longer than the stale period. */
    public boolean isStale(ApiTokenEntity token) {
        Instant lastSeen = token.getLastUsedAt() != null ? token.getLastUsedAt() : token.getCreatedAt();
        return lastSeen.plus(staleAfter()).isBefore(Instant.now());
    }

    /**
     * @param mintedWithMfa whether the session minting it had verified a second factor
     * @throws SecondFactorRequiredException when the owner must hold a second factor and this session
     *     had not verified one: the token would not authenticate anyway
     */
    @Transactional
    public Minted mint(
            UUID userId,
            String name,
            Instant expiresAt,
            List<Grant> requestedGrants,
            List<String> mcpTools,
            boolean mintedWithMfa) {
        if (!mintedWithMfa && requiresSecondFactor(userId)) {
            throw new SecondFactorRequiredException(
                    "Verify your second factor to create a token: sign in again with your authenticator or passkey.");
        }
        Instant now = Instant.now();
        Instant latest = now.plus(maxLifetime());
        if (expiresAt == null) {
            throw new IllegalArgumentException("A token needs an expiry, at the latest " + latest);
        }
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("The expiry must be in the future");
        }
        if (expiresAt.isAfter(latest)) {
            throw new IllegalArgumentException("The expiry is beyond the maximum token lifetime of "
                    + settings.value(ApiTokensSettings.MAX_LIFETIME) + "; the latest allowed is " + latest);
        }
        Secret secret = newSecret();
        ApiTokenEntity entity = tokens.save(new ApiTokenEntity(
                userId,
                name,
                secret.prefix(),
                secret.hash(),
                expiresAt,
                mcpTools == null ? List.of() : mcpTools,
                mintedWithMfa));
        String plaintext = secret.plaintext();
        for (Grant g : requestedGrants) {
            for (String action : g.permissions()) {
                tokenGrants.save(new ApiTokenGrantEntity(
                        entity.getId(), action, g.scopeType().name(), g.scopeId()));
            }
        }
        AuditEvent event =
                audit.begin(actorResolver.resolve(), "TOKEN_CREATE", RESOURCE, name, null, null, Map.of(), false);
        audit.succeed(event, 1);
        return new Minted(entity, plaintext);
    }

    public List<ApiTokenEntity> listFor(UUID userId) {
        return tokens.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /** Every user's tokens, newest first, for the administrators' inventory. */
    public List<ApiTokenEntity> listAll() {
        return tokens.findAllByOrderByCreatedAtDesc();
    }

    public List<Grant> grantsOf(UUID tokenId) {
        return tokenGrants.findByIdTokenId(tokenId).stream()
                .map(g -> new Grant(Grant.ScopeType.valueOf(g.getScopeType()), g.getScopeId(), Set.of(g.getAction())))
                .toList();
    }

    @Transactional
    public void revoke(UUID userId, UUID tokenId) {
        revoke(owned(userId, tokenId), Map.of());
    }

    /** An administrator revokes any user's token; the audit row names the owner. */
    @Transactional
    public void revokeAny(UUID tokenId) {
        ApiTokenEntity token = tokens.findById(tokenId).orElseThrow(() -> new NotFoundException(RESOURCE, tokenId));
        String owner = accounts.byId(token.getUserId())
                .map(UserAccounts.Account::username)
                .orElse(token.getUserId().toString());
        revoke(token, Map.of("owner", owner));
    }

    private void revoke(ApiTokenEntity token, Map<String, Object> params) {
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(Instant.now());
            tokens.save(token);
        }
        AuditEvent event = audit.begin(
                actorResolver.resolve(), "TOKEN_REVOKE", RESOURCE, token.getName(), null, null, params, false);
        audit.succeed(event, 1);
    }

    /**
     * A new secret for the same token. The replaced one keeps working until the overlap ends;
     * grants, allow-list, creation time and expiry stay, so rotation never extends a lifetime.
     */
    @Transactional
    public Minted rotate(UUID userId, UUID tokenId) {
        ApiTokenEntity token = owned(userId, tokenId);
        if (!token.isActive(Instant.now(), maxLifetime())) {
            throw new ConflictException("token-inactive", "A revoked or expired token cannot be rotated");
        }
        Secret secret = newSecret();
        token.rotate(
                secret.prefix(),
                secret.hash(),
                Instant.now().plus(settings.duration(ApiTokensSettings.ROTATION_OVERLAP)));
        tokens.save(token);
        AuditEvent event = audit.begin(
                actorResolver.resolve(), "TOKEN_ROTATE", RESOURCE, token.getName(), null, null, Map.of(), false);
        audit.succeed(event, 1);
        return new Minted(token, secret.plaintext());
    }

    public TokenUsage.Summary usage(UUID userId, UUID tokenId, int days) {
        return usage.summary(owned(userId, tokenId).getId(), days);
    }

    public TokenUsage.Summary usageAny(UUID tokenId, int days) {
        ApiTokenEntity token = tokens.findById(tokenId).orElseThrow(() -> new NotFoundException(RESOURCE, tokenId));
        return usage.summary(token.getId(), days);
    }

    /** Someone else's token reads as missing, never as forbidden. */
    private ApiTokenEntity owned(UUID userId, UUID tokenId) {
        return tokens.findById(tokenId)
                .filter(t -> t.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException(RESOURCE, tokenId));
    }

    /**
     * Authenticates a presented token, intersecting its configured grants with
     * its owner's current live grants (design.md decision 5) so demoting or
     * disabling the owner immediately narrows or disables the token.
     */
    public TokenPrincipal authenticate(String presented) {
        // Fixed-length prefix, not underscore-delimited: base64url's alphabet includes
        // '_', so searching for a separator character would be ambiguous.
        if (presented.length() <= PREFIX_LENGTH + 1
                || !presented.startsWith(PREFIX_TAG)
                || presented.charAt(PREFIX_LENGTH) != '_') {
            return null;
        }
        String prefix = presented.substring(0, PREFIX_LENGTH);
        byte[] hash = sha256(presented.substring(PREFIX_LENGTH + 1));
        Instant now = Instant.now();
        ApiTokenEntity token = tokens.findByPrefix(prefix).orElse(null);
        if (token == null) {
            token = tokens.findByPreviousPrefix(prefix).orElse(null);
            if (token == null || !MessageDigest.isEqual(hash, token.getPreviousTokenHash())) {
                return null;
            }
            if (!token.getPreviousValidUntil().isAfter(now)) {
                auditLateUse(token, now);
                return null;
            }
        } else if (!MessageDigest.isEqual(hash, token.getTokenHash())) {
            return null;
        }
        if (!token.isActive(now, maxLifetime())) {
            return null;
        }
        var owner = accounts.byId(token.getUserId()).orElse(null);
        if (owner == null || rejectsOwner(token, owner)) {
            return null;
        }
        Set<Grant> ownerGrants = grantLoader.loadFor(owner.id());
        Set<Grant> tokenGrantSet = tokenGrants.findByIdTokenId(token.getId()).stream()
                .map(g -> new Grant(Grant.ScopeType.valueOf(g.getScopeType()), g.getScopeId(), Set.of(g.getAction())))
                .collect(Collectors.toSet());
        Set<Grant> intersected = intersect(tokenGrantSet, ownerGrants);
        pendingLastUsed.put(token.getId(), now);
        return new TokenPrincipal(
                owner.id(),
                owner.username(),
                intersected,
                token.getId(),
                token.getName(),
                Set.copyOf(token.getMcpTools()));
    }

    /** A disabled owner, or one who must hold a second factor the token was minted without. */
    private boolean rejectsOwner(ApiTokenEntity token, UserAccounts.Account owner) {
        return owner.disabled() || (!token.isMintedWithMfa() && requiresSecondFactor(owner.id()));
    }

    private boolean requiresSecondFactor(UUID userId) {
        return secondFactors.map(f -> f.required(userId)).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isLive(UUID tokenId) {
        return tokens.findById(tokenId)
                .filter(t -> t.isActive(Instant.now(), maxLifetime()))
                .flatMap(t -> accounts.byId(t.getUserId()).filter(owner -> !rejectsOwner(t, owner)))
                .isPresent();
    }

    @Override
    @Transactional
    public int revokeAllOf(UUID userId) {
        Instant now = Instant.now();
        List<ApiTokenEntity> live = tokens.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(t -> t.getRevokedAt() == null)
                .toList();
        live.forEach(t -> t.setRevokedAt(now));
        tokens.saveAll(live);
        return live.size();
    }

    /**
     * The secret a rotation replaced, presented after its overlap: rejected, and audited under
     * the owner and token so a leaked old secret shows up in the trail. One row a minute per
     * token at most, so a flood of rejected requests cannot flood the audit trail.
     */
    private void auditLateUse(ApiTokenEntity token, Instant now) {
        Instant last = rejectionAudited.get(token.getId());
        if (last != null && last.isAfter(now.minus(Duration.ofMinutes(1)))) {
            return;
        }
        rejectionAudited.put(token.getId(), now);
        Actor anonymous = actorResolver.resolve();
        String owner = accounts.byId(token.getUserId())
                .map(UserAccounts.Account::username)
                .orElse(Actor.ANONYMOUS);
        Actor actor = new Actor(owner, anonymous.sourceIp(), anonymous.requestId(), token.getUserId(), token.getName());
        AuditEvent event = audit.begin(
                actor, "TOKEN_REJECTED", RESOURCE, token.getName(), null, null, Map.of("reason", "rotated"), false);
        audit.fail(event, "The secret was replaced by a rotation and its overlap has ended");
    }

    /**
     * At most one row-write per token per minute, however many requests it authenticates in that
     * window, plus the hourly usage counters. Driven by {@code ApiTokensJobs}.
     */
    @Transactional
    public void flush() {
        for (UUID tokenId : List.copyOf(pendingLastUsed.keySet())) {
            Instant at = pendingLastUsed.remove(tokenId);
            tokens.findById(tokenId).ifPresent(t -> {
                t.setLastUsedAt(at);
                tokens.save(t);
            });
        }
        usage.flush();
        rejectionAudited.clear();
    }

    private Secret newSecret() {
        String prefix = PREFIX_TAG + randomToken(PREFIX_BYTES);
        String secret = randomToken(SECRET_BYTES);
        return new Secret(prefix, secret);
    }

    private Set<Grant> intersect(Set<Grant> tokenGrants, Set<Grant> ownerGrants) {
        Set<Grant> result = new HashSet<>();
        for (Grant tg : tokenGrants) {
            for (Grant og : ownerGrants) {
                if (!covers(og, tg)) {
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

    /**
     * Whether an owner grant reaches the subject a token grant addresses, using
     * the same widening as {@link PermissionResolver}: global covers everything,
     * an environment covers its clusters, a cluster covers itself.
     *
     * <p>Scope-id equality alone would be wrong in the one direction that matters
     * in practice. A user whose grants are global — every administrator — could
     * only ever mint a globally scoped key: narrowing a key to one cluster would
     * intersect to nothing and produce a key that authenticates and can do
     * nothing. Narrowing a key is the entire point of minting one, so the check
     * has to walk the scopes rather than compare them.
     *
     * <p>The relation stays one-way. A cluster-scoped owner grant never satisfies
     * a global token grant, so a key still cannot exceed its owner.
     */
    private boolean covers(Grant owner, Grant token) {
        return switch (owner.scopeType()) {
            case GLOBAL -> true;
            case ENVIRONMENT ->
                switch (token.scopeType()) {
                    case GLOBAL -> false;
                    case ENVIRONMENT -> owner.scopeId().equals(token.scopeId());
                    case CLUSTER -> owner.scopeId().equals(environments.environmentOf(token.scopeId()));
                };
            case CLUSTER ->
                token.scopeType() == Grant.ScopeType.CLUSTER && owner.scopeId().equals(token.scopeId());
        };
    }

    private String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
