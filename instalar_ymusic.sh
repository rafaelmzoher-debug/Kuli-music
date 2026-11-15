#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
python3 - <<'PY'
from pathlib import Path
import re
p=Path('app/src/main/java/com/kuli/music/MainActivity.java')
s=p.read_text()
if 'ABRIR YMUSIC' not in s:
    if 'TextView firma = ' not in s:
        raise SystemExit('No encuentro el lugar para el botón. No se ha cambiado nada.')
    s=s.replace('        TextView firma = ', '''        Button ymusic = boton("♫  ABRIR YMUSIC", true);
        pantalla.addView(ymusic);
        ymusic.setOnClickListener(v -> abrirYMusic());
        espacio(pantalla, 14);

        TextView firma = ''', 1)
    marker='    private void abrirCanciones() {'
    if marker not in s:
        raise SystemExit('No encuentro abrirCanciones. No se ha cambiado nada.')
    method='''    private void abrirYMusic() {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent consulta = new Intent(Intent.ACTION_MAIN);
        consulta.addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> apps =
            pm.queryIntentActivities(consulta, 0);
        for (android.content.pm.ResolveInfo app : apps) {
            String nombre = app.loadLabel(pm).toString();
            if (nombre.equalsIgnoreCase("YMusic") ||
                nombre.toLowerCase(java.util.Locale.ROOT).contains("ymusic")) {
                Intent abrir = new Intent(Intent.ACTION_MAIN);
                abrir.addCategory(Intent.CATEGORY_LAUNCHER);
                abrir.setClassName(app.activityInfo.packageName, app.activityInfo.name);
                try {
                    startActivity(abrir);
                    return;
                } catch (Exception ignored) { }
            }
        }
        aviso("No encuentro YMusic instalada en este móvil");
    }

'''
    s=s.replace(marker, method+marker, 1)
    p.with_suffix('.java.antes_ymusic').write_text(p.read_text())
    p.write_text(s)
manifest=Path('app/src/main/AndroidManifest.xml')
m=manifest.read_text()
if 'android.intent.category.LAUNCHER' not in m.split('<application')[0]:
    q='''    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
    </queries>
'''
    if '<application' not in m:
        raise SystemExit('No encuentro <application> en AndroidManifest.xml')
    manifest.with_suffix('.xml.antes_ymusic').write_text(m)
    m=m.replace('    <application',q+'    <application',1)
    manifest.write_text(m)
print('Botón YMusic añadido. Icono y reproductor conservados.')
PY
./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk KULI-MUSIC-YMUSIC.apk
echo 'LISTO: KULI-MUSIC-YMUSIC.apk'
