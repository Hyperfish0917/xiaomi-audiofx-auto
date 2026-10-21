#!/usr/bin/env python3
"""Pure JVM policy tests; no Android DSP is simulated or asserted."""
from pathlib import Path
import hashlib,os,subprocess,urllib.request
root=Path(__file__).resolve().parent.parent
work=root/'.build'/'policy-tests';work.mkdir(parents=True,exist_ok=True)
j=work/'json.jar';expected='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not j.exists():
 with urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',timeout=60) as r:j.write_bytes(r.read())
assert hashlib.sha256(j.read_bytes()).hexdigest()==expected,'JSON test dependency hash mismatch'
ecj=root/'.build/tools/ecj.jar'
if not ecj.exists():raise SystemExit('Run python3 build.py first to fetch the pinned ECJ compiler.')
sources=[root/'src/mi14/audiofx'/n for n in ['Config.java','EqMath.java','RoutePolicy.java','ApplyStages.java','MiSoundEq.java','DolbyEq.java','StockEq.java']]+[root/'validation/ProfileTests.java',root/'validation/RegressionTests.java',root/'validation/PersistenceTests.java',root/'validation/StockEqTests.java']
subprocess.run(['java','-jar',str(ecj),'-8','-encoding','UTF-8','-classpath',str(j),'-d',str(work),*[str(p) for p in sources]],check=True)
subprocess.run(['java','-cp',os.pathsep.join([str(work),str(j)]),'ProfileTests'],check=True)

subprocess.run(['java','-cp',os.pathsep.join([str(work),str(j)]),'RegressionTests'],check=True)

subprocess.run(['java','-cp',os.pathsep.join([str(work),str(j)]),'PersistenceTests'],check=True)

subprocess.run(['java','-cp',os.pathsep.join([str(work),str(j)]),'StockEqTests'],check=True)

page_sources=[root/'src/mi14/audiofx/eqpage'/n for n in ['EqMath.java','PageStability.java','PageSyncRequest.java']]+[root/'validation/PageSyncTests.java',root/'validation/MathFixtureTest.java']
classpath=os.pathsep.join([str(work),str(j)])
subprocess.run(['java','-jar',str(ecj),'-8','-encoding','UTF-8','-classpath',classpath,'-d',str(work),*[str(p) for p in page_sources]],check=True)
for test in ['PageSyncTests','mi14.audiofx.eqpage.MathFixtureTest']:
 subprocess.run(['java','-cp',classpath,test],check=True)
