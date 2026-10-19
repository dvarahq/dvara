#!/usr/bin/env python3
"""Builds the compact vocabulary files the token counter ships, from each vendor's published tokenizer.

Run from the repository root with the two source files downloaded:

    python3 scripts/vocab/build_vocabularies.py \
        --tekken tekken.json --deepseek tokenizer.json \
        --out dvara-gateway-policy/src/main/resources/com/dvarahq/policy/guardrail/vocab

Sources (record the SHA-256 the script prints in vocab/SOURCES.md when you rebuild):

  tekken.json     https://huggingface.co/mistralai/Mistral-Small-3.2-24B-Instruct-2506/resolve/main/tekken.json
                  Apache-2.0. The same byte vocabulary as tekken_240911.json in mistral-common (Apache-2.0).
  tokenizer.json  https://huggingface.co/deepseek-ai/DeepSeek-V3-0324/resolve/main/tokenizer.json
                  MIT ("This repository and the model weights are licensed under the MIT License").

Both outputs are gzip streams written with Java's DataOutputStream conventions (big-endian), so the Java reader is
DataInputStream over a GZIPInputStream. Standard library only.

tekken.bin.gz     "DVTK1", the split pattern (writeUTF), the number of ordinary tokens N (int), then N tokens in
                  rank order, each a length (unsigned short) and its bytes. Only the ordinary tokens: Tekken's first
                  default_num_special_tokens ids are control tokens, and only the first default_vocab_size ids are
                  used, so N = default_vocab_size - default_num_special_tokens.
deepseek-v3.bin.gz "DVDS1", the number of split patterns (int) and each (writeUTF), in order; the vocabulary size V
                  (int) and V tokens in id order as raw bytes (length + bytes), byte-level characters decoded; the
                  number of merges M (int) and M merges in priority order, each (left id, right id, merged id).
"""
import argparse
import base64
import gzip
import hashlib
import json
import os
import struct


def bytes_to_unicode():
    """GPT-2's byte-level alphabet: each byte as one printable character."""
    bs = list(range(ord("!"), ord("~") + 1)) + list(range(ord("¡"), ord("¬") + 1)) + list(range(ord("®"), ord("ÿ") + 1))
    cs = bs[:]
    n = 0
    for b in range(256):
        if b not in bs:
            bs.append(b)
            cs.append(256 + n)
            n += 1
    return {chr(c): b for b, c in zip(bs, cs)}


def write_utf(out, text):
    data = text.encode("utf-8")  # the patterns are ASCII or BMP; Java's modified UTF-8 reads them the same
    out.write(struct.pack(">H", len(data)))
    out.write(data)


def write_token(out, token):
    out.write(struct.pack(">H", len(token)))
    out.write(token)


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def build_tekken(src, dst):
    t = json.load(open(src, encoding="utf-8"))
    cfg = t["config"]
    n = cfg["default_vocab_size"] - cfg["default_num_special_tokens"]
    tokens = [base64.b64decode(v["token_bytes"]) for v in t["vocab"][:n]]
    with gzip.GzipFile(dst, "wb", compresslevel=9, mtime=0) as out:
        out.write(b"DVTK1")
        write_utf(out, cfg["pattern"])
        out.write(struct.pack(">i", n))
        for token in tokens:
            write_token(out, token)
    return n


def build_deepseek(src, dst):
    t = json.load(open(src, encoding="utf-8"))
    pre = t["pre_tokenizer"]
    assert pre["type"] == "Sequence", pre["type"]
    patterns = []
    for p in pre["pretokenizers"]:
        if p["type"] == "Split":
            assert p["behavior"] == "Isolated" and not p["invert"], p
            patterns.append(p["pattern"]["Regex"])
        else:
            assert p["type"] == "ByteLevel" and not p["add_prefix_space"] and not p["use_regex"], p
    model = t["model"]
    assert model["type"] == "BPE" and not model.get("byte_fallback") and model.get("dropout") is None
    decode = bytes_to_unicode()
    vocab = model["vocab"]
    size = max(vocab.values()) + 1
    tokens = [None] * size
    special = 0
    for text, i in vocab.items():
        if all(c in decode for c in text):
            tokens[i] = bytes(decode[c] for c in text)
        else:
            # A special token (<｜begin▁of▁sentence｜> and the like) is stored as its own text; no merge of input
            # bytes produces it, so it only keeps the ids dense.
            tokens[i] = text.encode("utf-8")
            special += 1
    print(f"deepseek-v3: {special} special tokens kept as text")
    assert all(tok is not None for tok in tokens), "vocabulary ids are not dense"
    merges = []
    for m in model["merges"]:
        left, right = m.split(" ", 1) if isinstance(m, str) else m
        merges.append((vocab[left], vocab[right], vocab[left + right]))
    with gzip.GzipFile(dst, "wb", compresslevel=9, mtime=0) as out:
        out.write(b"DVDS1")
        out.write(struct.pack(">i", len(patterns)))
        for p in patterns:
            write_utf(out, p)
        out.write(struct.pack(">i", size))
        for token in tokens:
            write_token(out, token)
        out.write(struct.pack(">i", len(merges)))
        for a, b, r in merges:
            out.write(struct.pack(">iii", a, b, r))
    return size, len(merges)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tekken", required=True)
    ap.add_argument("--deepseek", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    n = build_tekken(args.tekken, os.path.join(args.out, "tekken.bin.gz"))
    v, m = build_deepseek(args.deepseek, os.path.join(args.out, "deepseek-v3.bin.gz"))
    print(f"tekken: {n} tokens, source sha256 {sha256(args.tekken)}")
    print(f"deepseek-v3: {v} tokens, {m} merges, source sha256 {sha256(args.deepseek)}")
    for name in ("tekken.bin.gz", "deepseek-v3.bin.gz"):
        print(f"{name}: {os.path.getsize(os.path.join(args.out, name))} bytes")


if __name__ == "__main__":
    main()
