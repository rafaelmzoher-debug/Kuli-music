# Pone una firma fija en las compilaciones de GitHub, para poder ACTUALIZAR las apps sin desinstalar.
import glob, os, re, shutil, subprocess, sys

raiz = os.getcwd()
ks = os.path.join(raiz, "debug.keystore")
if not os.path.exists(ks):
    sys.exit("Falta debug.keystore en esta carpeta (sube los dos archivos al Codespace).")
os.makedirs(os.path.join(raiz, ".kuli"), exist_ok=True)
shutil.copy(ks, os.path.join(raiz, ".kuli", "debug.keystore"))

cambiados = 0
for f in glob.glob(os.path.join(raiz, ".github", "workflows", "*.y*ml")):
    t = open(f, encoding="utf-8").read()
    if "kuli/debug.keystore" in t:
        print("Ya estaba:", f); continue
    lineas = t.split("\n")
    i = next((k for k, l in enumerate(lineas) if "assembleDebug" in l), None)
    if i is None:
        continue
    j = i
    while j > 0 and not re.match(r"\s*- ", lineas[j]):
        j -= 1
    sangria = re.match(r"(\s*)- ", lineas[j]).group(1)
    nuevo = [sangria + "- name: Firma fija (para poder actualizar sin desinstalar)",
             sangria + "  run: mkdir -p ~/.android && cp .kuli/debug.keystore ~/.android/debug.keystore"]
    lineas[j:j] = nuevo
    open(f, "w", encoding="utf-8").write("\n".join(lineas))
    print("Arreglado:", f); cambiados += 1

if cambiados == 0:
    print("No he encontrado dónde ponerlo. Mándame una captura del archivo en .github/workflows")
else:
    subprocess.run("git add .kuli .github && git commit -m 'Firma fija para actualizar sin desinstalar' && git pull --rebase --autostash origin main && git push origin HEAD", shell=True)
