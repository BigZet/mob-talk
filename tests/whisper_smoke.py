"""Opt-in regression for the installed decoder/model using synthetic WAV only.

Run with %LOCALAPPDATA%/MobTalk/venv/Scripts/python.exe; this is deliberately
excluded from default test discovery because it uses the installed ML stack.
"""
import argparse
import importlib.util
import io
import json
from pathlib import Path
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("wav", type=Path, help="Known synthetic speech WAV fixture")
    parser.add_argument("--device", choices=("auto", "cpu", "cuda"), default="auto")
    args = parser.parse_args()
    body = args.wav.read_bytes()
    # This is the production faster-whisper decoder boundary that broke when
    # PyAV 19 removed metadata_errors. No compatibility monkeypatch is used.
    from faster_whisper.audio import decode_audio
    decoded = decode_audio(io.BytesIO(body), sampling_rate=16000)
    assert decoded.size > 0 and str(decoded.dtype) == "float32", "WAV decoder produced no PCM"
    helper = Path(__file__).resolve().parents[1] / "tools" / "whisper_server.py"
    spec = importlib.util.spec_from_file_location("mobtalk_whisper_smoke", helper)
    worker = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = worker
    spec.loader.exec_module(worker)
    worker.validate_wav(body)
    runtime = worker.WhisperRuntime(worker.Settings(device=args.device))
    runtime._load()
    assert runtime.health()["ready"], "Whisper did not load"
    text = runtime.transcribe(body)
    assert text.strip(), "Known synthetic speech returned empty"
    # Keep recognized speech out of shell logs as in the HTTP worker.
    print(json.dumps({"decodedFrames": int(decoded.size), "speechCharacters": len(text),
                      "device": runtime.device, "model": runtime.settings.model}))


if __name__ == "__main__":
    main()
