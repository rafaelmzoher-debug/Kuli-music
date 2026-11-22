import re

p = "fotos/src/main/java/com/kuli/fotos/MainActivity.java"
s = open(p, encoding="utf-8").read()

patron = r'android\.widget\.Button btnGf = new android\.widget\.Button\(this\);[\s\S]*?barra\.addView\(btnGf\);'
nuevo = """android.widget.Button btnGf = new android.widget.Button(this);
        btnGf.setText("🗑 Capturas");
        btnGf.setOnClickListener(v -> borrarCapturasMovil());
        barra.addView(btnGf);"""

s2, n = re.subn(patron, nuevo, s, count=1)
assert n == 1, "No se encontró btnGf"

metodo = """    private void borrarCapturasMovil() {
        java.util.ArrayList<android.net.Uri> caps = new java.util.ArrayList<>();
        try (android.database.Cursor c = getContentResolver().query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{android.provider.MediaStore.Images.Media._ID, android.provider.MediaStore.Images.Media.DATA},
                android.provider.MediaStore.Images.Media.DATA + " LIKE ?",
                new String[]{"%Screenshot%"}, null)) {
            while (c != null && c.moveToNext()) {
                caps.add(android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
            }
        } catch (Throwable ignored) {}
        if (caps.isEmpty()) { aviso("No hay capturas de pantalla en el móvil"); return; }
        new AlertDialog.Builder(this)
            .setTitle("🗑 Borrar capturas")
            .setMessage("Se han encontrado " + caps.size() + " capturas de pantalla en el móvil. ¿Quieres borrarlas todas?")
            .setPositiveButton("BORRAR TODAS", (d, w) -> {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    try {
                        android.app.PendingIntent pi = android.provider.MediaStore.createDeleteRequest(getContentResolver(), caps);
                        startIntentSenderForResult(pi.getIntentSender(), 9555, null, 0, 0, 0);
                        return;
                    } catch (Throwable ignored) {}
                }
                int borradas = 0;
                for (android.net.Uri u : caps) {
                    try { if (getContentResolver().delete(u, null, null) > 0) borradas++; } catch (Throwable ignored) {}
                }
                aviso("Borradas " + borradas + " capturas");
            })
            .setNegativeButton("Cancelar", null).show();
    }
"""

idx = s2.rfind("}")
s2 = s2[:idx] + "\n" + metodo + "\n}\n"
open(p, "w", encoding="utf-8").write(s2)
print("PARCHE APLICADO OK")
