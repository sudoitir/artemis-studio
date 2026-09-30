package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.security.KeyProvider;
import io.github.sudoitir.artemisstudio.kernel.security.Keyring;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.crypto.SecretKey;

/** Keys from {@code kek-<n>} files (base64) in a directory, and named secrets from files beside them. */
class FileKeyProvider implements KeyProvider {

    private static final Pattern KEK_FILE = Pattern.compile("kek-(\\d+)");

    private final Path directory;

    FileKeyProvider(String directory) {
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException(
                    "Secret key provider 'file': artemis-studio.secrets.file.directory is not set.");
        }
        this.directory = Path.of(directory);
    }

    @Override
    public Keyring load() {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("Secret key provider 'file': " + directory + " is not a directory.");
        }
        TreeMap<Integer, SecretKey> keys = new TreeMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Matcher kek = KEK_FILE.matcher(file.getFileName().toString());
                if (kek.matches()) {
                    int version = Integer.parseInt(kek.group(1));
                    keys.put(version, Keyring.parse(name(), file.getFileName().toString(), Files.readString(file)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Secret key provider 'file': cannot read " + directory, e);
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException("Secret key provider 'file': no kek-<n> file in " + directory + ".");
        }
        return new Keyring(keys);
    }

    @Override
    public Optional<String> secret(String name) {
        Path file = directory.resolve(name);
        try {
            return Files.isRegularFile(file)
                    ? Optional.of(Files.readString(file).trim()).filter(v -> !v.isEmpty())
                    : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Secret key provider 'file': cannot read " + name, e);
        }
    }

    @Override
    public String name() {
        return "file";
    }
}
