package com.kuli.mapas;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarAppService;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarColor;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.navigation.model.RoutingInfo;
import androidx.car.app.navigation.model.Step;
import androidx.car.app.navigation.model.TravelEstimate;
import androidx.car.app.model.Distance;
import androidx.car.app.model.DateTimeWithZone;
import androidx.car.app.validation.HostValidator;
import androidx.core.graphics.drawable.IconCompat;
import java.util.TimeZone;

public class KuliCarAppService extends CarAppService {
    @NonNull
    @Override
    public HostValidator createHostValidator() {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new Session() {
            @NonNull
            @Override
            public Screen onCreateScreen(@NonNull Intent intent) {
                return new KuliCarScreen(getCarContext());
            }
        };
    }

    static class KuliCarScreen extends Screen implements SurfaceCallback, LocationListener {
        private Surface surface;
        private int width = 800, height = 480;
        private double lat = 36.7213, lon = -4.4214;
        private float speedKmh = 0f;
        private String avisoRadar = "Sin radares cercanos · Málaga GPS";
        private final double[][] radaresMalaga = {
            {36.6975, -4.4628, 80}, {36.7165, -4.4692, 80}, {36.7382, -4.4521, 80},
            {36.7451, -4.4152, 80}, {36.7248, -4.3645, 80}, {36.6632, -4.4912, 100},
            {36.6215, -4.5124, 80}, {36.5384, -4.6291, 80}, {36.5122, -4.8854, 80},
            {36.7485, -4.0821, 100}, {36.7621, -3.8745, 100}, {37.0184, -4.5582, 120}
        };

        KuliCarScreen(@NonNull CarContext carContext) {
            super(carContext);
            try {
                carContext.getCarService(AppManager.class).setSurfaceCallback(this);
                LocationManager lm = (LocationManager) carContext.getSystemService(CarContext.LOCATION_SERVICE);
                if (lm != null) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500, 3, this);
                    Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (last != null) onLocationChanged(last);
                }
            } catch (Throwable ignored) {}
        }

        @Override
        public void onSurfaceAvailable(@NonNull SurfaceContainer surfaceContainer) {
            this.surface = surfaceContainer.getSurface();
            this.width = Math.max(1, surfaceContainer.getWidth());
            this.height = Math.max(1, surfaceContainer.getHeight());
            dibujar();
        }

        @Override
        public void onSurfaceDestroyed(@NonNull SurfaceContainer surfaceContainer) {
            this.surface = null;
        }

        @Override
        public void onLocationChanged(@NonNull Location loc) {
            this.lat = loc.getLatitude();
            this.lon = loc.getLongitude();
            this.speedKmh = loc.hasSpeed() ? loc.getSpeed() * 3.6f : 0f;
            double minD = 999999;
            int lim = 80;
            for (double[] r : radaresMalaga) {
                float[] res = new float[1];
                Location.distanceBetween(lat, lon, r[0], r[1], res);
                if (res[0] < minD) { minD = res[0]; lim = (int) r[2]; }
            }
            if (minD < 1200) {
                avisoRadar = "⚠️ RADAR FIJO (" + lim + " km/h) a " + ((int) minD) + " m";
            } else {
                avisoRadar = "KULI MAPAS · Radar más cercano a " + String.format("%.1f km", minD / 1000.0);
            }
            dibujar();
            invalidate();
        }

        private void dibujar() {
            if (surface == null || !surface.isValid()) return;
            Canvas c = null;
            try {
                c = surface.lockCanvas(new Rect(0, 0, width, height));
                if (c == null) return;
                c.drawColor(Color.parseColor("#0B132B"));
                Paint grid = new Paint();
                grid.setColor(Color.parseColor("#1C2541"));
                grid.setStrokeWidth(2f);
                for (int x = 0; x < width; x += 60) c.drawLine(x, 0, x, height, grid);
                for (int y = 0; y < height; y += 60) c.drawLine(0, y, width, y, grid);

                float cx = width / 2f, cy = height * 0.58f;
                Paint radarP = new Paint(Paint.ANTI_ALIAS_FLAG);
                radarP.setColor(Color.parseColor("#EF233C"));
                for (double[] r : radaresMalaga) {
                    float dx = (float) ((r[1] - lon) * 14000.0);
                    float dy = (float) ((lat - r[0]) * 16500.0);
                    if (Math.abs(dx) < width && Math.abs(dy) < height) {
                        c.drawCircle(cx + dx, cy + dy, 16f, radarP);
                    }
                }

                Paint coche = new Paint(Paint.ANTI_ALIAS_FLAG);
                coche.setColor(Color.parseColor("#3A86FF"));
                c.drawCircle(cx, cy, 22f, coche);
                Paint borde = new Paint(Paint.ANTI_ALIAS_FLAG);
                borde.setStyle(Paint.Style.STROKE);
                borde.setStrokeWidth(5f);
                borde.setColor(Color.WHITE);
                c.drawCircle(cx, cy, 22f, borde);

                Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
                txt.setColor(Color.WHITE);
                txt.setTextSize(34f);
                c.drawText(String.format("%.0f km/h · %s", speedKmh, avisoRadar), 36f, height - 36f, txt);
            } catch (Throwable ignored) {
            } finally {
                if (c != null) {
                    try { surface.unlockCanvasAndPost(c); } catch (Throwable ignored) {}
                }
            }
        }

        @NonNull
        @Override
        public Template onGetTemplate() {
            Step step = new Step.Builder(avisoRadar).build();
            RoutingInfo routing = new RoutingInfo.Builder()
                .setCurrentStep(step, Distance.create(1.0, Distance.UNIT_KILOMETERS))
                .build();
            ActionStrip strip = new ActionStrip.Builder()
                .addAction(new Action.Builder()
                    .setTitle("📱 Abrir en móvil")
                    .setOnClickListener(() -> {
                        try {
                            Intent i = new Intent(getCarContext(), MainActivity.class);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            getCarContext().startActivity(i);
                        } catch (Throwable ignored) {}
                    }).build())
                .build();
            return new NavigationTemplate.Builder()
                .setNavigationInfo(routing)
                .setActionStrip(strip)
                .setBackgroundColor(CarColor.PRIMARY)
                .build();
        }
    }
}
