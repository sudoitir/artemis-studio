package io.github.sudoitir.artemisstudio.sql;

import io.github.sudoitir.artemisstudio.sql.ColumnCatalogue.Evaluation;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Predicate;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Term;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Splits a {@code WHERE} clause into the three places a predicate can be evaluated
 * (ADR-0058 D4), which is the whole of the feature's cost model:
 *
 * <ul>
 *   <li><b>target</b> — it names the queue, address or node, so it filters the target
 *       list during planning and no broker is asked about it at all;
 *   <li><b>pushdown</b> — the broker's selector evaluates it, so the broker returns
 *       fewer messages;
 *   <li><b>scan</b> — only Studio can evaluate it, so every message the broker
 *       returned is examined.
 * </ul>
 *
 * <p>The split is by top-level {@code AND} conjunct, because {@code AND} is the only
 * operator under which evaluating one part early cannot change the answer. A
 * disjunction is classified <em>as a whole</em> by its most expensive leaf: pushing
 * down one side of an {@code OR} would narrow the set the other side gets to see,
 * turning a wrong answer into something that looks like a right one. That is the one
 * mistake in this area that fails silently, so it is enforced here and asserted in
 * the tests.
 */
@Component
public class PredicateSplitter {

    private final SelectorRenderer selectors;

    public PredicateSplitter(SelectorRenderer selectors) {
        this.selectors = selectors;
    }

    /**
     * The three parts of a split clause. Any of them may be null, meaning "no
     * restriction from this layer".
     */
    public record Split(Predicate target, Predicate pushdown, Predicate scan) {

        public boolean requiresScan() {
            return scan != null;
        }

        public boolean hasPushdown() {
            return pushdown != null;
        }
    }

    public Split split(Predicate where) {
        if (where == null) {
            return new Split(null, null, null);
        }
        List<Predicate> conjuncts = new ArrayList<>();
        flatten(where, conjuncts);

        List<Predicate> target = new ArrayList<>();
        List<Predicate> pushdown = new ArrayList<>();
        List<Predicate> scan = new ArrayList<>();
        for (Predicate conjunct : conjuncts) {
            switch (classify(conjunct)) {
                case TARGET -> target.add(conjunct);
                case PUSHDOWN -> pushdown.add(conjunct);
                case SCAN -> scan.add(conjunct);
            }
        }
        return new Split(conjoin(target), conjoin(pushdown), conjoin(scan));
    }

    /** Flattens nested top-level {@code AND}s so each conjunct is classified on its own. */
    private void flatten(Predicate predicate, List<Predicate> into) {
        if (predicate instanceof Predicate.And and) {
            and.parts().forEach(part -> flatten(part, into));
        } else {
            into.add(predicate);
        }
    }

    private Predicate conjoin(List<Predicate> parts) {
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        return new Predicate.And(List.copyOf(parts));
    }

    /**
     * The most expensive evaluation any leaf of this predicate needs. A predicate is
     * only as cheap as its costliest term, so {@code SCAN} wins over {@code PUSHDOWN}
     * wins over {@code TARGET}.
     */
    public Evaluation classify(Predicate predicate) {
        Evaluation leaves =
                switch (predicate) {
                    case Predicate.And and -> worst(and.parts());
                    case Predicate.Or or -> worst(or.parts());
                    case Predicate.Not not -> classify(not.inner());
                    case Predicate.Compare compare -> classify(compare.term());
                    case Predicate.In in -> classify(in.term());
                    case Predicate.IsNull isNull -> classify(isNull.term());
                    case Predicate.Between between -> classify(between.term());
                    case Predicate.Like like ->
                        // ILIKE has no selector equivalent, and an approximate translation
                        // would silently change the result set — so it is a scan (D4).
                        like.caseInsensitive() ? Evaluation.SCAN : classify(like.term());
                };
        // Eligible in principle is not the same as renderable in fact: a property
        // name a selector cannot spell, or an ordering comparison on the durability
        // flag, would be dropped rather than pushed down. Demote it to a scan so the
        // predicate is still applied.
        if (leaves == Evaluation.PUSHDOWN && !selectors.isRenderable(predicate)) {
            return Evaluation.SCAN;
        }
        return leaves;
    }

    public Evaluation classify(Term term) {
        return switch (term) {
            case Term.ColumnTerm column -> column.column().evaluation();
            case Term.PropertyTerm ignored -> Evaluation.PUSHDOWN;
            case Term.JsonTerm ignored -> Evaluation.SCAN;
            case Term.CaseFold ignored -> Evaluation.SCAN;
        };
    }

    private Evaluation worst(List<Predicate> parts) {
        Evaluation worst = Evaluation.TARGET;
        for (Predicate part : parts) {
            Evaluation e = classify(part);
            if (e.ordinal() > worst.ordinal()) {
                worst = e;
            }
        }
        return worst;
    }
}
