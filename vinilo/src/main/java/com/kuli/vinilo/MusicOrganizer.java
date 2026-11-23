
package com.kuli.vinilo;

import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MusicOrganizer - Escanea /Download, limpia nombres, lee género ID3
 * y mueve los archivos MP3/M4A a las subcarpetas de estilo dentro de
 * /Music/KULI MUSIC/.
 *
 * Requiere MANAGE_EXTERNAL_STORAGE concedido manualmente en Ajustes.
 * Diseñado para uso privado en un solo dispositivo.
 */
public class MusicOrganizer {

    private static final String TAG = "KuliMusicOrganizer";

    // Rutas absolutas solicitadas
    public static final String DOWNLOAD_DIR = "/storage/emulated/0/Download";
    public static final String BASE_MUSIC_DIR = "/storage/emulated/0/Music/KULI MUSIC";

    // Subcarpetas de estilo
    public static final String STYLE_DANCE_REMIX = "Dance-Remix";
    public static final String STYLE_FLAMENCO_RUMBA = "Flamenco-Rumba";
    public static final String STYLE_LATINO = "Latino";
    public static final String STYLE_POP_ESPANOL = "Pop-Espanol";
    public static final String STYLE_CLASICOS_80_90 = "Clasicos-80-90";
    public static final String STYLE_VARIOS = "Varios";

    // Lista maestra de subcarpetas
    public static final String[] ALL_STYLES = {
            STYLE_DANCE_REMIX,
            STYLE_FLAMENCO_RUMBA,
            STYLE_LATINO,
            STYLE_POP_ESPANOL,
            STYLE_CLASICOS_80_90,
            STYLE_VARIOS
    };

    // Extensiones admitidas
    private static final String[] AUDIO_EXTENSIONS = {".mp3", ".m4a"};

    // -------------------------------------------------------------------------
    // Patrones para limpiar el nombre del archivo
    // -------------------------------------------------------------------------

    /**
     * Limpia el nombre base de un archivo de audio eliminando:
     * - Etiquetas entre corchetes: [HQ], [Official Video], [320kbps], etc.
     * - Etiquetas entre paréntesis: (Official Video), (Lyric Video), etc.
     * - Prefijos/sufijos comunes de sitios de descarga.
     * - Espacios y guiones bajos redundantes.
     *
     * @param rawName Nombre original sin extensión
     * @return Nombre limpio, sin caracteres problemáticos para el sistema de archivos
     */
    public static String cleanFileName(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            return "Unknown";
        }

        String cleaned = rawName;

        // 1. Eliminar corchetes y su contenido: [HQ], [Official Video], etc.
        cleaned = cleaned.replaceAll("\\[[^\\]]*\\]", " ");

        // 2. Eliminar paréntesis y su contenido: (Official Video), (Audio), etc.
        cleaned = cleaned.replaceAll("\\([^)]*\\)", " ");

        // 3. Eliminar palabras clave comunes que a veces van sin paréntesis
        String[] noiseWords = {
                "official video", "official audio", "official music video",
                "lyric video", "lyrics", "hq", "hd", "320kbps", "128kbps",
                "remastered", "remaster", "video oficial", "audio oficial",
                "videoclip", "videoclip oficial", "full hd", "4k"
        };
        for (String noise : noiseWords) {
            // Coincidencia sin distinguir mayúsculas/minúsculas, rodeada de espacios o extremos
            cleaned = cleaned.replaceAll("(?i)\\b" + Pattern.quote(noise) + "\\b", " ");
        }

        // 4. Eliminar prefijos comunes de sitios de descarga (ej: "y2mate.com - ")
        cleaned = cleaned.replaceAll("(?i)^(?:www\\.)?[a-z0-9]+\\.(?:com|net|org|es)\\s*[-–_]\\s*", "");

        // 5. Eliminar guiones bajos y convertir a espacios
        cleaned = cleaned.replace('_', ' ');

        // 6. Colapsar múltiples espacios en uno
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        // 7. Eliminar caracteres no válidos para nombres de archivo en Android/Linux
        //    Solo se permiten letras, números, espacios, guiones, puntos y algunos acentos.
        //    Se preservan letras acentuadas y ñ para el español.
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|]", "");

        // 8. Si quedó vacío o solo con guiones/espacios, devolver "Unknown"
        cleaned = cleaned.replaceAll("^[-\\s]+|[-\\s]+$", "").trim();
        if (cleaned.isEmpty()) {
            return "Unknown";
        }

        return cleaned;
    }

    // -------------------------------------------------------------------------
    // Detección de estilo a partir de metadatos ID3 o palabras clave
    // -------------------------------------------------------------------------

    /**
     * Determina el estilo musical de un archivo de audio.
     * Primero intenta leer el género ID3 con MediaMetadataRetriever.
     * Si falla o el género no está mapeado, usa palabras clave del
     * nombre del archivo y del artista.
     *
     * @param audioFile Archivo de audio
     * @param artist    Artista extraído de metadatos (puede ser null)
     * @param title     Título extraído de metadatos (puede ser null)
     * @return Una de las constantes STYLE_*
     */
    public static String detectStyle(File audioFile, String artist, String title) {
        String genre = null;
        MediaMetadataRetriever retriever = null;

        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(audioFile.getAbsolutePath());
            genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE);
        } catch (Exception e) {
            Log.w(TAG, "No se pudo leer género ID3 de: " + audioFile.getName(), e);
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (IOException e) {
                    // Ignorar
                }
            }
        }

        // 1. Intentar mapear el género ID3
        if (genre != null && !genre.trim().isEmpty()) {
            String mapped = mapGenreToStyle(genre);
            if (mapped != null) {
                Log.d(TAG, "Estilo por ID3 ('" + genre + "'): " + mapped);
                return mapped;
            }
        }

        // 2. Fallback: palabras clave en nombre de archivo, artista y título
        StringBuilder searchText = new StringBuilder();
        searchText.append(audioFile.getName().toLowerCase(Locale.ROOT)).append(" ");
        if (artist != null) searchText.append(artist.toLowerCase(Locale.ROOT)).append(" ");
        if (title != null) searchText.append(title.toLowerCase(Locale.ROOT));

        String text = searchText.toString();
        String style = detectStyleByKeywords(text);
        Log.d(TAG, "Estilo por palabras clave: " + style + " (texto: " + text + ")");
        return style;
    }

    /**
     * Mapea un string de género ID3 a una de las subcarpetas.
     * Devuelve null si no hay coincidencia clara.
     */
    private static String mapGenreToStyle(String genre) {
        String g = genre.toLowerCase(Locale.ROOT).trim();

        // Dance / Remix / Electrónica
        if (g.contains("dance") || g.contains("remix") || g.contains("electronic")
                || g.contains("house") || g.contains("techno") || g.contains("edm")
                || g.contains("club")) {
            return STYLE_DANCE_REMIX;
        }

        // Flamenco / Rumba
        if (g.contains("flamenco") || g.contains("rumba") || g.contains("sevillana")
                || g.contains("copla") || g.contains("tango")) {
            return STYLE_FLAMENCO_RUMBA;
        }

        // Latino / Reggaeton / Salsa / Bachata
        if (g.contains("latino") || g.contains("latin") || g.contains("reggaeton")
                || g.contains("salsa") || g.contains("bachata") || g.contains("merengue")
                || g.contains("cumbia") || g.contains("reggae")) {
            return STYLE_LATINO;
        }

        // Pop Español
        if (g.contains("pop") && (g.contains("espa") || g.contains("spanish"))) {
            return STYLE_POP_ESPANOL;
        }
        if (g.contains("pop espanol") || g.contains("pop español")) {
            return STYLE_POP_ESPANOL;
        }

        // Clásicos 80-90
        if (g.contains("80") || g.contains("90") || g.contains("eighties")
                || g.contains("nineties") || g.contains("classic") || g.contains("clasico")
                || g.contains("oldies") || g.contains("retro")) {
            return STYLE_CLASICOS_80_90;
        }

        // Géneros genéricos de pop que podrían ser españoles pero no se puede confirmar
        // se dejan para el fallback por palabras clave

        return null;
    }

    /**
     * Detecta estilo por palabras clave en texto (nombre de archivo + artista + título).
     */
    private static String detectStyleByKeywords(String text) {
        // Dance-Remix
        if (text.contains("remix") || text.contains("dance") || text.contains("extended mix")
                || text.contains("club mix") || text.contains("dj ")) {
            return STYLE_DANCE_REMIX;
        }

        // Flamenco-Rumba
        if (text.contains("flamenco") || text.contains("rumba") || text.contains("sevillana")
                || text.contains("camaron") || text.contains("paco de lucia")
                || text.contains("lola flores") || text.contains("rosario flores")
                || text.contains("los chunguitos") || text.contains("ketama")) {
            return STYLE_FLAMENCO_RUMBA;
        }

        // Latino
        if (text.contains("reggaeton") || text.contains("salsa") || text.contains("bachata")
                || text.contains("merengue") || text.contains("cumbia")
                || text.contains("daddy yankee") || text.contains("don omar")
                || text.contains("bad bunny") || text.contains("j balvin")
                || text.contains("maluma") || text.contains("shakira")
                || text.contains("karol g") || text.contains("ozuna")
                || text.contains("anuel") || text.contains("farruko")) {
            return STYLE_LATINO;
        }

        // Pop Español
        if (text.contains("pop espanol") || text.contains("pop español")
                || text.contains("alejandro sanz") || text.contains("pablo alboran")
                || text.contains("pablo alborán") || text.contains("malú") || text.contains("malu")
                || text.contains("vanesa martin") || text.contains("vanesa martín")
                || text.contains("manuel carrasco") || text.contains("lola indigo")
                || text.contains("aido") || text.contains("rozalen") || text.contains("rozalén")
                || text.contains("vetusta morla") || text.contains("love of lesbian")) {
            return STYLE_POP_ESPANOL;
        }

        // Clásicos 80-90
        if (text.contains("80s") || text.contains("90s") || text.contains("80's")
                || text.contains("90's") || text.contains("clasicos") || text.contains("clásicos")
                || text.contains("mecano") || text.contains("hombres g")
                || text.contains("duncan dhu") || text.contains("el ultimo de la fila")
                || text.contains("el último de la fila") || text.contains("radio futura")
                || text.contains("alaska") || text.contains("nacha pop")
                || text.contains("loquillo") || text.contains("gabinete caligari")) {
            return STYLE_CLASICOS_80_90;
        }

        // Por defecto
        return STYLE_VARIOS;
    }

    // -------------------------------------------------------------------------
    // Operaciones de archivo
    // -------------------------------------------------------------------------

    /**
     * Crea la carpeta base y todas las subcarpetas de estilo si no existen.
     * @return true si la estructura está lista, false si hubo error.
     */
    public static boolean ensureFolderStructure() {
        File baseDir = new File(BASE_MUSIC_DIR);
        if (!baseDir.exists()) {
            boolean created = baseDir.mkdirs();
            if (!created && !baseDir.exists()) {
                Log.e(TAG, "No se pudo crear la carpeta base: " + BASE_MUSIC_DIR);
                return false;
            }
        }

        for (String style : ALL_STYLES) {
            File styleDir = new File(baseDir, style);
            if (!styleDir.exists()) {
                boolean created = styleDir.mkdirs();
                if (!created && !styleDir.exists()) {
                    Log.e(TAG, "No se pudo crear la subcarpeta: " + styleDir.getAbsolutePath());
                    return false;
                }
            }
        }

        Log.d(TAG, "Estructura de carpetas verificada en: " + BASE_MUSIC_DIR);
        return true;
    }

    /**
     * Escanea /Download en busca de archivos MP3/M4A, los clasifica y mueve
     * a la subcarpeta correspondiente dentro de /Music/KULI MUSIC/.
     *
     * @return Número de archivos movidos exitosamente.
     */
    public static int organizeDownloads() {
        if (!ensureFolderStructure()) {
            Log.e(TAG, "No se pudo asegurar la estructura de carpetas. Abortando.");
            return 0;
        }

        File downloadDir = new File(DOWNLOAD_DIR);
        if (!downloadDir.exists() || !downloadDir.isDirectory()) {
            Log.e(TAG, "La carpeta de descargas no existe: " + DOWNLOAD_DIR);
            return 0;
        }

        File[] files = downloadDir.listFiles();
        if (files == null || files.length == 0) {
            Log.d(TAG, "No hay archivos en Download.");
            return 0;
        }

        int movedCount = 0;
        List<File> audioFiles = new ArrayList<>();

        // Filtrar solo archivos de audio admitidos
        for (File file : files) {
            if (file.isFile() && isAudioFile(file)) {
                audioFiles.add(file);
            }
        }

        Log.d(TAG, "Archivos de audio encontrados en Download: " + audioFiles.size());

        for (File audioFile : audioFiles) {
            try {
                // Leer metadatos para género, artista y título
                String genre = null;
                String artist = null;
                String title = null;
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(audioFile.getAbsolutePath());
                    genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE);
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                } catch (Exception e) {
                    Log.w(TAG, "Error leyendo metadatos de: " + audioFile.getName(), e);
                } finally {
                    try {
                        retriever.release();
                    } catch (IOException e) {
                        // Ignorar
                    }
                }

                // Determinar estilo
                String style = detectStyle(audioFile, artist, title);

                // Determinar nombre limpio del archivo
                String originalName = audioFile.getName();
                String extension = "";
                int dotIndex = originalName.lastIndexOf('.');
                if (dotIndex > 0) {
                    extension = originalName.substring(dotIndex);
                }
                String nameWithoutExt = dotIndex > 0
                        ? originalName.substring(0, dotIndex)
                        : originalName;

                String cleanedBase = cleanFileName(nameWithoutExt);
                String newFileName = cleanedBase + extension;

                // Construir destino
                File styleDir = new File(BASE_MUSIC_DIR, style);
                File destination = new File(styleDir, newFileName);

                // Evitar sobrescribir: si existe, añadir sufijo numérico
                int suffix = 1;
                while (destination.exists()) {
                    String suffixedName = cleanedBase + " (" + suffix + ")" + extension;
                    destination = new File(styleDir, suffixedName);
                    suffix++;
                }

                // Mover archivo (renameTo es atómico en el mismo sistema de archivos;
                // si falla, se intenta copiar+borrar como fallback)
                boolean moved = audioFile.renameTo(destination);

                if (!moved) {
                    // Fallback: copiar y borrar
                    Log.w(TAG, "renameTo falló para: " + originalName + ". Intentando copia manual.");
                    moved = copyFile(audioFile, destination);
                    if (moved) {
                        if (!audioFile.delete()) {
                            Log.w(TAG, "No se pudo borrar el original tras copiar: " + originalName);
                        }
                    }
                }

                if (moved) {
                    movedCount++;
                    Log.i(TAG, "Movido: " + originalName + " -> " + style + "/" + destination.getName());
                } else {
                    Log.e(TAG, "No se pudo mover: " + originalName);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error procesando archivo: " + audioFile.getName(), e);
            }
        }

        Log.i(TAG, "Organización completada. Archivos movidos: " + movedCount);
        return movedCount;
    }

    /**
     * Verifica si un archivo tiene extensión de audio admitida.
     */
    private static boolean isAudioFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        for (String ext : AUDIO_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copia un archivo usando streams de Java IO estándar.
     * @return true si la copia fue exitosa.
     */
    private static boolean copyFile(File source, File destination) {
        java.io.FileInputStream fis = null;
        java.io.FileOutputStream fos = null;
        try {
            fis = new java.io.FileInputStream(source);
            fos = new java.io.FileOutputStream(destination);
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            fos.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error copiando archivo: " + source.getName(), e);
            // Si la copia falló a medias, borrar el destino incompleto
            if (destination.exists()) {
                destination.delete();
            }
            return false;
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (IOException ignored) { }
            }
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) { }
            }
        }
    }

    /**
     * Devuelve la lista de archivos de audio en una subcarpeta de estilo.
     * Útil para que MainActivity construya la lista de reproducción.
     *
     * @param styleName Nombre de la subcarpeta (una de las constantes STYLE_*)
     * @return Array de archivos de audio, ordenados alfabéticamente por nombre.
     *         Nunca null; puede estar vacío.
     */
    public static File[] getTracksForStyle(String styleName) {
        File styleDir = new File(BASE_MUSIC_DIR, styleName);
        if (!styleDir.exists() || !styleDir.isDirectory()) {
            return new File[0];
        }

        File[] files = styleDir.listFiles();
        if (files == null) {
            return new File[0];
        }

        List<File> tracks = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && isAudioFile(file)) {
                tracks.add(file);
            }
        }

        // Ordenar alfabéticamente por nombre (ignorando mayúsculas)
        tracks.sort((a, b) ->
                a.getName().toLowerCase(Locale.ROOT)
                        .compareTo(b.getName().toLowerCase(Locale.ROOT)));

        return tracks.toArray(new File[0]);
    }

    /**
     * Lee los metadatos ID3 de un archivo de audio y los devuelve en un array
     * de tres elementos: [0]=título, [1]=artista, [2]=género.
     * Los valores pueden ser null si el archivo no los contiene.
     */
    public static String[] readMetadata(File audioFile) {
        String[] result = new String[3];
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(audioFile.getAbsolutePath());
            result[0] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            result[1] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            result[2] = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE);
        } catch (Exception e) {
            Log.w(TAG, "Error leyendo metadatos de: " + audioFile.getName(), e);
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {
                // Ignorar
            }
        }
        return result;
    }
}
