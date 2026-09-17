"""Download the official offline English model; no Nolee account or secret required."""
import hashlib
from pathlib import Path
import tempfile
import urllib.request
import zipfile

NAME = "vosk-model-small-en-us-0.15"
URL = f"https://alphacephei.com/vosk/models/{NAME}.zip"
SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"


def main():
    destination = Path(__file__).resolve().parents[1] / "models"
    with tempfile.TemporaryDirectory(prefix="nolee-vosk-") as temporary:
        archive = Path(temporary) / "model.zip"
        print(f"Downloading {URL}")
        with urllib.request.urlopen(URL, timeout=120) as response, archive.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
            raise RuntimeError("Model checksum mismatch; nothing extracted")
        with zipfile.ZipFile(archive) as model:
            for entry in model.infolist():
                target = (destination / entry.filename).resolve()
                if not target.is_relative_to((destination / NAME).resolve()):
                    raise RuntimeError("Unexpected archive path")
            model.extractall(destination)
    print(f"Ready: {destination / NAME}")


if __name__ == "__main__":
    main()
