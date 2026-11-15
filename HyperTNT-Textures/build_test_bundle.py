#!/usr/bin/env python3
"""Create the clearly labelled *item-only* test bundle without server state files."""
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo
import json
import re

root = Path(__file__).resolve().parent
project = root.parent
mapping = root / 'bedrock/custom_mappings/hypertnt-items.json'
pack = root / 'HyperTNT-Items.mcpack'
configs = project / 'HyperTNT/tnts'
expected = {
    'tnt_basic': 710101,
    'tnt_fire': 710102,
    'tnt_obs': 710103,
    'tnt_heavy': 710104,
    'tnt_explosive': 710105,
    'tnt_poseidon': 710106,
}
entries = json.loads(mapping.read_text(encoding='utf-8'))['items']['minecraft:tnt']
assert {x['custom_model_data'] for x in entries} == set(expected.values())
files = {
    'ПРОЧТИ-МЕНЯ.txt': (root / 'TEST-INSTALL.txt').read_bytes(),
    'bedrock/packs/HyperTNT-Items.mcpack': pack.read_bytes(),
    'bedrock/custom_mappings/hypertnt-items.json': mapping.read_bytes(),
}
for name, model in expected.items():
    data = (configs / (name + '.yml')).read_bytes()
    assert len(re.findall(rb'(?m)^\s*custom_model_data:\s*' + str(model).encode() + rb'\s*$', data)) == 1
    files[f'plugins/HyperTNT/tnts/{name}.yml'] = data

out = root / 'HyperTNT-Bedrock-ITEMS-TEST.zip'
with ZipFile(out, 'w', compression=ZIP_DEFLATED) as archive:
    for name, data in sorted(files.items()):
        info = ZipInfo(name, (2020, 1, 1, 0, 0, 0))
        info.compress_type = ZIP_DEFLATED
        info.external_attr = 0o100644 << 16
        archive.writestr(info, data)
with ZipFile(out) as archive:
    assert archive.testzip() is None
    assert set(archive.namelist()) == set(files)
print(f'{out.name}: {len(files)} files; item textures only, no server data files')
