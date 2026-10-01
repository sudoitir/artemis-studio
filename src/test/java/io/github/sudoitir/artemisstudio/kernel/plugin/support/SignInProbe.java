package io.github.sudoitir.artemisstudio.kernel.plugin.support;

import io.github.sudoitir.artemisstudio.kernel.security.VerifiedIdentity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The directory behind a fixture plugin's sign-in provider (compiled inside the test, loaded by its
 * own classloader): a JVM-wide rendezvous on the test classpath, which a plugin's classloader
 * delegates to, keyed by provider id so fixtures do not see each other.
 */
public final class SignInProbe {

    /** A user the directory knows. */
    public record Person(String subject, String username, String password, String email, Set<String> groups) {}

    /** One provider's directory, and the switches a test flips to make it misbehave. */
    public static final class Directory {
        public final Map<String, Person> people = new ConcurrentHashMap<>();
        public final Set<String> revoked = ConcurrentHashMap.newKeySet();
        public final List<Set<String>> asked = new CopyOnWriteArrayList<>();
        public volatile boolean throwing;
        public volatile long delayMillis;

        public Directory add(String subject, String username, String password, String... groups) {
            people.put(username, new Person(subject, username, password, username + "@corp.test", Set.of(groups)));
            return this;
        }
    }

    private static final Map<String, Directory> DIRECTORIES = new ConcurrentHashMap<>();

    private SignInProbe() {}

    public static Directory of(String providerId) {
        return DIRECTORIES.computeIfAbsent(providerId, k -> new Directory());
    }

    public static void forget(String providerId) {
        DIRECTORIES.remove(providerId);
    }

    public static Optional<VerifiedIdentity> authenticate(String providerId, String username, String password) {
        Directory directory = of(providerId);
        if (directory.delayMillis > 0) {
            try {
                // Waits on a latch nobody opens: the delay, ended early only by the caller giving up.
                new CountDownLatch(1).await(directory.delayMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        if (directory.throwing) {
            throw new IllegalStateException("the directory is down");
        }
        Person person = directory.people.get(username);
        if (person == null || !person.password().equals(password) || directory.revoked.contains(person.subject())) {
            return Optional.empty();
        }
        return Optional.of(new VerifiedIdentity(person.subject(), person.username(), person.email(), person.groups()));
    }

    public static Set<String> noLongerValid(String providerId, Set<String> subjects) {
        Directory directory = of(providerId);
        directory.asked.add(Set.copyOf(subjects));
        return Set.copyOf(directory.revoked);
    }
}
