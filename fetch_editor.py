"""Fetch pinned offline editor assets; never run npm lifecycle scripts."""
from pathlib import Path
import base64
import hashlib
import io
import json
import tarfile
import urllib.request

root = Path(__file__).resolve().parent
meta = json.load(urllib.request.urlopen('https://registry.npmjs.org/@toast-ui/editor/3.2.2', timeout=60))
data = urllib.request.urlopen(meta['dist']['tarball'], timeout=120).read()
expected = meta['dist']['integrity']
actual = 'sha512-' + base64.b64encode(hashlib.sha512(data).digest()).decode()
if actual != expected:
    raise RuntimeError('npm distribution integrity mismatch')
target = root / 'app/src/main/assets/editor/vendor'
target.mkdir(parents=True, exist_ok=True)
names = {
    'package/dist/toastui-editor.css': 'toastui-editor.css',
    'package/dist/i18n/zh-cn.js': 'zh-cn.js',
}
with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
    for source, name in names.items():
        content = archive.extractfile(source).read()
        dest = target / name
        if dest.exists():
            if dest.read_bytes() != content:
                raise RuntimeError('Refusing to replace modified vendor asset: ' + name)
        else:
            dest.write_bytes(content)
        print(name, len(content), 'bytes', flush=True)
# The npm UMD file externalizes ProseMirror. Use the official standalone browser bundle.
browser_url = 'https://uicdn.toast.com/editor/3.2.2/toastui-editor-all.min.js'
browser = urllib.request.urlopen(browser_url, timeout=120).read()
browser_sha = 'f50e1b7c0fc4e5d9a1ccd0d8be78cb3a950ccb3bf676fbf1627810c76aeaedd8'
if hashlib.sha256(browser).hexdigest() != browser_sha:
    raise RuntimeError('Standalone browser bundle SHA256 mismatch')
browser_path = target / 'toastui-editor-all.min.js'
if browser_path.exists() and browser_path.read_bytes() != browser:
    raise RuntimeError('Refusing to replace modified browser bundle')
if not browser_path.exists():
    browser_path.write_bytes(browser)
print('Standalone browser bundle SHA256 verified', flush=True)
(root / 'vendor-integrity.json').write_text(json.dumps({
    'browserBundleUrl': browser_url, 'browserBundleSHA256': browser_sha,
    'package': '@toast-ui/editor', 'version': '3.2.2',
    'url': meta['dist']['tarball'], 'integrity': expected,
}, indent=2) + '\n')
