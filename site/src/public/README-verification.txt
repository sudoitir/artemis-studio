# Google Search Console verification drops in here

This directory is copied verbatim into the built site, so a file called
`google<token>.html` placed beside this one is served at

    https://sudoitir.github.io/artemis-studio/google<token>.html

which is what Search Console's **HTML file** method fetches for a URL-prefix
property on `https://sudoitir.github.io/artemis-studio/`.

The alternative — Search Console's **HTML tag** method — has a commented slot in
`site/src/.vitepress/config.ts`, under `head`. Either one is enough; there is no
reason to use both.

`github.io` is on the public suffix list, so a *domain* property is not
available for this site. If Studio ever moves to its own domain, add a `CNAME`
file here, change `BASE`/`ORIGIN` in the config, and DNS TXT verification
becomes an option.

This file is harmless in the published site and exists to keep the slot obvious.
