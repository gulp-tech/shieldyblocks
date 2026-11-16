#!/usr/bin/env python3
"""Build a curated HyperTNT integration handoff from the supplied main commit."""
from hashlib import sha256
from pathlib import Path
from subprocess import check_output
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

root = Path(__file__).resolve().parent
reference = '7699a8d'
source_files = {
    'HyperTNT-2.1.0.jar': 'ce04cbcef588a92a78f83f35cde9b131d270106a8df55055199912dd603937a4',
    'HyperTNT-src.zip': '1cc0a3ce9a00e40e0368baeb3134b246126b1cd06d4c6679640c430cd2e2f1ac',
}
files = {'ПРОЧТИ-ПЕРВЫМ.md': (root / 'ИНТЕГРАЦИЯ-ДЛЯ-РАЗРАБОТЧИКА.md').read_bytes()}
for name, digest in source_files.items():
    data = check_output(['git', 'show', f'{reference}:{name}'], cwd=root.parent)
    if sha256(data).hexdigest() != digest:
        raise ValueError(f'Unexpected hash for {name}')
    files[f'plugin/{name}'] = data
for source, destination in [
    (root / 'HyperTNT-Items.mcpack', 'bedrock/HyperTNT-Items.mcpack'),
    (root / 'bedrock/custom_mappings/hypertnt-items.json', 'bedrock/custom_mappings/hypertnt-items.json'),
    (root / 'HyperTNT-Bedrock-ITEMS-TEST.zip', 'test/HyperTNT-Bedrock-ITEMS-TEST.zip'),
]:
    files[destination] = source.read_bytes()
rp = root / 'bedrock/resource_pack'
for source in sorted(rp.rglob('*')):
    if source.is_file():
        files['bedrock/resource_pack/' + source.relative_to(rp).as_posix()] = source.read_bytes()
assert not any(name.endswith(('regions.db', 'regions.yml', 'placed-tnts.txt')) for name in files)
out = root / 'HyperTNT-PurpurBedrock-developer.zip'
with ZipFile(out, 'w', compression=ZIP_DEFLATED) as archive:
    for name, data in sorted(files.items()):
        info = ZipInfo(name, (2020, 1, 1, 0, 0, 0))
        info.compress_type = ZIP_DEFLATED
        info.external_attr = 0o100644 << 16
        archive.writestr(info, data)
with ZipFile(out) as archive:
    assert archive.testzip() is None and set(archive.namelist()) == set(files)
print(f'{out.name}: {len(files)} files, sha256 {sha256(out.read_bytes()).hexdigest()}')
