## MODIFIED Requirements

### Requirement: The SDK offers a code editor

The plugin SDK SHALL export `CodeEditor`, which edits YAML or JSON with Studio's editor and colours. It SHALL take:
- a value;
- a change handler (without one it is read-only);
- a language;
- diagnostics located by 1-based line and column with a severity;
- a visible label that is its accessible name;
- an optional maximum height;
- optional line wrapping.

It SHALL summarise the diagnostics in a live region. It SHALL highlight keys, values, numbers and booleans, comments, punctuation and document markers distinctly, from Studio's colour tokens. Each token SHALL meet 4.5:1 contrast in both colour schemes. It SHALL fold blocks, match brackets and search.

Tab SHALL indent. Escape followed by Tab SHALL move focus out of the editor, and the editor SHALL say so. Past the maximum height the editor SHALL scroll inside itself, and a long line SHALL scroll horizontally within the editor, never past its container.

#### Scenario: Diagnostics are shown and announced
- **WHEN** a plugin passes an error for line 4, column 9
- **THEN** the editor marks that position and announces "1 error" with the first message

#### Scenario: A YAML document is highlighted
- **WHEN** the editor shows `queue: orders # the inbound queue`
- **THEN** the key, the value and the comment each carry a different highlight style

#### Scenario: Leaving the editor from the keyboard
- **WHEN** focus is in an editable editor and the operator presses Escape, then Tab
- **THEN** focus moves to the next control after the editor, and no tab character was inserted

#### Scenario: A long line stays inside a narrow container
- **WHEN** the editor inside a drawer shows a line wider than the drawer
- **THEN** the editor scrolls horizontally and the drawer does not

## ADDED Requirements

### Requirement: Plugins can read the Studio version and a cluster's name

Studio SHALL offer plugins a `@PluginApi` bean that gives the running Studio version, empty for a development build. It SHALL also give the display name of a cluster, empty unless the current caller holds a grant on that cluster.

#### Scenario: A plugin names its source
- **WHEN** a plugin asks for the version and the name of a cluster the caller can see, on a released Studio
- **THEN** it receives the release's version and the cluster's name

#### Scenario: A cluster the caller cannot see stays hidden
- **WHEN** a plugin asks for the name of a cluster on which the caller holds no grant
- **THEN** it receives nothing, exactly as for a cluster that does not exist
