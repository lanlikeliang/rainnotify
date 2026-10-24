"""Download official Android Platform Tools into the workspace, verifying metadata."""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = "https://dl.google.com/android/repository/"


def local_name(element: ET.Element) -> str:
    return element.tag.rsplit("}", 1)[-1]


def child_text(element: ET.Element, name: str) -> str:
    for item in element:
        if local_name(item) == name:
            return (item.text or "").strip()
    raise ValueError(f"Missing metadata field: {name}")


def download(url: str, path: Path) -> None:
    if not url.startswith(BASE):
        raise ValueError("Only the official Android repository is supported")
    curl = shutil.which("curl.exe") if os.name == "nt" else None
    if curl:
        # Windows Schannel still verifies the TLS certificate chain and hostname.
        # Best-effort revocation tolerates an unreachable revocation server;
        # --insecure and --ssl-no-revoke are deliberately not used.
        subprocess.run([
            curl, "--ssl-revoke-best-effort", "--silent", "--show-error", "--fail",
            "--location", "--connect-timeout", "15", "--max-time", "90", "--retry", "1",
            "--output", str(path), url,
        ], check=True)
        return
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "RainNotify-ToolBootstrap/1.0"})
            with urllib.request.urlopen(request, timeout=30) as response, path.open("wb") as output:
                while block := response.read(1024 * 1024):
                    output.write(block)
            return
        except OSError:
            if attempt == 2:
                raise
            time.sleep(2)


def install() -> dict:
    cache = ROOT / ".local-tools"
    cache.mkdir(exist_ok=True)
    metadata_path = cache / "platform-tools-metadata.xml"
    download(BASE + "repository2-1.xml", metadata_path)
    metadata = ET.parse(metadata_path).getroot()
    package = next(
        element for element in metadata.iter()
        if local_name(element) == "remotePackage"
        and element.get("path") == "platform-tools"
        and any(local_name(item) == "channelRef" and item.get("ref") == "channel-0"
                for item in element)
    )
    archives = next(item for item in package if local_name(item) == "archives")
    archive = next(item for item in archives if child_text(item, "host-os") == "windows")
    complete = next(item for item in archive if local_name(item) == "complete")
    filename = child_text(complete, "url")
    if not re.fullmatch(r"platform-tools_r[0-9.]+-win\.zip", filename):
        raise ValueError("Unexpected official archive path")
    size = int(child_text(complete, "size"))
    checksum = next(item for item in complete if local_name(item) == "checksum")
    algorithm = checksum.get("type", "sha1").replace("-", "").lower()
    if algorithm not in {"sha1", "sha256"}:
        raise ValueError("Unsupported metadata digest")
    archive_path = cache / filename
    download(BASE + filename, archive_path)
    actual_digest = hashlib.new(algorithm, archive_path.read_bytes()).hexdigest()
    if archive_path.stat().st_size != size or actual_digest != (checksum.text or "").strip():
        raise ValueError("Official archive size or checksum does not match metadata")
    destination = (cache / "android-sdk").resolve()
    destination.mkdir(exist_ok=True)
    with zipfile.ZipFile(archive_path) as archive_file:
        for member in archive_file.infolist():
            resolved = (destination / member.filename).resolve()
            if not resolved.is_relative_to(destination):
                raise ValueError("Archive entry escapes the tool directory")
            if (member.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("Unexpected symbolic link in archive")
        archive_file.extractall(destination)
    adb = destination / "platform-tools" / "adb.exe"
    if not adb.is_file():
        raise ValueError("ADB executable not found after extraction")
    receipt = {
        "source": BASE + filename,
        "metadata": BASE + "repository2-1.xml",
        "metadata_checksum_type": algorithm,
        "metadata_checksum": actual_digest,
        "sha256": hashlib.sha256(archive_path.read_bytes()).hexdigest(),
        "adb": str(adb),
    }
    (cache / "platform-tools-receipt.json").write_text(
        json.dumps(receipt, indent=2), encoding="utf-8"
    )
    return receipt


if __name__ == "__main__":
    print(json.dumps(install(), ensure_ascii=False, indent=2))
