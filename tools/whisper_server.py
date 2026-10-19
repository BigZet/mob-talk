"""Mob Talk's loopback-only, bounded local speech worker (Python 3.12+).

Importing this file does not import ML packages or download models. The main
entry point loads faster-whisper in a background thread after binding HTTP.
Only status codes are logged: audio and recognized text stay out of logs.
"""
from __future__ import annotations

import argparse
import base64
from array import array
from dataclasses import dataclass, field, replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import logging
import math
import os
from pathlib import Path
import re
import socket
import sys
import threading
import time
import wave


MAX_BODY_BYTES = 2 * 1024 * 1024
MAX_AUDIO_SECONDS = 15.0
_DLL_HANDLES = []
MODEL_REPOS = {
    "small": "Systran/faster-whisper-small", "medium": "Systran/faster-whisper-medium",
    "large-v2": "Systran/faster-whisper-large-v2", "large-v3": "Systran/faster-whisper-large-v3",
    "large-v3-turbo": "mobiuslabsgmbh/faster-whisper-large-v3-turbo",
}


LOCAL_MODEL_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")


def local_model_path(model, model_dir: Path):
    """A fine-tuned CTranslate2 folder with model.bin: models/<name> or an absolute path."""
    if not isinstance(model, str) or model in MODEL_REPOS or len(model) > 260:
        return None
    if LOCAL_MODEL_NAME.match(model) and not model.startswith("models--"):
        candidate = Path(model_dir) / model
    elif Path(model).is_absolute():
        candidate = Path(model)
    else:
        return None
    return candidate if (candidate / "model.bin").is_file() else None


def local_models(model_dir: Path) -> list:
    try:
        return sorted(path.name for path in Path(model_dir).iterdir()
                      if local_model_path(path.name, model_dir) is not None)
    except OSError:
        return []


def default_model_directory() -> Path:
    base = Path(os.environ.get("LOCALAPPDATA", Path.home() / ".cache"))
    return base / "MobTalk" / "models"


@dataclass(frozen=True)
class Settings:
    model: str = "large-v3"
    device: str = "auto"
    language: str = "ru"
    model_dir: Path = field(default_factory=default_model_directory)
    cpu_threads: int = 4
    beam_size: int = 5
    vad_silence_ms: int = 500
    hotwords: str = "Minecraft, Майнкрафт, крипер, зомби, скелет, пиглин, эндермен, варден"


class LoadCancelled(Exception):
    pass


class InvalidAudio(ValueError):
    pass


@dataclass(frozen=True)
class AudioInfo:
    duration: float
    silent: bool


def validate_wav(body: bytes) -> AudioInfo:
    if len(body) > MAX_BODY_BYTES:
        raise InvalidAudio("body_too_large")
    try:
        with wave.open(io.BytesIO(body), "rb") as source:
            channels, width, rate, frames, compression, _ = source.getparams()
            if compression != "NONE" or width != 2 or channels not in (1, 2):
                raise InvalidAudio("pcm16_required")
            if not 8000 <= rate <= 48000 or not 0 < frames <= rate * MAX_AUDIO_SECONDS:
                raise InvalidAudio("invalid_audio_duration_or_rate")
            pcm = source.readframes(frames)
            if len(pcm) != frames * channels * width:
                raise InvalidAudio("truncated_audio")
    except (wave.Error, EOFError, OSError) as error:
        raise InvalidAudio("invalid_wav") from error
    samples = array("h", pcm)
    if sys.byteorder != "little":
        samples.byteswap()
    peak = max(abs(sample) for sample in samples)
    rms = math.sqrt(sum(sample * sample for sample in samples) / len(samples))
    duration = frames / rate
    # A simple prefilter removes digital silence/very low input before Whisper;
    # Silero VAD and decoder confidence provide the second speech check.
    return AudioInfo(duration, duration < 0.15 or (rms < 80 and peak < 300))


def speech_text(segments) -> str:
    parts = []
    for segment in segments:
        text = segment.text.strip()
        if not text or segment.no_speech_prob >= 0.6 or segment.avg_logprob < -1.0:
            continue
        parts.append(text)
    return " ".join(parts)[:2000].strip()


def _enable_windows_nvidia_libraries() -> None:
    if os.name != "nt":
        return
    # Official NVIDIA Windows wheels put their DLLs below this venv's nvidia
    # package. Keep handles alive, and use PATH for CTranslate2's LoadLibrary.
    package_root = Path(sys.prefix) / "Lib" / "site-packages" / "nvidia"
    if not package_root.is_dir():
        return
    directories = sorted({path.parent for path in package_root.rglob("*.dll")})
    for directory in directories:
        _DLL_HANDLES.append(os.add_dll_directory(str(directory)))
    if directories:
        os.environ["PATH"] = os.pathsep.join(map(str, directories)) + os.pathsep + os.environ.get("PATH", "")


def load_model(settings: Settings, progress=None, cancelled=None):
    _enable_windows_nvidia_libraries()
    # Heavy imports are deliberately here, never on module import or GET health.
    import ctranslate2
    from faster_whisper import WhisperModel

    model_path = settings.model
    if settings.model not in MODEL_REPOS:
        local = local_model_path(settings.model, settings.model_dir)
        if local is None:
            # Never pass unknown names on: faster-whisper would download a Hugging Face repo.
            raise RuntimeError("model_unavailable")
        model_path = str(local)
    else:
        from huggingface_hub import snapshot_download
        from tqdm.auto import tqdm

        class DownloadProgress(tqdm):
            def update(self, n=1):
                if cancelled is not None and cancelled.is_set():
                    raise LoadCancelled()
                result = super().update(n)
                if progress:
                    progress("downloading", int(self.n), int(self.total or 0))
                return result

        if progress:
            progress("downloading", 0, 0)
        model_path = snapshot_download(MODEL_REPOS[settings.model], cache_dir=str(settings.model_dir),
            allow_patterns=["config.json", "preprocessor_config.json", "model.bin", "tokenizer.json", "vocabulary.*"],
            tqdm_class=DownloadProgress, max_workers=2)
    if cancelled is not None and cancelled.is_set():
        raise LoadCancelled()

    settings.model_dir.mkdir(parents=True, exist_ok=True)
    options = dict(download_root=str(settings.model_dir),
                   cpu_threads=settings.cpu_threads, num_workers=1)
    devices = ["cpu"] if settings.device == "cpu" else ["cuda", "cpu"]
    if settings.device == "cuda":
        devices = ["cuda"]
    for device in devices:
        try:
            if device == "cuda" and ctranslate2.get_cuda_device_count() == 0:
                raise RuntimeError("cuda_unavailable")
            if progress:
                progress("loading_" + device, 0, 0)
            model = WhisperModel(model_path, device=device,
                                 compute_type="int8_float16" if device == "cuda" else "int8",
                                 **options)
            if device == "cuda":
                # CUDA construction alone does not check cuDNN. Run one quiet
                # inference so /health cannot call a broken GPU runtime ready.
                import numpy
                segments, _ = model.transcribe(numpy.zeros(16000, dtype=numpy.float32),
                    language=settings.language, beam_size=1, vad_filter=False,
                    condition_on_previous_text=False, temperature=0.0)
                list(segments)
            return model, device
        except Exception as failure:
            description = str(failure).casefold()
            if "out of memory" in description or "alloc_failed" in description:
                raise RuntimeError("gpu_out_of_memory") from failure
            if device == "cpu" or settings.device == "cuda":
                raise
            print("MobTalk Whisper: CUDA unavailable; using CPU int8.", file=sys.stderr, flush=True)
    raise RuntimeError("model_unavailable")


class WhisperRuntime:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.model = None
        self.device = "pending"
        self.status = "loading"
        self.transcription_lock = threading.Lock()
        self._load_lock = threading.Lock()
        self._loader = None
        self.cancel_load = threading.Event()
        self.progress = {"stage": "starting", "completed": 0, "total": 0, "unit": "files"}
        self.error_code = ""
        self.started_at = time.monotonic()

    def start_loading(self):
        with self._load_lock:
            if self._loader is None:
                self._loader = threading.Thread(target=self._load, name="MobTalk-Whisper-Load", daemon=True)
                self._loader.start()

    def _load(self):
        try:
            self.model, self.device = load_model(self.settings, self._progress, self.cancel_load)
            if self.cancel_load.is_set():
                self.model = None
                self.status = "cancelled"
                return
            self.status = "ready"
            print(f"MobTalk Whisper: ready ({self.device}, {self.settings.model}).", flush=True)
        except LoadCancelled:
            self.model = None
            self.status = "cancelled"
        except Exception as error:
            self.status = "error"
            self.error_code = "gpu_out_of_memory" if "gpu_out_of_memory" in str(error) else "model_unavailable"
            # Exceptions can include input/model paths. Report only their type.
            print(f"MobTalk Whisper: model unavailable ({type(error).__name__}); run setup-whisper.ps1.",
                  file=sys.stderr, flush=True)

    def _progress(self, stage, completed, total):
        self.progress = {"stage": stage, "completed": completed, "total": total, "unit": "files"}

    def configure(self, values):
        model = values.get("model", self.settings.model)
        if model == "turbo":
            model = "large-v3-turbo"
        device = values.get("device", self.settings.device)
        language = values.get("language", self.settings.language)
        known = model in MODEL_REPOS or local_model_path(model, self.settings.model_dir) is not None
        if not known or device not in ("auto", "cpu", "cuda"):
            raise ValueError("invalid_model_or_device")
        if not isinstance(language, str) or not language or len(language) > 24:
            raise ValueError("invalid_language")
        updated = replace(self.settings, model=model, device=device, language=language,
            beam_size=max(1, min(10, int(values.get("beamSize", self.settings.beam_size)))),
            vad_silence_ms=max(150, min(1500, int(values.get("vadSilenceMs", self.settings.vad_silence_ms)))),
            cpu_threads=max(1, min(16, int(values.get("cpuThreads", self.settings.cpu_threads)))),
            hotwords=str(values.get("hotwords", self.settings.hotwords))[:2000])
        with self._load_lock:
            if updated == self.settings and self.status in ("ready", "loading"):
                return self.health()
            if self._loader is not None and self._loader.is_alive():
                raise RuntimeError("busy")
            if not self.transcription_lock.acquire(blocking=False):
                raise RuntimeError("busy")
            try:
                self.model = None
                import gc
                gc.collect()
                self.settings = updated
                self.device = "pending"
                self.status = "loading"
                self.error_code = ""
                self.cancel_load = threading.Event()
                self.started_at = time.monotonic()
                self._loader = threading.Thread(target=self._load, name="MobTalk-Whisper-Load", daemon=True)
                self._loader.start()
            finally:
                self.transcription_lock.release()
        return self.health()

    def health(self):
        ready = self.model is not None
        status = "busy" if ready and self.transcription_lock.locked() else self.status
        return {"protocol": 2, "ready": ready, "status": status, "model": self.settings.model, "device": self.device,
            "configuredDevice": self.settings.device, "language": self.settings.language,
            "beamSize": self.settings.beam_size, "vadSilenceMs": self.settings.vad_silence_ms,
            "cpuThreads": self.settings.cpu_threads, "hotwords": self.settings.hotwords,
            "progress": self.progress, "error": self.error_code,
            "loadingSeconds": round(time.monotonic() - self.started_at, 1)}

    def transcribe(self, body: bytes, context: str = "") -> str:
        segments, info = self.model.transcribe(io.BytesIO(body), language=self.settings.language,
            beam_size=self.settings.beam_size, temperature=0.0, vad_filter=True,
            vad_parameters={"min_silence_duration_ms": self.settings.vad_silence_ms, "speech_pad_ms": 250},
            condition_on_previous_text=False, hotwords=(self.settings.hotwords + ", " + context).strip(", "))
        if info.duration_after_vad <= 0:
            return ""
        return speech_text(segments)


def create_server(runtime: WhisperRuntime, port: int = 63919) -> ThreadingHTTPServer:
    class Handler(BaseHTTPRequestHandler):
        server_version = "MobTalkWhisper/1"

        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def log_message(self, format, *args):
            # BaseHTTPServer logs paths and arbitrary client values by default.
            pass

        def json_response(self, status, result):
            payload = json.dumps(result, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            try:
                self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_GET(self):
            if self.path == "/health":
                self.json_response(200, runtime.health())
            elif self.path == "/models":
                models = [{"id": model, "installed": any((runtime.settings.model_dir / ("models--" + repo.replace("/", "--"))).glob("snapshots/*/model.bin"))}
                          for model, repo in MODEL_REPOS.items()]
                models += [{"id": name, "installed": True, "local": True}
                           for name in local_models(runtime.settings.model_dir)]
                self.json_response(200, {"models": models, "active": runtime.settings.model})
            else:
                self.json_response(404, {"error": "not_found"})

        def do_POST(self):
            if self.path == "/cancel-load":
                runtime.cancel_load.set()
                self.json_response(200, {"status": "cancelling"})
                return
            if self.path == "/configure":
                try:
                    size = int(self.headers.get("Content-Length", "0"))
                    if not 0 < size <= 8192:
                        raise ValueError("invalid_body")
                    values = json.loads(self.rfile.read(size))
                    if not isinstance(values, dict):
                        raise ValueError("invalid_body")
                    self.json_response(202, runtime.configure(values))
                except (ValueError, TypeError):
                    self.json_response(400, {"error": "invalid_settings"})
                except RuntimeError:
                    self.json_response(409, {"error": "busy"})
                return
            if self.path != "/transcribe":
                self.json_response(404, {"error": "not_found"})
                return
            lengths = self.headers.get_all("Content-Length", [])
            if self.headers.get("Transfer-Encoding") or len(lengths) != 1:
                self.json_response(400, {"error": "content_length_required"})
                return
            try:
                size = int(lengths[0])
            except ValueError:
                size = -1
            if size <= 0:
                self.json_response(400, {"error": "invalid_content_length"})
                return
            if size > MAX_BODY_BYTES:
                self.json_response(413, {"error": "audio_too_large"})
                return
            if not runtime.transcription_lock.acquire(blocking=False):
                self.json_response(409, {"error": "busy"})
                return
            try:
                body = self.rfile.read(size)
                if len(body) != size:
                    self.json_response(400, {"error": "truncated_body"})
                    return
                try:
                    audio = validate_wav(body)
                except InvalidAudio:
                    self.json_response(400, {"error": "invalid_pcm16_wav"})
                    return
                if audio.silent:
                    self.json_response(200, {"text": "", "model": runtime.settings.model, "device": runtime.device,
                        "durationSeconds": audio.duration, "elapsedSeconds": 0.0})
                elif runtime.model is None:
                    self.json_response(503, {"error": "model_" + runtime.status})
                else:
                    context = ""
                    encoded = self.headers.get("X-MobTalk-Context", "")
                    if len(encoded) <= 2048:
                        try:
                            context = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)).decode("utf-8")[:500]
                        except (ValueError, UnicodeError):
                            pass
                    started = time.monotonic()
                    text = runtime.transcribe(body, context)
                    self.json_response(200, {"text": text, "model": runtime.settings.model, "device": runtime.device,
                        "elapsedSeconds": round(time.monotonic() - started, 3), "durationSeconds": audio.duration})
            except socket.timeout:
                self.json_response(408, {"error": "body_timeout"})
            except Exception:
                self.json_response(500, {"error": "transcription_failed"})
            finally:
                runtime.transcription_lock.release()

    return ThreadingHTTPServer(("127.0.0.1", port), Handler)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=63919)
    parser.add_argument("--model", default="large-v3")
    parser.add_argument("--device", choices=("auto", "cpu", "cuda"), default="auto")
    parser.add_argument("--language", default="ru")
    parser.add_argument("--model-dir", type=Path, default=default_model_directory())
    parser.add_argument("--cpu-threads", type=int, default=4)
    parser.add_argument("--beam-size", type=int, default=5)
    parser.add_argument("--vad-silence-ms", type=int, default=500)
    parser.add_argument("--hotwords", default=Settings().hotwords)
    parser.add_argument("--preload", action="store_true", help="Load/cache model, report actual device, and exit")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not 1 <= args.cpu_threads <= 16:
        parser.error("port must be 1..65535 and cpu-threads must be 1..16")
    # Third-party messages must not log recognized speech or request data.
    logging.getLogger("faster_whisper").setLevel(logging.ERROR)
    logging.getLogger("huggingface_hub").setLevel(logging.ERROR)
    settings = Settings(args.model, args.device, args.language, args.model_dir, args.cpu_threads,
        max(1, min(10, args.beam_size)), max(150, min(1500, args.vad_silence_ms)), args.hotwords)
    runtime = WhisperRuntime(settings)
    if args.preload:
        runtime._load()
        print(json.dumps(runtime.health()), flush=True)
        return 0 if runtime.model is not None else 1
    try:
        server = create_server(runtime, args.port)
    except OSError:
        print("MobTalk Whisper: loopback port unavailable.", file=sys.stderr, flush=True)
        return 1
    runtime.start_loading()
    print(f"MobTalk Whisper: http://127.0.0.1:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
