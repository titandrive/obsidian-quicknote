#!/usr/bin/env python3
"""
Fetches Node.js for Android (Termux's build, arm64) and the libraries it needs, ready to go in the
app as native libraries, for background sync (Obsidian Headless runs on Node).

Android only installs files named lib*.so from an app's native libraries, and only those can be
run. So Node becomes libobnode.so, and libraries with a version after ".so" (libssl.so.3) are
renamed to end in ".so" (libssl3.so), inside the files too: their own names and the names of
the ones they load. Each new name is no longer than the old one, so the names are rewritten in
place. Termux's packages are open source (Node: MIT); they're fetched here rather than kept in git.

Usage: fetch_node.py <output dir>   (the app's jniLibs/arm64-v8a)
"""
import io
import lzma
import os
import re
import sys
import tarfile
import urllib.request

REPO = "https://packages.termux.dev/apt/termux-main/"
INDEX = REPO + "dists/stable/main/binary-aarch64/Packages"
PREFIX = "data/data/com.termux/files/usr/"

# The files to ship, from the packages' lib and bin folders, and their names in the app.
FILES = {
    "bin/node": "libobnode.so",
    "lib/libz.so.1": "libz1.so",
    "lib/libcares.so": "libcares.so",
    "lib/libsqlite3.so": "libsqlite3.so",
    "lib/libcrypto.so.3": "libcrypto3.so",
    "lib/libssl.so.3": "libssl3.so",
    "lib/libicui18n.so.79": "libicui18n79.so",
    "lib/libicuuc.so.79": "libicuuc79.so",
    "lib/libicudata.so.79": "libicudata79.so",
    "lib/libc++_shared.so": "libc++_shared.so",
}
# Library names as the files refer to each other, and what they become.
RENAMES = {os.path.basename(k): v for k, v in FILES.items() if k.startswith("lib/") and os.path.basename(k) != v}


def packages():
    index = urllib.request.urlopen(INDEX).read().decode()
    found = {}
    for block in index.split("\n\n"):
        fields = dict(re.findall(r"^([\w-]+): (.*)$", block, re.M))
        if "Package" in fields:
            found[fields["Package"]] = fields
    return found


def wanted(found, root="nodejs-lts"):
    todo, seen = [root], []
    while todo:
        name = todo.pop()
        if name in seen or name not in found:
            continue
        seen.append(name)
        for dep in found[name].get("Depends", "").split(","):
            dep = dep.strip().split(" ")[0]
            if dep:
                todo.append(dep)
    return seen


def deb_files(data):
    """The files in a .deb (an ar archive holding data.tar.xz), by path, links followed later."""
    assert data[:8] == b"!<arch>\n"
    pos = 8
    while pos < len(data):
        name = data[pos:pos + 16].decode().strip().rstrip("/")
        size = int(data[pos + 48:pos + 58].decode().strip())
        body = data[pos + 60:pos + 60 + size]
        pos += 60 + size + (size & 1)
        if name.startswith("data.tar"):
            raw = lzma.decompress(body) if name.endswith(".xz") else body
            with tarfile.open(fileobj=io.BytesIO(raw)) as tar:
                members = {m.name.lstrip("./"): m for m in tar.getmembers()}
                out = {}
                for path, m in members.items():
                    out[path] = ("link", m.linkname) if m.issym() else ("file", tar.extractfile(m).read() if m.isfile() else None)
                return out
    return {}


def rename(data):
    for old, new in RENAMES.items():
        a, b = old.encode() + b"\0", new.encode() + b"\0"
        assert len(b) <= len(a), (old, new)
        data = data.replace(a, b + b"\0" * (len(a) - len(b)))
    return data


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    found = packages()
    tree = {}
    for name in wanted(found):
        url = REPO + found[name]["Filename"]
        print("fetching", name, found[name]["Version"])
        tree.update(deb_files(urllib.request.urlopen(url).read()))

    def resolve(path):
        for _ in range(10):
            kind, value = tree[PREFIX + path]
            if kind == "file":
                return value
            path = os.path.normpath(os.path.join(os.path.dirname(path), value))
        raise RuntimeError(path)

    for src, dst in FILES.items():
        with open(os.path.join(out, dst), "wb") as f:
            f.write(rename(resolve(src)))
        print(" ", dst)


if __name__ == "__main__":
    main()
