# Affective engine provenance

This Kotlin implementation adapts algorithms from the MIT-licensed projects:

- [affective-longing](https://github.com/pearthink123/affective-longing/tree/1b47e2377b96bb88b11b68ea0fd68a1b33dd38ef): VAD emotion, six relationship stages, OU mean reversion, and memory-triggered longing boost.
- [revive-companion](https://github.com/pearthink123/revive-companion/tree/7d11386b778c89de52479a91f838a99c3372fd07): Poisson longing probability, information-gain sources, Bayesian send utility, and confirmation after delivery.

The original projects' MIT terms and attribution remain applicable to derived algorithm code. Android persistence, per-assistant friend/lover labels, the optional Jev classifier, and the existing OrangeChat delivery boundary are local integrations.

Intentional differences: valence remains in [-1, 1]; time evolution uses exact OU transitions for long offline intervals; memory similarity only affects probability when an actual similarity score is available; longing resets when the user sends a message, not when the assistant's contact is delivered; there is no fixed night-time send block.
