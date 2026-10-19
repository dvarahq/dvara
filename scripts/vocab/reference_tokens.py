#!/usr/bin/env python3
"""Writes the token ids each vendor's own tokenizer gives for a fixed set of texts, for VocabularyExactnessTest.

    python3 -m venv /tmp/vocab-venv && /tmp/vocab-venv/bin/pip install tokenizers mistral-common
    /tmp/vocab-venv/bin/python scripts/vocab/reference_tokens.py --tekken tekken.json --deepseek tokenizer.json \
        --out dvara-gateway-policy/src/test/resources/com/dvarahq/policy/guardrail/vocab-reference.json

Mistral: mistral-common's Tekkenizer, encode(text, bos=False, eos=False). Its ids are offset by the number of
special tokens; the file keeps them as the tokenizer gives them, and the test subtracts the offset.
DeepSeek: Hugging Face tokenizers, Tokenizer.from_file(tokenizer.json).encode(text, add_special_tokens=False).
"""
import argparse
import json

TEXTS = [
    "The quick brown fox jumps over the lazy dog.",
    "Hello, world! It's 2026-10-18 and the gateway counted 1234567 tokens at 3.14159 cents each.",
    "public final class TokenCounter {\n    private static final int MAX = 1_000;\n\n    int count(String s) { return s.length(); }\n}\n",
    "def fib(n):\n\tif n < 2:\n\t\treturn n\n\treturn fib(n - 1) + fib(n - 2)\n",
    '{"model": "deepseek-chat", "messages": [{"role": "user", "content": "Summarise this."}], "max_tokens": 512}',
    "数据驻留规则要求请求留在欧盟区域内。",
    "ゲートウェイはトークンを数えます。カタカナとひらがなと漢字。",
    "게이트웨이는 각 요청의 토큰을 셉니다.",
    "गेटवे हर अनुरोध के टोकन गिनता है।",
    "تحسب البوابة الرموز لكل طلب.",
    "Шлюз считает токены для каждого запроса.",
    "Emoji: 👍🏽 🚀✨ 🇮🇳 👨‍👩‍👧‍👦 and a café with naïve résumé (é combining).",
    "tabs\tand  double  spaces   and nbsp em-space\u0085next-line line-sep",
    "\n\n\n   leading and trailing whitespace   \n\n",
    "!!! ??? ... --- *** ### @@@ $$$ %%% ^^^ &&& ((( ))) [[[ ]]] {{{ }}} <<< >>>",
    "HTTPServerError XMLHttpRequest getURLForID camelCase snake_case kebab-case SCREAMING_CASE",
    "https://example.com/path/to/resource?query=value&other=123#fragment",
    "| col1 | col2 |\n|------|------|\n| a    | b    |\n\n- item one\n- item two\n\n```java\nint x = 1;\n```",
    "0 01 012 0123 01234 012345 0123456 3.0e-10 -42 +7 1,000,000 ١٢٣ ४५६",
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "I'm you're they'll we've she'd don't CAN'T Won't",
    "Mixed 中文 and English, 日本語 too, with numbers 12345 and symbols ©®™ ±×÷ ≤≥≠ ∑∫√ €£¥.",
    # Each of these gives different ids if a split pattern is dropped or \s matches ASCII whitespace only, so they
    # pin the pre-tokenisation, not only the merges.
    "12345678901234",
    "\u00a0\u00a0hello world",
    "  \u2003  x",
    "foo  \u00a0 bar",
    "tabs\t\t\u00a0x",
    "a  \u3000b",
    "",
    " ",
    "a",
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tekken", required=True)
    ap.add_argument("--deepseek", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    from mistral_common.tokens.tokenizers.tekken import Tekkenizer
    from tokenizers import Tokenizer

    tekken = Tekkenizer.from_file(args.tekken)
    deepseek = Tokenizer.from_file(args.deepseek)
    data = {
        "tekkenSpecialOffset": tekken.num_special_tokens,
        "cases": [
            {
                "text": text,
                "tekken": tekken.encode(text, bos=False, eos=False),
                "deepseek": deepseek.encode(text, add_special_tokens=False).ids,
            }
            for text in TEXTS
        ],
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"{len(TEXTS)} cases written to {args.out}")


if __name__ == "__main__":
    main()
