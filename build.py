#!/usr/bin/env python3
"""Pinned standalone Android build: Java 17+, Python 3 and keytool; Linux x86_64 aapt2.
No Gradle, handcrafted DEX, or platform classes bundled in the APK.
"""
from pathlib import Path
import argparse,hashlib,json,os,shutil,struct,subprocess,urllib.request,zipfile
ROOT=Path(__file__).resolve().parent
parser=argparse.ArgumentParser(description='Build AudioFx Auto; no signing key is included in this repository.')
parser.add_argument('--keystore',default=os.environ.get('AUDIOFX_KEYSTORE'),help='Your own PKCS12 signing key; otherwise output an unsigned APK')
args=parser.parse_args()
if args.keystore and not os.environ.get('AUDIOFX_STORE_PASSWORD'):
    raise SystemExit('Set AUDIOFX_STORE_PASSWORD before building with --keystore')
BUILD=ROOT/'.build';TOOLS=BUILD/'tools';TOOLS.mkdir(parents=True,exist_ok=True)
def run(args):subprocess.run([str(x) for x in args],check=True)
lock=json.loads((ROOT/'dependencies.lock.json').read_text())
for name,meta in lock.items():
    p=TOOLS/name
    if not p.exists():
        with urllib.request.urlopen(meta['url'],timeout=90) as r:p.write_bytes(r.read())
    if hashlib.sha256(p.read_bytes()).hexdigest()!=meta['sha256']:raise SystemExit('Hash mismatch: '+name)
for name in ['api','provider','aidl','shared']:
    with zipfile.ZipFile(TOOLS/f'shizuku-{name}.aar') as z:(TOOLS/f'shizuku-{name}.jar').write_bytes(z.read('classes.jar'))
with zipfile.ZipFile(TOOLS/'aapt2-linux.jar') as z:(TOOLS/'aapt2').write_bytes(z.read('aapt2'))
(TOOLS/'aapt2').chmod(0o755)
for name in ['classes','generated','dex','signer']:
    p=BUILD/name
    if p.exists():shutil.rmtree(p)
    p.mkdir(parents=True)
run([TOOLS/'aapt2','compile','--dir',ROOT/'res','-o',BUILD/'resources.zip'])
run([TOOLS/'aapt2','link','-I',TOOLS/'android-all.jar','--manifest',ROOT/'AndroidManifest.xml',
     '--java',BUILD/'generated','--auto-add-overlay','-o',BUILD/'resources.apk',BUILD/'resources.zip'])
libs=[TOOLS/f'shizuku-{name}.jar' for name in ['api','provider','aidl','shared']]+[TOOLS/'annotation.jar']
cp=os.pathsep.join(str(x) for x in [TOOLS/'android-all.jar',*libs])
sources=sorted((ROOT/'src').rglob('*.java'))+sorted((BUILD/'generated').rglob('*.java'))
run(['java','-jar',TOOLS/'ecj.jar','-8','-encoding','UTF-8','-classpath',cp,'-d',BUILD/'classes',*sources])
run(['java','-cp',TOOLS/'r8.jar','com.android.tools.r8.D8','--min-api','26','--lib',TOOLS/'android-all.jar',
     '--output',BUILD/'dex',*sorted((BUILD/'classes').rglob('*.class')),*libs])
unsigned=BUILD/'unsigned.apk'
# Repack while aligning every uncompressed entry to 4 bytes, before APK signing.
with zipfile.ZipFile(BUILD/'resources.apk') as src,zipfile.ZipFile(unsigned,'w') as dst:
    for info in src.infolist():
        data=src.read(info.filename)
        extra=info.extra
        if info.compress_type==zipfile.ZIP_STORED:
            base=dst.fp.tell()+30+len(info.filename.encode('utf-8'))+len(extra)+4
            pad=(-base)%4
            info.extra=extra+struct.pack('<HH',0xcafe,pad)+b'\0'*pad
        dst.writestr(info,data)
    for p in sorted((BUILD/'dex').glob('classes*.dex')):
        dst.write(p,p.name,compress_type=zipfile.ZIP_DEFLATED)
dist=ROOT/'dist';dist.mkdir(exist_ok=True)
if args.keystore:
    key=Path(args.keystore).expanduser().resolve()
    if not key.is_file():raise SystemExit('Specified keystore does not exist')
    run(['java','-jar',TOOLS/'ecj.jar','-8','-classpath',TOOLS/'apksig.jar','-d',BUILD/'signer',ROOT/'build-tools/SignApk.java'])
    apk=dist/'AudioFxAuto_v64.apk'
    run(['java','-cp',os.pathsep.join([str(BUILD/'signer'),str(TOOLS/'apksig.jar')]),'SignApk',key,unsigned,apk])
else:
    apk=dist/'AudioFxAuto_v64-unsigned.apk';shutil.copyfile(unsigned,apk)
    print('UNSIGNED: not installable until signed with your own key. See docs/BUILD.md.')
print('APK:',apk)
print('SHA256:',hashlib.sha256(apk.read_bytes()).hexdigest())
