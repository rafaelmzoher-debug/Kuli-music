# Mete la firma fija DENTRO de cada app (no depende de GitHub Actions), para poder actualizar sin desinstalar.
import os, shutil, subprocess, sys
raiz = os.getcwd()
ks = os.path.join(raiz, "debug.keystore")
if not os.path.exists(ks):
    sys.exit("Falta debug.keystore en esta carpeta.")
os.makedirs(os.path.join(raiz, ".kuli"), exist_ok=True)
shutil.copy(ks, os.path.join(raiz, ".kuli", "debug.keystore"))
bloque = (
    "    signingConfigs {\n"
    "        getByName(\"debug\") {\n"
    "            storeFile = rootProject.file(\".kuli/debug.keystore\")\n"
    "            storePassword = \"android\"\n"
    "            keyAlias = \"androiddebugkey\"\n"
    "            keyPassword = \"android\"\n"
    "        }\n"
    "    }\n")
for m in ["app", "fotos", "familia", "ordenes"]:
    f = os.path.join(raiz, m, "build.gradle.kts")
    if not os.path.exists(f):
        print("No existe", f); continue
    t = open(f, encoding="utf-8").read()
    if "kuli/debug.keystore" in t:
        print("Ya estaba:", m); continue
    lineas = t.split("\n")
    i = next((k for k, l in enumerate(lineas) if l.strip() == "android {"), None)
    if i is None:
        print("No he encontrado 'android {' en", m); continue
    lineas.insert(i + 1, bloque.rstrip("\n"))
    open(f, "w", encoding="utf-8").write("\n".join(lineas))
    print("Arreglado:", m)
subprocess.run("git add -f .kuli && git add app fotos familia ordenes && git commit -m 'Firma fija dentro de cada app' ; git pull --rebase --autostash origin main && git push origin HEAD", shell=True)
print("\nFirma que usan las apps:")
subprocess.run("git ls-files .kuli", shell=True)
