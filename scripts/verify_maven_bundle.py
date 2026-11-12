"""Validate the exact SDK-only Central bundle and every PGP signature; never upload."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile
from xml.etree import ElementTree as ET
from zipfile import ZipFile


def verify(bundle: Path, public_key: Path, gpg: str = "gpg", msys_paths: bool = False) -> None:
    key_data = public_key.read_bytes()
    assert b"BEGIN PGP PUBLIC KEY BLOCK" in key_data and b"PRIVATE KEY" not in key_data, "Expected an armored public key, not private key material"
    def gpg_path(path: Path) -> str:
        value = str(path.resolve())
        if msys_paths and path.resolve().drive:
            return "/" + value[0].lower() + value[2:].replace("\\", "/")
        return value
    root = Path(__file__).resolve().parents[1]
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    version = ET.parse(root / "pom.xml").getroot().findtext("m:version", namespaces=ns)
    group = "io/github/nijikamori"
    artifacts = {"veyraeval-platform": ["pom"], "veyraeval-protocol": ["pom", "jar", "sources.jar", "javadoc.jar"], "veyraeval-sdk": ["pom", "jar", "sources.jar", "javadoc.jar"]}
    expected = set()
    for artifact, extensions in artifacts.items():
        for ext in extensions:
            suffix = "-" + ext if ext in ("sources.jar", "javadoc.jar") else "." + ext
            expected.add(f"{group}/{artifact}/{version}/{artifact}-{version}{suffix}")
    with ZipFile(bundle) as archive, tempfile.TemporaryDirectory(prefix="veyraeval-pgp-") as scratch:
        names = archive.namelist()
        assert len(names) == len(set(names)), "Duplicate bundle members"
        names = {n for n in names if not n.endswith("/")}
        checksums = {"md5": "md5", "sha1": "sha1", "sha256": "sha256", "sha512": "sha512"}
        allowed = expected | {n + ".asc" for n in expected}
        allowed |= {n + "." + ext for n in tuple(allowed) for ext in checksums}
        assert names <= allowed, f"Unexpected bundle members: {sorted(names-allowed)}"
        assert expected | {n + ".asc" for n in expected} <= names, "Missing SDK component or signature"
        home = Path(scratch) / "keyring"
        home.mkdir(mode=0o700)
        command = [gpg, "--homedir", gpg_path(home), "--batch", "--no-auto-key-retrieve"]
        result = subprocess.run(command + ["--import"], input=key_data, capture_output=True)
        assert result.returncode == 0, "Cannot import expected public signing key"
        for index, name in enumerate(sorted(expected)):
            payload = archive.read(name)
            for ext, algorithm in checksums.items():
                member = name + "." + ext
                assert member in names, f"Missing {ext}: {name}"
                actual = archive.read(member).decode("ascii").strip().lower()
                assert actual == hashlib.new(algorithm, payload).hexdigest(), f"Checksum mismatch: {member}"
            for member in (name + ".asc." + ext for ext in checksums):
                if member in names:
                    algorithm = checksums[member.rsplit(".", 1)[1]]
                    assert archive.read(member).decode("ascii").strip().lower() == hashlib.new(algorithm, archive.read(name + ".asc")).hexdigest(), f"Signature checksum mismatch: {member}"
            data = Path(scratch) / f"artifact-{index}"
            signature = data.with_suffix(".asc")
            data.write_bytes(payload)
            signature.write_bytes(archive.read(name + ".asc"))
            result = subprocess.run(command + ["--verify", gpg_path(signature), gpg_path(data)], capture_output=True)
            assert result.returncode == 0, f"Invalid PGP signature: {name}"
    print(f"Central bundle verified: {len(expected)} POM/jar files, checksums and PGP signatures; only parent, protocol and SDK. Nothing uploaded.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--public-key", type=Path, required=True)
    parser.add_argument("--gpg", default="gpg")
    parser.add_argument("--msys-paths", action="store_true", help="Use POSIX paths with Git for Windows GPG")
    args = parser.parse_args()
    verify(args.bundle, args.public_key, args.gpg, args.msys_paths)
