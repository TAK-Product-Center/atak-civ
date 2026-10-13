package com.atakmap.android.gptdouggeo.plugin;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Offline, synthetic 2D point preview; NOT an ATAK basemap, GPS or navigational overlay. */
public final class GeoAtlasMiniMapView extends View {
    private static final int BG = Color.rgb(9, 25, 40);
    private static final int GRID = Color.rgb(35, 72, 92);
    private static final int CYAN = Color.rgb(102, 221, 227);
    private static final int INK = Color.rgb(214, 235, 245);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Sample> points = new ArrayList<>();

    public GeoAtlasMiniMapView(Context context) {
        super(context);
        readSyntheticSample(context);
        setContentDescription("Synthetic offline preview, no real map or network connections");
    }

    private void readSyntheticSample(Context context) {
        try (InputStream stream = context.getAssets().open("synthetic_field_demo.geojson");
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] data = new byte[4096];
            int count;
            while ((count = stream.read(data)) != -1) {
                if (buffer.size() + count > 32768) return;
                buffer.write(data, 0, count);
            }
            JSONArray features = new JSONObject(buffer.toString("UTF-8")).getJSONArray("features");
            for (int i = 0; i < features.length() && i < 12; i++) {
                JSONObject feature = features.getJSONObject(i);
                JSONObject geometry = feature.getJSONObject("geometry");
                if (!"Point".equals(geometry.optString("type"))) continue;
                JSONArray coords = geometry.getJSONArray("coordinates");
                double lon = coords.getDouble(0);
                double lat = coords.getDouble(1);
                if (Math.abs(lon) > 180 || Math.abs(lat) > 90
                        || Double.isNaN(lon) || Double.isNaN(lat)
                        || Double.isInfinite(lon) || Double.isInfinite(lat)) continue;
                String label = feature.getJSONObject("properties").optString("name", "Sample")
                        .replace("\n", " ");
                points.add(new Sample(lon, lat, label.length() > 24 ? label.substring(0, 24) : label));
            }
        } catch (Exception ignored) {
            // Never fall back to a remote feed. Missing fixture => empty offline view.
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float density = getResources().getDisplayMetrics().density;
        canvas.drawColor(BG);
        paint.setColor(GRID);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        for (int i = 1; i < 8; i++) {
            float x = w * i / 8, y = h * i / 8;
            canvas.drawLine(x, 0, x, h, paint);
            canvas.drawLine(0, y, w, y, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(248, 195, 114));
        paint.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        canvas.drawText("SYNTHETIC / OFFLINE / NOT FOR NAVIGATION", 12 * density, 22 * density, paint);
        if (points.isEmpty()) {
            paint.setColor(INK);
            canvas.drawText("No local sample features", 14 * density, h / 2, paint);
            return;
        }
        double left = Double.POSITIVE_INFINITY, right = Double.NEGATIVE_INFINITY;
        double bottom = Double.POSITIVE_INFINITY, top = Double.NEGATIVE_INFINITY;
        for (Sample p : points) {
            left = Math.min(left, p.lon); right = Math.max(right, p.lon);
            bottom = Math.min(bottom, p.lat); top = Math.max(top, p.lat);
        }
        double dx = Math.max(0.005, right-left), dy = Math.max(0.005, top-bottom);
        float margin = 48 * density;
        for (Sample p : points) {
            float x = (float)(margin + (p.lon-left + (dx - (right-left))/2)
                    * Math.max(1, w - 2 * margin) / dx);
            float y = (float)(h-margin - (p.lat-bottom + (dy - (top-bottom))/2)
                    * Math.max(1, h - 2 * margin) / dy);
            paint.setColor(CYAN); canvas.drawCircle(x, y, 6 * density, paint);
            paint.setColor(INK); paint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
            canvas.drawText(p.label, x + 8 * density, y - 7 * density, paint);
        }
    }

    private static final class Sample {
        final double lon, lat;
        final String label;
        Sample(double longitude, double latitude, String name) {
            lon = longitude; lat = latitude; label = name;
        }
    }
}
