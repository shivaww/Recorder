# GENERATION_PROMPT — manifest (the prompt is now modular)

The single mega-prompt was split so each video type gets a SMALLER but DEEPER
prompt. Paste in this order into your AI chat:

  1. GENERATION_PROMPT_CORE.md      (always) - technical contract + craft
  2. GENERATION_PROMPT_ADAPTIVE.md  (always) - creative director: CONTENT DNA,
                                             visual decision engine, imagery
                                             ladder, anti-template rules
  3. TYPE_CARDS.md -> ONE card      (optional) - extra bias for a known type

Then attach the SRT (renamed .txt), state the block start time, and set
VIDEO_TYPE to one of:
  documentary | storytelling | podcast | explainer | study | news | listicle |
  adaptive

Use `adaptive` for any unknown/hybrid video - it infers the best blend from
the transcript instead of forcing a genre.

Why modular:
- CORE stays stable (app contract, scrub-safe JS, sound, imagery, chrome).
- ADAPTIVE makes the LLM DERIVE original visuals per beat (no template lock).
- TYPE CARDS add bias without hardcoding, so the LLM isn't trapped in examples.

Companion docs: PROMPT_SPEC.md (what the app does with the HTML),
ACCEPTANCE.md (on-device checklist).
