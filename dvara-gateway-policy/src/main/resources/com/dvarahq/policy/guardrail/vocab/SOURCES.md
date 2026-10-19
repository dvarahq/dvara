# Token vocabularies

Built by `scripts/vocab/build_vocabularies.py` from each vendor's published tokenizer. Only the data the counter
needs is kept: the ordinary tokens' bytes, and for DeepSeek the merge order. Nothing here is code.

| file | built from | licence | source SHA-256 | file SHA-256 |
|---|---|---|---|---|
| `tekken.bin.gz` | Mistral AI's Tekken vocabulary, `tekken.json` from https://huggingface.co/mistralai/Mistral-Small-3.2-24B-Instruct-2506 (the same token bytes as `tekken_240911.json` in mistral-common) | Apache-2.0, Mistral AI | `6e2501687ccd0e1f30f36319eaf2b46958b897811e246cd8eb5d385b9e3de7d1` | `1d20e5cc82ae185a9b5a28acc8ec5b1a62af3b19718e4d5debd52654cdb30d40` |
| `deepseek-v3.bin.gz` | DeepSeek's V3 tokenizer, `tokenizer.json` from https://huggingface.co/deepseek-ai/DeepSeek-V3-0324 | MIT, Copyright (c) 2023 DeepSeek; the full text is in `LICENSE-deepseek-v3.txt` | `621ac2e32d0dba658404412318818aaa8ce8cda492e59830109d8da6b517fb41` | `c1c633139d6becc03908e32c9e0c2a277262db27a45c5ff46865743bce1796fb` |

To check them, download the two sources, run the build script, and compare the file SHA-256 values: the gzip
output is written with a fixed timestamp, so the same input gives the same bytes.
