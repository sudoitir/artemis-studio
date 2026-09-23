package io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime;

import java.beans.Introspector;
import java.lang.reflect.Field;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Clears the JVM-wide, class-keyed caches that would otherwise keep a plugin's classloader
 * reachable after every reference {@link PluginRuntime} itself held is gone (design.md's spike
 * report; task 6.2's "Open item"). None of these caches are per-classloader by construction —
 * {@link CachedIntrospectionResults} is the one exception, keyed by class and offering
 * {@link CachedIntrospectionResults#clearClassLoader} to evict only the entries for one loader;
 * the others (java.beans' own introspector cache, and Spring's reflection/annotation/type caches)
 * are cleared wholesale, which is safe here because a plugin unload is rare compared to the cost
 * of rebuilding these caches once.
 *
 * <p><b>The root cause this task went looking for</b> (task 6.2's "Open item"; the spike found the
 * loader was not collected and named JVM-wide, class-keyed caches as the likely cause): a heap dump
 * of an uncollected plugin classloader, walked for what still referenced it, showed the expected
 * {@code WeakReference}/{@code WeakHashMap} entries (harmless — a weak reference never blocks
 * collection) plus one that is not weak: an {@code org.springframework.util.ConcurrentReferenceHashMap}
 * entry. That map's default reference type is {@code SOFT}, not weak, and a soft reference is only
 * cleared under actual memory pressure — {@code System.gc()} does not reliably clear it in a test
 * or production JVM with headroom, so the plugin's {@code Class} objects (and through them, its
 * classloader) stayed reachable indefinitely. {@link AnnotationUtils#clearCache()} already empties
 * the annotation-metadata {@code ConcurrentReferenceHashMap}s it owns, but three more of Spring's
 * own soft-referenced caches have no public clear method: {@link BridgeMethodResolver}'s method
 * cache, {@link GenericTypeResolver}'s type-variable cache, and
 * {@code org.springframework.core.convert.Property}'s annotation cache. All three are simple
 * {@code static final Map} fields, so they are cleared here by reflection — best-effort, and never
 * fatal to an unload if a Spring upgrade renames one (logged and skipped, not thrown).
 *
 * <p>Those three turned out not to be the one actually pinning the plugin's classloader in
 * practice; walking the heap dump's reference chain from the still-live {@code URLClassLoader} up
 * through a {@code ConcurrentReferenceHashMap$SoftEntryReference} → {@code Segment} →
 * {@code Segment[]} led to {@link org.springframework.core.io.support.SpringFactoriesLoader}'s own
 * {@code static final Map<ClassLoader, ...> cache} — keyed directly by {@code ClassLoader}, so a
 * plugin's Hibernate/JPA bootstrap (which loads {@code spring.factories} contributions through
 * {@code SpringFactoriesLoader.forDefaultResourceLocation(loader)}) leaves the plugin's own
 * classloader as a soft-referenced map key forever, absent memory pressure. Being keyed by
 * classloader, it is cleared precisely (one {@code cache.remove(loader)}), not wholesale.
 *
 * <p>Fixing that one was not enough on its own: {@code @EnableTransactionManagement(proxyTargetClass
 * = true)} (non-negotiable — the spike proved plain JDK proxies don't apply {@code @Transactional}/
 * {@code @PreAuthorize} to a plugin's own classes) makes Spring AOP use
 * {@code org.springframework.aop.framework.ObjenesisCglibAopProxy} to instantiate every CGLIB proxy
 * without calling its constructor. That class holds one JVM-wide, {@code static final}
 * {@code SpringObjenesis}, whose own {@code cache} field is — again — a
 * {@code ConcurrentReferenceHashMap}, this time keyed by {@code Class<?>} rather than by
 * {@code ClassLoader}: every plugin controller or service Spring proxies (any
 * {@code @Transactional}/{@code @PreAuthorize} bean, which in practice is every plugin bean
 * {@link PluginInfrastructure} exists to secure) leaves its generated proxy {@code Class} — and so
 * its classloader — soft-reachable through this cache. Being keyed by {@code Class}, not by
 * {@code ClassLoader}, it cannot be evicted by one {@code remove(loader)}; every entry whose key's
 * classloader is this one is removed instead.
 */
@Slf4j
final class PluginClassloaderCaches {

    private PluginClassloaderCaches() {}

    static void clear(ClassLoader loader) {
        CachedIntrospectionResults.clearClassLoader(loader);
        Introspector.flushCaches();
        ReflectionUtils.clearCache();
        AnnotationUtils.clearCache();
        ResolvableType.clearCache();
        clearStaticMapField(BridgeMethodResolver.class, "cache");
        clearStaticMapField(GenericTypeResolver.class, "typeVariableCache");
        clearStaticMapField("org.springframework.core.convert.Property", "annotationCache");
        removeFromStaticMapField("org.springframework.core.io.support.SpringFactoriesLoader", "cache", loader);
        clearObjenesisCacheForLoader(loader);
    }

    /**
     * {@code ObjenesisCglibAopProxy.objenesis} is a static field holding one {@code SpringObjenesis}
     * for the whole JVM; its {@code cache} is an instance field, keyed by the proxy {@code Class}
     * rather than by {@code ClassLoader}, so eviction means removing every key whose own classloader
     * is this one, not a single {@code remove(loader)}.
     */
    private static void clearObjenesisCacheForLoader(ClassLoader loader) {
        try {
            Class<?> owner = Class.forName("org.springframework.aop.framework.ObjenesisCglibAopProxy");
            Field objenesisField = owner.getDeclaredField("objenesis");
            objenesisField.setAccessible(true);
            Object objenesis = objenesisField.get(null);
            if (objenesis == null) {
                return;
            }
            Field cacheField = objenesis.getClass().getDeclaredField("cache");
            cacheField.setAccessible(true);
            if (cacheField.get(objenesis) instanceof Map<?, ?> cache) {
                cache.keySet()
                        .removeIf(key -> key instanceof Class<?> proxyClass && proxyClass.getClassLoader() == loader);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.debug(
                    "Could not evict {} from ObjenesisCglibAopProxy's proxy-instantiator cache (Spring internals may"
                            + " have changed); a plugin unload may need a restart to fully reclaim its classloader.",
                    loader,
                    e);
        }
    }

    /** Like {@link #clearStaticMapField}, but removes only this one classloader's own entry. */
    private static void removeFromStaticMapField(String ownerClassName, String fieldName, ClassLoader loader) {
        try {
            Class<?> owner = Class.forName(ownerClassName);
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            if (field.get(null) instanceof Map<?, ?> map) {
                map.remove(loader);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.debug(
                    "Could not evict {} from {}#{} (Spring internals may have changed); a plugin unload may need"
                            + " a restart to fully reclaim its classloader.",
                    loader,
                    ownerClassName,
                    fieldName,
                    e);
        }
    }

    private static void clearStaticMapField(Class<?> owner, String fieldName) {
        clearStaticMapField(owner.getName(), fieldName);
    }

    /** By class name too: {@code Property} is package-private, so its {@code Class} isn't public API to import. */
    private static void clearStaticMapField(String ownerClassName, String fieldName) {
        try {
            Class<?> owner = Class.forName(ownerClassName);
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            if (field.get(null) instanceof Map<?, ?> map) {
                map.clear();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.debug(
                    "Could not clear {}#{} (Spring internals may have changed); a plugin unload may need a"
                            + " restart to fully reclaim its classloader.",
                    ownerClassName,
                    fieldName,
                    e);
        }
    }
}
