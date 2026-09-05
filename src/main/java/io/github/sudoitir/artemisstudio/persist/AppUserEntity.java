package io.github.sudoitir.artemisstudio.persist;

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
 * Maps {@code app_user} (changesets 003, 014). A local account carries a bcrypt
 * {@code passwordHash}; an OIDC-provisioned account carries {@code issuer} +
 * {@code subject} instead and a null hash (ADR-0037, ADR-0040).
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

    @Column(name = "issuer")
    private String issuer;

    @Column(name = "subject")
    private String subject;

    @Column(name = "auth_source", nullable = false)
    private String authSource = "LOCAL";

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
        u.authSource = "LOCAL";
        return u;
    }

    public static AppUserEntity oidc(String username, String email, String issuer, String subject) {
        AppUserEntity u = new AppUserEntity();
        u.username = username;
        u.email = email;
        u.issuer = issuer;
        u.subject = subject;
        u.authSource = "OIDC";
        return u;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
