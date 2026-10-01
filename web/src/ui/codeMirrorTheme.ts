import { HighlightStyle } from '@codemirror/language';
import { EditorView } from '@codemirror/view';
import { tags } from '@lezer/highlight';

/**
 * Every colour comes from `theme.css`, so the editor follows the colour scheme
 * with the rest of the console and neither scheme is inferred from the other
 * (non-negotiable #6). A SQL identifier deliberately stays body text, so the eye
 * lands on what was typed rather than on the syntax. A YAML or JSON document is
 * mostly keys and values, so those two carry colour, and the punctuation that
 * holds them together steps back.
 */
export const codeHighlight = HighlightStyle.define([
  { tag: [tags.keyword, tags.operatorKeyword], color: 'var(--as-code-keyword)' },
  {
    tag: [tags.propertyName, tags.definition(tags.propertyName)],
    color: 'var(--as-code-property)',
  },
  {
    // `content` is a YAML plain scalar: in a document, the value.
    tag: [tags.string, tags.special(tags.string), tags.content, tags.attributeValue],
    color: 'var(--as-code-string)',
  },
  { tag: [tags.number, tags.bool, tags.null], color: 'var(--as-code-number)' },
  // Anchors, aliases and tags: rare, and worth noticing when they appear.
  { tag: [tags.labelName, tags.typeName], color: 'var(--as-code-meta)' },
  {
    tag: [tags.comment, tags.lineComment, tags.blockComment],
    color: 'var(--as-code-comment)',
  },
  // Structure, not content: `: , - [ ] { }` and the `---` / `...` document markers.
  {
    tag: [tags.separator, tags.punctuation, tags.squareBracket, tags.brace, tags.meta],
    color: 'var(--as-text-dimmed)',
  },
]);

const squiggle = (colour: string) => ({
  textDecoration: 'underline wavy',
  textDecorationColor: colour,
  textUnderlineOffset: '0.1875rem',
});

export const codeTheme = EditorView.theme({
  '&': {
    color: 'var(--as-text)',
    backgroundColor: 'var(--as-surface)',
    border: '1px solid var(--as-border)',
    borderRadius: 'var(--mantine-radius-md)',
    fontSize: 'var(--mantine-font-size-sm)',
  },
  // Inside the box, so the editor's own border never clips the ring.
  '&.cm-focused': {
    outline: 'var(--as-focus-ring)',
    outlineOffset: 'calc(var(--as-focus-offset) * -1)',
  },
  '.cm-content': {
    fontFamily: 'var(--mantine-font-family-monospace)',
    padding: '0.625rem 0.75rem',
    caretColor: 'var(--as-text)',
  },
  '.cm-cursor, .cm-dropCursor': { borderInlineStartColor: 'var(--as-text)' },
  // Line numbers and the lint gutter: CodeMirror's own default is a light grey
  // panel, which glares in the dark scheme.
  '.cm-gutters': {
    backgroundColor: 'var(--as-surface)',
    color: 'var(--as-text-dimmed)',
    borderInlineEnd: '1px solid var(--as-border)',
  },
  '.cm-activeLineGutter': { backgroundColor: 'transparent', color: 'var(--as-text)' },
  '.cm-activeLine': { backgroundColor: 'var(--as-code-active-line)' },
  // Horizontal overflow scrolls inside the editor, never out of its container.
  '.cm-scroller': { overflow: 'auto' },
  '.cm-foldGutter .cm-gutterElement': { cursor: 'pointer', paddingInline: '0.125rem' },
  '.cm-foldPlaceholder': {
    backgroundColor: 'var(--as-surface-raised)',
    border: '1px solid var(--as-border)',
    color: 'var(--as-text-dimmed)',
  },
  '&.cm-focused .cm-matchingBracket': {
    backgroundColor: 'transparent',
    outline: '1px solid var(--as-text-dimmed)',
  },
  '&.cm-focused .cm-nonmatchingBracket': {
    backgroundColor: 'transparent',
    textDecoration: 'underline wavy var(--as-danger)',
  },
  '.cm-selectionMatch': { backgroundColor: 'var(--as-code-active-line)' },
  '.cm-searchMatch': {
    backgroundColor: 'transparent',
    outline: '1px solid var(--as-accent)',
  },
  '.cm-searchMatch.cm-searchMatch-selected': { backgroundColor: 'var(--as-selected)' },
  '.cm-panels': {
    backgroundColor: 'var(--as-surface-raised)',
    color: 'var(--as-text)',
  },
  '.cm-panels-bottom': { borderBlockStart: '1px solid var(--as-border)' },
  '.cm-panel.cm-search': { fontFamily: 'var(--mantine-font-family)', fontSize: 'var(--mantine-font-size-sm)' },
  '.cm-textfield': {
    backgroundColor: 'var(--as-surface)',
    color: 'var(--as-text)',
    border: '1px solid var(--as-border)',
    borderRadius: 'var(--mantine-radius-sm)',
  },
  '.cm-button': {
    backgroundImage: 'none',
    backgroundColor: 'var(--as-surface)',
    color: 'var(--as-text)',
    border: '1px solid var(--as-border)',
    borderRadius: 'var(--mantine-radius-sm)',
  },
  '.cm-placeholder': { color: 'var(--as-text-dimmed)' },
  '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': {
    backgroundColor: 'var(--as-selected)',
  },
  '.cm-tooltip': {
    backgroundColor: 'var(--as-surface-raised)',
    border: '1px solid var(--as-border)',
    borderRadius: 'var(--mantine-radius-sm)',
    color: 'var(--as-text)',
  },
  '.cm-tooltip-autocomplete ul li[aria-selected]': {
    backgroundColor: 'var(--as-selected)',
    color: 'var(--as-text)',
    outline: '1px solid var(--as-accent)',
    outlineOffset: '-1px',
  },
  '.cm-completionDetail': {
    color: 'var(--as-text-dimmed)',
    fontStyle: 'normal',
  },
  // Wavy underline plus the danger colour: the underline carries the meaning on
  // its own, so the colour is redundant emphasis rather than the only signal.
  '.cm-as-error-token': {
    textDecoration: 'underline wavy var(--as-danger)',
    textUnderlineOffset: '0.1875rem',
  },
  // The lint layer ships its own marks in fixed colours (a data-URI squiggle, a gutter icon, a bar on
  // each message). They take the status tokens here, and the gutter marks differ in shape as well as
  // colour, so a severity is never carried by colour alone.
  '.cm-lintRange-error': { backgroundImage: 'none', ...squiggle('var(--as-danger)') },
  '.cm-lintRange-warning': { backgroundImage: 'none', ...squiggle('var(--as-warning)') },
  '.cm-lintRange-active': { backgroundColor: 'var(--as-selected)' },
  '.cm-diagnostic-error': { borderInlineStartColor: 'var(--as-danger)' },
  '.cm-diagnostic-warning': { borderInlineStartColor: 'var(--as-warning)' },
  '.cm-lintPoint:after': { borderBottomColor: 'var(--as-danger)' },
  '.cm-lintPoint-warning:after': { borderBottomColor: 'var(--as-warning)' },
  '.cm-lint-marker-error, .cm-lint-marker-warning, .cm-lint-marker-info': {
    content: 'none',
    backgroundColor: 'var(--as-text-dimmed)',
  },
  '.cm-lint-marker-error': { backgroundColor: 'var(--as-danger)', borderRadius: '50%' },
  '.cm-lint-marker-warning': {
    backgroundColor: 'var(--as-warning)',
    clipPath: 'polygon(50% 0, 100% 100%, 0 100%)',
  },
});
