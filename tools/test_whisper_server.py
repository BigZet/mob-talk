"""Offline protocol/decoder regression tests; no ML packages, weights or microphone.

Run with: py -3.12 -m unittest discover -s tools -p test_whisper_server.py
HTTP tests use an ephemeral loopback port and a fake decoder only.
"""
from array import array
import base64
import contextlib
import http.client
import io
import json
from pathlib import Path
import sys
import tempfile
import threading
from types import ModuleType, SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import wave

import whisper_server as whisper


def pcm_wav(seconds=0.5, *, rate=16000, channels=1, width=2, amplitude=1000, frames=None):
    frames = int(seconds * rate) if frames is None else frames
    data = array("h", [amplitude]) * (frames * channels) if width == 2 else bytes(frames * channels * width)
    if width == 2 and sys.byteorder != "little":
        data.byteswap()
    target = io.BytesIO()
    with wave.open(target, "wb") as output:
        output.setnchannels(channels)
        output.setsampwidth(width)
        output.setframerate(rate)
        output.writeframes(data.tobytes() if isinstance(data, array) else data)
    return target.getvalue()


def segment(text="Крипер, стой рядом.", *, no_speech_prob=0.05, avg_logprob=-0.2):
    return SimpleNamespace(text=text, no_speech_prob=no_speech_prob, avg_logprob=avg_logprob)


class FakeModel:
    def __init__(self, segments=None, duration_after_vad=0.4):
        self.segments = [segment()] if segments is None else segments
        self.duration_after_vad = duration_after_vad
        self.calls = []

    def transcribe(self, source, **options):
        self.calls.append((source.getvalue(), options))
        return iter(self.segments), SimpleNamespace(duration_after_vad=self.duration_after_vad)


class AudioValidationTests(unittest.TestCase):
    def test_pcm16_mono_and_stereo_are_accepted(self):
        for channels in (1, 2):
            with self.subTest(channels=channels):
                audio = whisper.validate_wav(pcm_wav(channels=channels))
                self.assertEqual(audio.duration, 0.5)
                self.assertFalse(audio.silent)

    def test_fifteen_seconds_is_allowed_but_one_extra_frame_is_not(self):
        self.assertEqual(whisper.validate_wav(pcm_wav(seconds=15)).duration, 15)
        with self.assertRaises(whisper.InvalidAudio):
            whisper.validate_wav(pcm_wav(frames=16000 * 15 + 1))

    def test_invalid_width_rate_empty_and_truncated_audio_are_rejected(self):
        invalid = {
            "pcm8": pcm_wav(width=1),
            "low_rate": pcm_wav(rate=4000),
            "empty": pcm_wav(frames=0),
            "truncated": pcm_wav()[:-2],
            "not_wav": b"not a wave file",
        }
        for case, body in invalid.items():
            with self.subTest(case=case), self.assertRaises(whisper.InvalidAudio):
                whisper.validate_wav(body)

    def test_digital_silence_low_input_and_very_short_audio_are_silent(self):
        for body in (pcm_wav(amplitude=0), pcm_wav(amplitude=40), pcm_wav(seconds=0.1)):
            with self.subTest(size=len(body)):
                self.assertTrue(whisper.validate_wav(body).silent)

    def test_oversized_audio_is_rejected_before_wav_parsing(self):
        with self.assertRaisesRegex(whisper.InvalidAudio, "body_too_large"):
            whisper.validate_wav(bytes(whisper.MAX_BODY_BYTES + 1))

    def test_unconfident_and_no_speech_segments_are_filtered(self):
        segments = [segment("  Эндермен  "), segment("invented", no_speech_prob=0.6),
                    segment("noise", avg_logprob=-1.1), segment("  рядом. "), segment(" ")]
        self.assertEqual(whisper.speech_text(segments), "Эндермен рядом.")
        self.assertEqual(len(whisper.speech_text([segment("а" * 3000)])), 2000)


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="mobtalk-whisper-test-")
        self.addCleanup(directory.cleanup)
        self.runtime = whisper.WhisperRuntime(whisper.Settings(model_dir=Path(directory.name)))

    def make_ready(self):
        model = FakeModel()
        self.runtime.model = model
        self.runtime.device = "cpu"
        self.runtime.status = "ready"
        return model

    def test_default_decoder_uses_russian_beam_five_vad_and_minecraft_context(self):
        model = self.make_ready()
        body = pcm_wav()
        self.assertEqual(self.runtime.transcribe(body, "Клювик, Minecraft"), "Крипер, стой рядом.")
        forwarded, options = model.calls[0]
        self.assertEqual(forwarded, body)
        self.assertEqual(options["language"], "ru")
        self.assertEqual(options["beam_size"], 5)
        self.assertEqual(options["temperature"], 0.0)
        self.assertTrue(options["vad_filter"])
        self.assertEqual(options["vad_parameters"], {"min_silence_duration_ms": 500, "speech_pad_ms": 250})
        self.assertFalse(options["condition_on_previous_text"])
        self.assertIn("Minecraft", options["hotwords"])
        self.assertIn("Клювик", options["hotwords"])

    def test_vad_empty_audio_does_not_use_decoder_hallucinations(self):
        self.runtime.model = FakeModel([segment("ложное приветствие")], duration_after_vad=0)
        self.assertEqual(self.runtime.transcribe(pcm_wav()), "")

    def test_configure_updates_model_language_device_and_decoding_settings(self):
        self.make_ready()
        started, release = threading.Event(), threading.Event()
        loaded = FakeModel()

        def load(settings, progress, cancelled):
            progress("downloading", 2, 5)
            started.set()
            if not release.wait(2):
                raise AssertionError("test loader was not released")
            return loaded, "cuda"

        with patch.object(whisper, "load_model", side_effect=load) as loader, contextlib.redirect_stdout(io.StringIO()):
            try:
                self.runtime.configure({"model": "medium", "device": "cuda", "language": "de", "beamSize": 7,
                                        "vadSilenceMs": 700, "cpuThreads": 2, "hotwords": "Minecraft, Алекс"})
                self.assertTrue(started.wait(1))
                health = self.runtime.health()
                self.assertFalse(health["ready"])
                self.assertEqual(health["status"], "loading")
                self.assertEqual(health["model"], "medium")
                self.assertEqual(health["configuredDevice"], "cuda")
                self.assertEqual(health["language"], "de")
                self.assertEqual(health["progress"]["completed"], 2)
                self.assertEqual(health["progress"]["total"], 5)
            finally:
                release.set()
                self.runtime._loader.join(2)
            self.assertFalse(self.runtime._loader.is_alive())
            loader.assert_called_once()
        health = self.runtime.health()
        self.assertTrue(health["ready"])
        self.assertEqual(health["status"], "ready")
        self.assertEqual(health["device"], "cuda")
        self.runtime.transcribe(pcm_wav(), "Бастионы")
        options = loaded.calls[0][1]
        self.assertEqual(options["language"], "de")
        self.assertEqual(options["beam_size"], 7)
        self.assertEqual(options["vad_parameters"]["min_silence_duration_ms"], 700)
        self.assertEqual(self.runtime.settings.cpu_threads, 2)
        self.assertIn("Алекс", options["hotwords"])
        self.assertIn("Бастионы", options["hotwords"])

    def test_configure_same_settings_preserves_loaded_model(self):
        model = self.make_ready()
        with patch.object(whisper, "load_model") as loader:
            health = self.runtime.configure({"model": "large-v3", "device": "auto", "language": "ru"})
            loader.assert_not_called()
        self.assertIs(self.runtime.model, model)
        self.assertTrue(health["ready"])

    def test_configure_rejects_busy_transcription_without_replacing_model(self):
        model = self.make_ready()
        self.runtime.transcription_lock.acquire()
        try:
            with patch.object(whisper, "load_model") as loader:
                with self.assertRaisesRegex(RuntimeError, "busy"):
                    self.runtime.configure({"model": "small"})
                loader.assert_not_called()
            self.assertIs(self.runtime.model, model)
            self.assertEqual(self.runtime.settings.model, "large-v3")
            self.assertEqual(self.runtime.health()["status"], "busy")
        finally:
            self.runtime.transcription_lock.release()

    def test_configure_holds_transcription_lock_while_replacing_model(self):
        self.make_ready()
        def collect():
            acquired = self.runtime.transcription_lock.acquire(blocking=False)
            if acquired:
                self.runtime.transcription_lock.release()
            self.assertFalse(acquired, "transcribe must not start while configure unloads the model")
        with patch("gc.collect", side_effect=collect), patch.object(whisper, "load_model", return_value=(FakeModel(), "cpu")), \
                contextlib.redirect_stdout(io.StringIO()):
            self.runtime.configure({"model": "small"})
            self.runtime._loader.join(2)
        self.assertFalse(self.runtime.transcription_lock.locked())

    def test_second_load_and_configure_cannot_overlap_running_loader(self):
        started, release = threading.Event(), threading.Event()

        def load(*args):
            started.set()
            if not release.wait(2):
                raise AssertionError("test loader was not released")
            return FakeModel(), "cpu"

        with patch.object(whisper, "load_model", side_effect=load) as loader, contextlib.redirect_stdout(io.StringIO()):
            try:
                self.runtime.start_loading()
                self.assertTrue(started.wait(1))
                self.runtime.start_loading()
                with self.assertRaisesRegex(RuntimeError, "busy"):
                    self.runtime.configure({"model": "small"})
                loader.assert_called_once()
                self.assertEqual(self.runtime.settings.model, "large-v3")
            finally:
                release.set()
                self.runtime._loader.join(2)

    def test_invalid_configuration_leaves_existing_model_available(self):
        model = self.make_ready()
        for values in ({"model": "unknown"}, {"device": "remote"}, {"language": ""}):
            with self.subTest(values=values), self.assertRaises(ValueError):
                self.runtime.configure(values)
        self.assertIs(self.runtime.model, model)
        self.assertEqual(self.runtime.settings.language, "ru")

    def test_runtime_reports_explicit_gpu_out_of_memory(self):
        with patch.object(whisper, "load_model", side_effect=RuntimeError("gpu_out_of_memory")), \
                contextlib.redirect_stderr(io.StringIO()):
            self.runtime._load()
        self.assertFalse(self.runtime.health()["ready"])
        self.assertEqual(self.runtime.health()["status"], "error")
        self.assertEqual(self.runtime.health()["error"], "gpu_out_of_memory")

    def test_cuda_oom_is_classified_without_silent_cpu_fallback(self):
        ctranslate2 = ModuleType("ctranslate2")
        ctranslate2.get_cuda_device_count = lambda: 1
        faster_whisper = ModuleType("faster_whisper")
        faster_whisper.WhisperModel = Mock(side_effect=RuntimeError("CUDA failed: out of memory"))
        # A local placeholder name skips all download/cache code; only constructor error handling runs.
        settings = whisper.Settings(model="local-test-model", device="auto", model_dir=self.runtime.settings.model_dir)
        with patch.dict(sys.modules, {"ctranslate2": ctranslate2, "faster_whisper": faster_whisper}), \
                patch.object(whisper, "_enable_windows_nvidia_libraries"):
            with self.assertRaisesRegex(RuntimeError, "gpu_out_of_memory"):
                whisper.load_model(settings)
        self.assertEqual(faster_whisper.WhisperModel.call_count, 1)
        self.assertEqual(faster_whisper.WhisperModel.call_args.kwargs["device"], "cuda")


class HttpProtocolTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="mobtalk-whisper-http-test-")
        self.addCleanup(directory.cleanup)
        self.runtime = whisper.WhisperRuntime(whisper.Settings(model_dir=Path(directory.name)))
        self.model = FakeModel()
        self.runtime.model, self.runtime.device, self.runtime.status = self.model, "cpu", "ready"
        self.server = whisper.create_server(self.runtime, 0)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(1)

    def request(self, method, path, body=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            return response.status, json.loads(response.read().decode("utf-8"))
        finally:
            connection.close()

    def test_health_protocol_two_reports_actual_model_and_device(self):
        status, health = self.request("GET", "/health")
        self.assertEqual(status, 200)
        self.assertEqual(health["protocol"], 2)
        self.assertTrue(health["ready"])
        self.assertEqual(health["model"], "large-v3")
        self.assertEqual(health["device"], "cpu")
        self.assertEqual(health["language"], "ru")
        self.assertEqual(health["beamSize"], 5)
        self.assertEqual(self.server.server_address[0], "127.0.0.1")
        self.assertEqual(self.model.calls, [])

    def test_models_lists_all_five_and_detects_cached_model_without_loading(self):
        path = self.runtime.settings.model_dir / "models--Systran--faster-whisper-medium" / "snapshots" / "test" / "model.bin"
        path.parent.mkdir(parents=True)
        path.touch()
        with patch.object(whisper, "load_model") as loader:
            status, result = self.request("GET", "/models")
            loader.assert_not_called()
        self.assertEqual(status, 200)
        self.assertEqual(result["active"], "large-v3")
        models = {model["id"]: model["installed"] for model in result["models"]}
        self.assertEqual(set(models), {"small", "medium", "large-v2", "large-v3", "large-v3-turbo"})
        self.assertTrue(models["medium"])
        self.assertFalse(models["large-v3"])

    def test_transcribe_valid_pcm_returns_text_metadata_and_decodes_name_header(self):
        body = pcm_wav()
        name = "Minecraft, Клювик"
        header = base64.urlsafe_b64encode(name.encode("utf-8")).decode("ascii").rstrip("=")
        status, result = self.request("POST", "/transcribe", body, {"X-MobTalk-Context": header})
        self.assertEqual(status, 200)
        self.assertEqual(result["text"], "Крипер, стой рядом.")
        self.assertEqual(result["model"], "large-v3")
        self.assertEqual(result["device"], "cpu")
        self.assertEqual(result["durationSeconds"], 0.5)
        self.assertGreaterEqual(result["elapsedSeconds"], 0)
        self.assertEqual(self.model.calls[0][0], body)
        self.assertIn(name, self.model.calls[0][1]["hotwords"])
        self.assertFalse(self.runtime.transcription_lock.locked())

    def test_silent_transcription_returns_comparison_metadata_without_decoding(self):
        status, result = self.request("POST", "/transcribe", pcm_wav(amplitude=0))
        self.assertEqual(status, 200)
        self.assertEqual(result["text"], "")
        self.assertEqual(self.model.calls, [])
        self.assertEqual(result.get("model"), "large-v3")
        self.assertEqual(result.get("device"), "cpu")
        self.assertEqual(result.get("durationSeconds"), 0.5)
        self.assertIsInstance(result.get("elapsedSeconds"), (int, float))

    def test_invalid_pcm_and_overlong_recording_are_rejected_without_inference(self):
        for body in (b"not-wav", pcm_wav(width=1), pcm_wav(frames=16000 * 15 + 1)):
            with self.subTest(size=len(body)):
                status, result = self.request("POST", "/transcribe", body)
                self.assertEqual(status, 400)
                self.assertEqual(result["error"], "invalid_pcm16_wav")
        self.assertEqual(self.model.calls, [])
        self.assertFalse(self.runtime.transcription_lock.locked())

    def test_not_ready_and_busy_transcription_have_explicit_errors(self):
        self.runtime.model, self.runtime.status = None, "loading"
        status, result = self.request("POST", "/transcribe", pcm_wav())
        self.assertEqual(status, 503)
        self.assertEqual(result["error"], "model_loading")
        self.runtime.model, self.runtime.status = self.model, "ready"
        self.runtime.transcription_lock.acquire()
        try:
            status, result = self.request("POST", "/transcribe", pcm_wav())
            self.assertEqual(status, 409)
            self.assertEqual(result["error"], "busy")
        finally:
            self.runtime.transcription_lock.release()
        self.assertEqual(self.model.calls, [])

    def test_busy_configure_http_does_not_start_new_model_load(self):
        self.runtime.transcription_lock.acquire()
        try:
            with patch.object(whisper, "load_model") as loader:
                status, result = self.request("POST", "/configure", json.dumps({"model": "small"}).encode())
                self.assertEqual(status, 409)
                self.assertEqual(result["error"], "busy")
                loader.assert_not_called()
            self.assertEqual(self.runtime.settings.model, "large-v3")
        finally:
            self.runtime.transcription_lock.release()

    def test_cancel_load_stops_mock_loader_and_reports_cancelled(self):
        started = threading.Event()

        def load(settings, progress, cancelled):
            progress("downloading", 1, 4)
            started.set()
            if not cancelled.wait(2):
                raise AssertionError("cancel signal was not received")
            raise whisper.LoadCancelled()

        self.runtime.model = None
        self.runtime.status = "loading"
        with patch.object(whisper, "load_model", side_effect=load):
            self.runtime.start_loading()
            try:
                self.assertTrue(started.wait(1))
                status, result = self.request("POST", "/cancel-load")
                self.assertEqual(status, 200)
                self.assertEqual(result["status"], "cancelling")
            finally:
                self.runtime.cancel_load.set()
                self.runtime._loader.join(2)
        self.assertFalse(self.runtime._loader.is_alive())
        status, health = self.request("GET", "/health")
        self.assertEqual(status, 200)
        self.assertEqual(health["status"], "cancelled")
        self.assertFalse(health["ready"])
        self.assertIsNone(self.runtime.model)


if __name__ == "__main__":
    unittest.main()
