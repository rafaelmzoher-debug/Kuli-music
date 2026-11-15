#!/bin/sh
set -eu
cd "$(dirname "$0")"
python3 - <<'PYCODE'
from pathlib import Path
import re
p=Path('app/src/main/AndroidManifest.xml')
if not p.exists():
    raise SystemExit('No se encuentra AndroidManifest.xml')
s=p.read_text()
if '<application' not in s:
    raise SystemExit('No se encuentra <application>')
s=re.sub(r'(<application\b[^>]*?)\sandroid:icon="[^"]*"',r'\1',s,count=1,flags=re.S)
s=re.sub(r'(<application\b[^>]*?)\sandroid:roundIcon="[^"]*"',r'\1',s,count=1,flags=re.S)
s=s.replace('<application','<application android:icon="@mipmap/kuli_icon" android:roundIcon="@mipmap/kuli_icon"',1)
p.write_text(s)
print('Icono KULI instalado en el manifiesto')
PYCODE
./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk KULI-MUSIC-ICONO-NUEVO.apk
printf '\nLISTO: KULI-MUSIC-ICONO-NUEVO.apk\n'
