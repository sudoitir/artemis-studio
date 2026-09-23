package io.github.sudoitir.artemisstudio.kernel.plugin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type a plugin may reference. Put on a bean's concrete class, it also exports that bean
 * into the curated API context every plugin's own context is parented on: the host builds that
 * context from {@code mainContext.getBeansWithAnnotation(PluginApi.class)}, registering each under
 * its main-context bean name (design.md, task 6.1). A type not carrying this annotation is not
 * part of the plugin API and is not resolvable from a plugin's context, even if the plugin's
 * classloader can otherwise see the class.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PluginApi {}
