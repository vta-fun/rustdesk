"""Prepare rebuilt RustDesk Android libraries for the Morphe patch bundle."""

import argparse
import hashlib
from pathlib import Path
import re
import struct
import tempfile
from zipfile import BadZipFile, ZIP_STORED, ZipFile


STOCK_SHA256 = "cfc44193ef96dd601d25c9a29f1aa5385effdbaae33d2abbb1d88495797f6b84"
LIB_DIR = "lib/arm64-v8a/"
REPLACEMENTS = ("libapp.so", "librustdesk.so")
BACKUP_APP = "libapp-backup.so"
FLUTTER_ENGINE = "libflutter.so"
CXX_RUNTIME = "libc++_shared.so"
PAYLOAD_DIR = (
    Path(__file__).resolve().parent
    / "patches/build/generated/rustdeskPayload/rustdesk/1.5.1/arm64-v8a"
)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def file_sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def native_libraries(apk):
    libraries = [
        name for name in apk.namelist() if name.startswith("lib/") and name.endswith(".so")
    ]
    if len(libraries) != len(set(libraries)) or any(
        not name.startswith(LIB_DIR) for name in libraries
    ):
        raise ValueError(f"{apk.filename}: expected a single arm64-v8a APK")
    for name in (*REPLACEMENTS, FLUTTER_ENGINE, CXX_RUNTIME):
        if LIB_DIR + name not in libraries:
            raise ValueError(f"{apk.filename}: missing {LIB_DIR}{name}")
    return libraries


def flutter_assets(apk):
    return {
        entry.filename: sha256(apk.read(entry))
        for entry in apk.infolist()
        if not entry.is_dir() and entry.filename.startswith("assets/flutter_assets/")
    }


def read_arm64_elf(apk, name):
    data = apk.read(LIB_DIR + name)
    if not data.startswith(b"\x7fELF\x02\x01") or data[16:20] != b"\x03\x00\xb7\x00":
        raise ValueError(f"{apk.filename}: {name} is not an AArch64 ELF shared library")
    header_start = struct.unpack_from("<Q", data, 32)[0]
    header_size, header_count = struct.unpack_from("<HH", data, 54)
    if header_size != 56 or header_start + header_size * header_count > len(data):
        raise ValueError(f"{apk.filename}: invalid program headers in {name}")
    load_alignments = [
        struct.unpack_from("<Q", data, offset + 48)[0]
        for offset in range(header_start, header_start + header_size * header_count, header_size)
        if struct.unpack_from("<I", data, offset)[0] == 1  # PT_LOAD
    ]
    if not load_alignments or min(load_alignments) < 16_384:
        raise ValueError(f"{apk.filename}: {name} does not support 16 KiB pages")
    return data


def verify_patched(stock, patched, rebuilt):
    stock_libraries = set(native_libraries(stock))
    if set(native_libraries(patched)) != stock_libraries:
        raise ValueError("The patched APK's list of native libraries changed")
    for path in stock_libraries:
        name = path.removeprefix(LIB_DIR)
        expected = rebuilt[name] if name in rebuilt else stock.read(path)
        if patched.read(path) != expected:
            raise ValueError(f"The patched APK has unexpected contents in {path}")
        if stock.getinfo(path).compress_type == ZIP_STORED:
            if patched.getinfo(path).compress_type != ZIP_STORED:
                raise ValueError(f"The patched APK compressed the stored library {path}")
    def untouched_entries(apk):
        return {
            entry.filename for entry in apk.infolist()
            if not entry.is_dir() and entry.filename not in stock_libraries
            and not re.fullmatch(r"META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))", entry.filename)
        }

    unchanged = untouched_entries(stock)
    if unchanged != untouched_entries(patched):
        raise ValueError("The patched APK added or removed non-signature entries")
    for name in unchanged:
        if stock.read(name) != patched.read(name):
            raise ValueError(f"The patched APK modified {name}")


def check_flutter_compatibility(apk, engine, stock_assets):
    native_libraries(apk)
    if engine != apk.read(LIB_DIR + FLUTTER_ENGINE):
        raise ValueError(f"{apk.filename}: Flutter engine differs from the stock APK")
    assets = flutter_assets(apk)
    if stock_assets != assets:
        differing = sorted(
            name for name in stock_assets.keys() | assets.keys()
            if stock_assets.get(name) != assets.get(name)
        )
        raise ValueError(f"{apk.filename}: Flutter assets differ: " + ", ".join(differing))


def prepare(stock_path, rebuilt_path, backup_path, patched_path):
    if file_sha256(stock_path) != STOCK_SHA256:
        raise ValueError("The stock APK must be the official RustDesk 1.5.1 AArch64 release")

    with ZipFile(stock_path) as stock, ZipFile(rebuilt_path) as rebuilt:
        native_libraries(stock)
        engine = stock.read(LIB_DIR + FLUTTER_ENGINE)
        stock_assets = flutter_assets(stock)
        check_flutter_compatibility(rebuilt, engine, stock_assets)

        original = {
            name: read_arm64_elf(stock, name) for name in (*REPLACEMENTS, CXX_RUNTIME)
        }
        replacement = {name: read_arm64_elf(rebuilt, name) for name in REPLACEMENTS}
        for name in REPLACEMENTS:
            if original[name] == replacement[name]:
                raise ValueError(f"{name} is unchanged in the rebuilt APK")

        if patched_path is not None:
            with ZipFile(patched_path) as patched:
                verify_patched(stock, patched, replacement)
            print(f"Verified Morphe output: {patched_path}")
            return

        with ZipFile(backup_path) as backup:
            check_flutter_compatibility(backup, engine, stock_assets)
            backup_app = read_arm64_elf(backup, "libapp.so")
            if backup_app in (original["libapp.so"], replacement["libapp.so"]):
                raise ValueError("The backup-only Flutter library must be a separate build")
            replacement[BACKUP_APP] = backup_app

    original[FLUTTER_ENGINE] = engine
    checksums = "".join(
        f"{kind}.{name}={sha256(data)}\n"
        for kind, libraries in (("original", original), ("patched", replacement))
        for name, data in libraries.items()
    )
    PAYLOAD_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=PAYLOAD_DIR.parent) as temp:
        staging = Path(temp)
        for name, data in replacement.items():
            (staging / name).write_bytes(data)
        (staging / "sha256.properties").write_text(checksums, encoding="ascii")
        for name in (*replacement, "sha256.properties"):
            (staging / name).replace(PAYLOAD_DIR / name)
    print(f"Prepared verified arm64-v8a payload: {PAYLOAD_DIR}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stock_apk", type=Path)
    parser.add_argument("rebuilt_apk", type=Path)
    parser.add_argument("--backup-only", type=Path, help="APK built with only the settings entry")
    parser.add_argument("--patched", type=Path, help="verify the Morphe output instead")
    args = parser.parse_args()
    if args.backup_only is None and args.patched is None:
        parser.error("--backup-only is required when preparing the bundle")
    try:
        prepare(args.stock_apk, args.rebuilt_apk, args.backup_only, args.patched)
    except (BadZipFile, OSError, KeyError, ValueError, struct.error) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
