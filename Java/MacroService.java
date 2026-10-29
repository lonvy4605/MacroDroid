package com.uwaa.macro;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JseMathLib;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * UwaaMacro: auto clicker + editor visual + skrip Lua (LuaJ).
 *
 * Satu sumber kebenaran: JSON "actions" milik skrip.
 *  - Editor visual (penanda melayang) mengubah JSON itu.
 *  - uCompile() mengubah JSON menjadi kode Lua. Dashboard dan HUD memakai fungsi yang SAMA.
 *  - Skrip bermode "lua" berjalan apa adanya (tanpa penanda).
 *
 * Skema skrip: {name, mode: "visual"|"lua", loop: 0 (tak terbatas) | n, actions: [...], code: "..."}
 *  click : {type, fx, fy, hold, jit, delay}
 *  swipe : {type, fx, fy, fx2, fy2, dur, delay}
 *  image : {type, fx, fy, tw, th, tpl, thr(%), ok: click|none, fail: skip|stop|retry, hold, jit, delay}
 */
public class MacroService extends AccessibilityService {

    public static volatile MacroService instance;
    private static final String PREFS = "UwaaMacroPrefs";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private WindowManager wm;
    private SharedPreferences prefs;

    // HUD
    private LinearLayout hud;
    private WindowManager.LayoutParams hudLp;
    private TextView playBtn, statusTv, eyeBtn;
    private volatile String statusText = "Siap";

    // Editor visual
    private JSONArray acts = new JSONArray();
    private boolean editVisible = true;
    private LinesView lines;
    private WindowManager.LayoutParams linesLp;
    private final ArrayList<MarkerView> markers = new ArrayList<MarkerView>();
    private LinearLayout dialog;
    private int W = 1080, H = 2400;

    // Skrip
    private volatile boolean running = false;
    private Thread scriptThread;

    // Joystick analog
    private GestureDescription.StrokeDescription stick;
    private float stickX, stickY;

    // Screenshot + template
    private volatile long lastShotTs = 0;
    private final Map<String, Bitmap> templates = new HashMap<String, Bitmap>();

    // =====================================================================
    //  Siklus hidup service
    // =====================================================================

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        uRefreshScreen();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopScript();
        uRemoveHud();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        stopScript();
    }

    // =====================================================================
    //  Dashboard (dipanggil dari MainActivity.onCreate)
    // =====================================================================

    public static void attachDashboardToActivity(final Activity activity) {
        final SharedPreferences sp = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        uSeedScripts(sp);

        WebView web = new WebView(activity);
        web.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        web.setBackgroundColor(0xFF09080F);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);

        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public String status() {
                try {
                    JSONObject o = new JSONObject();
                    MacroService s = instance;
                    o.put("acc", isAccEnabled(activity));
                    o.put("connected", s != null);
                    o.put("running", s != null && s.running);
                    o.put("api", Build.VERSION.SDK_INT);
                    o.put("lua", luaPresent());
                    o.put("hud", s != null && s.hud != null);
                    o.put("text", s != null ? s.statusText : "");
                    return o.toString();
                } catch (Exception e) {
                    return "{}";
                }
            }

            @JavascriptInterface
            public String getScripts() {
                return sp.getString("scripts", "[]");
            }

            @JavascriptInterface
            public int getActive() {
                return sp.getInt("active", 0);
            }

            @JavascriptInterface
            public void saveScripts(String json, int active) {
                sp.edit().putString("scripts", json).putInt("active", active).apply();
            }

            /** Kompilasi aksi visual -> Lua. Fungsi yang sama dipakai saat skrip dijalankan. */
            @JavascriptInterface
            public String compile(String actionsJson, int loop) {
                try {
                    return uCompile(new JSONArray(actionsJson), loop);
                } catch (Exception e) {
                    return "-- error: " + e.getMessage();
                }
            }

            @JavascriptInterface
            public void openAccessibility() {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }
                });
            }

            @JavascriptInterface
            public void launch() {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        MacroService s = instance;
                        if (s == null) {
                            Toast.makeText(activity, "Aktifkan layanan aksesibilitas dulu", Toast.LENGTH_LONG).show();
                            activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                            return;
                        }
                        s.showHud();
                        activity.moveTaskToBack(true);
                    }
                });
            }
        }, "AppBridge");

        web.loadUrl("file:///android_asset/dashboard.html");
        activity.setContentView(web);
    }

    private static boolean luaPresent() {
        try {
            Class.forName("org.luaj.vm2.Globals");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isAccEnabled(Context ctx) {
        String s = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        ComponentName me = new ComponentName(ctx, MacroService.class);
        TextUtils.SimpleStringSplitter sp = new TextUtils.SimpleStringSplitter(':');
        sp.setString(s);
        while (sp.hasNext()) {
            ComponentName c = ComponentName.unflattenFromString(sp.next());
            if (c != null && c.equals(me)) return true;
        }
        return false;
    }

    // =====================================================================
    //  Data skrip
    // =====================================================================

    private static JSONObject uNewClick(double fx, double fy) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", "click");
        o.put("fx", fx);
        o.put("fy", fy);
        o.put("hold", 55);
        o.put("jit", 4);
        o.put("delay", 500);
        return o;
    }

    private static JSONObject uNewVisual(String name, JSONArray actions, int loop) throws Exception {
        JSONObject s = new JSONObject();
        s.put("name", name);
        s.put("mode", "visual");
        s.put("loop", loop);
        s.put("actions", actions);
        s.put("code", uCompile(actions, loop));
        return s;
    }

    private static void uSeedScripts(SharedPreferences sp) {
        if (sp.contains("scripts")) return;
        try {
            JSONArray arr = new JSONArray();

            JSONArray a = new JSONArray();
            a.put(uNewClick(0.5, 0.5));
            arr.put(uNewVisual("Visual - klik tengah layar", a, 0));

            JSONObject b = new JSONObject();
            b.put("name", "Lua - deteksi gambar.lua");
            b.put("mode", "lua");
            b.put("loop", 0);
            b.put("code",
                "-- 1) Simpan template dari layar saat ini (x, y = pojok kiri atas):\n" +
                "--    saveTemplate(\"tombol\", 400, 900, 120, 60)\n" +
                "-- 2) Lalu cari dan klik:\n" +
                "while running() do\n" +
                "  local x, y = findImage(\"tombol\", 0.85)\n" +
                "  if x then\n" +
                "    status(\"Ketemu di \" .. x .. \",\" .. y)\n" +
                "    tap(x, y, 60, 5)\n" +
                "  else\n" +
                "    status(\"Mencari...\")\n" +
                "  end\n" +
                "  sleep(500)\n" +
                "end\n");
            arr.put(b);

            sp.edit().putString("scripts", arr.toString()).putInt("active", 0).apply();
        } catch (Exception e) {
        }
    }

    /** Kompilator JSON -> Lua. Statis supaya bisa dipanggil dari dashboard maupun service. */
    static String uCompile(JSONArray a, int loop) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- Dibuat otomatis dari editor visual.\n");
        if (a == null || a.length() == 0) {
            sb.append("status(\"Belum ada aksi\")\n");
            return sb.toString();
        }
        sb.append("local w, h = screen()\n");
        sb.append("local function langkah()\n");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String t = o.optString("type", "click");
            int delay = o.optInt("delay", 500);
            sb.append("  status(\"Aksi ").append(i + 1).append("\")\n");
            if ("swipe".equals(t)) {
                sb.append(String.format(Locale.US, "  swipe(w * %.4f, h * %.4f, w * %.4f, h * %.4f, %d)\n",
                    o.optDouble("fx", 0.5), o.optDouble("fy", 0.5),
                    o.optDouble("fx2", 0.5), o.optDouble("fy2", 0.6), o.optInt("dur", 300)));
            } else if ("image".equals(t)) {
                String nm = uClean(o.optString("tpl", ""));
                String thr = String.format(Locale.US, "%.2f", o.optInt("thr", 85) / 100.0);
                String find = "findImage(\"" + nm + "\", " + thr + ")";
                String ok = o.optString("ok", "click");
                String fail = o.optString("fail", "skip");
                sb.append("  do\n");
                sb.append("    local x, y = ").append(find).append("\n");
                if ("retry".equals(fail)) {
                    sb.append("    while not x and running() do\n");
                    sb.append("      sleep(300)\n");
                    sb.append("      x, y = ").append(find).append("\n");
                    sb.append("    end\n");
                }
                sb.append("    if x then\n");
                if ("click".equals(ok)) {
                    sb.append(String.format(Locale.US, "      tap(x, y, %d, %d)\n", o.optInt("hold", 55), o.optInt("jit", 4)));
                } else {
                    sb.append("      status(\"Ketemu\")\n");
                }
                sb.append("    else\n");
                if ("stop".equals(fail)) {
                    sb.append("      status(\"Gambar tidak ketemu\")\n");
                    sb.append("      return false\n");
                } else {
                    sb.append("      status(\"Tidak ketemu, lewati\")\n");
                }
                sb.append("    end\n");
                sb.append("  end\n");
            } else {
                sb.append(String.format(Locale.US, "  tap(w * %.4f, h * %.4f, %d, %d)\n",
                    o.optDouble("fx", 0.5), o.optDouble("fy", 0.5), o.optInt("hold", 55), o.optInt("jit", 4)));
            }
            if (delay > 0) sb.append("  sleep(").append(delay).append(")\n");
        }
        sb.append("  return true\n");
        sb.append("end\n");
        sb.append("local n = 0\n");
        sb.append("while running() do\n");
        sb.append("  n = n + 1\n");
        sb.append("  if langkah() == false then break end\n");
        if (loop > 0) sb.append("  if n >= ").append(loop).append(" then break end\n");
        sb.append("  sleep(10)\n");
        sb.append("end\n");
        return sb.toString();
    }

    private static String uClean(String s) {
        return s.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    /** Muat aksi skrip aktif ke `acts`. True bila skrip aktif bermode visual. */
    private boolean uLoadActive() {
        try {
            JSONArray arr = new JSONArray(prefs.getString("scripts", "[]"));
            int idx = prefs.getInt("active", 0);
            if (arr.length() == 0) {
                arr.put(uNewVisual("Visual baru", new JSONArray(), 0));
                idx = 0;
                prefs.edit().putString("scripts", arr.toString()).putInt("active", 0).apply();
            }
            if (idx < 0 || idx >= arr.length()) idx = 0;
            JSONObject s = arr.getJSONObject(idx);
            if (!"visual".equals(s.optString("mode", "lua"))) {
                acts = new JSONArray();
                return false;
            }
            JSONArray a = s.optJSONArray("actions");
            acts = a == null ? new JSONArray() : a;
            return true;
        } catch (Exception e) {
            acts = new JSONArray();
            return false;
        }
    }

    /** Simpan `acts` ke skrip aktif dan perbarui kode Lua-nya. */
    private void uPersist() {
        try {
            JSONArray arr = new JSONArray(prefs.getString("scripts", "[]"));
            int idx = prefs.getInt("active", 0);
            if (idx < 0 || idx >= arr.length()) return;
            JSONObject s = arr.getJSONObject(idx);
            if (!"visual".equals(s.optString("mode", "lua"))) return;
            s.put("actions", acts);
            s.put("code", uCompile(acts, s.optInt("loop", 0)));
            prefs.edit().putString("scripts", arr.toString()).apply();
        } catch (Exception e) {
        }
    }

    private String uActiveCode() {
        try {
            JSONArray arr = new JSONArray(prefs.getString("scripts", "[]"));
            int idx = prefs.getInt("active", 0);
            if (idx < 0 || idx >= arr.length()) return "";
            JSONObject s = arr.getJSONObject(idx);
            if ("visual".equals(s.optString("mode", "lua"))) {
                return uCompile(s.optJSONArray("actions"), s.optInt("loop", 0));
            }
            return s.optString("code", "");
        } catch (Exception e) {
            return "";
        }
    }

    // =====================================================================
    //  Utilitas tampilan
    // =====================================================================

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int[] uScreen() {
        DisplayMetrics dm = new DisplayMetrics();
        if (wm != null) wm.getDefaultDisplay().getRealMetrics(dm);
        else dm = getResources().getDisplayMetrics();
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    private void uRefreshScreen() {
        int[] sc = uScreen();
        W = sc[0];
        H = sc[1];
    }

    private int uOverlayType() {
        return Build.VERSION.SDK_INT >= 22
            ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private WindowManager.LayoutParams uParams(int w, int h, int flags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h, uOverlayType(), flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        return lp;
    }

    private TextView uMk(String text, int sizeSp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFF3F4F6);
        tv.setTextSize(sizeSp);
        tv.setPadding(dp(8), dp(5), dp(8), dp(5));
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private TextView uBtn(String text, int color) {
        TextView tv = uMk(text, 13);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(10));
        tv.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), dp(6), dp(3), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void uToast(final String s) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MacroService.this, s, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void uSetStatus(final String s) {
        statusText = s;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (statusTv != null) statusTv.setText(s);
            }
        });
    }

    // =====================================================================
    //  HUD melayang
    // =====================================================================

    public void showHud() {
        main.post(new Runnable() {
            @Override
            public void run() {
                uBuildHud();
                uRebuildMarkers();
            }
        });
    }

    private void uBuildHud() {
        if (hud != null || wm == null) return;
        uRefreshScreen();

        hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEB0F0D18);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0xFF8B5CF6);
        hud.setBackground(bg);
        hud.setPadding(dp(4), dp(2), dp(4), dp(4));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView drag = uMk("\u2725", 20);
        playBtn = uMk(running ? "\u25A0" : "\u25B6", 20);
        TextView addClick = uMk("\u25CE", 20);
        TextView addSwipe = uMk("\u21C4", 20);
        TextView addImage = uMk("\u25A3", 20);
        eyeBtn = uMk(editVisible ? "\u25C9" : "\u25CC", 20);
        TextView close = uMk("\u2715", 18);

        row.addView(drag);
        row.addView(playBtn);
        row.addView(addClick);
        row.addView(addSwipe);
        row.addView(addImage);
        row.addView(eyeBtn);
        row.addView(close);
        hud.addView(row);

        statusTv = uMk(statusText, 11);
        statusTv.setTextColor(0xFFA7F3D0);
        statusTv.setSingleLine(true);
        statusTv.setEllipsize(TextUtils.TruncateAt.END);
        statusTv.setMaxWidth(dp(260));
        hud.addView(statusTv);

        hudLp = uParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        hudLp.x = dp(12);
        hudLp.y = dp(80);

        drag.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            int sx, sy;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX();
                        dy = e.getRawY();
                        sx = hudLp.x;
                        sy = hudLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        hudLp.x = sx + (int) (e.getRawX() - dx);
                        hudLp.y = sy + (int) (e.getRawY() - dy);
                        try {
                            wm.updateViewLayout(hud, hudLp);
                        } catch (Exception ex) {
                        }
                        return true;
                }
                return true;
            }
        });

        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (running) stopScript();
                else startScript();
            }
        });
        addClick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uAddAction("click");
            }
        });
        addSwipe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uAddAction("swipe");
            }
        });
        addImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uAddAction("image");
            }
        });
        eyeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editVisible = !editVisible;
                eyeBtn.setText(editVisible ? "\u25C9" : "\u25CC");
                uRebuildMarkers();
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopScript();
                uRemoveHud();
            }
        });

        try {
            wm.addView(hud, hudLp);
        } catch (Exception e) {
            hud = null;
        }
    }

    private void uRemoveHud() {
        main.post(new Runnable() {
            @Override
            public void run() {
                uClearMarkers();
                uCloseDialog();
                try {
                    if (lines != null) wm.removeView(lines);
                } catch (Exception e) {
                }
                lines = null;
                try {
                    if (hud != null) wm.removeView(hud);
                } catch (Exception e) {
                }
                hud = null;
            }
        });
    }

    private void uUpdatePlay() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (playBtn != null) playBtn.setText(running ? "\u25A0" : "\u25B6");
                uSetTouchable(!running);
            }
        });
    }

    // =====================================================================
    //  Editor visual: penanda, garis, dialog
    // =====================================================================

    private void uAddAction(String type) {
        if (!uLoadActive()) {
            uToast("Skrip aktif bermode Lua. Aktifkan/buat skrip Visual di dashboard.");
            return;
        }
        if (running) {
            uToast("Hentikan skrip dulu");
            return;
        }
        try {
            int n = acts.length();
            double off = (n % 6) * 0.04;
            JSONObject o = uNewClick(0.5 - 0.1 + off, 0.4 + off);
            o.put("type", type);
            if ("swipe".equals(type)) {
                o.remove("hold");
                o.remove("jit");
                o.put("fx", 0.5);
                o.put("fy", 0.6);
                o.put("fx2", 0.5);
                o.put("fy2", 0.35);
                o.put("dur", 300);
            } else if ("image".equals(type)) {
                o.put("tw", 120);
                o.put("th", 80);
                o.put("tpl", "img_" + System.currentTimeMillis());
                o.put("thr", 85);
                o.put("ok", "click");
                o.put("fail", "skip");
            }
            acts.put(o);
            uPersist();
            uRebuildMarkers();
            if ("image".equals(type)) {
                uToast("Atur kotak di atas objek, ketuk kotak, lalu tekan Ambil gambar");
            }
        } catch (Exception e) {
        }
    }

    private void uClearMarkers() {
        for (int i = 0; i < markers.size(); i++) {
            try {
                wm.removeView(markers.get(i));
            } catch (Exception e) {
            }
        }
        markers.clear();
    }

    private void uRebuildMarkers() {
        if (hud == null || wm == null) return;
        uClearMarkers();
        boolean vis = uLoadActive();
        uRefreshScreen();

        if (lines == null) {
            lines = new LinesView(this);
            linesLp = uParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            try {
                wm.addView(lines, linesLp);
                // HUD harus di atas garis
                wm.removeView(hud);
                wm.addView(hud, hudLp);
            } catch (Exception e) {
            }
        }
        lines.setVisibility(editVisible ? View.VISIBLE : View.GONE);
        lines.invalidate();

        if (!vis) {
            uSetStatus("Skrip aktif: mode Lua (tanpa penanda)");
            return;
        }
        for (int i = 0; i < acts.length(); i++) {
            JSONObject o = acts.optJSONObject(i);
            if (o == null) continue;
            String t = o.optString("type", "click");
            if ("swipe".equals(t)) {
                uMakeMarker(i, false, 1, dp(40), dp(40));
                uMakeMarker(i, true, 1, dp(30), dp(30));
            } else if ("image".equals(t)) {
                uMakeMarker(i, false, 2, Math.max(dp(30), o.optInt("tw", 120)), Math.max(dp(30), o.optInt("th", 80)));
            } else {
                uMakeMarker(i, false, 0, dp(40), dp(40));
            }
        }
        uSetStatus(acts.length() + " aksi");
        if (running) uSetTouchable(false);
        // HUD tetap paling atas
        try {
            wm.removeView(hud);
            wm.addView(hud, hudLp);
        } catch (Exception e) {
        }
    }

    private void uMakeMarker(int idx, boolean end, int kind, int w, int h) {
        MarkerView m = new MarkerView(this, idx, end, kind);
        m.lp = uParams(w, h,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        m.syncFromAction();
        m.setVisibility(editVisible ? View.VISIBLE : View.GONE);
        try {
            wm.addView(m, m.lp);
            markers.add(m);
        } catch (Exception e) {
        }
    }

    private void uSetTouchable(boolean t) {
        for (int i = 0; i < markers.size(); i++) {
            MarkerView m = markers.get(i);
            if (t) m.lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else m.lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                wm.updateViewLayout(m, m.lp);
            } catch (Exception e) {
            }
        }
    }

    /** Sembunyikan/tampilkan seluruh overlay (dipakai saat mengambil template). Panggil dari thread utama. */
    private void uOverlayVisible(boolean show) {
        int v = show ? View.VISIBLE : View.GONE;
        if (hud != null) hud.setVisibility(v);
        if (dialog != null) dialog.setVisibility(v);
        if (lines != null) lines.setVisibility(show && editVisible ? View.VISIBLE : View.GONE);
        for (int i = 0; i < markers.size(); i++) {
            markers.get(i).setVisibility(show && editVisible ? View.VISIBLE : View.GONE);
        }
    }

    /** Garis penghubung antar aksi. */
    private class LinesView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        LinesView(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            float prevX = -1, prevY = -1;
            p.setStyle(Paint.Style.STROKE);
            for (int i = 0; i < acts.length(); i++) {
                JSONObject o = acts.optJSONObject(i);
                if (o == null) continue;
                float x = (float) (o.optDouble("fx", 0.5) * W);
                float y = (float) (o.optDouble("fy", 0.5) * H);
                if (prevX >= 0) {
                    p.setColor(0x99FFFFFF);
                    p.setStrokeWidth(dp(2));
                    c.drawLine(prevX, prevY, x, y, p);
                }
                if ("swipe".equals(o.optString("type", "click"))) {
                    float x2 = (float) (o.optDouble("fx2", 0.5) * W);
                    float y2 = (float) (o.optDouble("fy2", 0.6) * H);
                    p.setColor(0xFFF59E0B);
                    p.setStrokeWidth(dp(4));
                    c.drawLine(x, y, x2, y2, p);
                    prevX = x2;
                    prevY = y2;
                } else {
                    prevX = x;
                    prevY = y;
                }
            }
        }
    }

    /** Penanda melayang yang bisa digeser. Ketuk untuk membuka dialog. */
    private class MarkerView extends View {
        final int idx;
        final boolean end;
        final int kind; // 0 klik, 1 swipe, 2 gambar
        WindowManager.LayoutParams lp;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float downX, downY, startCx, startCy;
        private boolean moved;

        MarkerView(Context c, int idx, boolean end, int kind) {
            super(c);
            this.idx = idx;
            this.end = end;
            this.kind = kind;
        }

        void syncFromAction() {
            JSONObject o = acts.optJSONObject(idx);
            if (o == null) return;
            double fx = end ? o.optDouble("fx2", 0.5) : o.optDouble("fx", 0.5);
            double fy = end ? o.optDouble("fy2", 0.6) : o.optDouble("fy", 0.5);
            lp.x = (int) (fx * W) - lp.width / 2;
            lp.y = (int) (fy * H) - lp.height / 2;
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            int col;
            if (kind == 2) col = 0xFF10B981;
            else if (end) col = 0xFFF59E0B;
            else if (kind == 1) col = 0xFF3B82F6;
            else col = 0xFF8B5CF6;
            p.setStyle(Paint.Style.FILL);
            p.setColor((col & 0x00FFFFFF) | 0x55000000);
            if (kind == 2) c.drawRoundRect(new RectF(2, 2, w - 2, h - 2), dp(8), dp(8), p);
            else c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f - 2, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(col);
            if (kind == 2) c.drawRoundRect(new RectF(2, 2, w - 2, h - 2), dp(8), dp(8), p);
            else c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f - 2, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            p.setTextSize(dp(end ? 12 : 15));
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(String.valueOf(idx + 1), w / 2f, h / 2f + dp(5), p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startCx = lp.x + lp.width / 2f;
                    startCy = lp.y + lp.height / 2f;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float ddx = e.getRawX() - downX, ddy = e.getRawY() - downY;
                    if (!moved && Math.abs(ddx) + Math.abs(ddy) > dp(8)) moved = true;
                    if (moved) {
                        float cx = Math.max(0, Math.min(W, startCx + ddx));
                        float cy = Math.max(0, Math.min(H, startCy + ddy));
                        lp.x = (int) cx - lp.width / 2;
                        lp.y = (int) cy - lp.height / 2;
                        try {
                            wm.updateViewLayout(this, lp);
                            JSONObject o = acts.optJSONObject(idx);
                            if (o != null) {
                                o.put(end ? "fx2" : "fx", cx / (double) W);
                                o.put(end ? "fy2" : "fy", cy / (double) H);
                            }
                        } catch (Exception ex) {
                        }
                        if (lines != null) lines.invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (moved) uPersist();
                    else uShowDialog(idx);
                    return true;
            }
            return true;
        }
    }

    // ---- Dialog pengeditan aksi ----

    private EditText uField(LinearLayout parent, String label, String val) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = uMk(label, 12);
        l.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        EditText e = new EditText(this);
        e.setText(val);
        e.setTextColor(0xFFFFFFFF);
        e.setTextSize(13);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF0D0B14);
        g.setCornerRadius(dp(8));
        e.setBackground(g);
        r.addView(e, new LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(r);
        return e;
    }

    private static int uInt(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (Exception ex) {
            return def;
        }
    }

    private static int uClamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void uCloseDialog() {
        if (dialog != null) {
            try {
                wm.removeView(dialog);
            } catch (Exception e) {
            }
            dialog = null;
        }
    }

    private void uShowDialog(final int idx) {
        final JSONObject o = acts.optJSONObject(idx);
        if (o == null || wm == null) return;
        uCloseDialog();
        final String type = o.optString("type", "click");

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2151320);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0xFF8B5CF6);
        box.setBackground(bg);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));

        String title = "Aksi " + (idx + 1) + ("swipe".equals(type) ? " - Swipe" : ("image".equals(type) ? " - Deteksi gambar" : " - Klik"));
        TextView tt = uMk(title, 14);
        tt.setTextColor(0xFFDDD6FE);
        box.addView(tt);

        final EditText fDelay = uField(box, "Jeda sesudah (ms)", String.valueOf(o.optInt("delay", 500)));
        EditText fHold = null, fJit = null, fDur = null, fThr = null, fTw = null, fTh = null;
        final String[] ok = {o.optString("ok", "click")};
        final String[] fail = {o.optString("fail", "skip")};

        if ("swipe".equals(type)) {
            fDur = uField(box, "Durasi swipe (ms)", String.valueOf(o.optInt("dur", 300)));
        } else {
            fHold = uField(box, "Tahan (ms)", String.valueOf(o.optInt("hold", 55)));
            fJit = uField(box, "Jitter (px)", String.valueOf(o.optInt("jit", 4)));
        }
        if ("image".equals(type)) {
            fThr = uField(box, "Kemiripan (%)", String.valueOf(o.optInt("thr", 85)));
            fTw = uField(box, "Lebar kotak (px)", String.valueOf(o.optInt("tw", 120)));
            fTh = uField(box, "Tinggi kotak (px)", String.valueOf(o.optInt("th", 80)));

            LinearLayout r1 = new LinearLayout(this);
            final TextView okBtn = uBtn("Ketemu: " + ok[0], 0xFF262238);
            final TextView failBtn = uBtn("Gagal: " + fail[0], 0xFF262238);
            r1.addView(okBtn);
            r1.addView(failBtn);
            box.addView(r1);
            okBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ok[0] = "click".equals(ok[0]) ? "none" : "click";
                    okBtn.setText("Ketemu: " + ok[0]);
                }
            });
            failBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if ("skip".equals(fail[0])) fail[0] = "stop";
                    else if ("stop".equals(fail[0])) fail[0] = "retry";
                    else fail[0] = "skip";
                    failBtn.setText("Gagal: " + fail[0]);
                }
            });
        }

        final EditText eHold = fHold, eJit = fJit, eDur = fDur, eThr = fThr, eTw = fTw, eTh = fTh;

        // Terapkan nilai field ke JSON aksi
        final Runnable apply = new Runnable() {
            @Override
            public void run() {
                try {
                    o.put("delay", uClamp(uInt(fDelay, 500), 0, 600000));
                    if (eDur != null) o.put("dur", uClamp(uInt(eDur, 300), 10, 10000));
                    if (eHold != null) o.put("hold", uClamp(uInt(eHold, 55), 1, 10000));
                    if (eJit != null) o.put("jit", uClamp(uInt(eJit, 4), 0, 200));
                    if (eThr != null) {
                        o.put("thr", uClamp(uInt(eThr, 85), 30, 100));
                        o.put("tw", uClamp(uInt(eTw, 120), 20, 800));
                        o.put("th", uClamp(uInt(eTh, 80), 20, 800));
                        o.put("ok", ok[0]);
                        o.put("fail", fail[0]);
                    }
                } catch (Exception e) {
                }
            }
        };

        if ("image".equals(type)) {
            LinearLayout rc = new LinearLayout(this);
            TextView cap = uBtn("Ambil gambar dari kotak", 0xFF0F766E);
            rc.addView(cap);
            box.addView(rc);
            cap.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    apply.run();
                    uPersist();
                    uCloseDialog();
                    uRebuildMarkers();
                    uCaptureTemplate(idx);
                }
            });
        }

        LinearLayout rb = new LinearLayout(this);
        TextView del = uBtn("Hapus", 0xFF7F1D1D);
        TextView cancel = uBtn("Batal", 0xFF262238);
        TextView save = uBtn("Simpan", 0xFF8B5CF6);
        rb.addView(del);
        rb.addView(cancel);
        rb.addView(save);
        box.addView(rb);

        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JSONArray n = new JSONArray();
                for (int i = 0; i < acts.length(); i++) {
                    if (i != idx) n.put(acts.opt(i));
                }
                acts = n;
                uPersist();
                uCloseDialog();
                uRebuildMarkers();
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uCloseDialog();
            }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                apply.run();
                uPersist();
                uCloseDialog();
                uRebuildMarkers();
            }
        });

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        LinearLayout wrap = new LinearLayout(this);
        wrap.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog = wrap;

        WindowManager.LayoutParams lp = uParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT, 0);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(60);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
        try {
            wm.addView(dialog, lp);
        } catch (Exception e) {
            dialog = null;
        }
    }

    // =====================================================================
    //  Menjalankan skrip Lua
    // =====================================================================

    private void startScript() {
        if (running) return;
        final String code = uActiveCode();
        if (code.trim().length() == 0) {
            uToast("Skrip kosong");
            return;
        }
        if (!luaPresent()) {
            uToast("Library LuaJ belum aktif");
            return;
        }
        uRefreshScreen();
        uCloseDialog();
        running = true;
        uUpdatePlay();
        uSetStatus("Berjalan...");
        scriptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Globals g = uNewGlobals();
                    LuaValue chunk = g.load(code, "skrip");
                    chunk.call();
                    uSetStatus("Selesai");
                } catch (LuaError e) {
                    String m = String.valueOf(e.getMessage());
                    uSetStatus(m.contains("dihentikan") ? "Dihentikan" : "Error: " + m);
                } catch (Throwable t) {
                    uSetStatus("Error: " + t);
                } finally {
                    running = false;
                    uStickUp();
                    uUpdatePlay();
                }
            }
        });
        scriptThread.start();
    }

    private void stopScript() {
        running = false;
        uUpdatePlay();
    }

    private Globals uNewGlobals() {
        Globals g = new Globals();
        g.load(new BaseLib());
        g.load(new PackageLib());
        g.load(new Bit32Lib());
        g.load(new TableLib());
        g.load(new StringLib());
        g.load(new JseMathLib());
        LoadState.install(g);
        LuaC.install(g);
        g.set("running", new Fn(1));
        g.set("sleep", new Fn(2));
        g.set("tap", new Fn(3));
        g.set("swipe", new Fn(4));
        g.set("screen", new Fn(5));
        g.set("status", new Fn(6));
        g.set("findImage", new Fn(7));
        g.set("saveTemplate", new Fn(8));
        g.set("stickDown", new Fn(9));
        g.set("stickMove", new Fn(10));
        g.set("stickUp", new Fn(11));
        g.set("toast", new Fn(12));
        g.set("rand", new Fn(13));
        return g;
    }

    /** Satu kelas untuk semua fungsi Lua; `op` memilih fungsinya. */
    private class Fn extends VarArgFunction {
        final int op;

        Fn(int op) {
            this.op = op;
        }

        @Override
        public Varargs invoke(Varargs a) {
            switch (op) {
                case 1:
                    return LuaValue.valueOf(running);
                case 2:
                    uSleep(a.optlong(1, 100));
                    return NONE;
                case 3:
                    uCheck();
                    uTap(a.todouble(1), a.todouble(2), a.optint(3, 55), a.optint(4, 0));
                    return NONE;
                case 4:
                    uCheck();
                    uSwipe(a.todouble(1), a.todouble(2), a.todouble(3), a.todouble(4), a.optint(5, 300));
                    return NONE;
                case 5:
                    return LuaValue.varargsOf(LuaValue.valueOf(W), LuaValue.valueOf(H));
                case 6:
                    uSetStatus(a.tojstring(1));
                    return NONE;
                case 7: {
                    uCheck();
                    int[] r = uFind(uClean(a.tojstring(1)), a.optdouble(2, 0.85));
                    if (r == null) return NIL;
                    return LuaValue.varargsOf(LuaValue.valueOf(r[0]), LuaValue.valueOf(r[1]));
                }
                case 8:
                    uCheck();
                    return LuaValue.valueOf(uSaveTemplate(uClean(a.tojstring(1)), a.toint(2), a.toint(3), a.toint(4), a.toint(5)));
                case 9:
                    uStickDown((float) a.todouble(1), (float) a.todouble(2));
                    return NONE;
                case 10:
                    uStickMove((float) a.todouble(1), (float) a.todouble(2));
                    return NONE;
                case 11:
                    uStickUp();
                    return NONE;
                case 12:
                    uToast(a.tojstring(1));
                    return NONE;
                case 13: {
                    int lo = a.optint(1, 0), hi = a.optint(2, 100);
                    if (hi < lo) hi = lo;
                    return LuaValue.valueOf(lo + rnd.nextInt(hi - lo + 1));
                }
            }
            return NONE;
        }
    }

    private void uCheck() {
        if (!running) throw new LuaError("dihentikan");
    }

    private void uSleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            uCheck();
            long left = end - System.currentTimeMillis();
            if (left <= 0) break;
            try {
                Thread.sleep(Math.min(left, 40));
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    // =====================================================================
    //  Gestur
    // =====================================================================

    private boolean uDispatch(GestureDescription g) {
        final CountDownLatch l = new CountDownLatch(1);
        final boolean[] ok = new boolean[1];
        boolean sent = dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) {
                ok[0] = true;
                l.countDown();
            }

            @Override
            public void onCancelled(GestureDescription d) {
                l.countDown();
            }
        }, main);
        if (!sent) return false;
        try {
            l.await(6, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
        }
        return ok[0];
    }

    private void uTap(double x, double y, int hold, int jit) {
        if (jit > 0) {
            x += rnd.nextInt(jit * 2 + 1) - jit;
            y += rnd.nextInt(jit * 2 + 1) - jit;
        }
        Path p = new Path();
        p.moveTo((float) Math.max(0, x), (float) Math.max(0, y));
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(1, hold)));
        uDispatch(b.build());
    }

    private void uSwipe(double x1, double y1, double x2, double y2, int dur) {
        Path p = new Path();
        p.moveTo((float) Math.max(0, x1), (float) Math.max(0, y1));
        p.lineTo((float) Math.max(0, x2), (float) Math.max(0, y2));
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(10, dur)));
        uDispatch(b.build());
    }

    // ---- Joystick analog (tahan jari, Android 8+) ----

    private void uStickDown(float x, float y) {
        if (Build.VERSION.SDK_INT < 26) return;
        Path p = new Path();
        p.moveTo(x, y);
        stick = new GestureDescription.StrokeDescription(p, 0, 60, true);
        stickX = x;
        stickY = y;
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(stick);
        dispatchGesture(b.build(), null, null);
    }

    private void uStickMove(float x, float y) {
        if (Build.VERSION.SDK_INT < 26 || stick == null) return;
        Path p = new Path();
        p.moveTo(stickX, stickY);
        p.lineTo(x, y);
        stick = stick.continueStroke(p, 0, 80, true);
        stickX = x;
        stickY = y;
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(stick);
        dispatchGesture(b.build(), null, null);
    }

    private void uStickUp() {
        if (Build.VERSION.SDK_INT < 26 || stick == null) return;
        try {
            Path p = new Path();
            p.moveTo(stickX, stickY);
            GestureDescription.StrokeDescription end = stick.continueStroke(p, 0, 40, false);
            stick = null;
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(end);
            dispatchGesture(b.build(), null, null);
        } catch (Exception e) {
            stick = null;
        }
    }

    // =====================================================================
    //  Screenshot + deteksi gambar
    // =====================================================================

    /** Screenshot lewat refleksi (API 30+). Mengembalikan null bila gagal. */
    private Bitmap uShot() {
        if (Build.VERSION.SDK_INT < 30) return null;
        long wait = 450 - (System.currentTimeMillis() - lastShotTs);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
            }
        }
        final Bitmap[] out = new Bitmap[1];
        final CountDownLatch l = new CountDownLatch(1);
        try {
            Class<?> cbCls = Class.forName("android.accessibilityservice.AccessibilityService$TakeScreenshotCallback");
            Object cb = Proxy.newProxyInstance(cbCls.getClassLoader(), new Class<?>[]{cbCls}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method m, Object[] args) {
                    if (m.getDeclaringClass() == Object.class) {
                        String n = m.getName();
                        if ("hashCode".equals(n)) return System.identityHashCode(proxy);
                        if ("equals".equals(n)) return proxy == args[0];
                        return "shotcb";
                    }
                    try {
                        if ("onSuccess".equals(m.getName()) && args != null && args.length > 0) {
                            Object res = args[0];
                            Object hb = res.getClass().getMethod("getHardwareBuffer").invoke(res);
                            Object cs = res.getClass().getMethod("getColorSpace").invoke(res);
                            Class<?> hbc = Class.forName("android.hardware.HardwareBuffer");
                            Class<?> csc = Class.forName("android.graphics.ColorSpace");
                            Bitmap hw = (Bitmap) Bitmap.class.getMethod("wrapHardwareBuffer", hbc, csc).invoke(null, hb, cs);
                            if (hw != null) {
                                out[0] = hw.copy(Bitmap.Config.ARGB_8888, false);
                                hw.recycle();
                            }
                            hbc.getMethod("close").invoke(hb);
                        }
                    } catch (Throwable t) {
                    }
                    l.countDown();
                    return null;
                }
            });
            Method tm = AccessibilityService.class.getMethod("takeScreenshot", int.class, Executor.class, cbCls);
            tm.invoke(this, 0, new Executor() {
                @Override
                public void execute(Runnable r) {
                    main.post(r);
                }
            }, cb);
            l.await(4, TimeUnit.SECONDS);
        } catch (Throwable t) {
        }
        lastShotTs = System.currentTimeMillis();
        return out[0];
    }

    private File uTplFile(String name) {
        return new File(getFilesDir(), "tpl_" + name + ".png");
    }

    private Bitmap uTemplate(String name) {
        Bitmap b = templates.get(name);
        if (b != null) return b;
        File f = uTplFile(name);
        if (!f.exists()) return null;
        b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b != null) templates.put(name, b);
        return b;
    }

    private boolean uCropSave(Bitmap s, String name, int x, int y, int w, int h) {
        try {
            float k = s.getWidth() / (float) W;
            int sx = Math.max(0, (int) (x * k)), sy = Math.max(0, (int) (y * k));
            int sw = (int) (w * k), sh = (int) (h * k);
            if (sx + sw > s.getWidth()) sw = s.getWidth() - sx;
            if (sy + sh > s.getHeight()) sh = s.getHeight() - sy;
            if (sw < 8 || sh < 8) return false;
            Bitmap c = Bitmap.createBitmap(s, sx, sy, sw, sh);
            FileOutputStream fo = new FileOutputStream(uTplFile(name));
            c.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            templates.put(name, c);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean uSaveTemplate(String name, int x, int y, int w, int h) {
        Bitmap s = uShot();
        if (s == null) {
            uSetStatus("Screenshot gagal (butuh Android 11+ dan canTakeScreenshot)");
            return false;
        }
        boolean ok = uCropSave(s, name, x, y, w, h);
        s.recycle();
        return ok;
    }

    private void uCaptureTemplate(int idx) {
        JSONObject o = acts.optJSONObject(idx);
        if (o == null) return;
        if (Build.VERSION.SDK_INT < 30) {
            uToast("Screenshot butuh Android 11+");
            return;
        }
        final String name = uClean(o.optString("tpl", "img"));
        final int tw = o.optInt("tw", 120), th = o.optInt("th", 80);
        final int cx = (int) (o.optDouble("fx", 0.5) * W), cy = (int) (o.optDouble("fy", 0.5) * H);
        main.post(new Runnable() {
            @Override
            public void run() {
                uOverlayVisible(false);
            }
        });
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(350);
                } catch (InterruptedException e) {
                }
                Bitmap s = uShot();
                boolean ok = false;
                if (s != null) {
                    ok = uCropSave(s, name, cx - tw / 2, cy - th / 2, tw, th);
                    s.recycle();
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        uOverlayVisible(true);
                    }
                });
                uToast(ok ? "Gambar tersimpan" : "Gagal mengambil gambar");
            }
        }).start();
    }

    /** Cari template di layar. Mengembalikan {x, y} pusat, atau null. */
    private int[] uFind(String name, double thr) {
        Bitmap tpl = uTemplate(name);
        if (tpl == null) {
            uSetStatus("Template '" + name + "' belum ada");
            return null;
        }
        Bitmap scr = uShot();
        if (scr == null) {
            uSetStatus("Screenshot gagal (butuh Android 11+ dan canTakeScreenshot)");
            return null;
        }
        int[] r = uMatch(scr, tpl, thr);
        scr.recycle();
        return r;
    }

    private static int[] uGray(Bitmap b, int w, int h) {
        Bitmap sc = Bitmap.createScaledBitmap(b, w, h, true);
        int[] px = new int[w * h];
        sc.getPixels(px, 0, w, 0, 0, w, h);
        if (sc != b) sc.recycle();
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = (((c >> 16) & 0xFF) * 30 + ((c >> 8) & 0xFF) * 59 + (c & 0xFF) * 11) / 100;
        }
        return px;
    }

    /** Pencocokan template (SAD) pada gambar yang diperkecil. */
    private static int[] uMatch(Bitmap scr, Bitmap tpl, double thr) {
        int s = Math.max(1, Math.min(4, Math.min(tpl.getWidth(), tpl.getHeight()) / 12));
        int sw = scr.getWidth() / s, sh = scr.getHeight() / s;
        int tw = Math.max(2, tpl.getWidth() / s), th = Math.max(2, tpl.getHeight() / s);
        if (tw >= sw || th >= sh) return null;
        int[] sg = uGray(scr, sw, sh);
        int[] tg = uGray(tpl, tw, th);
        int limit = (int) ((1.0 - thr) * 255.0 * tw * th);
        int best = Integer.MAX_VALUE, bx = -1, by = -1;
        for (int y = 0; y <= sh - th; y++) {
            for (int x = 0; x <= sw - tw; x++) {
                int sum = 0;
                int cut = Math.min(best, limit + 1);
                boolean dead = false;
                for (int ty = 0; ty < th; ty++) {
                    int base = (y + ty) * sw + x, tb = ty * tw;
                    for (int tx = 0; tx < tw; tx++) {
                        int d = sg[base + tx] - tg[tb + tx];
                        sum += d < 0 ? -d : d;
                    }
                    if (sum >= cut) {
                        dead = true;
                        break;
                    }
                }
                if (!dead && sum < best) {
                    best = sum;
                    bx = x;
                    by = y;
                }
            }
        }
        if (bx < 0 || best > limit) return null;
        return new int[]{(bx + tw / 2) * s, (by + th / 2) * s};
    }
}
