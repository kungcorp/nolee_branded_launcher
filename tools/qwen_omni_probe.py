#!/usr/bin/env python3
"""Measure one-shot Qwen-Omni WAV-to-text-and-speech latency.

The probe deliberately talks to Model Studio directly. It is for evaluating the
provider before the same request is placed behind Nolee's device gateway.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path
import sys
import time
import wave

import requests


DEFAULT_BASE_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Send a WAV question to Qwen-Omni and time its streamed response."
    )
    parser.add_argument("wav", type=Path, help="WAV file containing the spoken question")
    parser.add_argument(
        "--model", default="qwen3.5-omni-flash", help="Model Studio model ID"
    )
    parser.add_argument(
        "--voice", default="Tina", help="Qwen-Omni output voice"
    )
    parser.add_argument(
        "--speech-instruction",
        default="",
        help="Natural-language direction for speaking rate, volume, tone, or emotion",
    )
    parser.add_argument(
        "--base-url",
        default=os.environ.get("DASHSCOPE_BASE_URL", DEFAULT_BASE_URL),
        help="Model Studio OpenAI-compatible base URL",
    )
    parser.add_argument(
        "--output",
        type=Path,
        help="Output WAV path (default: <input>.qwen-response.wav)",
    )
    parser.add_argument(
        "--timeout", type=float, default=90.0, help="HTTP timeout in seconds"
    )
    return parser.parse_args()


def write_pcm_wav(path: Path, pcm: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(24_000)
        output.writeframes(pcm)


def main() -> int:
    args = arguments()
    api_key = os.environ.get("DASHSCOPE_API_KEY", "").strip()
    if not api_key:
        print("DASHSCOPE_API_KEY is not set", file=sys.stderr)
        return 2
    if not args.wav.is_file():
        print(f"Input WAV does not exist: {args.wav}", file=sys.stderr)
        return 2

    audio = base64.b64encode(args.wav.read_bytes()).decode("ascii")
    system_instruction = (
        "You are Nolee Ask AI. Answer the user's spoken question directly and briefly, "
        "in the language they used."
    )
    if args.speech_instruction.strip():
        system_instruction += " " + args.speech_instruction.strip()

    payload = {
        "model": args.model,
        "messages": [
            {
                "role": "system",
                "content": system_instruction,
            },
            {
                "role": "user",
                "content": [
                    {
                        "type": "input_audio",
                        "input_audio": {
                            "data": f"data:;base64,{audio}",
                            "format": "wav",
                        },
                    }
                ],
            },
        ],
        "stream": True,
        "stream_options": {"include_usage": True},
        "modalities": ["text", "audio"],
        "audio": {"voice": args.voice, "format": "wav"},
    }

    endpoint = args.base_url.rstrip("/") + "/chat/completions"
    started = time.perf_counter()
    first_headers: float | None = None
    first_text: float | None = None
    first_audio: float | None = None
    last_text: float | None = None
    last_audio: float | None = None
    text_chunks = 0
    audio_chunks = 0
    mixed_chunks = 0
    text_parts: list[str] = []
    audio_parts: list[str] = []
    usage: dict[str, object] | None = None

    try:
        with requests.post(
            endpoint,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            },
            json=payload,
            stream=True,
            timeout=args.timeout,
        ) as response:
            first_headers = time.perf_counter() - started
            if not response.ok:
                print(
                    f"Model Studio returned HTTP {response.status_code}: "
                    f"{response.text[:1000]}",
                    file=sys.stderr,
                )
                return 1

            for raw_line in response.iter_lines(decode_unicode=True):
                if not raw_line or not raw_line.startswith("data:"):
                    continue
                data = raw_line[5:].strip()
                if data == "[DONE]":
                    break
                chunk = json.loads(data)
                if chunk.get("usage"):
                    usage = chunk["usage"]
                for choice in chunk.get("choices") or []:
                    delta = choice.get("delta") or {}
                    content = delta.get("content")
                    audio_delta = delta.get("audio") or {}
                    audio_data = audio_delta.get("data")
                    now = time.perf_counter() - started
                    if content:
                        if first_text is None:
                            first_text = now
                        last_text = now
                        text_chunks += 1
                        text_parts.append(content)
                    if audio_data:
                        if first_audio is None:
                            first_audio = now
                        last_audio = now
                        audio_chunks += 1
                        audio_parts.append(audio_data)
                    if content and audio_data:
                        mixed_chunks += 1
    except (requests.RequestException, json.JSONDecodeError) as error:
        print(f"Qwen probe failed: {error}", file=sys.stderr)
        return 1

    completed = time.perf_counter() - started
    answer = "".join(text_parts).strip()
    output_path = args.output or args.wav.with_suffix(".qwen-response.wav")
    pcm = base64.b64decode("".join(audio_parts)) if audio_parts else b""
    if pcm:
        write_pcm_wav(output_path, pcm)

    result = {
        "model": args.model,
        "input_bytes": args.wav.stat().st_size,
        "response_headers_ms": round((first_headers or 0) * 1000),
        "first_text_ms": round(first_text * 1000) if first_text is not None else None,
        "first_audio_ms": round(first_audio * 1000) if first_audio is not None else None,
        "last_text_ms": round(last_text * 1000) if last_text is not None else None,
        "last_audio_ms": round(last_audio * 1000) if last_audio is not None else None,
        "text_chunks": text_chunks,
        "audio_chunks": audio_chunks,
        "mixed_text_audio_chunks": mixed_chunks,
        "audio_started_after_text_finished_ms": (
            round((first_audio - last_text) * 1000)
            if first_audio is not None and last_text is not None
            else None
        ),
        "complete_ms": round(completed * 1000),
        "answer": answer,
        "audio_bytes": len(pcm),
        "audio_path": str(output_path.resolve()) if pcm else None,
        "usage": usage,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
