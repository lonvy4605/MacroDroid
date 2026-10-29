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
 *          (fx, fy = PUSAT area gambar; tw, th = ukuran area dalam piksel)
 *
 * Syarat manifest / konfigurasi service aksesibilitas:
 *   android:canPerformGestures="true"
 *   android:canTakeScreenshot="true"   (untuk findImage, Android 11+)
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
    private Runnable syncRun;
    private String lastSync = "";

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
            prefs.edit().putString("scripts", arr.toString()).commit();
            lastSync = uSnapshot();
        } catch (Exception e) {
        }
    }

    private String uSnapshot() {
        return prefs.getString("scripts", "[]") + "|" + prefs.getInt("active", 0);
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

    private void uTint(TextView tv, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(10));
        tv.setBackground(g);
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

    private TextView uHudBtn(String text, int color, View.OnClickListener l) {
        TextView tv = uMk(text, 16);
        tv.setMinWidth(dp(38));
        uTint(tv, color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), 0, dp(2), 0);
        tv.setLayoutParams(lp);
        if (l != null) tv.setOnClickListener(l);
        return tv;
    }

    private void uBuildHud() {
        if (hud != null || wm == null) return;
        uRefreshScreen();

        hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6151320);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0xFF262238);
        hud.setBackground(bg);
        hud.setPadding(dp(4), dp(4), dp(4), dp(6));

        // geser HUD
        final float[] st = new float[4];
        View.OnTouchListener dragger = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (hudLp == null || hud == null) return false;
                int a = e.getAction();
                if (a == MotionEvent.ACTION_DOWN) {
                    st[0] = e.getRawX();
                    st[1] = e.getRawY();
                    st[2] = hudLp.x;
                    st[3] = hudLp.y;
                    return true;
                } else if (a == MotionEvent.ACTION_MOVE) {
                    hudLp.x = (int) (st[2] + e.getRawX() - st[0]);
                    hudLp.y = (int) (st[3] + e.getRawY() - st[1]);
                    try {
                        wm.updateViewLayout(hud, hudLp);
                    } catch (Exception ex) {
                    }
                    return true;
                }
                return true;
            }
        };

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView handle = uHudBtn("\u2261", 0xFF262238, null);
        handle.setOnTouchListener(dragger);
        row.addView(handle);

        playBtn = uHudBtn("\u25B6", 0xFF10B981, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (running) stopScript();
                else startScript();
            }
        });
        row.addView(playBtn);

        row.addView(uHudBtn("+", 0xFF8B5CF6, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uAddAction("click");
            }
        }));
        row.addView(uHudBtn("\u2194", 0xFF047857, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uAddAction("swipe");
            }
        }));
        row.addView(uHudBtn("\u25A3", 0xFFB45309, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uAddAction("image");
            }
        }));
        eyeBtn = uHudBtn("\u25C9", 0xFF262238, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editVisible = !editVisible;
                if (eyeBtn != null) eyeBtn.setText(editVisible ? "\u25C9" : "\u25CB");
                uRebuildMarkers();
            }
        });
        row.addView(eyeBtn);
        row.addView(uHudBtn("\u2715", 0xFF7F1D1D, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopScript();
                uRemoveHud();
            }
        }));
        hud.addView(row);

        statusTv = new TextView(this);
        statusTv.setText(statusText);
        statusTv.setTextColor(0xFF9CA3AF);
        statusTv.setTextSize(11);
        statusTv.setSingleLine(true);
        statusTv.setEllipsize(TextUtils.TruncateAt.END);
        statusTv.setMaxWidth(dp(260));
        statusTv.setPadding(dp(8), dp(4), dp(8), 0);
        statusTv.setOnTouchListener(dragger);
        hud.addView(statusTv);

        hudLp = uParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        hudLp.x = dp(8);
        hudLp.y = dp(90);
        try {
            wm.addView(hud, hudLp);
        } catch (Exception e) {
            hud = null;
            return;
        }
        uUpdatePlay();

        // sinkron otomatis bila skrip diubah dari dashboard
        syncRun = new Runnable() {
            @Override
            public void run() {
                if (hud == null) return;
                if (!running && dialog == null && editVisible) {
                    String cur = uSnapshot();
                    if (!cur.equals(lastSync)) uRebuildMarkers();
                }
                main.postDelayed(this, 1000);
            }
        };
        main.postDelayed(syncRun, 1000);
    }

    private void uRemoveHud() {
        if (syncRun != null) main.removeCallbacks(syncRun);
        uCloseDialog();
        uClearMarkers();
        if (hud != null && wm != null) {
            try {
                wm.removeView(hud);
            } catch (Exception e) {
            }
        }
        hud = null;
        playBtn = null;
        statusTv = null;
        eyeBtn = null;
    }

    private void uUpdatePlay() {
        if (playBtn == null) return;
        playBtn.setText(running ? "\u25A0" : "\u25B6");
        uTint(playBtn, running ? 0xFFDC2626 : 0xFF10B981);
    }

    private void uSetOverlayVisible(boolean v) {
        int vis = v ? View.VISIBLE : View.INVISIBLE;
        if (hud != null) hud.setVisibility(vis);
        if (lines != null) lines.setVisibility(vis);
        for (int i = 0; i < markers.size(); i++) markers.get(i).setVisibility(vis);
    }

    // =====================================================================
    //  Penanda (editor visual)
    // =====================================================================

    private void uClearMarkers() {
        if (wm != null) {
            for (int i = 0; i < markers.size(); i++) {
                try {
                    wm.removeView(markers.get(i));
                } catch (Exception e) {
                }
            }
            if (lines != null) {
                try {
                    wm.removeView(lines);
                } catch (Exception e) {
                }
            }
        }
        markers.clear();
        lines = null;
    }

    private void uRebuildMarkers() {
        uClearMarkers();
        if (hud == null || wm == null) return;
        uRefreshScreen();
        boolean visual = uLoadActive();
        lastSync = uSnapshot();
        if (!visual) {
            if (!running) uSetStatus("Skrip Lua aktif (tanpa penanda)");
            return;
        }
        if (!running) uSetStatus("Siap: " + acts.length() + " aksi");
        if (!editVisible || running) return;

        lines = new LinesView(this);
        linesLp = uParams(W, H,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        linesLp.x = 0;
        linesLp.y = 0;
        try {
            wm.addView(lines, linesLp);
        } catch (Exception e) {
            lines = null;
        }

        for (int i = 0; i < acts.length(); i++) {
            JSONObject o = acts.optJSONObject(i);
            if (o == null) continue;
            String t = o.optString("type", "click");
            uAddMarker(i, false, (float) (o.optDouble("fx", 0.5) * W), (float) (o.optDouble("fy", 0.5) * H), t);
            if ("swipe".equals(t)) {
                uAddMarker(i, true, (float) (o.optDouble("fx2", 0.5) * W), (float) (o.optDouble("fy2", 0.6) * H), t);
            }
        }
    }

    private void uAddMarker(int idx, boolean end, float cx, float cy, String type) {
        MarkerView mv = new MarkerView(this, idx, end, type);
        int size = dp(40);
        WindowManager.LayoutParams lp = uParams(size, size,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        lp.x = (int) cx - size / 2;
        lp.y = (int) cy - size / 2;
        mv.lp = lp;
        try {
            wm.addView(mv, lp);
            markers.add(mv);
        } catch (Exception e) {
        }
    }

    private void uMoveAction(int idx, boolean end, float cx, float cy) {
        JSONObject o = acts.optJSONObject(idx);
        if (o == null) return;
        double fx = Math.max(0, Math.min(1, cx / (double) W));
        double fy = Math.max(0, Math.min(1, cy / (double) H));
        try {
            if (end) {
                o.put("fx2", fx);
                o.put("fy2", fy);
            } else {
                o.put("fx", fx);
                o.put("fy", fy);
            }
        } catch (Exception e) {
        }
        if (lines != null) lines.invalidate();
    }

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
            double sh = (n % 6) * 0.04;
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("fx", 0.5 + sh);
            o.put("fy", 0.45 + sh);
            o.put("delay", 500);
            if ("swipe".equals(type)) {
                o.put("fx2", 0.5 + sh);
                o.put("fy2", 0.30 + sh);
                o.put("dur", 300);
            } else {
                o.put("hold", 55);
                o.put("jit", 4);
                if ("image".equals(type)) {
                    o.put("tw", 120);
                    o.put("th", 60);
                    o.put("tpl", "tpl" + (n + 1));
                    o.put("thr", 85);
                    o.put("ok", "click");
                    o.put("fail", "skip");
                }
            }
            acts.put(o);
            uPersist();
            editVisible = true;
            if (eyeBtn != null) eyeBtn.setText("\u25C9");
            uRebuildMarkers();
        } catch (Exception e) {
        }
    }

    private class MarkerView extends View {
        final int idx;
        final boolean end;
        final String type;
        WindowManager.LayoutParams lp;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String label;
        private float dx, dy, sx, sy;
        private boolean moved;

        MarkerView(Context c, int idx, boolean end, String type) {
            super(c);
            this.idx = idx;
            this.end = end;
            this.type = type;
            int col = "swipe".equals(type) ? (end ? 0xCC059669 : 0xCC10B981)
                : ("image".equals(type) ? 0xCCF59E0B : 0xCC8B5CF6);
            fill.setColor(col);
            fill.setStyle(Paint.Style.FILL);
            ring.setColor(0xFFFFFFFF);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2));
            txt.setColor(0xFFFFFFFF);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTextSize(dp(14));
            txt.setFakeBoldText(true);
            label = (idx + 1) + (end ? "'" : "");
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - dp(2);
            c.drawCircle(cx, cy, r, fill);
            c.drawCircle(cx, cy, r, ring);
            float ty = cy - (txt.ascent() + txt.descent()) / 2f;
            c.drawText(label, cx, ty, txt);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getAction();
            if (a == MotionEvent.ACTION_DOWN) {
                dx = e.getRawX();
                dy = e.getRawY();
                sx = lp.x;
                sy = lp.y;
                moved = false;
                return true;
            } else if (a == MotionEvent.ACTION_MOVE) {
                float mx = e.getRawX() - dx, my = e.getRawY() - dy;
                if (!moved && (Math.abs(mx) > dp(6) || Math.abs(my) > dp(6))) moved = true;
                if (moved) {
                    lp.x = (int) (sx + mx);
                    lp.y = (int) (sy + my);
                    try {
                        wm.updateViewLayout(this, lp);
                    } catch (Exception ex) {
                    }
                    uMoveAction(idx, end, lp.x + getWidth() / 2f, lp.y + getHeight() / 2f);
                }
                return true;
            } else if (a == MotionEvent.ACTION_UP) {
                if (moved) uPersist();
                else uOpenDialog(idx);
                return true;
            }
            return true;
        }
    }

    private class LinesView extends View {
        private final Paint pl = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ps = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pr = new Paint(Paint.ANTI_ALIAS_FLAG);

        LinesView(Context c) {
            super(c);
            pl.setColor(0x889CA3AF);
            pl.setStrokeWidth(dp(2));
            ps.setColor(0xCC10B981);
            ps.setStrokeWidth(dp(4));
            pr.setColor(0xFFF59E0B);
            pr.setStyle(Paint.Style.STROKE);
            pr.setStrokeWidth(dp(2));
        }

        @Override
        protected void onDraw(Canvas c) {
            float px = -1, py = -1;
            for (int i = 0; i < acts.length(); i++) {
                JSONObject o = acts.optJSONObject(i);
                if (o == null) continue;
                String t = o.optString("type", "click");
                float x = (float) (o.optDouble("fx", 0.5) * W);
                float y = (float) (o.optDouble("fy", 0.5) * H);
                if (px >= 0) c.drawLine(px, py, x, y, pl);
                if ("swipe".equals(t)) {
                    float x2 = (float) (o.optDouble("fx2", 0.5) * W);
                    float y2 = (float) (o.optDouble("fy2", 0.6) * H);
                    c.drawLine(x, y, x2, y2, ps);
                    px = x2;
                    py = y2;
                } else {
                    if ("image".equals(t)) {
                        float hw = o.optInt("tw", 120) / 2f, hh = o.optInt("th", 60) / 2f;
                        c.drawRect(new RectF(x - hw, y - hh, x + hw, y + hh), pr);
                    }
                    px = x;
                    py = y;
                }
            }
        }
    }

    // =====================================================================
    //  Dialog pengaturan aksi
    // =====================================================================

    private EditText uField(LinearLayout parent, String label, String value, boolean numeric) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(0xFF9CA3AF);
        l.setTextSize(12);
        l.setPadding(0, dp(8), 0, dp(2));
        parent.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(0xFFFFFFFF);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF0D0B14);
        g.setCornerRadius(dp(8));
        g.setStroke(dp(1), 0xFF262238);
        e.setBackground(g);
        parent.addView(e);
        return e;
    }

    private TextView uCycle(LinearLayout parent, String label, final String[] opts, String cur) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(0xFF9CA3AF);
        l.setTextSize(12);
        l.setPadding(0, dp(8), 0, dp(2));
        parent.addView(l);
        final TextView t = uMk(cur, 14);
        uTint(t, 0xFF262238);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String c = t.getText().toString();
                int k = 0;
                for (int i = 0; i < opts.length; i++) if (opts[i].equals(c)) k = i;
                t.setText(opts[(k + 1) % opts.length]);
            }
        });
        parent.addView(t);
        return t;
    }

    private int uInt(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (Exception ex) {
            return def;
        }
    }

    private void uCloseDialog() {
        if (dialog != null && wm != null) {
            try {
                wm.removeView(dialog);
            } catch (Exception e) {
            }
        }
        dialog = null;
    }

    private void uOpenDialog(final int idx) {
        if (wm == null || running) return;
        uCloseDialog();
        final JSONObject o = acts.optJSONObject(idx);
        if (o == null) return;
        final String type = o.optString("type", "click");
        final boolean isSwipe = "swipe".equals(type);
        final boolean isImage = "image".equals(type);

        final LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFA151320);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0xFF8B5CF6);
        outer.setBackground(bg);
        outer.setPadding(dp(14), dp(12), dp(14), dp(10));

        TextView title = uMk("Aksi " + (idx + 1) + " - " + (isSwipe ? "Geser" : (isImage ? "Deteksi gambar" : "Klik")), 16);
        title.setGravity(Gravity.LEFT);
        title.setPadding(0, 0, 0, dp(4));
        outer.addView(title);

        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        sv.addView(box);
        outer.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // urutan deklarasi = urutan tampil
        final EditText eTpl = isImage ? uField(box, "Nama template", o.optString("tpl", "tpl" + (idx + 1)), false) : null;
        final EditText eTw = isImage ? uField(box, "Lebar area (px)", String.valueOf(o.optInt("tw", 120)), true) : null;
        final EditText eTh = isImage ? uField(box, "Tinggi area (px)", String.valueOf(o.optInt("th", 60)), true) : null;
        final EditText eThr = isImage ? uField(box, "Kecocokan minimum (%)", String.valueOf(o.optInt("thr", 85)), true) : null;
        final TextView cOk = isImage ? uCycle(box, "Bila ketemu (ketuk untuk ganti)", new String[]{"click", "none"}, o.optString("ok", "click")) : null;
        final TextView cFail = isImage ? uCycle(box, "Bila tidak ketemu (ketuk untuk ganti)", new String[]{"skip", "stop", "retry"}, o.optString("fail", "skip")) : null;
        final EditText eHold = !isSwipe ? uField(box, "Tahan (ms)", String.valueOf(o.optInt("hold", 55)), true) : null;
        final EditText eJit = !isSwipe ? uField(box, "Jitter posisi (px)", String.valueOf(o.optInt("jit", 4)), true) : null;
        final EditText eDur = isSwipe ? uField(box, "Durasi geser (ms)", String.valueOf(o.optInt("dur", 300)), true) : null;
        final EditText eDelay = uField(box, "Jeda setelah aksi (ms)", String.valueOf(o.optInt("delay", 500)), true);

        final Runnable apply = new Runnable() {
            @Override
            public void run() {
                try {
                    o.put("delay", Math.max(0, uInt(eDelay, 500)));
                    if (eHold != null) o.put("hold", Math.max(1, uInt(eHold, 55)));
                    if (eJit != null) o.put("jit", Math.max(0, uInt(eJit, 4)));
                    if (eDur != null) o.put("dur", Math.max(1, uInt(eDur, 300)));
                    if (eTpl != null) {
                        String nm = uClean(eTpl.getText().toString().trim());
                        o.put("tpl", nm.length() == 0 ? "tpl" + (idx + 1) : nm);
                        o.put("tw", Math.max(8, uInt(eTw, 120)));
                        o.put("th", Math.max(8, uInt(eTh, 60)));
                        o.put("thr", Math.max(30, Math.min(100, uInt(eThr, 85))));
                        o.put("ok", cOk.getText().toString());
                        o.put("fail", cFail.getText().toString());
                    }
                } catch (Exception e) {
                }
                uPersist();
            }
        };

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView bSave = uBtn("Simpan", 0xFF8B5CF6);
        TextView bDel = uBtn("Hapus", 0xFF7F1D1D);
        TextView bClose = uBtn("Tutup", 0xFF262238);
        btns.addView(bDel);
        btns.addView(bClose);
        btns.addView(bSave);

        if (isImage) {
            TextView bCap = uBtn("Ambil template dari layar", 0xFFB45309);
            outer.addView(bCap);
            bCap.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    apply.run();
                    String nm = o.optString("tpl", "tpl" + (idx + 1));
                    int tw = o.optInt("tw", 120), th = o.optInt("th", 60);
                    int cx = (int) (o.optDouble("fx", 0.5) * W), cy = (int) (o.optDouble("fy", 0.5) * H);
                    uCloseDialog();
                    uCaptureFromScreen(nm, cx - tw / 2, cy - th / 2, tw, th);
                }
            });
        }
        outer.addView(btns);

        bSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                apply.run();
                uCloseDialog();
                uRebuildMarkers();
            }
        });
        bDel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                acts.remove(idx);
                uPersist();
                uCloseDialog();
                uRebuildMarkers();
            }
        });
        bClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uCloseDialog();
            }
        });

        int hgt = isImage ? dp(560) : (isSwipe ? dp(330) : dp(380));
        hgt = Math.min(hgt, H - dp(120));
        WindowManager.LayoutParams lp = uParams(W - dp(24), hgt, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        lp.x = dp(12);
        lp.y = dp(40);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
        try {
            wm.addView(outer, lp);
            dialog = outer;
        } catch (Exception e) {
            dialog = null;
        }
    }

    // =====================================================================
    //  Menjalankan skrip Lua
    // =====================================================================

    private void startScript() {
        if (running) return;
        if (scriptThread != null && scriptThread.isAlive()) {
            uToast("Skrip sebelumnya masih berhenti, coba lagi");
            return;
        }
        if (!luaPresent()) {
            uToast("LuaJ belum dipasang (Local library)");
            return;
        }
        final String code = uActiveCode();
        if (code.length() == 0) {
            uToast("Skrip kosong");
            return;
        }
        uRefreshScreen();
        uCloseDialog();
        running = true;
        uClearMarkers();
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
                    if (running) {
                        String m = String.valueOf(e.getMessage());
                        if (m.length() > 140) m = m.substring(0, 140);
                        uSetStatus("Error: " + m);
                        uToast("Error Lua: " + m);
                    } else {
                        uSetStatus("Dihentikan");
                    }
                } catch (Throwable t) {
                    uSetStatus("Gagal: " + t);
                } finally {
                    running = false;
                    uStickRelease();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            uUpdatePlay();
                            if (hud != null) {
                                String keep = statusText;
                                uRebuildMarkers();
                                if (statusTv != null) {
                                    statusText = keep;
                                    statusTv.setText(keep);
                                }
                            }
                        }
                    });
                }
            }
        });
        scriptThread.start();
    }

    public void stopScript() {
        running = false;
        Thread t = scriptThread;
        if (t != null) t.interrupt();
        main.post(new Runnable() {
            @Override
            public void run() {
                uUpdatePlay();
            }
        });
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
        String[] names = {"running", "sleep", "tap", "swipe", "screen", "status", "findImage",
            "saveTemplate", "stickDown", "stickMove", "stickUp", "toast", "rand", "now"};
        for (int i = 0; i < names.length; i++) g.set(names[i], new LuaFn(i));
        return g;
    }

    private void uCheck() {
        if (!running) throw new LuaError("dihentikan");
    }

    private void uSleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            uCheck();
            long rem = end - System.currentTimeMillis();
            if (rem <= 0) break;
            try {
                Thread.sleep(Math.min(rem, 40));
            } catch (InterruptedException e) {
                throw new LuaError("dihentikan");
            }
        }
    }

    private class LuaFn extends VarArgFunction {
        final int id;

        LuaFn(int id) {
            this.id = id;
        }

        @Override
        public Varargs invoke(Varargs a) {
            switch (id) {
                case 0:
                    return LuaValue.valueOf(running);
                case 1:
                    uSleep((long) a.optdouble(1, 0));
                    return LuaValue.NONE;
                case 2:
                    uCheck();
                    uTap(a.checkdouble(1), a.checkdouble(2), a.optint(3, 55), a.optint(4, 0));
                    return LuaValue.NONE;
                case 3:
                    uCheck();
                    uSwipe(a.checkdouble(1), a.checkdouble(2), a.checkdouble(3), a.checkdouble(4), a.optint(5, 300));
                    return LuaValue.NONE;
                case 4:
                    return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(W), LuaValue.valueOf(H)});
                case 5:
                    uSetStatus(a.optjstring(1, ""));
                    return LuaValue.NONE;
                case 6: {
                    uCheck();
                    String nm = uClean(a.checkjstring(1));
                    double thr = a.optdouble(2, 0.85);
                    Bitmap tpl = uLoadTpl(nm);
                    if (tpl == null) throw new LuaError("Template tidak ada: " + nm);
                    Bitmap shot = uShot();
                    if (shot == null) {
                        uSetStatus("Screenshot gagal");
                        return LuaValue.NIL;
                    }
                    int[] r = uMatch(shot, tpl, thr);
                    shot.recycle();
                    if (r == null) return LuaValue.NIL;
                    return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(r[0]), LuaValue.valueOf(r[1])});
                }
                case 7: {
                    uCheck();
                    Bitmap shot = uShot();
                    boolean ok = uSaveTpl(shot, a.checkjstring(1), a.checkint(2), a.checkint(3), a.checkint(4), a.checkint(5));
                    if (shot != null) shot.recycle();
                    return LuaValue.valueOf(ok);
                }
                case 8:
                    uCheck();
                    uStickDown(a.checkdouble(1), a.checkdouble(2));
                    return LuaValue.NONE;
                case 9:
                    uCheck();
                    uStickMove(a.checkdouble(1), a.checkdouble(2));
                    return LuaValue.NONE;
                case 10:
                    uStickRelease();
                    return LuaValue.NONE;
                case 11:
                    uToast(a.optjstring(1, ""));
                    return LuaValue.NONE;
                case 12: {
                    int lo = a.optint(1, 0), hi = a.optint(2, 100);
                    if (hi < lo) {
                        int t = lo;
                        lo = hi;
                        hi = t;
                    }
                    return LuaValue.valueOf(lo + rnd.nextInt(hi - lo + 1));
                }
                case 13:
                    return LuaValue.valueOf((double) System.currentTimeMillis());
            }
            return LuaValue.NONE;
        }
    }

    // =====================================================================
    //  Gestur
    // =====================================================================

    private boolean uDispatch(GestureDescription g) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = new boolean[]{false};
        boolean sent = dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) {
                ok[0] = true;
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription d) {
                latch.countDown();
            }
        }, null);
        if (!sent) return false;
        try {
            latch.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
        }
        return ok[0];
    }

    private float uClampX(double x) {
        return (float) Math.max(0, Math.min(W - 1, x));
    }

    private float uClampY(double y) {
        return (float) Math.max(0, Math.min(H - 1, y));
    }

    private void uTap(double x, double y, int hold, int jit) {
        if (jit > 0) {
            x += rnd.nextInt(2 * jit + 1) - jit;
            y += rnd.nextInt(2 * jit + 1) - jit;
        }
        Path p = new Path();
        p.moveTo(uClampX(x), uClampY(y));
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(1, hold)));
        uDispatch(b.build());
    }

    private void uSwipe(double x1, double y1, double x2, double y2, int ms) {
        Path p = new Path();
        p.moveTo(uClampX(x1), uClampY(y1));
        p.lineTo(uClampX(x2), uClampY(y2));
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(1, ms)));
        uDispatch(b.build());
    }

    // Joystick analog: jari ditahan lalu digeser
    private void uStickDown(double x, double y) {
        if (Build.VERSION.SDK_INT < 26) throw new LuaError("Joystick butuh Android 8+");
        uStickRelease();
        stickX = uClampX(x);
        stickY = uClampY(y);
        Path p = new Path();
        p.moveTo(stickX, stickY);
        stick = new GestureDescription.StrokeDescription(p, 0, 1, true);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(stick);
        uDispatch(b.build());
    }

    private void uStickMove(double x, double y) {
        if (stick == null) throw new LuaError("Panggil stickDown dulu");
        float nx = uClampX(x), ny = uClampY(y);
        Path p = new Path();
        p.moveTo(stickX, stickY);
        p.lineTo(nx, ny);
        stick = stick.continueStroke(p, 0, 40, true);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(stick);
        uDispatch(b.build());
        stickX = nx;
        stickY = ny;
    }

    private void uStickRelease() {
        if (stick == null) return;
        try {
            Path p = new Path();
            p.moveTo(stickX, stickY);
            GestureDescription.StrokeDescription end = stick.continueStroke(p, 0, 1, false);
            stick = null;
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(end);
            uDispatch(b.build());
        } catch (Throwable t) {
        }
        stick = null;
    }

    // =====================================================================
    //  Screenshot + deteksi gambar
    // =====================================================================

    /** Ambil screenshot layar (Android 11+). Dipanggil dari thread non-UI. */
    private Bitmap uShot() {
        if (Build.VERSION.SDK_INT < 30) throw new LuaError("Screenshot butuh Android 11+");
        long wait = 380 - (System.currentTimeMillis() - lastShotTs);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
            }
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final Bitmap[] out = new Bitmap[1];
        try {
            Class<?> cbCls = Class.forName("android.accessibilityservice.AccessibilityService$TakeScreenshotCallback");
            Object cb = Proxy.newProxyInstance(cbCls.getClassLoader(), new Class<?>[]{cbCls}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method m, Object[] args) {
                    String n = m.getName();
                    if ("onSuccess".equals(n) && args != null && args.length > 0) {
                        try {
                            Object res = args[0];
                            Object hb = res.getClass().getMethod("getHardwareBuffer").invoke(res);
                            Object cs = res.getClass().getMethod("getColorSpace").invoke(res);
                            Class<?> hbCls = Class.forName("android.hardware.HardwareBuffer");
                            Class<?> csCls = Class.forName("android.graphics.ColorSpace");
                            Bitmap hw = (Bitmap) Bitmap.class.getMethod("wrapHardwareBuffer", hbCls, csCls).invoke(null, hb, cs);
                            if (hw != null) {
                                out[0] = hw.copy(Bitmap.Config.ARGB_8888, false);
                                hw.recycle();
                            }
                            hbCls.getMethod("close").invoke(hb);
                        } catch (Throwable t) {
                        }
                        latch.countDown();
                    } else if ("onFailure".equals(n)) {
                        latch.countDown();
                    }
                    return null;
                }
            });
            Method ts = AccessibilityService.class.getMethod("takeScreenshot", int.class, Executor.class, cbCls);
            Executor ex = new Executor() {
                @Override
                public void execute(Runnable r) {
                    new Thread(r).start();
                }
            };
            ts.invoke(this, 0, ex, cb);
            latch.await(5, TimeUnit.SECONDS);
        } catch (Throwable t) {
        }
        lastShotTs = System.currentTimeMillis();
        return out[0];
    }

    private File uTplDir() {
        File d = new File(getFilesDir(), "templates");
        d.mkdirs();
        return d;
    }

    private Bitmap uLoadTpl(String name) {
        name = uClean(name);
        Bitmap b = templates.get(name);
        if (b != null) return b;
        File f = new File(uTplDir(), name + ".png");
        if (!f.exists()) return null;
        b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b != null) templates.put(name, b);
        return b;
    }

    private boolean uSaveTpl(Bitmap shot, String name, int x, int y, int w, int h) {
        if (shot == null) return false;
        try {
            x = Math.max(0, Math.min(x, shot.getWidth() - 2));
            y = Math.max(0, Math.min(y, shot.getHeight() - 2));
            w = Math.max(2, Math.min(w, shot.getWidth() - x));
            h = Math.max(2, Math.min(h, shot.getHeight() - y));
            Bitmap c = Bitmap.createBitmap(shot, x, y, w, h);
            String nm = uClean(name);
            FileOutputStream fo = new FileOutputStream(new File(uTplDir(), nm + ".png"));
            c.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            templates.remove(nm);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Dari dialog: sembunyikan overlay, ambil screenshot, potong area, simpan sebagai template. */
    private void uCaptureFromScreen(final String name, final int x, final int y, final int w, final int h) {
        uSetOverlayVisible(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String err = "";
                try {
                    try {
                        Thread.sleep(350);
                    } catch (InterruptedException e) {
                    }
                    Bitmap shot = uShot();
                    ok = uSaveTpl(shot, name, x, y, w, h);
                    if (shot != null) shot.recycle();
                } catch (Throwable t) {
                    err = " (" + t.getMessage() + ")";
                }
                final String msg = ok ? "Template \"" + uClean(name) + "\" tersimpan" : "Gagal ambil template" + err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        uSetOverlayVisible(true);
                        Toast.makeText(MacroService.this, msg, Toast.LENGTH_SHORT).show();
                        uRebuildMarkers();
                    }
                });
            }
        }).start();
    }

    private int[] uGray(Bitmap src, int w, int h) {
        Bitmap sc = (src.getWidth() == w && src.getHeight() == h) ? src : Bitmap.createScaledBitmap(src, w, h, true);
        int[] px = new int[w * h];
        sc.getPixels(px, 0, w, 0, 0, w, h);
        if (sc != src) sc.recycle();
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            px[i] = (((c >> 16) & 255) * 30 + ((c >> 8) & 255) * 59 + (c & 255) * 11) / 100;
        }
        return px;
    }

    /** Template matching (selisih absolut, grayscale, diperkecil). Mengembalikan pusat kecocokan terbaik atau null. */
    private int[] uMatch(Bitmap shot, Bitmap tpl, double thr) {
        int tw = tpl.getWidth(), th = tpl.getHeight();
        int s = Math.max(1, Math.min(4, Math.min(tw, th) / 12));
        int sw = shot.getWidth() / s, sh = shot.getHeight() / s;
        int mw = tw / s, mh = th / s;
        if (mw < 2 || mh < 2 || mw > sw || mh > sh) return null;
        int[] a = uGray(shot, sw, sh);
        int[] b = uGray(tpl, mw, mh);
        long limit = (long) ((1.0 - thr) * 255.0 * mw * mh);
        long best = -1;
        int bx = -1, by = -1;
        for (int y = 0; y <= sh - mh; y++) {
            if (!running) return null;
            for (int x = 0; x <= sw - mw; x++) {
                long sad = 0;
                boolean bad = false;
                for (int ty = 0; ty < mh; ty++) {
                    int ro = (y + ty) * sw + x, to = ty * mw;
                    for (int tx = 0; tx < mw; tx++) {
                        int d = a[ro + tx] - b[to + tx];
                        sad += d < 0 ? -d : d;
                    }
                    if (sad > limit) {
                        bad = true;
                        break;
                    }
                }
                if (!bad) {
                    limit = sad;
                    best = sad;
                    bx = x;
                    by = y;
                }
            }
        }
        if (best < 0) return null;
        return new int[]{bx * s + tw / 2, by * s + th / 2};
    }
}
