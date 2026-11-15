#!/usr/bin/env python3
"""Build the Bedrock item pack without overriding vanilla TNT textures."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import json

root = Path(__file__).resolve().parent
rp = root / 'bedrock' / 'resource_pack'
mapping_path = root / 'bedrock' / 'custom_mappings' / 'hypertnt-items.json'
manifest = json.loads((rp / 'manifest.json').read_text(encoding='utf8'))
atlas = json.loads((rp / 'textures' / 'item_texture.json').read_text(encoding='utf8'))
mapping = json.loads(mapping_path.read_text(encoding='utf8'))
assert manifest['modules'][0]['type'] == 'resources'
assert set(mapping['items']) == {'minecraft:tnt'}
items = mapping['items']['minecraft:tnt']
assert len(items) == 6 and len({item['custom_model_data'] for item in items}) == 6
for item in items:
    assert item['custom_model_data'] > 0 and item['icon'] in atlas['texture_data']
    png = rp / (atlas['texture_data'][item['icon']]['textures'] + '.png')
    assert png.read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
assert 'tnt' not in atlas['texture_data'] and 'minecraft:tnt' not in atlas['texture_data']
assert not any(p.name in {'tnt.png', 'tnt_side.png', 'tnt_top.png', 'tnt_bottom.png'} for p in rp.rglob('*'))
# These are reference assets only; the pack does not yet register placed blocks.
for name in ('power', 'blaze', 'deepslate', 'mega', 'super', 'water'):
    geometry = json.loads((rp / 'models' / 'blocks' / f'{name}_tnt.geo.json').read_text(encoding='utf8'))
    assert geometry['minecraft:geometry'][0]['description']['identifier'] == f'geometry.minty_utnt.{name}_tnt'
    png = rp / 'textures' / 'minty' / 'utnt' / 'blocks' / f'{name}_tnt.png'
    assert png.read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
out = root / 'HyperTNT-Items.mcpack'
with ZipFile(out, 'w', ZIP_DEFLATED) as pack:
    for file in sorted(rp.rglob('*')):
        if file.is_file(): pack.write(file, file.relative_to(rp).as_posix())
with ZipFile(out) as pack:
    assert pack.testzip() is None
    assert 'manifest.json' in pack.namelist()
print(f'Created {out.name}: {len(items)} item textures, no vanilla texture overrides')
