import { HighlightStyle } from "@codemirror/language";
import { EditorView } from "@codemirror/view";
import { tags } from "@lezer/highlight";

/**
 * Every colour comes from `theme.css`, so the editor follows the colour scheme
 * with the rest of the console and neither scheme is inferred from the other
 * (non-negotiable #6). Four token roles: an identifier deliberately stays body
 * text, so the eye lands on what was typed rather than on the syntax.
 */
export const codeHighlight = HighlightStyle.define([
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

export const codeTheme = EditorView.theme({
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
  // Line numbers and the lint gutter: CodeMirror's own default is a light grey
  // panel, which glares in the dark scheme.
  ".cm-gutters": {
    backgroundColor: "var(--as-surface)",
    color: "var(--as-text-dimmed)",
    borderInlineEnd: "1px solid var(--as-border)",
  },
  ".cm-activeLineGutter": { backgroundColor: "transparent" },
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
