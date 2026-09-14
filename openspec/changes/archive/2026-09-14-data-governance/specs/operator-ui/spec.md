## ADDED Requirements

### Requirement: Governed message content is presented for what it is

Wherever message content is shown (message detail, SQL results, flow detail), the interface SHALL present each governed value distinctly:

- **masked value**: a labelled token naming its class in words;
- **dropped credential**: a labelled marker;
- **withheld content**: a notice stating why, and naming the setting that changes it where one does;
- **value shown in clear by grant**: marked as sensitive.

Colour SHALL NOT be the only carrier of any of these states. Copying or downloading governed content SHALL state that the copy contains masked values where it does.

#### Scenario: A masked value is labelled in words

- **WHEN** a masked email appears in message detail
- **THEN** it is shown as a token reading that it is a masked email, not as a string resembling an address

#### Scenario: A withheld body names its reason

- **WHEN** message detail shows a message whose binary body was withheld
- **THEN** the body area states that the binary body could not be classified and was withheld

### Requirement: The governance screens teach and gate honestly

The governance screens SHALL:

- list rules, marking built-in rules as not deletable while leaving their enable control available;
- list open findings with confirm and dismiss actions;
- show how many stored rows are still under an earlier policy version.

A user without the governance write permission SHALL see the change controls disabled, with the reason reachable from the keyboard. An empty inbox SHALL explain what a finding is and why there are none; a filtered-empty inbox SHALL say so and offer to clear the filter.

#### Scenario: A read-only user sees why controls are disabled

- **WHEN** a user with governance read but not governance write opens the rules screen
- **THEN** the create, edit and delete controls are disabled and a keyboard-reachable explanation names the missing permission

#### Scenario: An empty inbox teaches

- **WHEN** the inbox has no findings
- **THEN** it explains that findings are personal data detected in fields no rule covers, and that none have been detected yet
