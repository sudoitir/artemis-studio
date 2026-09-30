package io.github.sudoitir.artemisstudio.kernel.security.internal;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.ast.BeanReference;
import org.springframework.expression.spel.ast.MethodReference;
import org.springframework.expression.spel.ast.SpelNodeImpl;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.expression.spel.support.StandardTypeLocator;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.ReflectionUtils;

/**
 * Reads the permissions a class's {@code @PreAuthorize} guards name through {@code @perm.can(...)}.
 * Only a permission argument that is a literal or a {@code T(type).CONSTANT} is resolved; one that
 * depends on the call ({@code #var}, {@code filterObject}) is skipped, as are wildcards.
 */
final class GuardPermissions {

    /** One permission a guard names, where, and whether the guard checks it against a cluster. */
    record GuardRef(String permission, String location, boolean withCluster) {}

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    private GuardPermissions() {}

    static List<GuardRef> of(Class<?> type, ClassLoader loader) {
        List<GuardRef> refs = new ArrayList<>();
        read(type, type.getName(), loader, refs);
        for (var method : ReflectionUtils.getUniqueDeclaredMethods(type, ReflectionUtils.USER_DECLARED_METHODS)) {
            read(method, type.getName() + "#" + method.getName(), loader, refs);
        }
        return refs;
    }

    private static void read(AnnotatedElement element, String location, ClassLoader loader, List<GuardRef> refs) {
        PreAuthorize guard = AnnotatedElementUtils.findMergedAnnotation(element, PreAuthorize.class);
        if (guard == null) {
            return;
        }
        SpelNode ast;
        try {
            ast = ((SpelExpression) PARSER.parseExpression(guard.value())).getAST();
        } catch (ParseException _) {
            return; // Spring Security rejects it at call time; not this check's concern
        }
        walk(ast, guard.value(), location, loader, refs);
    }

    private static void walk(SpelNode node, String source, String location, ClassLoader loader, List<GuardRef> refs) {
        for (int i = 0; i < node.getChildCount(); i++) {
            SpelNode child = node.getChild(i);
            if (child instanceof BeanReference bean
                    && "perm".equals(bean.getName())
                    && i + 1 < node.getChildCount()
                    && node.getChild(i + 1) instanceof MethodReference call
                    && "can".equals(call.getName())
                    && call.getChildCount() > 0) {
                String permission = constant(call.getChild(call.getChildCount() - 1), source, loader);
                if (permission != null && !permission.equals("*") && !permission.endsWith(":*")) {
                    refs.add(new GuardRef(permission, location, call.getChildCount() > 1));
                }
            }
            walk(child, source, location, loader, refs);
        }
    }

    private static String constant(SpelNode argument, String source, ClassLoader loader) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setTypeLocator(new StandardTypeLocator(loader));
        try {
            Object value = new SpelExpression(source, (SpelNodeImpl) argument, new SpelParserConfiguration())
                    .getValue(context);
            return value instanceof String s ? s : null;
        } catch (EvaluationException _) {
            return null; // depends on the call, e.g. #permission or filterObject
        }
    }
}
