package com.gesits.dashboard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

// MapLibre v9 — namespace com.mapbox.mapboxsdk
import com.mapbox.mapboxsdk.Mapbox;
import com.mapbox.mapboxsdk.camera.CameraPosition;
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory;
import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.geometry.LatLngBounds;
import com.mapbox.mapboxsdk.maps.MapView;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.maps.Style;
import com.mapbox.mapboxsdk.style.layers.LineLayer;
import com.mapbox.mapboxsdk.style.layers.PropertyFactory;
import com.mapbox.mapboxsdk.style.layers.CircleLayer;
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MapActivity extends Activity {

    private static final String TAG = "GesitsMap";
    public static MapActivity instance; // untuk update overlay dari MainActivity

    private static final String STYLE_DAY   = "https://tiles.openfreemap.org/styles/liberty";
    private static final String STYLE_NIGHT = "https://tiles.openfreemap.org/styles/dark";

    private static final String[] OSRM_SERVERS = {
        "https://router.project-osrm.org",
        "http://router.project-osrm.org",           // fallback HTTP
        "https://routing.openstreetmap.de/routed-car",
        "http://routing.openstreetmap.de/routed-car", // fallback HTTP
    };

    // ── Map ──
    private MapView    mapView;
    private MapboxMap  mapboxMap;
    private boolean    mapReady = false;

    // ── HTTP ──
    private OkHttpClient httpClient;

    // ── GPS ──
    private LocationManager  locationManager;
    private LocationListener locationListener;

    // ── TTS ──
    private TextToSpeech tts;
    private boolean      ttsReady = false;

    // ── State ──
    private double  userLat = 0, userLng = 0;
    private double  destLat = 0, destLng = 0;
    private String  destLabel   = "";
    private boolean navActive   = false;
    private boolean isNight     = false;
    private boolean followUser  = true;
    private List<LatLng> routePoints = new ArrayList<>();
    private List<NavStep> navSteps   = new ArrayList<>();
    private int    stepIdx    = 0;
    private double remainDist = 0;
    private String lastSpoken = "";

    // ── AT+MAPS ──
    private final Handler  mapsHandler  = new Handler(Looper.getMainLooper());
    private Runnable       mapsRunnable;
    public static BluetoothService btServiceRef;

    // ── Main handler ──
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── UI ──
    private TextView tvInstruction, tvStepDist, tvRouteInfo;
    private View     navBar, routePanel;
    private Button   btnNight, btnStop;
    private EditText etSearch;
    private LinearLayout searchResultsLayout;
    private ScrollView   searchScroll;

    // Views yang butuh margin update saat rotate
    private View  searchRowView, searchScrollView, instrBarView;
    private FrameLayout.LayoutParams searchRowLP, searchScrollLP, instrBarLP;

    // Info overlay (SOC + Voltase) — pojok kanan atas
    private TextView tvSocOverlay;
    private double   lastSoc = -1, lastVbat = -1;

    // ══════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // MapLibre v9 init — tidak butuh token untuk OpenFreeMap
        Mapbox.getInstance(this);

        httpClient = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

        // TTS
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("id", "ID"));
                if (r == TextToSpeech.LANG_MISSING_DATA ||
                    r == TextToSpeech.LANG_NOT_SUPPORTED)
                    tts.setLanguage(Locale.ENGLISH);
                tts.setSpeechRate(0.95f);
                ttsReady = true;
            }
        });

        // Data dari Intent
        Intent intent = getIntent();
        if (intent != null) {
            destLat   = intent.getDoubleExtra("destLat", 0);
            destLng   = intent.getDoubleExtra("destLng", 0);
            destLabel = intent.getStringExtra("destLabel");
            if (destLabel == null) destLabel = "";
            isNight   = intent.getBooleanExtra("nightMode", false);
        }

        buildUI(savedInstanceState);
        instance = this;
        startGps();
        startMapsStream();

        if (destLat != 0 && destLng != 0) {
            mainHandler.postDelayed(this::fetchRoute, 1500);
        }
    }

    // ══════════════════════════════════════
    // BUILD UI
    // ══════════════════════════════════════

    private void buildUI(Bundle savedState) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        // ── MapView ──
        mapView = new MapView(this);
        root.addView(mapView, matchParent());
        mapView.onCreate(savedState);
        mapView.getMapAsync(map -> {
            mapboxMap = map;
            mapboxMap.setStyle(isNight ? STYLE_NIGHT : STYLE_DAY, style -> {
                mapReady = true;
                if (userLat != 0) moveCameraTo(userLat, userLng, 15);
                if (destLat != 0) addDestMarker();
            });
            mapboxMap.addOnMapClickListener(point -> {
                if (!navActive) {
                    destLat   = point.getLatitude();
                    destLng   = point.getLongitude();
                    destLabel = String.format("%.5f, %.5f", destLat, destLng);
                    addDestMarker();
                    fetchRoute();
                }
                return true;
            });
        });

        // ── Top Bar ──
        LinearLayout topBar = makeRow(Color.parseColor("#CC0d1117"));
        // Gunakan padding dinamis berdasarkan status bar height
        topBar.setPadding(dp(12), getStatusBarHeight() + dp(4), dp(12), dp(10));
        topBar.setId(android.R.id.background); // id untuk update saat rotate

        Button btnBack = makeBtn("← Kembali", 13);
        btnBack.setOnClickListener(v -> finish());

        TextView tvTitle = new TextView(this);
        tvTitle.setText("GESITS Nav");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(15);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvTitle.setPadding(dp(8), 0, 0, 0);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnNight = makeBtn(isNight ? "☀ Siang" : "🌙 Malam", 12);
        btnNight.setOnClickListener(v -> toggleNight());

        topBar.addView(btnBack);
        topBar.addView(tvTitle);
        topBar.addView(btnNight);

        FrameLayout.LayoutParams topLP = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(topBar, topLP);

        // ── Search Bar ──
        LinearLayout searchRow = makeRow(Color.parseColor("#CC161b22"));
        searchRow.setPadding(dp(12), dp(6), dp(12), dp(6));

        etSearch = new EditText(this);
        etSearch.setHint("Cari tujuan...");
        etSearch.setHintTextColor(Color.parseColor("#8b949e"));
        etSearch.setTextColor(Color.WHITE);
        etSearch.setBackgroundColor(Color.parseColor("#0d1117"));
        etSearch.setPadding(dp(10), dp(8), dp(10), dp(8));
        etSearch.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        etSearch.setOnEditorActionListener((v, a, e) -> {
            doSearch(etSearch.getText().toString().trim());
            return true;
        });

        Button btnSearch = makeBtn("Cari", 13);
        btnSearch.setOnClickListener(v ->
            doSearch(etSearch.getText().toString().trim()));

        searchRow.addView(etSearch);
        searchRow.addView(btnSearch);

        FrameLayout.LayoutParams searchLP = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        searchLP.topMargin = getStatusBarHeight() + dp(54);
        root.addView(searchRow, searchLP);
        searchRowView = searchRow;
        searchRowLP   = searchLP;

        // ── Search Results Dropdown ──
        searchScroll = new ScrollView(this);
        searchResultsLayout = new LinearLayout(this);
        searchResultsLayout.setOrientation(LinearLayout.VERTICAL);
        searchResultsLayout.setBackgroundColor(Color.parseColor("#EE0d1117"));
        searchScroll.addView(searchResultsLayout);
        searchScroll.setVisibility(View.GONE);

        FrameLayout.LayoutParams srLP = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(220), Gravity.TOP);
        srLP.topMargin = getStatusBarHeight() + dp(106);
        root.addView(searchScroll, srLP);
        searchScrollView = searchScroll;
        searchScrollLP   = srLP;

        // ── Nav Instruction Bar ──
        LinearLayout instrBar = new LinearLayout(this);
        instrBar.setOrientation(LinearLayout.VERTICAL);
        instrBar.setBackgroundColor(Color.parseColor("#EE0d1117"));
        instrBar.setPadding(dp(16), dp(12), dp(16), dp(12));
        instrBar.setVisibility(View.GONE);

        tvInstruction = new TextView(this);
        tvInstruction.setTextColor(Color.WHITE);
        tvInstruction.setTextSize(18);
        tvInstruction.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvInstruction.setText("Mulai berkendara");

        tvStepDist = new TextView(this);
        tvStepDist.setTextColor(Color.parseColor("#8b949e"));
        tvStepDist.setTextSize(13);

        instrBar.addView(tvInstruction);
        instrBar.addView(tvStepDist);

        FrameLayout.LayoutParams instrLP = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        instrLP.topMargin = getStatusBarHeight() + dp(108);
        root.addView(instrBar, instrLP);
        navBar     = instrBar;
        instrBarLP  = instrLP;
        instrBarView = instrBar;

        // ── Route Info Panel ──
        LinearLayout routeBar = makeRow(Color.parseColor("#EE0d1117"));
        routeBar.setPadding(dp(16), dp(12), dp(16), dp(12));
        routeBar.setVisibility(View.GONE);

        tvRouteInfo = new TextView(this);
        tvRouteInfo.setTextColor(Color.WHITE);
        tvRouteInfo.setTextSize(13);
        tvRouteInfo.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnStartNav = makeBtn("Mulai", 13);
        btnStartNav.setBackgroundColor(Color.parseColor("#2979ff"));
        btnStartNav.setOnClickListener(v -> startNavigation());

        Button btnCancelRoute = makeBtn("Batal", 13);
        btnCancelRoute.setOnClickListener(v -> cancelRoute());

        routeBar.addView(tvRouteInfo);
        routeBar.addView(btnStartNav);
        routeBar.addView(btnCancelRoute);

        FrameLayout.LayoutParams routeLP = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        routeLP.bottomMargin = dp(72);
        root.addView(routeBar, routeLP);
        routePanel = routeBar;

        // ── Bottom Bar ──
        LinearLayout bottomBar = makeRow(Color.parseColor("#CC0d1117"));
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(16), dp(10), dp(16), dp(20));

        Button btnCenter = makeBtn("Ikuti GPS", 13);
        btnCenter.setOnClickListener(v -> {
            followUser = true;
            if (userLat != 0) moveCameraTo(userLat, userLng, 16);
        });

        btnStop = makeBtn("Stop Nav", 13);
        btnStop.setBackgroundColor(Color.parseColor("#c62828"));
        btnStop.setOnClickListener(v -> {
            if (navActive) stopNavigation();
            else finish();
        });

        bottomBar.addView(btnCenter);
        bottomBar.addView(btnStop);

        FrameLayout.LayoutParams botLP = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(bottomBar, botLP);

        // ── SOC + Voltase Overlay — pojok kiri bawah di atas bottom bar ──
        tvSocOverlay = new TextView(this);
        tvSocOverlay.setTextColor(Color.WHITE);
        tvSocOverlay.setTextSize(13);
        tvSocOverlay.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvSocOverlay.setText("SOC: —%");
        tvSocOverlay.setBackgroundColor(Color.parseColor("#CC0d1117"));
        tvSocOverlay.setPadding(dp(10), dp(6), dp(10), dp(6));

        FrameLayout.LayoutParams socLP = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        socLP.bottomMargin = dp(72);
        socLP.leftMargin   = dp(12);
        root.addView(tvSocOverlay, socLP);
    }

    // ══════════════════════════════════════
    // GPS
    // ══════════════════════════════════════

    private void startGps() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location loc) {
                userLat = loc.getLatitude();
                userLng = loc.getLongitude();
                onLocationUpdate();
            }
            @Override public void onStatusChanged(String p, int s, Bundle e) {}
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
        };
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000, 1, locationListener);
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 2000, 5, locationListener);
            Location last = locationManager.getLastKnownLocation(
                LocationManager.GPS_PROVIDER);
            if (last == null) last = locationManager.getLastKnownLocation(
                LocationManager.NETWORK_PROVIDER);
            if (last != null) { userLat = last.getLatitude(); userLng = last.getLongitude(); }
        } catch (SecurityException e) {
            Log.e(TAG, "GPS denied: " + e.getMessage());
        }
    }

    private void onLocationUpdate() {
        if (!mapReady) return;
        if (followUser) moveCameraTo(userLat, userLng, -1);
        drawUserDot();
        if (navActive) checkNavStep();
    }

    private void drawUserDot() {
        if (mapboxMap == null || !mapReady) return;
        try {
            mapboxMap.getStyle(style -> {
                if (style == null) return;
                try {
                    if (style.getLayer("user-dot") != null) style.removeLayer("user-dot");
                    if (style.getSource("user-dot-src") != null) style.removeSource("user-dot-src");
                    String gj = String.format(
                        "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\"," +
                        "\"coordinates\":[%.6f,%.6f]}}", userLng, userLat);
                    style.addSource(new GeoJsonSource("user-dot-src", gj));
                    style.addLayer(new CircleLayer("user-dot", "user-dot-src")
                        .withProperties(
                            PropertyFactory.circleRadius(10f),
                            PropertyFactory.circleColor("#2196F3"),
                            PropertyFactory.circleStrokeWidth(3f),
                            PropertyFactory.circleStrokeColor("#FFFFFF")));
                } catch (Exception e) { Log.e(TAG, "drawUserDot inner: " + e.getMessage()); }
            });
        } catch (Exception e) { Log.e(TAG, "drawUserDot: " + e.getMessage()); }
    }

    private void moveCameraTo(double lat, double lng, double zoom) {
        if (mapboxMap == null) return;
        CameraPosition.Builder b = new CameraPosition.Builder()
            .target(new LatLng(lat, lng));
        if (zoom > 0) b.zoom(zoom);
        mapboxMap.animateCamera(CameraUpdateFactory.newCameraPosition(b.build()), 500);
    }

    // ══════════════════════════════════════
    // SEARCH
    // ══════════════════════════════════════

    private void doSearch(String query) {
        if (query.isEmpty()) return;
        hideKeyboard();
        String url = "https://nominatim.openstreetmap.org/search?format=json&q=" +
            android.net.Uri.encode(query) + "&accept-language=id&limit=5" +
            (userLat != 0 ? String.format("&viewbox=%.4f,%.4f,%.4f,%.4f",
                userLng-0.5, userLat+0.5, userLng+0.5, userLat-0.5) : "");

        Request req = new Request.Builder().url(url)
            .header("User-Agent", "GesitsDashboard/1.0").build();
        httpClient.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call c, IOException e) {
                mainHandler.post(() -> Toast.makeText(MapActivity.this,
                    "Pencarian gagal", Toast.LENGTH_SHORT).show());
            }
            @Override public void onResponse(Call c, Response r) throws IOException {
                try {
                    JSONArray arr = new JSONArray(r.body().string());
                    mainHandler.post(() -> showSearchResults(arr));
                } catch (Exception e) { Log.e(TAG, "Search: " + e.getMessage()); }
            }
        });
    }

    private void showSearchResults(JSONArray arr) {
        searchResultsLayout.removeAllViews();
        if (arr.length() == 0) {
            searchScroll.setVisibility(View.GONE);
            Toast.makeText(this, "Tidak ditemukan", Toast.LENGTH_SHORT).show();
            return;
        }
        searchScroll.setVisibility(View.VISIBLE);
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject obj = arr.getJSONObject(i);
                final double lat  = obj.getDouble("lat");
                final double lon  = obj.getDouble("lon");
                final String name = obj.optString("display_name", "Tanpa nama");
                TextView tv = new TextView(this);
                tv.setText(name);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(13);
                tv.setPadding(dp(14), dp(12), dp(14), dp(12));
                tv.setBackgroundColor(i%2==0
                    ? Color.parseColor("#0d1117")
                    : Color.parseColor("#161b22"));
                tv.setOnClickListener(v -> {
                    destLat   = lat; destLng = lon;
                    destLabel = name.length()>60 ? name.substring(0,60)+"..." : name;
                    searchScroll.setVisibility(View.GONE);
                    addDestMarker();
                    fetchRoute();
                    moveCameraTo(lat, lon, 15);
                });
                searchResultsLayout.addView(tv);
            } catch (Exception ignored) {}
        }
    }

    // ══════════════════════════════════════
    // ROUTING
    // ══════════════════════════════════════

    private void fetchRoute() {
        if (userLat==0 || destLat==0) return;
        mainHandler.post(() -> {
            tvRouteInfo.setText("⏳ Mengambil rute...");
            routePanel.setVisibility(View.VISIBLE);
        });
        tryOsrm(0);
    }

    private void tryOsrm(int idx) {
        if (idx >= OSRM_SERVERS.length) {
            mainHandler.post(() -> {
                Toast.makeText(MapActivity.this,
                    "Routing gagal. Cek koneksi internet lalu tap peta lagi.",
                    Toast.LENGTH_LONG).show();
                routePanel.setVisibility(View.GONE);
            });
            return;
        }
        String url = String.format(
            "%s/route/v1/driving/%.6f,%.6f;%.6f,%.6f" +
            "?geometries=geojson&steps=true&overview=full",
            OSRM_SERVERS[idx], userLng, userLat, destLng, destLat);

        Log.d(TAG, "Routing attempt #" + idx + ": " + url);

        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", "GesitsDashboard/1.0 (Android)")
            .header("Accept", "application/json")
            .build();

        httpClient.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                Log.w(TAG, "OSRM #" + idx + " fail: " + e.getMessage());
                tryOsrm(idx + 1);
            }

            @Override
            public void onResponse(Call c, Response r) throws IOException {
                try {
                    if (!r.isSuccessful()) {
                        Log.w(TAG, "OSRM #" + idx + " HTTP " + r.code());
                        tryOsrm(idx + 1);
                        return;
                    }
                    String body = r.body().string();
                    JSONObject d = new JSONObject(body);
                    if (!"Ok".equals(d.optString("code"))) {
                        Log.w(TAG, "OSRM #" + idx + " code=" + d.optString("code"));
                        tryOsrm(idx + 1);
                        return;
                    }
                    JSONObject route = d.getJSONArray("routes").getJSONObject(0);
                    double distM = route.getDouble("distance");
                    double durS  = route.getDouble("duration");

                    JSONArray coords = route.getJSONObject("geometry")
                        .getJSONArray("coordinates");
                    List<LatLng> pts = new ArrayList<>();
                    for (int i = 0; i < coords.length(); i++) {
                        JSONArray co = coords.getJSONArray(i);
                        pts.add(new LatLng(co.getDouble(1), co.getDouble(0)));
                    }

                    JSONArray steps = route.getJSONArray("legs")
                        .getJSONObject(0).getJSONArray("steps");
                    List<NavStep> ns = new ArrayList<>();
                    for (int i = 0; i < steps.length(); i++) {
                        JSONObject st  = steps.getJSONObject(i);
                        JSONObject mn  = st.getJSONObject("maneuver");
                        JSONArray  loc = mn.getJSONArray("location");
                        ns.add(new NavStep(
                            mn.optString("modifier", ""),
                            st.optString("name", ""),
                            st.getDouble("distance"),
                            loc.getDouble(1), loc.getDouble(0)));
                    }

                    Log.d(TAG, "Route OK #" + idx + ": " + pts.size() + " pts");
                    mainHandler.post(() -> {
                        routePoints = pts;
                        navSteps    = ns;
                        remainDist  = distM;
                        drawRoute();
                        showRouteInfo(distM, durS);
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Route parse #" + idx + ": " + e.getMessage());
                    tryOsrm(idx + 1);
                }
            }
        });
    }

    private void drawRoute() {
        if (mapboxMap==null || routePoints.isEmpty()) return;
        mapboxMap.getStyle(style -> {
            if (style.getLayer("route-line")!=null) style.removeLayer("route-line");
            if (style.getSource("route-src")!=null) style.removeSource("route-src");
            StringBuilder sb = new StringBuilder();
            for (int i=0; i<routePoints.size(); i++) {
                if (i>0) sb.append(",");
                sb.append(String.format("[%.6f,%.6f]",
                    routePoints.get(i).getLongitude(),
                    routePoints.get(i).getLatitude()));
            }
            String gj = "{\"type\":\"Feature\",\"geometry\":{" +
                "\"type\":\"LineString\",\"coordinates\":["+sb+"]}}";
            try { style.addSource(new GeoJsonSource("route-src", gj)); } catch (Exception ignored) {}
            style.addLayer(new LineLayer("route-line","route-src")
                .withProperties(
                    PropertyFactory.lineColor("#2979ff"),
                    PropertyFactory.lineWidth(6f),
                    PropertyFactory.lineCap("round"),
                    PropertyFactory.lineJoin("round")));

            if (!navActive) {
                LatLngBounds.Builder bb = new LatLngBounds.Builder();
                for (LatLng p : routePoints) bb.include(p);
                try {
                    mapboxMap.animateCamera(
                        CameraUpdateFactory.newLatLngBounds(bb.build(), dp(60)));
                } catch (Exception ignored) {}
            }
        });
    }

    private void addDestMarker() {
        if (mapboxMap==null||!mapReady) return;
        mapboxMap.getStyle(style -> {
            if (style.getLayer("dest-dot")!=null)  style.removeLayer("dest-dot");
            if (style.getSource("dest-src")!=null) style.removeSource("dest-src");
            String gj = String.format(
                "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\"," +
                "\"coordinates\":[%.6f,%.6f]}}", destLng, destLat);
            try { style.addSource(new GeoJsonSource("dest-src", gj)); } catch (Exception ignored) {}
            style.addLayer(new CircleLayer("dest-dot","dest-src")
                .withProperties(
                    PropertyFactory.circleRadius(12f),
                    PropertyFactory.circleColor("#f44336"),
                    PropertyFactory.circleStrokeWidth(3f),
                    PropertyFactory.circleStrokeColor("#FFFFFF")));
        });
    }

    private void showRouteInfo(double distM, double durS) {
        String dist = distM>=1000
            ? String.format("%.1f km",distM/1000)
            : String.format("%.0f m",distM);
        int mins = (int)(durS/60);
        String dur = mins>=60
            ? String.format("%dj %dm",mins/60,mins%60)
            : mins+" mnt";
        String lbl = destLabel.length()>40 ? destLabel.substring(0,40)+"..." : destLabel;
        tvRouteInfo.setText(dist + "  " + dur + "\n" + lbl);
        routePanel.setVisibility(View.VISIBLE);
    }

    // ══════════════════════════════════════
    // NAVIGATION
    // ══════════════════════════════════════

    private void startNavigation() {
        if (routePoints.isEmpty()) { fetchRoute(); return; }
        navActive = true; stepIdx = 0; followUser = true;
        routePanel.setVisibility(View.GONE);
        navBar.setVisibility(View.VISIBLE);
        updateNavInstruction();
        speak("Navigasi dimulai" + (destLabel.isEmpty() ? "" : ". Menuju "+destLabel));
        if (userLat!=0) moveCameraTo(userLat, userLng, 17);
    }

    private void stopNavigation() {
        navActive = false;
        navBar.setVisibility(View.GONE);
        speak("Navigasi dihentikan");
        btnStop.setText("Tutup");
    }

    private void cancelRoute() {
        stopNavigation();
        routePoints.clear(); navSteps.clear();
        routePanel.setVisibility(View.GONE);
        if (mapboxMap!=null) mapboxMap.getStyle(style -> {
            if (style.getLayer("route-line")!=null) style.removeLayer("route-line");
            if (style.getSource("route-src")!=null) style.removeSource("route-src");
        });
    }

    private void checkNavStep() {
        if (navSteps.isEmpty()||stepIdx>=navSteps.size()) return;
        NavStep step = navSteps.get(stepIdx);
        float[] res = new float[1];
        Location.distanceBetween(userLat, userLng, step.lat, step.lng, res);
        float dist = res[0];
        String distStr = dist<1000
            ? String.format("%.0f m",dist)
            : String.format("%.1f km",dist/1000);
        tvStepDist.setText(distStr);

        String key = step.modifier + stepIdx;
        if (dist < 80 && !lastSpoken.equals(key)) {
            lastSpoken = key;
            speak("Dalam "+distStr+", "+toIndonesian(step.modifier));
        }
        if (dist < 25) {
            stepIdx++;
            if (stepIdx >= navSteps.size()) onArrived();
            else updateNavInstruction();
        }
    }

    private void updateNavInstruction() {
        if (stepIdx>=navSteps.size()) return;
        NavStep s = navSteps.get(stepIdx);
        String street = s.streetName.isEmpty() ? "" : " ke "+s.streetName;
        tvInstruction.setText(toIndonesian(s.modifier)+street);
    }

    private void onArrived() {
        navActive = false;
        navBar.setVisibility(View.GONE);
        speak("Anda telah tiba di tujuan");
        Toast.makeText(this,"Tiba di tujuan!",Toast.LENGTH_LONG).show();
    }

    private String toIndonesian(String mod) {
        if (mod==null) return "Lanjutkan";
        switch (mod) {
            case "left":         return "Belok kiri";
            case "right":        return "Belok kanan";
            case "sharp left":   return "Belok tajam kiri";
            case "sharp right":  return "Belok tajam kanan";
            case "slight left":  return "Sedikit ke kiri";
            case "slight right": return "Sedikit ke kanan";
            case "uturn":        return "Putar balik";
            case "straight":     return "Jalan terus";
            default:             return "Lanjutkan";
        }
    }

    // ══════════════════════════════════════
    // AT+MAPS STREAM
    // ══════════════════════════════════════

    private void startMapsStream() {
        mapsRunnable = new Runnable() {
            @Override public void run() {
                captureAndSend();
                mapsHandler.postDelayed(this, 3000);
            }
        };
        mapsHandler.postDelayed(mapsRunnable, 3000);
    }

    private void captureAndSend() {
        if (mapboxMap == null || !mapReady) return;
        BluetoothService svc = btServiceRef;
        if (svc == null || !svc.isConnected()) return;
        try {
            mapboxMap.snapshot(bitmap -> {
                if (bitmap == null) return;
                try {
                    Bitmap small = Bitmap.createScaledBitmap(bitmap, 350, 220, true);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    small.compress(Bitmap.CompressFormat.JPEG, 50, baos);
                    String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                    svc.send("AT+MAPS=\"" + b64 + "\"");
                    small.recycle();
                } catch (Exception e) {
                    Log.e(TAG, "AT+MAPS encode error: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "AT+MAPS snapshot error: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════
    // NIGHT MODE
    // ══════════════════════════════════════

    // Update SOC + Voltase overlay dari data BT (dipanggil MainActivity)
    public void updateSocOverlay(double soc, double vbat) {
        if (soc  >= 0) lastSoc  = soc;
        if (vbat > 0)  lastVbat = vbat;
        if (tvSocOverlay == null) return;
        mainHandler.post(() -> {
            StringBuilder sb = new StringBuilder();
            if (lastSoc  >= 0) sb.append(String.format("🔋 %.0f%%", lastSoc));
            if (lastVbat > 0) {
                if (sb.length() > 0) sb.append("  ");
                sb.append(String.format("⚡ %.1fV", lastVbat));
            }
            if (sb.length() == 0) sb.append("SOC: —");
            tvSocOverlay.setText(sb.toString());
        });
    }

    private void toggleNight() {
        isNight = !isNight;
        btnNight.setText(isNight ? "Siang" : "Malam");
        if (mapboxMap!=null) {
            mapboxMap.setStyle(isNight ? STYLE_NIGHT : STYLE_DAY, style -> {
                mapReady = true;
                drawUserDot();
                if (destLat!=0) addDestMarker();
                if (!routePoints.isEmpty()) drawRoute();
            });
        }
    }

    // ══════════════════════════════════════
    // TTS
    // ══════════════════════════════════════

    private void speak(String text) {
        if (!ttsReady||tts==null||text==null||text.isEmpty()) return;
        tts.stop();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav_tts");
    }

    // ══════════════════════════════════════
    // NAV STEP
    // ══════════════════════════════════════

    static class NavStep {
        String modifier, streetName;
        double lat, lng;
        NavStep(String m, String s, double d, double la, double lo) {
            modifier=m; streetName=s; lat=la; lng=lo;
        }
    }

    // ══════════════════════════════════════
    // UI HELPERS
    // ══════════════════════════════════════

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int getStatusBarHeight() {
        int resourceId = getResources().getIdentifier(
            "status_bar_height", "dimen", "android");
        if (resourceId > 0) return getResources().getDimensionPixelSize(resourceId);
        return dp(24); // fallback
    }

    private Button makeBtn(String text, int sp) {
        Button b = new Button(this);
        b.setText(text); b.setTextSize(sp);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor("#1f2937"));
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4),0,dp(4),0);
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout makeRow(int bg) {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_VERTICAL);
        ll.setBackgroundColor(bg);
        return ll;
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager)
            getSystemService(INPUT_METHOD_SERVICE);
        if (imm!=null&&etSearch!=null)
            imm.hideSoftInputFromWindow(etSearch.getWindowToken(),0);
    }

    // ══════════════════════════════════════
    // MAPVIEW LIFECYCLE — WAJIB
    // ══════════════════════════════════════

    @Override protected void onStart()   { super.onStart();   mapView.onStart();   }
    @Override protected void onResume()  { super.onResume();  mapView.onResume();  }
    @Override protected void onPause()   { super.onPause();   mapView.onPause();   }
    @Override protected void onStop()    { super.onStop();    mapView.onStop();    }

    // Handle rotate — update semua topMargin yang bergantung status bar
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mapView == null) return;

        int sbh  = getStatusBarHeight();
        boolean land = newConfig.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE;

        // Update top bar padding
        View topBar = mapView.getRootView().findViewById(android.R.id.background);
        if (topBar instanceof LinearLayout) {
            topBar.setPadding(dp(12), sbh + dp(4), dp(12), dp(10));
        }

        // Search bar — lebih rapat di landscape
        if (searchRowLP != null && searchRowView != null) {
            searchRowLP.topMargin = sbh + (land ? dp(44) : dp(54));
            searchRowView.setLayoutParams(searchRowLP);
        }

        // Search results — lebih pendek di landscape
        if (searchScrollLP != null && searchScrollView != null) {
            searchScrollLP.topMargin = sbh + (land ? dp(90) : dp(106));
            searchScrollLP.height    = land ? dp(160) : dp(220);
            searchScrollView.setLayoutParams(searchScrollLP);
        }

        // Nav instruction bar
        if (instrBarLP != null && instrBarView != null) {
            instrBarLP.topMargin = sbh + (land ? dp(90) : dp(108));
            instrBarView.setLayoutParams(instrBarLP);
        }

        // Resize peta
        mapView.post(() -> mapView.requestLayout());
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        mapView.onSaveInstanceState(state);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        instance = null;
        mapsHandler.removeCallbacks(mapsRunnable);
        if (tts!=null) { tts.stop(); tts.shutdown(); tts=null; }
        if (locationManager!=null&&locationListener!=null)
            locationManager.removeUpdates(locationListener);
        mapView.onDestroy();
        super.onDestroy();
    }
}
