package io.github.sudoitir.artemisstudio.kernel.security.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps {@code app_user}. A local account carries a bcrypt {@code passwordHash}; an account
 * provisioned by an external provider carries its {@code providerId} and
 * {@code externalSubject} instead, and a null hash (ADR-0037, ADR-0073).
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AppUserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "username", nullable = false, updatable = false)
    private String username;

    @Column(name = "email")
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private String providerId = LoginService.DEFAULT_PROVIDER;

    @Column(name = "external_subject", updatable = false)
    private String externalSubject;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "disabled", nullable = false)
    private boolean disabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static AppUserEntity local(String username, String email, String passwordHash) {
        AppUserEntity u = new AppUserEntity();
        u.username = username;
        u.email = email;
        u.passwordHash = passwordHash;
        return u;
    }

    public static AppUserEntity external(String providerId, String subject, String username, String email) {
        AppUserEntity u = new AppUserEntity();
        u.providerId = providerId;
        u.externalSubject = subject;
        u.username = username;
        u.email = email;
        return u;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
