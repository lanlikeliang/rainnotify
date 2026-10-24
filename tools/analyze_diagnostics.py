"""Summarize app JSONL diagnostics; never confuse simulation with live events."""
from __future__ import annotations

import argparse
import json
import math
from collections import defaultdict
from pathlib import Path
from typing import Iterable


def summarize(records: Iterable[dict]) -> dict:
    groups: dict[tuple[str, str, str], list[dict]] = defaultdict(list)
    totals = {"real": {"received": 0, "submitted": 0, "duplicate_submissions": 0},
              "simulation": {"received": 0, "submitted": 0, "duplicate_submissions": 0}}
    for record in records:
        source = record.get("source")
        event = record.get("event_id")
        run = record.get("run_id")
        if source in totals and isinstance(event, str) and isinstance(run, str):
            groups[(run, source, event)].append(record)
    for (_, source, _), rows in groups.items():
        if any(row.get("kind") == "event_received" for row in rows):
            totals[source]["received"] += 1
        submissions = [row for row in rows if row.get("kind") == "notification_submitted"]
        totals[source]["submitted"] += bool(submissions)
        totals[source]["duplicate_submissions"] += max(0, len(submissions) - 1)
    output: dict = {"sources": {}, "limits": [
        "Notification submission is not proof of a visible banner or sound.",
        "Missing events cannot be counted without an independent classroom reference.",
        "Simulation does not establish Rain Classroom protocol or background network reliability.",
        "Teacher-to-device latency is not available from these local timestamps.",
    ]}
    for source, counts in totals.items():
        durations = []
        unmatched = 0
        for (_, event_source, _), rows in groups.items():
            if event_source != source:
                continue
            receive = [row.get("elapsed_ms") for row in rows if row.get("kind") == "event_received"]
            submit = [row.get("elapsed_ms") for row in rows if row.get("kind") == "notification_submitted"]
            receive = [value for value in receive if type(value) in (int, float) and math.isfinite(value)]
            submit = [value for value in submit if type(value) in (int, float) and math.isfinite(value)]
            if receive and submit and min(submit) >= min(receive):
                durations.append(min(submit) - min(receive))
            if receive and not submit:
                unmatched += 1
        durations.sort()
        output["sources"][source] = {
            **counts,
            "received_without_submission": unmatched,
            "local_pipeline_samples": len(durations),
            "local_pipeline_max_ms": max(durations) if durations else None,
            "local_pipeline_p95_ms": durations[math.ceil(0.95 * len(durations)) - 1] if len(durations) >= 20 else None,
        }
    return output


def read_report(path: Path) -> tuple[list[dict], int]:
    records, malformed = [], 0
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if not line.strip():
            continue
        try:
            record = json.loads(line)
            if not isinstance(record, dict):
                raise ValueError("Expected object")
            records.append(record)
        except (ValueError, json.JSONDecodeError):
            malformed += 1
    return records, malformed


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    arguments = parser.parse_args()
    records, malformed = read_report(arguments.report)
    result = summarize(records)
    result["malformed_lines"] = malformed
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
