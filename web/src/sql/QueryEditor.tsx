import { useEffect, useId, useRef } from "react";
import {
  EditorState,
  Prec,
  StateEffect,
  StateField,
  type Extension,
} from "@codemirror/state";
import {
  Decoration,
  EditorView,
  keymap,
  placeholder,
  type DecorationSet,
} from "@codemirror/view";
import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import {
  autocompletion,
  closeBrackets,
  closeBracketsKeymap,
  completionKeymap,
  type CompletionContext,
  type CompletionResult,
} from "@codemirror/autocomplete";
import { sql } from "@codemirror/lang-sql";
import { Text } from "@mantine/core";
import { tags } from "@lezer/highlight";

import { COLUMNS, EVALUATION_WORDS, FUNCTIONS } from "./catalogue.ts";

/**
 * Every colour comes from `theme.css`, so the editor follows the colour scheme
 * with the rest of the console and neither scheme is inferred from the other
 * (non-negotiable #6). Four token roles: an identifier deliberately stays body
 * text, so the eye lands on what was typed rather than on the syntax.
 */
const highlight = HighlightStyle.define([
  { tag: tags.keyword, color: "var(--as-code-keyword)" },
  { tag: tags.operatorKeyword, color: "var(--as-code-keyword)" },
  {
    tag: [tags.string, tags.special(tags.string)],
    color: "var(--as-code-string)",
  },
  { tag: [tags.number, tags.bool, tags.null], color: "var(--as-code-number)" },
  {
    tag: [tags.comment, tags.lineComment, tags.blockComment],
    color: "var(--as-code-comment)",
  },
]);

const theme = EditorView.theme({
  "&": {
    color: "var(--as-text)",
    backgroundColor: "var(--as-surface)",
    border: "1px solid var(--as-border)",
    borderRadius: "var(--mantine-radius-md)",
    fontSize: "var(--mantine-font-size-sm)",
  },
  "&.cm-focused": {
    outline: "2px solid var(--mantine-primary-color-filled)",
    outlineOffset: "-1px",
  },
  ".cm-content": {
    fontFamily: "var(--mantine-font-family-monospace)",
    padding: "10px 12px",
    caretColor: "var(--as-text)",
  },
  ".cm-cursor, .cm-dropCursor": { borderInlineStartColor: "var(--as-text)" },
  ".cm-placeholder": { color: "var(--as-text-dimmed)" },
  ".cm-selectionBackground, &.cm-focused .cm-selectionBackground": {
    backgroundColor: "var(--as-grid-row-hover)",
  },
  ".cm-tooltip": {
    backgroundColor: "var(--as-surface-raised)",
    border: "1px solid var(--as-border)",
    borderRadius: "var(--mantine-radius-sm)",
    color: "var(--as-text)",
  },
  ".cm-tooltip-autocomplete ul li[aria-selected]": {
    backgroundColor: "var(--mantine-primary-color-filled)",
    color: "var(--mantine-color-white)",
  },
  ".cm-completionDetail": {
    color: "var(--as-text-dimmed)",
    fontStyle: "normal",
  },
  // Wavy underline plus the danger colour: the underline carries the meaning on
  // its own, so the colour is redundant emphasis rather than the only signal.
  ".cm-as-error-token": {
    textDecoration: "underline wavy var(--as-danger)",
    textUnderlineOffset: "3px",
  },
});

/**
 * The completion source. Written by hand rather than handed to `lang-sql`'s
 * `schema` option because a queue name is not a SQL identifier: `ORDER.IN` is a
 * reserved word and a dot, so it is always double-quoted, and the schema
 * completer has no way to produce that.
 */
function completions(queues: string[]) {
  const columnOptions = COLUMNS.map((c) => ({
    label: c.name,
    type: "property",
    detail: EVALUATION_WORDS[c.evaluation],
    info: c.indexOnly ? `${c.description} Index only.` : c.description,
  }));
  const functionOptions = FUNCTIONS.map((f) => ({
    label: `${f}(`,
    type: "function",
    detail: "function",
  }));
  const sourceOptions = [
    { label: "broker.", type: "namespace", detail: "read the live brokers" },
    { label: "index.", type: "namespace", detail: "read the historical index" },
  ];

  return (context: CompletionContext): CompletionResult | null => {
    // After FROM, the only useful completion is a queue name — quoted, because
    // that is the only spelling the parser accepts.
    const from = context.matchBefore(
      /from\s+(?:broker\.|index\.)?"?[\w.*#$-]*/i,
    );
    if (from) {
      const openQuote = from.text.lastIndexOf('"');
      const start =
        openQuote >= 0
          ? from.from + openQuote
          : from.to - wordAfterFrom(from.text).length;
      return {
        from: start,
        options: [
          ...(openQuote >= 0 ? [] : sourceOptions),
          ...queues.map((q) => ({
            label: `"${q}"`,
            type: "class",
            detail: "queue",
          })),
        ],
        validFor: /^["\w.*#$-]*$/,
      };
    }

    const word = context.matchBefore(/[\w.]*/);
    if (!word || (word.from === word.to && !context.explicit)) return null;
    return {
      from: word.from,
      options: [
        ...columnOptions,
        ...functionOptions,
        {
          label: "props.",
          type: "property",
          detail: "an application property, by name",
        },
      ],
      validFor: /^[\w.]*$/,
    };
  };
}

/**
 * The offending-token underline (7.10).
 *
 * <p>The plan strip already names the token a refusal is about. Naming it and
 * leaving the operator to find it in their own query is half an answer — on a long
 * query it is the half that costs the time. This marks it in place, so the error
 * and the text it is about are in the same field of view.
 */
const setErrorToken = StateEffect.define<string | null>();

const errorMark = Decoration.mark({ class: "cm-as-error-token" });

const errorField = StateField.define<DecorationSet>({
  create: () => Decoration.none,
  update(marks, transaction) {
    let token: string | null | undefined;
    for (const effect of transaction.effects) {
      if (effect.is(setErrorToken)) token = effect.value;
    }
    if (token === undefined) {
      // No new verdict: keep what is marked, moved along with the edit.
      return marks.map(transaction.changes);
    }
    if (!token) return Decoration.none;
    const text = transaction.state.doc.toString();
    // The first occurrence, case-insensitively: the server reports the token as the
    // parser saw it, which may differ in case from what was typed.
    const at = text.toLowerCase().indexOf(token.toLowerCase());
    return at < 0
      ? Decoration.none
      : Decoration.set([errorMark.range(at, at + token.length)]);
  },
  provide: (field) => EditorView.decorations.from(field),
});

/** The token after `FROM `, so the completion replaces it rather than appending to it. */
function wordAfterFrom(text: string): string {
  return /from\s+(.*)$/i.exec(text)?.[1] ?? "";
}

/**
 * The console's query editor: CodeMirror 6 with the SQL grammar, completion over
 * the column catalogue and the cluster's live queue names, and ⌘/Ctrl-Enter to
 * run.
 *
 * <p>Deliberately not `basicSetup` — line numbers, folding and a gutter belong to
 * a file, not to a four-line query. Everything here earns its place.
 */
export function QueryEditor({
  value,
  onChange,
  onRun,
  queues,
  label = "Query",
  errorToken = null,
}: {
  value: string;
  onChange: (next: string) => void;
  onRun: () => void;
  /** Queue names for completion, from the cluster's current snapshot. */
  queues: string[];
  label?: string;
  /** The token a refusal is about, underlined in place. Null clears the mark. */
  errorToken?: string | null;
}) {
  const labelId = useId();
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);

  // The extensions are built once, so the keymap and completion source close over
  // the first render's callbacks. These refs keep them current without tearing
  // the editor down and losing the cursor on every parent render.
  const latest = useRef({ onChange, onRun, queues });
  latest.current = { onChange, onRun, queues };

  useEffect(() => {
    if (!host.current) return;

    const extensions: Extension[] = [
      history(),
      // Above the default keymap: Enter alone inserts a newline, and Mod-Enter
      // must not be swallowed by anything that binds it first.
      Prec.high(
        keymap.of([
          {
            key: "Mod-Enter",
            preventDefault: true,
            run: () => {
              latest.current.onRun();
              return true;
            },
          },
        ]),
      ),
      keymap.of([
        ...closeBracketsKeymap,
        ...completionKeymap,
        ...defaultKeymap,
        ...historyKeymap,
      ]),
      closeBrackets(),
      sql({ upperCaseKeywords: true }),
      syntaxHighlighting(highlight),
      autocompletion({
        override: [(c) => completions(latest.current.queues)(c)],
      }),
      placeholder('SELECT * FROM "ORDER.IN" WHERE priority > 4 LIMIT 100'),
      EditorView.lineWrapping,
      errorField,
      // The editor's content is a contenteditable div, not a form control, so a
      // <label for> cannot reach it. The visible label below is its accessible
      // name through aria-labelledby — a placeholder is not a label.
      EditorView.contentAttributes.of({
        "aria-labelledby": labelId,
        role: "textbox",
      }),
      theme,
      EditorView.updateListener.of((update) => {
        if (update.docChanged)
          latest.current.onChange(update.state.doc.toString());
      }),
    ];

    const editor = new EditorView({
      state: EditorState.create({ doc: value, extensions }),
      parent: host.current,
    });
    view.current = editor;
    return () => {
      editor.destroy();
      view.current = null;
    };
    // Built once for the editor's lifetime; `value` is synced by the effect below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [labelId]);

  // An external change to the query — a loaded example, a restored URL — is
  // pushed in. A change the editor itself made is already in the document, and
  // replacing it would move the cursor to the end mid-typing.
  useEffect(() => {
    const editor = view.current;
    if (!editor) return;
    const current = editor.state.doc.toString();
    if (current === value) return;
    editor.dispatch({
      changes: { from: 0, to: current.length, insert: value },
    });
  }, [value]);

  useEffect(() => {
    view.current?.dispatch({ effects: setErrorToken.of(errorToken) });
  }, [errorToken]);

  return (
    <div>
      <Text
        id={labelId}
        component="label"
        size="xs"
        fw={600}
        c="dimmed"
        display="block"
        mb={4}
      >
        {label}
      </Text>
      <div ref={host} />
    </div>
  );
}
