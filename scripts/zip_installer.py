"""Zip the player installer: zip_installer.py <out.zip> <folder name inside> <file>... (each file goes in flat).
Entries are marked as made on Unix with their modes, so macOS keeps install-macos.command executable."""
import os, sys, time, zipfile

out, top, files = sys.argv[1], sys.argv[2], sys.argv[3:]
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for path in files:
        name = os.path.basename(path)
        info = zipfile.ZipInfo(f'{top}/{name}', time.localtime(os.path.getmtime(path))[:6])
        info.create_system = 3
        mode = 0o755 if name.endswith(('.command', '.sh')) else 0o644
        info.external_attr = (0o100000 | mode) << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        with open(path, 'rb') as f:
            z.writestr(info, f.read())
print(out)
