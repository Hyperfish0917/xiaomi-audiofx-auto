#!/usr/bin/env python3
"""Review the staged/tracked publication set; never print detected secret values."""
from pathlib import Path
import hashlib,re,subprocess,sys,zipfile
ROOT=Path(__file__).resolve().parent.parent
if (ROOT/'.git').exists():
    names=subprocess.check_output(['git','ls-files','-z'],cwd=ROOT).decode().split('\0')
    paths=[ROOT/n for n in names if n]
    if not paths:raise SystemExit('Stage the intended publication files first.')
else:
    paths=[p for p in ROOT.rglob('*') if p.is_file() and not any(x in p.relative_to(ROOT).parts for x in ('.build','dist','__pycache__','.git'))]
approved_root={'README.md','PRIVACY.md','CHANGELOG.md','THIRD_PARTY_NOTICES.md','THIRD_PARTY_LICENSE_Shizuku.txt','AndroidManifest.xml','build.py','dependencies.lock.json','.gitignore'}
fixture_mac={'00:00:00:00:00:00','02:00:00:00:00:00','AA:BB:CC:DD:EE:01','AA:BB:CC:DD:EE:02','AA:BB:CC:DD:EE:03'}
mac=re.compile(rb'(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}')
email=re.compile(rb'[A-Za-z0-9_.+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}')
checks=[
 ('private_key',re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----')),
 ('github_token',re.compile(rb'gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}')),
 ('personal_absolute_path',re.compile(rb'/(?:workspace/scratch|Users|home)/[A-Za-z0-9_.-]+/')),
]
errors=[]
def inspect(name,data):
    for kind,pattern in checks:
        if pattern.search(data):errors.append((name,kind))
    if any(v.decode().upper() not in fixture_mac for v in mac.findall(data)):errors.append((name,'unapproved_address_literal'))
    if any(not v.endswith(b'@users.noreply.github.com') for v in email.findall(data)):errors.append((name,'review_email'))
for p in paths:
    rel=p.relative_to(ROOT);name=rel.as_posix()
    if p.is_symlink():errors.append((name,'symlink'));continue
    if not p.is_file():errors.append((name,'missing_file'));continue
    allowed=name in approved_root or (
        rel.parts[0] in {'src','res','build-tools','validation','docs','.github'}
        and p.suffix in {'.java','.xml','.py','.md','.yml','.yaml'}
    ) or name in {'downloads/AudioFxAuto_v64.apk','downloads/SHA256SUMS.txt','LICENSES/Apache-2.0.txt'}
    if not allowed:errors.append((name,'not_in_publication_allowlist'));continue
    if name.endswith('.apk'):
        with zipfile.ZipFile(p) as apk:
            expected={'AndroidManifest.xml','res/drawable/ic_audio.xml','resources.arsc','classes.dex'}
            if set(apk.namelist())!=expected:errors.append((name,'unexpected_apk_entries'))
            for member in apk.namelist():inspect(name+'!'+member,apk.read(member))
        sums=ROOT/'downloads/SHA256SUMS.txt'
        if not sums.exists() or hashlib.sha256(p.read_bytes()).hexdigest() not in sums.read_text():errors.append((name,'release_checksum'))
    else:inspect(name,p.read_bytes())
if errors:
    for name,kind in errors:print('REVIEW',name,kind)
    sys.exit(1)
print('PUBLICATION_PRIVACY_CHECK=PASS')
print('FILES_REVIEWED='+str(len(paths)))
print('Only approved files, synthetic addresses and public firmware compatibility identifiers are included.')
print('This scan supports manual review; it is not an absolute anonymity guarantee.')
