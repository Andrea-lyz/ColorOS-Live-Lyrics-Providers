# OpenCC character data

The bundled `hanzi-t2s.tsv` is derived from [OpenCC TSCharacters.txt](https://github.com/BYVoid/OpenCC/blob/556ed22496d650bd0b13b6c163be9814637970ae/data/dictionary/TSCharacters.txt), tag `ver.1.1.9`, commit `556ed22496d650bd0b13b6c163be9814637970ae`.

Upstream dictionary SHA-256: `6b5a0a799bea2bb22c001f635eaa3fc2904310f0c08addbff275477a80ecf09a`.

Modification: keep only changed mappings with exactly one input code point and exactly one output code point/candidate; remove comments and multi-valued entries. This is character folding for matching, not phrase conversion, romanization or artist-name inference. Ambiguous entries remain unchanged.

The data is distributed under the Apache License 2.0; see [LICENSE](LICENSE). OpenCC is maintained by Carbo Kuo and contributors.
