import tempfile
import unittest
from pathlib import Path

from tools.analyze_diagnostics import read_report, summarize


def record(kind, elapsed, source="real", event="e", run="r"):
    return {"kind": kind, "elapsed_ms": elapsed, "source": source,
            "event_id": event, "run_id": run}


class DiagnosticTest(unittest.TestCase):
    def test_simulation_cannot_count_as_real(self):
        result = summarize([record("event_received", 100, "simulation"),
                            record("notification_submitted", 104, "simulation")])
        self.assertEqual(result["sources"]["real"]["received"], 0)
        self.assertEqual(result["sources"]["simulation"]["local_pipeline_max_ms"], 4)

    def test_missing_submission_and_duplicates_are_separate(self):
        result = summarize([record("event_received", 100),
                            record("notification_submitted", 105),
                            record("notification_submitted", 110),
                            record("event_received", 120, event="missing")])
        counts = result["sources"]["real"]
        self.assertEqual(counts["duplicate_submissions"], 1)
        self.assertEqual(counts["received_without_submission"], 1)

    def test_monotonic_clocks_from_different_runs_are_not_combined(self):
        result = summarize([record("event_received", 100, run="first"),
                            record("notification_submitted", 200, run="second")])
        self.assertEqual(result["sources"]["real"]["local_pipeline_samples"], 0)

    def test_small_sample_does_not_report_p95(self):
        result = summarize([record("event_received", 100), record("notification_submitted", 102)])
        self.assertIsNone(result["sources"]["real"]["local_pipeline_p95_ms"])

    def test_invalid_numbers_and_reversed_times_are_not_latency_samples(self):
        result = summarize([record("event_received", 100), record("notification_submitted", 10),
                            record("event_received", float("nan"), event="nan"),
                            record("notification_submitted", 300, event="nan")])
        self.assertEqual(result["sources"]["real"]["local_pipeline_samples"], 0)

    def test_malformed_or_truncated_lines_are_reported(self):
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", suffix=".jsonl",
                                         dir=Path(__file__).resolve().parent, delete=False) as temporary:
            temporary.write('{"kind":"event_received"}\n{broken\n[]\n')
            path = Path(temporary.name)
        try:
            records, malformed = read_report(path)
            self.assertEqual(len(records), 1)
            self.assertEqual(malformed, 2)
        finally:
            path.unlink()


if __name__ == "__main__":
    unittest.main()
