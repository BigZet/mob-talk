"""Local worker boundaries. Runs with plain Python; no model or downloads."""
import http.client
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import threading
from types import SimpleNamespace
import unittest
import wave


HELPER_PATH = Path(__file__).resolve().parents[1] / "tools" / "whisper_server.py"


def load_worker():
    if not HELPER_PATH.exists():
        raise AssertionError("Whisper worker has not been implemented")
    if "mobtalk_whisper" not in sys.modules:
        spec = importlib.util.spec_from_file_location("mobtalk_whisper", HELPER_PATH)
        worker = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = worker
        spec.loader.exec_module(worker)
    return sys.modules["mobtalk_whisper"]


def make_wav(seconds=0.5, sample=1200, rate=16000, channels=1, width=2):
    output = io.BytesIO()
    with wave.open(output, "wb") as stream:
        stream.setnchannels(channels)
        stream.setsampwidth(width)
        stream.setframerate(rate)
        frame = struct.pack("<h", sample) if width == 2 else b"\x80"
        stream.writeframes(frame * int(seconds * rate) * channels)
    return output.getvalue()


class WorkerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.worker = load_worker()

    def test_plain_import_does_not_load_faster_whisper(self):
        self.assertNotIn("faster_whisper", sys.modules)

    def test_accepts_pcm16_at_duration_limit(self):
        audio = self.worker.validate_wav(make_wav(seconds=120))
        self.assertEqual(120, audio.duration)
        self.assertFalse(audio.silent)

    def test_rejects_non_wav_unsupported_pcm_and_truncation(self):
        for body in (b"not wav", make_wav(width=1), make_wav(channels=3),
                     make_wav(rate=96000), make_wav()[:-2]):
            with self.subTest(length=len(body)):
                with self.assertRaises(self.worker.InvalidAudio):
                    self.worker.validate_wav(body)

    def test_rejects_empty_and_overlong_audio(self):
        for body in (make_wav(seconds=0), make_wav(seconds=120.01)):
            with self.assertRaises(self.worker.InvalidAudio):
                self.worker.validate_wav(body)

    def test_detects_silence_without_model(self):
        self.assertTrue(self.worker.validate_wav(make_wav(sample=0)).silent)
        self.assertTrue(self.worker.validate_wav(make_wav(sample=20)).silent)

    def test_filters_empty_no_speech_and_low_confidence_segments(self):
        segments = [SimpleNamespace(text="   ", no_speech_prob=0.0, avg_logprob=-0.1),
                    SimpleNamespace(text="подписывайтесь", no_speech_prob=0.91, avg_logprob=-0.1),
                    SimpleNamespace(text="шум", no_speech_prob=0.1, avg_logprob=-1.8),
                    SimpleNamespace(text=" Привет! ", no_speech_prob=0.1, avg_logprob=-0.2)]
        self.assertEqual("Привет!", self.worker.speech_text(segments))
        self.assertEqual("", self.worker.speech_text(segments[:3]))


class HttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.worker = load_worker()

    def setUp(self):
        self.runtime = self.worker.WhisperRuntime(self.worker.Settings())
        self.server = self.worker.create_server(self.runtime, port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(self, method, route, body=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        connection.request(method, route, body=body, headers=headers or {})
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def test_health_reports_loading_before_lazy_initialization(self):
        status, result = self.request("GET", "/health")
        self.assertEqual(200, status)
        self.assertEqual(False, result["ready"])
        self.assertEqual("loading", result["status"])
        self.assertEqual("small", result["model"])

    def test_silence_returns_empty_even_while_model_loads(self):
        self.assertEqual((200, {"text": ""}), self.request("POST", "/transcribe", make_wav(sample=0)))

    def test_speech_without_loaded_model_is_unavailable(self):
        self.assertEqual(503, self.request("POST", "/transcribe", make_wav())[0])

    def test_invalid_and_oversize_body_are_rejected_before_inference(self):
        self.assertEqual(400, self.request("POST", "/transcribe", b"wrong format")[0])
        self.assertEqual(413, self.request("POST", "/transcribe", b"",
            {"Content-Length": "4194305"})[0])
        self.assertEqual(400, self.request("POST", "/transcribe", b"",
            {"Content-Length": "-1"})[0])

    def test_concurrent_transcription_is_busy(self):
        self.runtime.transcription_lock.acquire()
        try:
            self.assertEqual(409, self.request("POST", "/transcribe", make_wav())[0])
        finally:
            self.runtime.transcription_lock.release()

    def test_transcription_returns_only_speech_with_vad_enabled(self):
        class Model:
            def transcribe(_, audio, **options):
                self.assertTrue(options["vad_filter"])
                self.assertEqual(1, options["beam_size"])
                self.assertEqual("ru", options["language"])
                return iter([SimpleNamespace(text=" Как дела? ", no_speech_prob=0.01,
                                             avg_logprob=-0.1)]), SimpleNamespace(duration_after_vad=0.4)
        self.runtime.model = Model()
        self.runtime.device = "cpu"
        self.runtime.status = "ready"
        self.assertEqual((200, {"text": "Как дела?"}), self.request("POST", "/transcribe", make_wav()))

    def test_vad_no_speech_returns_empty_even_if_model_fabricates_text(self):
        class Model:
            def transcribe(_, audio, **options):
                return iter([SimpleNamespace(text="Спасибо за просмотр", no_speech_prob=0.1,
                                             avg_logprob=-0.1)]), SimpleNamespace(duration_after_vad=0)
        self.runtime.model = Model()
        self.runtime.status = "ready"
        self.assertEqual((200, {"text": ""}), self.request("POST", "/transcribe", make_wav()))

    @unittest.skipUnless(os.name == "nt" and shutil.which("pwsh"), "Windows PowerShell 7 integration")
    def test_start_script_reuses_existing_helper_without_starting_a_process(self):
        self.runtime.model = object()
        self.runtime.device = "cpu"
        self.runtime.status = "ready"
        script = HELPER_PATH.parent.parent / "scripts" / "start-whisper.ps1"
        result = subprocess.run(["pwsh", "-NoProfile", "-File", str(script),
            "-Port", str(self.server.server_port), "-WaitSeconds", "2"],
            capture_output=True, text=True, timeout=8)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(f"http://127.0.0.1:{self.server.server_port}", result.stdout)
        self.assertIn("device=cpu", result.stdout)


if __name__ == "__main__":
    unittest.main()
