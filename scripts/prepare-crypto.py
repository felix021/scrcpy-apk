#!/usr/bin/env python3
"""Fetch the pinned BoringSSL source used for ADB TLS 1.3 and SPAKE2."""
import hashlib
from pathlib import Path
import tarfile
import urllib.request

root = Path(__file__).resolve().parent.parent
revision = '0131ae9d78e42fcc2c1811315e62410ad96d8230'
expected = '893e755fe720627769d7bd49d15815e86cc0fd4a4ae84b3717caf6fce4255bb4'
cache = root / 'build' / 'boringssl'
cache.mkdir(parents=True, exist_ok=True)
archive = cache / (revision + '.tar.gz')
if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
    temporary = archive.with_suffix('.download')
    with urllib.request.urlopen('https://codeload.github.com/google/boringssl/tar.gz/' + revision, timeout=90) as response:
        temporary.write_bytes(response.read())
    if hashlib.sha256(temporary.read_bytes()).hexdigest() != expected:
        raise SystemExit('BoringSSL source SHA-256 mismatch')
    temporary.replace(archive)
source = cache / ('boringssl-' + revision)
if not source.exists():
    with tarfile.open(archive) as source_archive:
        for member in source_archive.getmembers():
            if cache.resolve() not in (cache / member.name).resolve().parents:
                raise SystemExit('Unsafe BoringSSL archive path')
        source_archive.extractall(cache)
print(source)
