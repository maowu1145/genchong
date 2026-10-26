"""Read-only payload checks; writes only its JSON verification report. Requires Pillow."""
from pathlib import Path
from PIL import Image
import hashlib
import json
import argparse

REPO = Path(__file__).resolve().parents[2]
ASSETS = REPO / 'app/src/main/assets'
HERE = Path(__file__).resolve().parent
root = json.loads((ASSETS/'pets/catalog.json').read_text('utf-8'))
trace = json.loads((HERE/'source-to-runtime.json').read_text('utf-8'))
parser = argparse.ArgumentParser()
parser.add_argument('--source-root', default=trace['sourceRoot'])
source_root = Path(parser.parse_args().source_root)
records = {r['runtimePath']: r for r in trace['records']}
entries = root['characters']
expected = {'doro': 5, 'grudge_panda': 6, 'spinning_cat': 2, 'sweat_emoji': 8,
            'kinship_ride': 3, 'thursday_bucket_cat': 5}
assert root['schemaVersion'] == 1
assert root['completedCharacterCount'] == len(entries) == 6
assert {e['id'] for e in entries} == set(expected)
all_paths = set()
animation_results = []
for entry in entries:
    cid = entry['id']
    assert len(entry['states']) == expected[cid]
    assert entry['idle'] in entry['states'].values()
    assert entry['functional']
    assert entry['anchor'] == {'x': 0.5, 'y': 0.890625}
    for state, path in entry['states'].items():
        assert path.startswith(f'pets/{cid}/')
        assert entry['stateAnchors'][state] == records[path]['anchor']
        assert records[path]['canvasSize'] == [512,512]
        all_paths.add(path)
    frames = entry['animationFrames']
    if frames:
        assert cid in ('spinning_cat', 'kinship_ride') and len(frames) == 8
        assert entry['animationLoop'] is True
        assert entry['animationCanvas'] == {'width':256, 'height':256}
        cat = cid == 'spinning_cat'
        assert entry['frameDurationMs'] == (40 if cat else 100)
        assert entry['animationAnchorY'] == (216/256 if cat else 230/256)
        assert entry['animationStates'] == (['idle'] if cat else ['idle','calculating'])
        bottoms, stable_bands = [], []
        for i, path in enumerate(frames):
            assert path == f"pets/{cid}/animation/{'spin' if cat else 'rock'}_{i:02}.png"
            assert records[path]['canvasSize'] == [256,256]
            assert records[path]['anchor'] == {'x':.5, 'y':entry['animationAnchorY']}
            with Image.open(ASSETS/path) as image:
                alpha = image.getchannel('A')
                solid = alpha.point(lambda a: 255 if a >= 128 else 0)
                bottoms.append(solid.getbbox()[3])
                stable_bands.append(image.crop((0,190,256,256)).tobytes())
            all_paths.add(path)
        if cat:
            # Source rasterization rounds the visible solid edge to 215 or 216;
            # the approved registration anchor remains fixed at y=216 for all frames.
            assert bottoms == [records[p]['provenance']['sourcePreparation']['bottom'] for p in frames]
            assert max(bottoms)-min(bottoms) <= 1
        else:
            assert len(set(stable_bands)) == 1
        animation_results.append(dict(id=cid,frameCount=8,cycleMs=8*entry['frameDurationMs'],
            solidAlphaBottoms=bottoms,stableBaseBand=True if not cat else None))

actual = {p.relative_to(ASSETS).as_posix() for p in (ASSETS/'pets').rglob('*.png')}
assert all_paths == actual == set(records) and len(all_paths) == 45
assert {p.suffix for p in (ASSETS/'pets').rglob('*') if p.is_file()} == {'.json','.png'}
assert len(list((ASSETS/'pets').rglob('*.json'))) == 1
assert not any(any(t in p for t in ('mask','reference','sheet','atlas')) for p in actual)
total_bytes = decoded_bytes = partial_pixels = 0
for path in sorted(all_paths):
    target = ASSETS/path
    record = records[path]
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    assert digest == record['sha256'] == record['runtimeSha256']
    assert digest == hashlib.sha256((source_root/record['sourcePath']).read_bytes()).hexdigest()
    with Image.open(target) as image:
        assert image.mode == 'RGBA'
        assert list(image.size) == record['canvasSize']
        alpha = image.getchannel('A')
        h = alpha.histogram()
        assert h[0] and h[255] and sum(h[1:255])
        assert list(alpha.getbbox()) == record['visibleBounds']
        assert all(alpha.crop(b).getextrema() == (0,0) for b in
            [(0,0,image.width,1),(0,image.height-1,image.width,image.height),
             (0,0,1,image.height),(image.width-1,0,image.width,image.height)])
        partial_pixels += sum(h[1:255])
        decoded_bytes += image.width*image.height*4
    total_bytes += target.stat().st_size
assert decoded_bytes < 40*1024*1024
pending = json.loads((HERE/'pending-30.json').read_text('utf-8'))
assert pending['pendingCount'] == len(pending['candidates']) == 30
assert len({e['id'] for e in pending['candidates']}) == 30
assert all(e['runtimeRegistered'] is False and e['productionStatus']=='not_started' for e in pending['candidates'])
assert not set(expected).intersection(e['id'] for e in pending['candidates'])
report = dict(result='PASS',method='Python/Pillow file and metadata checks; no Android/device claims',
    completedCharacters=6,pendingCharacters=30,staticStatePngs=29,animationPngs=16,totalPngs=45,
    pngBytes=total_bytes,argb8888DecodedBytes=decoded_bytes,bitmapCacheLimitBytes=40*1024*1024,
    sourceHashesMatch=True,exactRegisteredPayload=True,rgbaAndSoftAlpha=True,
    semitransparentPixels=partial_pixels,transparentBorders=True,animationChecks=animation_results,
    exclusions=['No Android device rendering or memory measurement','No APK build in delegated task',
                'Reference/art release status remains pending; technical pass is not release clearance'])
(HERE/'technical-validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n','utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
