package com.kuli.ordenes;

// Albarán 26/1925340 (09/11/2026, dotación inicial): lo que recogiste en el almacén de CIRCET.
final class Albaran {
    static final String ID = "26/1925340";

    // código, nombre, números de serie...
    static final String[][] CON_SERIE = {
        {"00400711", "Módulo Base 1 Fibra 360 (RTF6121)", "88FEB637A872"},
        {"00400713", "Extensor Fibra 360 (RTF6122)", "DC08DAE9AD92"},
        {"00412537", "Deco UHD Movistar+ H2", "E8B2FE07165C", "CCAB2CA74DEE", "E8B2FE032D24", "CCAB2C967FB6"},
        {"00412547", "Deco UHD Movistar+ H3", "90D092797CBC"},
        {"00412967", "Router WiFi 7 Askey (RTF8317VW-SX)", "88FEB61A9B30", "88FEB61AAF30", "88FEB61A9D50", "88FEB61A6800", "88FEB61A81B0"},
        {"00413012", "Router Smart WiFi 7 Askey (RTF8316VW)", "88FEB6228BC0", "88FEB62624E0", "88FEB6279850", "88FEB6268920", "88FEB610BEE0"},
        {"00414936", "Router AFR 5G Mitrastar", "2C9682BF7004"},
        {"00415959", "Amplificador Smart WiFi 7 Mitra", "B014DF246A40", "B014DF0E7E9C"},
    };

    // código, nombre, cantidad (sin número de serie)
    static final String[][] SIN_SERIE = {
        {"00400714", "Enfrentador FTTR", "1"},
        {"00400717", "Kit fibra FTTR 60m", "1"},
        {"00530151", "Cable exterior 4,5mm 30m", "1"},
        {"00530152", "Cable exterior 4,5mm 50m", "1"},
        {"00530153", "Cable exterior 4,5mm 80m", "1"},
        {"00530154", "Cable exterior 4,5mm 150m", "1"},
        {"00530170", "Cable interior SC/APC 30m", "2"},
        {"00530171", "Cable interior SC/APC 50m", "1"},
        {"00530172", "Cable interior SC/APC 80m", "1"},
        {"00546040", "Roseta de fibra óptica", "5"},
        {"00547876", "Etiquetas acometidas f.o. (100 uds)", "25"},
        {"00547877", "Etiquetas DataMatrix acometida", "9"},
        {"50080103", "Tarjeta SIM", "1"},
    };

    static String nombre(String codigo) {
        for (String[] f : CON_SERIE) if (f[0].equals(codigo)) return f[1];
        for (String[] f : SIN_SERIE) if (f[0].equals(codigo)) return f[1];
        return "";
    }
}
