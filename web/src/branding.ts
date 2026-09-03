/**
 * Single source of every user-visible product name on the web side.
 * Mirror of `Branding.java`. See `docs/adr/0001-project-name-and-trademark-risk.md`.
 *
 * A rename is: this file, `Branding.java`, the Maven `artifactId`, the image
 * coordinates, and `index.html` <title>. Nothing else.
 */
export const branding = {
  productName: 'Artemis Studio',
  productShortName: 'Studio',
  tagline:
    'Cluster-wide management and observability for Apache ActiveMQ Artemis',
  trademarkNotice:
    'Apache ActiveMQ and Apache ActiveMQ Artemis are trademarks of the Apache ' +
    'Software Foundation. Artemis Studio is an independent project and is not ' +
    'produced by, endorsed by, or affiliated with the Apache Software Foundation.',
  projectUrl: 'https://github.com/sudoitir/artemis-studio',
} as const;
