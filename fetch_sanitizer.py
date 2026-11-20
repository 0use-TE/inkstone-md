"""Install a separately pinned modern HTML sanitizer; no npm scripts."""
from pathlib import Path
import base64
import hashlib
import io
import json
import tarfile
import urllib.request

root = Path(__file__).resolve().parent
meta = json.load(urllib.request.urlopen('https://registry.npmjs.org/dompurify/3.3.0', timeout=60))
data = urllib.request.urlopen(meta['dist']['tarball'], timeout=120).read()
expected = meta['dist']['integrity']
if 'sha512-' + base64.b64encode(hashlib.sha512(data).digest()).decode() != expected:
    raise RuntimeError('Sanitizer integrity mismatch')
target = root / 'app/src/main/assets/editor/vendor'
with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
    for source, name in [('package/dist/purify.min.js', 'purify.min.js'), ('package/LICENSE', 'LICENSE-dompurify')]:
        content = archive.extractfile(source).read()
        dest = target / name
        if dest.exists() and dest.read_bytes() != content:
            raise RuntimeError('Refusing to replace modified sanitizer asset')
        if not dest.exists():
            dest.write_bytes(content)
        print(name, len(content), 'bytes', flush=True)
path = root / 'sanitizer-integrity.json'
record = json.dumps({'package': 'dompurify', 'version': '3.3.0', 'url': meta['dist']['tarball'], 'integrity': expected}, indent=2) + '\n'
if path.exists() and path.read_text() != record:
    raise RuntimeError('Existing integrity record differs')
if not path.exists():
    path.write_text(record)
