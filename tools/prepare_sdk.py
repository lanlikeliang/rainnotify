"""Install the two verified Google SDK archives already downloaded locally."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".local-tools" / "downloads"
SDK = ROOT / ".local-tools" / "android-sdk"
PACKAGES = {
    "platforms;android-36": ("platform-36_r02.zip", "android-36", "platforms/android-36"),
    "build-tools;35.0.0": ("build-tools_r35_windows.zip", "android-15", "build-tools/35.0.0"),
}


def local_name(element: ET.Element) -> str:
    return element.tag.rsplit("}", 1)[-1]


def install(accept_license: bool) -> None:
    if not accept_license:
        raise SystemExit("Read the saved SDK license, then supply --accept-sdk-license to install.")
    metadata = ET.parse(CACHE / "repository2-1.xml").getroot()
    receipt = []
    for package_id, (archive_name, archive_root, folder) in PACKAGES.items():
        matches = []
        for package in metadata:
            if local_name(package) != "remotePackage" or package.get("path") != package_id:
                continue
            for complete in package.iter():
                if local_name(complete) != "complete":
                    continue
                fields = {local_name(item): item for item in complete}
                if fields["url"].text == archive_name:
                    matches.append(fields)
        if len(matches) != 1:
            raise ValueError(f"Expected exactly one official metadata record for {archive_name}")
        fields = matches[0]
        archive_path = CACHE / archive_name
        algorithm = fields["checksum"].get("type", "sha1").replace("-", "").lower()
        if algorithm not in {"sha1", "sha256"}:
            raise ValueError("Unsupported digest")
        digest = hashlib.new(algorithm, archive_path.read_bytes()).hexdigest()
        if archive_path.stat().st_size != int(fields["size"].text or "0") or digest != fields["checksum"].text:
            raise ValueError(f"Official metadata checksum mismatch: {archive_name}")
        destination = (SDK / folder).resolve()
        destination.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(archive_path) as archive:
            for item in archive.infolist():
                member = PurePosixPath(item.filename)
                if member.is_absolute() or ".." in member.parts or "\\" in item.filename:
                    raise ValueError("Unsafe archive path")
                if not member.parts or member.parts[0] != archive_root:
                    raise ValueError("Unexpected archive root")
                if (item.external_attr >> 16) & 0o170000 == 0o120000:
                    raise ValueError("Symbolic link in SDK archive")
                target = destination.joinpath(*member.parts[1:]).resolve()
                if not target.is_relative_to(destination):
                    raise ValueError("Archive path escapes destination")
                if item.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with archive.open(item) as source, target.open("wb") as output:
                        shutil.copyfileobj(source, output)
        receipt.append({"package": package_id, "source": "https://dl.google.com/android/repository/" + archive_name,
                        "metadata_digest": digest, "sha256": hashlib.sha256(archive_path.read_bytes()).hexdigest()})
    license_element = next(item for item in metadata
                           if local_name(item) == "license" and item.get("id") == "android-sdk-license")
    license_text = (license_element.text or "").strip()
    if not license_text:
        raise ValueError("SDK license missing from official metadata")
    license_dir = SDK / "licenses"
    license_dir.mkdir(exist_ok=True)
    (license_dir / "android-sdk-license").write_text(hashlib.sha1(license_text.encode("utf-8")).hexdigest() + "\n")
    (ROOT / ".local-tools" / "sdk-receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print(json.dumps({"sdk": str(SDK), "packages": list(PACKAGES), "verified": True}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--accept-sdk-license", action="store_true")
    install(parser.parse_args().accept_sdk_license)
