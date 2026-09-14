package io.github.sudoitir.artemisstudio.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.feature.sql.ColumnCatalogue.Column;
import io.github.sudoitir.artemisstudio.feature.sql.QueryAst.Literal;
import io.github.sudoitir.artemisstudio.feature.sql.QueryAst.Operator;
import io.github.sudoitir.artemisstudio.feature.sql.QueryAst.Predicate;
import io.github.sudoitir.artemisstudio.feature.sql.QueryAst.Term;
import io.github.sudoitir.artemisstudio.platform.broker.MessageBrowser.BodyEncoding;
import io.github.sudoitir.artemisstudio.platform.broker.MessageBrowser.BrowsedMessage;
import io.github.sudoitir.artemisstudio.platform.governance.ContentPolicy;
import io.github.sudoitir.artemisstudio.platform.governance.GovernContext;
import io.github.sudoitir.artemisstudio.platform.governance.GovernedMessage;
import io.github.sudoitir.artemisstudio.platform.governance.Location;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SqlGovernanceTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final ContentPolicy policy = mock(ContentPolicy.class);
    private final SqlGovernance governance = new SqlGovernance(policy);

    @BeforeEach
    void policy() {
        when(policy.classifies(any(), anyString())).thenReturn(false);
        when(policy.classifies(Location.PROPERTY, "customerEmail")).thenReturn(true);
        when(policy.classifies(Location.HEADER, "correlationId")).thenReturn(true);
        clear(false);
    }

    private void clear(boolean clear) {
        when(policy.context(eq(CLUSTER), isNull())).thenReturn(new GovernContext(CLUSTER, null, clear));
    }

    private static QueryAst where(Predicate predicate, List<QueryAst.Order> orderBy) {
        return new QueryAst(QueryAst.Source.DEFAULT, "orders", List.of(), predicate, orderBy, null, "SELECT ...");
    }

    private static Predicate.Compare eqStr(Term term, String value) {
        return new Predicate.Compare(term, Operator.EQ, new Literal.Str(value));
    }

    @Test
    void aPredicateOnAMaskedPropertyIsRefusedNamingTheField() {
        QueryAst ast = where(
                new Predicate.Or(List.of(
                        eqStr(new Term.PropertyTerm("region"), "eu"),
                        new Predicate.Not(
                                eqStr(new Term.CaseFold(new Term.PropertyTerm("customerEmail"), false), "x")))),
                List.of());

        assertThatThrownBy(() -> governance.guardPredicates(CLUSTER, ast))
                .isInstanceOf(GovernanceRefusedException.class)
                .hasMessageContaining("props.customerEmail")
                .satisfies(e ->
                        assertThat(((GovernanceRefusedException) e).field()).isEqualTo("props.customerEmail"));
    }

    @Test
    void orderingByAMaskedHeaderIsRefused() {
        QueryAst ast = where(
                null, List.of(new QueryAst.Order(new Term.ColumnTerm(Column.CORRELATION_ID), QueryAst.Direction.ASC)));

        assertThatThrownBy(() -> governance.guardPredicates(CLUSTER, ast))
                .isInstanceOf(GovernanceRefusedException.class);
    }

    @Test
    void clearAccessMayFilterOnAMaskedField() {
        clear(true);
        QueryAst ast = where(eqStr(new Term.PropertyTerm("customerEmail"), "x"), List.of());

        assertThatCode(() -> governance.guardPredicates(CLUSTER, ast)).doesNotThrowAnyException();
    }

    @Test
    void unclassifiedFieldsAndFullTextSearchAreAllowed() {
        QueryAst ast = where(
                new Predicate.And(List.of(eqStr(new Term.PropertyTerm("region"), "eu"), new Predicate.Match("jane"))),
                List.of());

        assertThatCode(() -> governance.guardPredicates(CLUSTER, ast)).doesNotThrowAnyException();
    }

    @Test
    void aResidualIsEvaluatedAgainstTheMaskedCopyWithTypesKept() {
        BrowsedMessage raw = new BrowsedMessage(
                7,
                3,
                true,
                4,
                1L,
                0L,
                10L,
                "g",
                "corr",
                null,
                null,
                "{\"email\":\"jane@example.com\"}",
                BodyEncoding.TEXT,
                "application/json",
                false,
                null,
                Map.of("contact", "jane@example.com"),
                Map.of("count", 3L),
                Map.of(),
                Map.of(),
                Map.of("vip", true));
        when(policy.govern(eq(GovernContext.masked(CLUSTER, "orders")), any()))
                .thenReturn(new GovernedMessage(
                        Map.of("groupId", "g", "correlationId", "corr"),
                        Map.of("contact", "[redacted email]", "count", 3L, "vip", true),
                        "{\"email\":\"[redacted email]\"}",
                        List.of(),
                        List.of(),
                        Map.of(),
                        1));

        BrowsedMessage evaluated = governance.forEvaluation(CLUSTER, "orders", raw);

        assertThat(evaluated.body()).doesNotContain("jane@example.com");
        assertThat(evaluated.stringProperties()).containsEntry("contact", "[redacted email]");
        assertThat(evaluated.intProperties()).containsEntry("count", 3L);
        assertThat(evaluated.booleanProperties()).containsEntry("vip", true);
        assertThat(evaluated.messageId()).isEqualTo(7);
    }
}
