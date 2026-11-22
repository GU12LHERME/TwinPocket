package com.twinsanityevolved.game;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.twinsanityevolved.game.Lang.T;

/**
 * The app's entry (launcher icon), in the app's main process, with no native code and no SDL. A landscape screen for a
 * handheld: on the left the game (the chosen disc image, its state, a large JOGAR button, the ISO picker and what the
 * last start did); on the right the settings by category (Gráficos, Áudio, Controles, Sistema) and the Diagnóstico
 * page (the report, the probe, the SDL test, the diagnostic mode).
 *
 * The settings are the game's own, in ps2r.ini (Settings.java): the game's menus (Options > graphic options, Options >
 * android settings) and this screen edit the same keys, and every change here is written at once.
 *
 * Controller: the D-pad moves between the rows (Android's focus search), left / right change a setting, A chooses (the
 * system's fallback of BUTTON_A to DPAD_CENTER), L1 / R1 change category, START plays, B comes back to JOGAR.
 */
public final class LauncherActivity extends Activity {
    private static final int REQUEST_PICK_ISO = 41;
    private final AtomicBoolean copyCancel = new AtomicBoolean(false);
    private boolean copying;
    private boolean starting;

    // the screen
    private View playButton;
    private TextView isoName, isoState, lastRun;
    private LinearLayout page;          // the one scrolling column: the game, then every section
    private ScrollView pageScroll;
    private final List<View> sections = new ArrayList<>();   // the section headers (L1 / R1 jump between them)
    private LinearLayout pendingRow;    // a half-filled row of the two-column card grid
    private int currentTab = 0;
    private TextView status;            // the diagnostics' status text

    // the palette
    static final int BG = 0xFF2E7D32, CARD = 0x66173F18, CARD_FOCUS = 0x88204F20, TEXT = 0xFFFFFFFF, SUBTLE = 0xFFE3F2D9,
            OUTLINE = 0xFF143A12, ACCENT = 0xFFFFE04A, PILL = 0xFF234A1F, GREEN_HI = 0xFF7CC75A, GREEN_LO = 0xFF3D8B2F,
            RED_HI = 0xFFD9604C, RED_LO = 0xFF9A2E22, GOOD = 0xFFFFE04A, BAD = 0xFFFFB0A0, ROW_FOCUS = CARD_FOCUS;

    // The audio driver a game starts with when the player chose none: OpenSL ES -- the one that played cleanly on the
    // Retroid Pocket 6 (SDL's own order, AAudio first, crackled there). Every driver stays selectable.
    static final String DEFAULT_AUDIO_DRIVER = "openslES";

    // Diagnostic mode (off by default): the next game start logs the GPU driver's messages (TE_DIAG_GL=1) and arms the
    // L3 + R3 draw capture (TE_DIAG_CAPTURE=1) and logs the sticks (TE_INPUT_LOG=1), for a report about a graphics or a
    // controls problem. Off, none of them costs anything.
    static final String[] DIAGNOSTIC_ENV = {"TE_DIAG_GL=1", "TE_DIAG_CAPTURE=1", "TE_INPUT_LOG=1"};

    // ---- the settings this screen shows: the game's keys and words (Settings.java) ---------------------------------------
    static final class Choice {
        final String key, label, help, def;
        final String[] values, names;

        Choice(String key, String label, String help, String def, String[] values, String[] names) {
            this.key = key;
            this.label = label;
            this.help = help;
            this.def = def;
            this.values = values;
            this.names = names;
        }

        int index(String v) {
            for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return i;
            for (int i = 0; i < values.length; i++) if (values[i].equals(def)) return i;
            return 0;
        }
    }

    static final Choice[] GRAPHICS = {
        new Choice(Settings.TEXTURE_FILTER, "Filtro de texturas", "Bilinear corrige os brilhos quadrados (máscara do Aku Aku, cogumelos, cachoeira)",
            "bilinear", new String[] {"title", "point", "bilinear"}, new String[] {"Original (do jogo)", "Point (pixelado)", "Bilinear — recomendado"}),
        new Choice(Settings.ANISOTROPY, "Filtragem anisotrópica",
            "Texturas do chão e paredes mais nítidas em ângulo; limitada ao máximo do driver (o log diz o aplicado)",
            "1", new String[] {"1", "2", "4", "8", "16"}, new String[] {"Desligada — padrão", "2x", "4x", "8x", "16x"}),
        new Choice(Settings.FXAA, "Antisserrilhado (FXAA)",
            "Suaviza bordas serrilhadas na imagem final; pode suavizar um pouco textos e o HUD",
            "off", new String[] {"off", "on"}, new String[] {"Desligado — padrão", "FXAA"}),
        new Choice(Settings.DITHERING, "Dithering",
            "Original: a saída de 16 bits com dithering do próprio PS2. Desligado: a imagem de 24 bits do port, mais lisa",
            "off", new String[] {"off", "original"}, new String[] {"Desligado — padrão", "Original (PS2)"}),
        new Choice(Settings.SCALE, "Resolução interna", "Mais alta: imagem mais nítida e mais trabalho para a GPU",
            "1", new String[] {"1", "2", "3", "4"}, new String[] {"1x (nativa do PS2)", "2x", "3x", "4x"}),
        new Choice(Settings.ASPECT, "Proporção da tela",
            "Widescreen com a opção widescreen do jogo ligada (Opções → Opções gráficas, fica no save) é o widescreen verdadeiro; desligada, a imagem 4:3 fica esticada",
            "16:9", new String[] {"4:3", "16:9"}, new String[] {"Original (4:3)", "Widescreen (16:9) — padrão"}),
        new Choice(Settings.FRAME_RATE, "Taxa de quadros", "Quantos quadros são mostrados por segundo; a velocidade do jogo não muda",
            "60", new String[] {"30", "60"}, new String[] {"30 FPS", "60 FPS — padrão"}),
        new Choice(Settings.VSYNC, "VSync", "Sincroniza com a tela; desligue se notar engasgos no ritmo dos quadros",
            "on", new String[] {"off", "on"}, new String[] {"Desligado", "Ligado — padrão"}),
        new Choice(Settings.FPS_COUNTER, "Contador de FPS", "Mostra o FPS no canto da tela durante o jogo",
            "off", new String[] {"off", "on"}, new String[] {"Desligado", "Ligado"}),
        new Choice(Settings.DRAW_DISTANCE, "Distância de detalhe (LOD, experimental)",
            "Mantém os modelos detalhados por mais distância, só entre os que o jogo já carregou. Exige mais da GPU",
            "original", new String[] {"original", "medium", "long", "max"},
            new String[] {"Original (1x)", "Média (2x)", "Longa (3x)", "Máxima (4x)"}),
    };
    static final Choice[] AUDIO = {
        new Choice(Settings.AUDIO_DRIVER, "Driver de áudio", "OpenSL ES é o que funcionou no Retroid Pocket 6 (vale no próximo início)",
            DEFAULT_AUDIO_DRIVER, new String[] {"openslES", "aaudio", "android", "auto"},
            new String[] {"OpenSL ES — recomendado", "AAudio", "AudioTrack (Java)", "Automático (ordem do SDL)"}),
        new Choice(Settings.AUDIO_BUFFER, "Tamanho do buffer", "Maior: mais estável, com um pouco mais de atraso (vale no próximo início)",
            "2048", new String[] {"1024", "2048", "4096"}, new String[] {"1024 (21 ms)", "2048 (43 ms) — padrão", "4096 (85 ms)"}),
    };
    static final Choice[] CONTROLS = {
        new Choice(Settings.STICK_DEADZONE, "Zona morta dos analógicos", "Quanto do centro do analógico é ignorado",
            "0.15", new String[] {"0.05", "0.10", "0.15", "0.20", "0.25"}, new String[] {"5%", "10%", "15% — padrão", "20%", "25%"}),
        new Choice(Settings.STICK_SENSITIVITY, "Sensibilidade dos analógicos", "Maior: o personagem chega à velocidade máxima com menos inclinação",
            "1.33", new String[] {"1.00", "1.33", "1.66", "2.00"}, new String[] {"100%", "133% — padrão", "166%", "200%"}),
    };
    static final Choice LANGUAGE = new Choice(Lang.KEY, "Idioma / Language", null, "auto", new String[] {"auto", "pt-BR", "en"},
        new String[] {"Automático (idioma do sistema)", "Português (Brasil)", "English"});
    static final String[] TAB_NAMES = {"Gráficos", "Áudio", "Controles", "Sistema", "Conquistas", "Diagnóstico"};

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private static GradientDrawable rounded(int color, float radius, int strokeWidth, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (strokeWidth > 0) g.setStroke(strokeWidth, strokeColor);
        return g;
    }

    private static GradientDrawable vertical(int top, int bottom, float radius, int strokeWidth, int strokeColor) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {top, bottom});
        g.setCornerRadius(radius);
        if (strokeWidth > 0) g.setStroke(strokeWidth, strokeColor);
        return g;
    }

    // a card's background: translucent dark green, a yellow outline where the controller's focus is
    private Drawable cardBackground() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, rounded(CARD_FOCUS, dp(18), dp(3), ACCENT));
        s.addState(new int[] {android.R.attr.state_pressed}, rounded(CARD_FOCUS, dp(18), dp(3), ACCENT));
        s.addState(new int[] {}, rounded(CARD, dp(18), 0, 0));
        return s;
    }

    // a glossy pill button (JOGAR green, SAIR red), outlined in yellow when focused
    private Drawable pill(int hi, int lo) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, vertical(hi, lo, dp(40), dp(4), ACCENT));
        s.addState(new int[] {android.R.attr.state_pressed}, vertical(lo, hi, dp(40), dp(4), ACCENT));
        s.addState(new int[] {}, vertical(hi, lo, dp(40), dp(3), OUTLINE));
        return s;
    }

    /** Text with a dark outline (the screen's bold titles): drawn twice, the stroke under the fill. */
    static final class OutlineText extends TextView {
        private final int outline;
        private final float width;
        OutlineText(Context c, int outline, float width) {
            super(c);
            this.outline = outline;
            this.width = width;
        }
        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            final int fill = getCurrentTextColor();
            getPaint().setStyle(android.graphics.Paint.Style.STROKE);
            getPaint().setStrokeJoin(android.graphics.Paint.Join.ROUND);
            getPaint().setStrokeWidth(width);
            setTextColor(outline);
            super.onDraw(canvas);
            getPaint().setStyle(android.graphics.Paint.Style.FILL);
            setTextColor(fill);
            super.onDraw(canvas);
        }
    }

    /** The background: a green field, light rays from the top centre, a soft glow and a thin frame. */
    static final class RaysBackground extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        RaysBackground(Context c) { super(c); }
        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            final int w = getWidth(), h = getHeight();
            final float cx = w * 0.5f, cy = h * 0.18f;
            paint.setShader(new android.graphics.RadialGradient(cx, cy, Math.max(w, h) * 0.9f,
                new int[] {0xFF8BD06A, 0xFF4CA63B, 0xFF2E7D32, 0xFF1F5E22}, new float[] {0f, 0.25f, 0.6f, 1f},
                android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            paint.setColor(0x1FFFFFFF);
            final int rays = 18;
            final float far = Math.max(w, h) * 1.5f;
            android.graphics.Path ray = new android.graphics.Path();
            for (int i = 0; i < rays; i++) {
                final double a0 = (Math.PI * 2 * i) / rays, a1 = a0 + Math.PI / rays * 0.55;
                ray.reset();
                ray.moveTo(cx, cy);
                ray.lineTo(cx + (float) Math.cos(a0) * far, cy + (float) Math.sin(a0) * far);
                ray.lineTo(cx + (float) Math.cos(a1) * far, cy + (float) Math.sin(a1) * far);
                ray.close();
                canvas.drawPath(ray, paint);
            }
            paint.setShader(new android.graphics.RadialGradient(cx, cy, Math.min(w, h) * 0.35f, 0x55FFFFCC, 0x00FFFFCC,
                android.graphics.Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, Math.min(w, h) * 0.35f, paint);
            paint.setShader(null);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(4f);
            paint.setColor(0x55C8F0A8);
            final float in = 18f;
            canvas.drawRect(in, in, w - in, h - in, paint);
            paint.setStyle(android.graphics.Paint.Style.FILL);
        }
    }

    /** A round green arrow button (left / right of a value), touch only: the card takes the controller's left / right. */
    static final class ArrowButton extends View {
        private final boolean right;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        ArrowButton(Context c, boolean right) {
            super(c);
            this.right = right;
            setFocusable(false);
        }
        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            final float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f - 2f, cx = w / 2f, cy = h / 2f;
            paint.setShader(null);
            paint.setColor(OUTLINE);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setShader(new android.graphics.LinearGradient(0, cy - r, 0, cy + r, GREEN_HI, GREEN_LO, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r - 3f, paint);
            paint.setShader(null);
            paint.setColor(0x40FFFFFF);
            canvas.drawCircle(cx, cy - r * 0.35f, r * 0.55f, paint);
            paint.setColor(0xFFFFFFFF);
            android.graphics.Path t = new android.graphics.Path();
            final float s = r * 0.42f;
            if (right) { t.moveTo(cx - s * 0.6f, cy - s); t.lineTo(cx + s, cy); t.lineTo(cx - s * 0.6f, cy + s); }
            else { t.moveTo(cx + s * 0.6f, cy - s); t.lineTo(cx - s, cy); t.lineTo(cx + s * 0.6f, cy + s); }
            t.close();
            canvas.drawPath(t, paint);
        }
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(T(s));   // (a text not in the table -- a file name, a status line -- stays as it is)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private OutlineText outlined(String s, float sp, int color) {
        OutlineText t = new OutlineText(this, OUTLINE, dp(4));
        t.setText(T(s));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setPadding(dp(3), 0, dp(3), 0);
        return t;
    }

    // a pill button with an outlined label (JOGAR, SAIR, Selecionar ISO)
    private View pillButton(String label, float sp, int hi, int lo, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(pill(hi, lo));
        f.setFocusable(true);
        f.setClickable(true);
        f.setOnClickListener(l);
        OutlineText t = outlined(label, sp, TEXT);
        t.setGravity(Gravity.CENTER);
        f.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        f.setTag(label);
        return f;
    }

    private LinearLayout.LayoutParams fill(int height, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        p.topMargin = top;
        return p;
    }

    private LinearLayout.LayoutParams centred(int width, int height, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.topMargin = top;
        p.gravity = Gravity.CENTER_HORIZONTAL;
        return p;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diag.installCrashHandler(this, "main");
        new Settings(this).migrate(prefs());
        Lang.init(this);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().setStatusBarColor(0xFF1F5E22);
        getWindow().setNavigationBarColor(0xFF1F5E22);

        FrameLayout root = new FrameLayout(this);
        root.addView(new RaysBackground(this), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(28), dp(14), dp(28), dp(14));
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        pageScroll = new ScrollView(this);
        pageScroll.setVerticalScrollBarEnabled(true);
        pageScroll.setScrollbarFadingEnabled(false);
        if (Build.VERSION.SDK_INT >= 29) {
            pageScroll.setVerticalScrollbarThumbDrawable(rounded(ACCENT, dp(4), 0, 0));
            pageScroll.setVerticalScrollbarTrackDrawable(rounded(0x40143A12, dp(4), 0, 0));
        }
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(8), dp(4), dp(18), dp(24));
        pageScroll.addView(page);
        column.addView(pageScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // the hints at the bottom (A confirmar, the D-pad mover, L1/R1 seções, START jogar)
        LinearLayout hints = new LinearLayout(this);
        hints.setOrientation(LinearLayout.HORIZONTAL);
        hints.setGravity(Gravity.CENTER);
        hints.setBackground(rounded(0xCC1E4A1B, dp(18), 0, 0));
        hints.setPadding(dp(16), dp(8), dp(16), dp(8));
        hints.addView(hint("A", "Confirmar"));
        hints.addView(hint("✥", "Mover"));
        hints.addView(hint("L1/R1", "Seções"));
        hints.addView(hint("START", "Jogar"));
        column.addView(hints, fill(ViewGroup.LayoutParams.WRAP_CONTENT, dp(8)));

        setContentView(root);
        buildPage();
        playButton.requestFocus();
    }

    // a hint of the bottom bar: a round badge and its word
    private View hint(String badge, String word) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView b = text(badge, 13, TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(vertical(GREEN_HI, GREEN_LO, dp(16), dp(2), OUTLINE));
        b.setPadding(dp(10), dp(3), dp(10), dp(3));
        h.addView(b);
        TextView w = text(word, 15, TEXT, false);
        w.setPadding(dp(8), 0, dp(22), 0);
        h.addView(w);
        return h;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        starting = false;
        if (playButton != null) playButton.setEnabled(true);
        if (playButton instanceof FrameLayout && ((FrameLayout) playButton).getChildAt(0) instanceof TextView)
            ((TextView) ((FrameLayout) playButton).getChildAt(0)).setText(T("▶  JOGAR"));
        refresh();
    }

    // ---- the controller -------------------------------------------------------------------------------------------------
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_BUTTON_L1:
                    jumpSection(-1);
                    return true;
                case KeyEvent.KEYCODE_BUTTON_R1:
                    jumpSection(1);
                    return true;
                case KeyEvent.KEYCODE_BUTTON_START:
                    startGame(false);
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    // L1 / R1: the previous / next section, its first card focused
    private void jumpSection(int d) {
        if (sections.isEmpty()) return;
        final int y = pageScroll.getScrollY();
        int target = -1;
        if (d > 0) {
            for (int i = 0; i < sections.size(); i++) if (sections.get(i).getTop() > y + dp(8)) { target = i; break; }
            if (target < 0) target = sections.size() - 1;
        } else {
            for (int i = sections.size() - 1; i >= 0; i--) if (sections.get(i).getTop() < y - dp(8)) { target = i; break; }
            if (target < 0) { pageScroll.smoothScrollTo(0, 0); playButton.requestFocus(); return; }
        }
        final View header = sections.get(target);
        pageScroll.smoothScrollTo(0, header.getTop());
        View next = header.focusSearch(View.FOCUS_DOWN);
        if (next != null) next.requestFocus();
    }

    @Override
    public void onBackPressed() {
        // B (the system's fallback to BACK) first brings the focus back to JOGAR; from there it leaves
        if (!playButton.isFocused()) {
            pageScroll.smoothScrollTo(0, 0);
            playButton.requestFocus();
            return;
        }
        super.onBackPressed();
    }

    // ---- the page -------------------------------------------------------------------------------------------------------
    // (the sections' builders call showTab after a change: the page is built again where it was, the same card focused)
    private void showTab(int index) {
        currentTab = index;
        View focused = getCurrentFocus();
        final Object key = focused != null ? focused.getTag() : null;
        final int y = pageScroll.getScrollY();
        buildPage();
        pageScroll.post(() -> {
            pageScroll.scrollTo(0, y);
            if (key != null) {
                View again = page.findViewWithTag(key);
                if (again != null) again.requestFocus();
            }
        });
    }

    private void buildPage() {
        page.removeAllViews();
        sections.clear();
        pendingRow = null;
        status = null;
        Settings st = new Settings(this);

        // the game: the name, the chosen disc, JOGAR, SAIR, the ISO picker, the last run
        OutlineText title = outlined("CRASH TWINSANITY", 46, ACCENT);
        title.setGravity(Gravity.CENTER);
        page.addView(title, centred(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, dp(10)));
        OutlineText sub = outlined("TwinsanityEvolved · port nativo Android", 16, TEXT);
        sub.setGravity(Gravity.CENTER);
        page.addView(sub, centred(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        isoName = text("—", 15, TEXT, true);
        isoName.setGravity(Gravity.CENTER);
        isoState = text("", 13, SUBTLE, false);
        isoState.setGravity(Gravity.CENTER);
        page.addView(isoName, centred(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, dp(10)));
        page.addView(isoState, centred(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, dp(2)));
        playButton = pillButton("▶  JOGAR", 30, GREEN_HI, GREEN_LO, v -> startGame(false));
        page.addView(playButton, centred(dp(440), dp(76), dp(14)));
        page.addView(pillButton("⏻  SAIR", 20, RED_HI, RED_LO, v -> confirmExit()), centred(dp(280), dp(54), dp(8)));
        page.addView(pillButton("Selecionar ISO…", 16, GREEN_HI, GREEN_LO, v -> pickIso()), centred(dp(280), dp(46), dp(8)));
        lastRun = text("", 13, SUBTLE, false);
        lastRun.setGravity(Gravity.CENTER);
        page.addView(lastRun, centred(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, dp(8)));

        section("Perfil");
        add(presetRow(st));
        flush();

        section("Gráficos");
        for (Choice c : GRAPHICS) add(choiceRow(st, c));
        note("As mesmas opções ficam no jogo: Opções → Opções gráficas.");

        section("Áudio");
        for (Choice c : AUDIO) add(choiceRow(st, c));
        note(T("O volume dos efeitos e da música fica no menu do jogo (Opções → Opções de som). ")
                + T("Driver e buffer também estão no jogo: Opções → android settings."));

        section("Controles");
        for (Choice c : CONTROLS) add(choiceRow(st, c));
        note("Os analógicos mudam na hora, também pelo jogo (Opções → android settings).");
        note("BOTÕES — escolha um botão do PS2 e aperte o botão do controle que deve fazer o papel dele. Se o botão "
                + "já estiver em uso, os dois trocam de lugar. Vale no próximo início do jogo; o launcher não muda.");
        for (int i = 0; i < PAD_BUTTONS.length; i++) add(padRow(st, i));
        add(actionRow("Restaurar botões padrão", "O layout do controle volta ao do DualShock 2", v -> confirmPadRestore()));
        flush();

        section("Sistema");
        add(choiceRow(st, LANGUAGE));
        add(actionRow("Restaurar apenas gráficos",
            "Para tela preta ou falha ao iniciar: só as opções gráficas voltam ao padrão (áudio, controles, idioma, saves e conquistas ficam)",
            v -> confirmRestoreGraphics()));
        add(actionRow("Restaurar configurações padrão", "Gráficos, áudio e controles voltam aos valores recomendados", v -> confirmRestore()));
        note("O launcher muda na hora; as opções do port dentro do jogo ficam em inglês (a fonte do jogo).");
        note(T("Arquivo de configurações (compartilhado com o jogo):\n") + st.file().getAbsolutePath());
        note(T("Versão ") + versionName() + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ") · " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?"));

        section("Conquistas");
        achievementsPage();
        flush();

        section("Diagnóstico");
        add(actionRow("Compartilhar relatório", "Um arquivo de texto com os logs da última execução", v -> buildReport(true)));
        add(actionRow("Copiar relatório", "O mesmo relatório para a área de transferência", v -> buildReport(false)));
        final boolean diag = prefs().getBoolean("diagnostic_mode", false);
        add(actionRow(T("Modo diagnóstico: ") + T(diag ? "LIGADO" : "desligado"),
                "Registra as mensagens da GPU e os analógicos e permite capturar um quadro com L3 + R3 (deixe desligado para jogar)", v -> {
                    prefs().edit().putBoolean("diagnostic_mode", !diag).apply();
                    Diag.log(this, "main", "diagnostic mode " + (!diag ? "on" : "off"));
                    showTab(5);
                }));
        add(actionRow("Diagnóstico sem o jogo", "Carrega as bibliotecas nativas e testa o ambiente", v -> startActivity(new Intent(this, ProbeActivity.class))));
        add(actionRow("Teste do SDL sem o jogo", "Abre a janela, o controle e o áudio sem a ISO", v -> startSdlTest()));
        flush();
        status = text("", 11, SUBTLE, false);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextIsSelectable(true);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(rounded(CARD, dp(18), 0, 0));
        page.addView(status, fill(ViewGroup.LayoutParams.WRAP_CONTENT, dp(10)));
        status.setText(statusText());
        refresh();
    }

    // a section's header: a small star and the name, outlined in yellow
    private void section(String name) {
        flush();
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        OutlineText star = outlined("✦", 22, ACCENT);
        h.addView(star);
        OutlineText t = outlined(T(name).toUpperCase(Locale.ROOT), 26, ACCENT);
        t.setPadding(dp(6), 0, dp(6), 0);
        h.addView(t);
        page.addView(h, fill(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)));
        sections.add(h);
    }

    // a card into the two-column grid (a row is completed by the next card, or by flush())
    private void add(View card) {
        if (pendingRow == null) {
            pendingRow = new LinearLayout(this);
            pendingRow.setOrientation(LinearLayout.HORIZONTAL);
            page.addView(pendingRow, fill(ViewGroup.LayoutParams.WRAP_CONTENT, dp(14)));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            p.rightMargin = dp(10);
            pendingRow.addView(card, p);
        } else {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            p.leftMargin = dp(10);
            pendingRow.addView(card, p);
            pendingRow = null;
        }
    }

    private void flush() {
        if (pendingRow != null) {
            // an odd card keeps half the width (the grid stays aligned)
            View spacer = new View(this);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.leftMargin = dp(10);
            pendingRow.addView(spacer, p);
            pendingRow = null;
        }
    }

    private void note(String s) {
        flush();
        TextView t = text(s, 13, SUBTLE, false);
        t.setPadding(dp(8), dp(10), dp(8), 0);
        page.addView(t);
    }

    // a card's frame: the title (outlined), the help line, and below them the control (a value row, or nothing)
    private View rowFrame(String label, String help, View control) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBackground());
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag("card:" + label);
        card.addView(outlined(label, 20, TEXT));
        if (help != null) {
            TextView h = text(help, 13, SUBTLE, false);
            h.setPadding(dp(3), dp(4), 0, 0);
            card.addView(h);
        }
        if (control != null) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.topMargin = dp(12);
            card.addView(control, p);
        }
        return card;
    }

    // the value row of a choice card: ◀ [ VALUE ] ▶ and a dot per value under it
    private View valueRow(final TextView value, final LinearLayout dots, Runnable prev, Runnable next) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ArrowButton left = new ArrowButton(this, false);
        left.setOnClickListener(v -> prev.run());
        row.addView(left, new LinearLayout.LayoutParams(dp(52), dp(52)));
        value.setGravity(Gravity.CENTER);
        value.setBackground(rounded(PILL, dp(28), dp(2), 0xFFB9A84A));
        value.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        vp.leftMargin = dp(6);
        vp.rightMargin = dp(6);
        row.addView(value, vp);
        ArrowButton right = new ArrowButton(this, true);
        right.setOnClickListener(v -> next.run());
        row.addView(right, new LinearLayout.LayoutParams(dp(52), dp(52)));
        box.addView(row);
        if (dots != null) {
            dots.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dp.topMargin = dp(6);
            box.addView(dots, dp);
        }
        return box;
    }

    private void showDots(LinearLayout dots, int count, int at) {
        dots.removeAllViews();
        for (int i = 0; i < count; i++) {
            View d = new View(this);
            d.setBackground(rounded(i == at ? ACCENT : 0x80C8E6B8, dp(4), 0, 0));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(8), dp(8));
            p.leftMargin = dp(3);
            p.rightMargin = dp(3);
            dots.addView(d, p);
        }
    }

    private TextView valueText() {
        OutlineText v = new OutlineText(this, OUTLINE, dp(3));
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        v.setTextColor(ACCENT);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return v;
    }

    // an action card: the title, the help, and a pill that says what A does
    private View actionRow(String label, String help, View.OnClickListener l) {
        TextView go = valueText();
        go.setText(T("ABRIR"));
        go.setGravity(Gravity.CENTER);
        go.setBackground(rounded(PILL, dp(28), dp(2), 0xFFB9A84A));
        go.setPadding(dp(10), dp(8), dp(10), dp(8));
        View card = rowFrame(label, help, go);
        card.setOnClickListener(l);
        return card;
    }

    // A setting: A or the right arrow = the next value, left / right on the controller = the previous / next; written at once
    private View choiceRow(final Settings st, final Choice c) {
        final TextView value = valueText();
        final LinearLayout dots = new LinearLayout(this);
        final int[] at = {c.index(st.get(c.key, c.def))};
        final Runnable show = () -> {
            value.setText(T(c.names[at[0]]).toUpperCase(Locale.ROOT));
            showDots(dots, c.values.length, at[0]);
        };
        final java.util.function.IntConsumer step = d -> {
            at[0] = (at[0] + d + c.values.length) % c.values.length;
            if (st.set(c.key, c.values[at[0]])) {
                Diag.log(this, "main", "setting " + c.key + " = " + c.values[at[0]]);
            } else {
                Toast.makeText(this, T("Não foi possível salvar em ") + st.file(), Toast.LENGTH_LONG).show();
            }
            show.run();
            if (Lang.KEY.equals(c.key)) {   // the launcher's language: the screen is built again in it
                Lang.init(this);
                recreate();
            }
        };
        final View row = rowFrame(c.label, c.help, valueRow(value, dots, () -> step.accept(-1), () -> step.accept(1)));
        show.run();
        row.setOnClickListener(v -> step.accept(1));
        row.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { step.accept(-1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { step.accept(1); return true; }
            return false;
        });
        return row;
    }

    // Leaving the app (not the same as the game's "return to launcher", which ends a game session and comes back here): the
    // launcher's task is finished and removed from the recent apps. A game or diagnostic process still running (normally
    // none while this screen is shown: the game's process ends when its activity finishes) is ended first, so nothing of
    // the app keeps running. The settings, the saves, the ISO and the logs are files: nothing is lost.
    private void confirmExit() {
        final boolean gameAlive = Diag.processAlive(this, "game");
        new AlertDialog.Builder(this)
            .setTitle(T("Sair do aplicativo?"))
            .setMessage(T(gameAlive
                    ? "O jogo ainda está aberto em segundo plano. Saia pelo jogo (Opções → android settings → return to launcher) "
                      + "para não perder progresso não salvo. Sair mesmo assim encerra o jogo."
                    : "O TwinsanityEvolved será fechado. Configurações, saves e a ISO continuam guardados."))
            .setPositiveButton(T("Sair"), (d, w) -> exitApp())
            .setNegativeButton(T("Cancelar"), null)
            .show();
    }

    private void exitApp() {
        Diag.log(this, "main", "exit requested from the launcher");
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null && am.getRunningAppProcesses() != null) {
            for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
                if (p.processName != null && (p.processName.endsWith(":game") || p.processName.endsWith(":probe"))) {
                    Diag.log(this, "main", "ending " + p.processName + " (pid " + p.pid + ") before leaving");
                    android.os.Process.killProcess(p.pid);
                }
            }
        }
        finishAndRemoveTask();
    }

    // ---- the gamepad-button remap (ps2r.ini android.pad.<ps2 button> = <SDL control>; the game applies it at its start) ----
    // The PS2 buttons (the engine's [pad] names), their labels, and SDL's standard layout (the DualShock 2's)
    static final String[] PAD_BUTTONS = {"cross", "circle", "square", "triangle", "l1", "r1", "l2", "r2", "l3", "r3",
                                         "select", "start", "up", "down", "left", "right"};
    static final String[] PAD_LABELS = {"Xis (✕)", "Círculo (○)", "Quadrado (□)", "Triângulo (△)", "L1", "R1", "L2", "R2",
                                        "L3 (analógico esquerdo)", "R3 (analógico direito)", "Select", "Start",
                                        "Direcional ↑", "Direcional ↓", "Direcional ←", "Direcional →"};
    static final String[] PAD_DEFAULTS = {"a", "b", "x", "y", "leftshoulder", "rightshoulder", "lefttrigger", "righttrigger",
                                          "leftstick", "rightstick", "back", "start", "dpup", "dpdown", "dpleft", "dpright"};

    // An Android gamepad key as SDL's game-controller control name (SDL's Android joystick maps the same keys)
    static String sdlControl(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return "a";
            case KeyEvent.KEYCODE_BUTTON_B: return "b";
            case KeyEvent.KEYCODE_BUTTON_X: return "x";
            case KeyEvent.KEYCODE_BUTTON_Y: return "y";
            case KeyEvent.KEYCODE_BUTTON_L1: return "leftshoulder";
            case KeyEvent.KEYCODE_BUTTON_R1: return "rightshoulder";
            case KeyEvent.KEYCODE_BUTTON_L2: return "lefttrigger";
            case KeyEvent.KEYCODE_BUTTON_R2: return "righttrigger";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "leftstick";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "rightstick";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "back";
            case KeyEvent.KEYCODE_BUTTON_START: return "start";
            case KeyEvent.KEYCODE_DPAD_UP: return "dpup";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "dpdown";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "dpleft";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "dpright";
            default: return null;
        }
    }

    static String controlLabel(String c) {
        switch (c) {
            case "a": return "A";
            case "b": return "B";
            case "x": return "X";
            case "y": return "Y";
            case "leftshoulder": return "L1";
            case "rightshoulder": return "R1";
            case "lefttrigger": return "L2";
            case "righttrigger": return "R2";
            case "leftstick": return "L3";
            case "rightstick": return "R3";
            case "back": return "Select";
            case "start": return "Start";
            case "dpup": return "↑";
            case "dpdown": return "↓";
            case "dpleft": return "←";
            case "dpright": return "→";
            default: return c;
        }
    }

    private String padControl(Settings st, int i) {
        return st.get("android.pad." + PAD_BUTTONS[i], PAD_DEFAULTS[i]);
    }

    private View padRow(final Settings st, final int i) {
        final TextView value = valueText();
        value.setGravity(Gravity.CENTER);
        value.setBackground(rounded(PILL, dp(28), dp(2), 0xFFB9A84A));
        value.setPadding(dp(10), dp(8), dp(10), dp(8));
        value.setText(controlLabel(padControl(st, i)));   // (A, B, L1, ...: the same in both languages)
        View row = rowFrame(T(PAD_LABELS[i]), null, value);
        row.setTag("pad:" + PAD_BUTTONS[i]);
        row.setOnClickListener(v -> capturePad(st, i));
        return row;
    }

    // Press-to-bind: the next gamepad key is the PS2 button's control; a control already bound to another PS2 button
    // swaps with this one's (no button is ever left without a control). Cancel: the dialog's button (touch) or 6 s.
    private void capturePad(final Settings st, final int i) {
        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(T(PAD_LABELS[i]))
            .setMessage(T("Aperte agora o botão do controle para ") + T(PAD_LABELS[i]) + T(".\n(Atual: ") + controlLabel(padControl(st, i))
                    + T(". Cancela sozinho em 6 segundos.)"))
            .setNegativeButton(T("Cancelar"), null)
            .create();
        final android.os.Handler h = new android.os.Handler(getMainLooper());
        final Runnable timeout = () -> { if (dialog.isShowing()) dialog.dismiss(); };
        dialog.setOnKeyListener((d, keyCode, e) -> {
            String control = sdlControl(keyCode);
            if (control == null) return false;
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            String old = padControl(st, i);
            boolean ok = true;
            for (int k = 0; k < PAD_BUTTONS.length; k++)
                if (k != i && padControl(st, k).equals(control)) ok &= st.set("android.pad." + PAD_BUTTONS[k], old);
            ok &= st.set("android.pad." + PAD_BUTTONS[i], control);
            Diag.log(this, "main", "pad remap: " + PAD_BUTTONS[i] + " = " + control + (ok ? "" : " (NOT written)"));
            h.removeCallbacks(timeout);
            dialog.dismiss();
            showTab(2);
            return true;
        });
        dialog.setOnDismissListener(d -> h.removeCallbacks(timeout));
        dialog.show();
        h.postDelayed(timeout, 6000);
    }

    private void confirmPadRestore() {
        new AlertDialog.Builder(this)
            .setTitle(T("Restaurar botões padrão?"))
            .setMessage(T("Todos os botões voltam ao layout do DualShock 2 (A = ✕, B = ○, X = □, Y = △...)."))
            .setPositiveButton(T("Restaurar"), (d, w) -> {
                Settings st = new Settings(this);
                for (int k = 0; k < PAD_BUTTONS.length; k++) st.set("android.pad." + PAD_BUTTONS[k], PAD_DEFAULTS[k]);
                Diag.log(this, "main", "pad remap restored to the standard layout");
                showTab(2);
            })
            .setNegativeButton(T("Cancelar"), null)
            .show();
    }

    // ---- graphics presets: the filter, the anisotropy, the LOD, FXAA and dithering together (the game's 'graphics preset'
    // row is the same, display.cpp); the resolution, the frame rate, the shape, the audio and the controls never change ----
    static final String[] PRESET_NAMES = {"Original", "Equilibrado", "Qualidade", "Personalizado"};
    static final String[][] PRESETS = {   // anisotropy, LOD
        {"1", "original"}, {"4", "medium"}, {"16", "max"}};

    private int presetNow(Settings st) {
        for (int i = 0; i < PRESETS.length; i++) {
            if (st.get(Settings.TEXTURE_FILTER, "bilinear").equals("bilinear") && st.get(Settings.ANISOTROPY, "1").equals(PRESETS[i][0])
                    && st.get(Settings.DRAW_DISTANCE, "original").equals(PRESETS[i][1]) && st.get(Settings.FXAA, "off").equals("off")
                    && st.get(Settings.DITHERING, "off").equals("off"))
                return i;
        }
        return 3;
    }

    private View presetRow(final Settings st) {
        final TextView value = valueText();
        final LinearLayout dots = new LinearLayout(this);
        final int now = presetNow(st);
        value.setText(T(PRESET_NAMES[now]).toUpperCase(Locale.ROOT));
        showDots(dots, 3, now < 3 ? now : -1);
        final Runnable[] go = new Runnable[2];
        View row = rowFrame("Predefinição gráfica",
            "Original: como o jogo. Equilibrado: anisotrópica 4x, LOD 2x. Qualidade: anisotrópica 16x, LOD 4x. A resolução não muda (2x ainda não validado)",
            valueRow(value, dots, () -> go[0].run(), () -> go[1].run()));
        row.setTag("card:preset");
        final java.util.function.IntConsumer step = d -> {
            int next = now == 3 ? (d > 0 ? 0 : 2) : (now + d + 3) % 3;
            st.set(Settings.TEXTURE_FILTER, "bilinear");
            st.set(Settings.ANISOTROPY, PRESETS[next][0]);
            st.set(Settings.DRAW_DISTANCE, PRESETS[next][1]);
            st.set(Settings.FXAA, "off");
            st.set(Settings.DITHERING, "off");
            Diag.log(this, "main", "graphics preset " + PRESET_NAMES[next]);
            showTab(0);
        };
        go[0] = () -> step.accept(-1);
        go[1] = () -> step.accept(1);
        row.setOnClickListener(v -> step.accept(1));
        row.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { step.accept(-1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { step.accept(1); return true; }
            return false;
        });
        return row;
    }

    // ---- local achievements (platform/nativeee/host/achievements.cpp; achievements.txt beside ps2r.ini) ----
    static final String[][] ACHIEVEMENTS = {   // id, title, how
        {"reach_island", "Explorador da ilha", "Volte à praia da ilha inicial vindo de outra área"},
        {"reach_iceberg", "Recepção gelada", "Chegue ao laboratório do iceberg"},
        {"reach_academy", "De volta às aulas", "Chegue ao pátio da academia"},
        {"reach_other_island", "O outro lado", "Chegue à ilha do outro lado"},
        {"reach_dimension", "Uma nova dimensão", "Chegue à décima dimensão"},
    };

    private File achievementsFile() {
        return new File(new Settings(this).file().getParentFile(), "achievements.txt");
    }

    private void achievementsPage() {
        java.util.Map<String, Long> got = new java.util.HashMap<>();
        String text = Diag.readFile(achievementsFile(), 0);
        if (text != null)
            for (String line : text.split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0 || line.startsWith("#")) continue;
                try { got.put(line.substring(0, eq), Long.parseLong(line.substring(eq + 1).trim())); } catch (NumberFormatException ignored) {}
            }
        int count = 0;
        for (String[] a : ACHIEVEMENTS) if (got.containsKey(a[0])) count++;
        note(T("Conquistas locais do port (não são do RetroAchievements). Desbloqueadas: ") + count + " / " + ACHIEVEMENTS.length);
        java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
        for (String[] a : ACHIEVEMENTS) {
            Long when = got.get(a[0]);
            TextView state = valueText();
            state.setText(when != null ? "✓  " + df.format(new java.util.Date(when * 1000)) : T("BLOQUEADA"));
            state.setTextColor(when != null ? ACCENT : SUBTLE);
            state.setGravity(Gravity.CENTER);
            state.setBackground(rounded(PILL, dp(28), dp(2), when != null ? ACCENT : 0x80B9A84A));
            state.setPadding(dp(10), dp(8), dp(10), dp(8));
            View row = rowFrame(a[1], a[2], state);
            row.setTag("ach:" + a[0]);
            add(row);
        }
        note("Só contam visitas jogando: carregar um save ou usar mods não desbloqueia nada.");
        add(actionRow("Zerar conquistas", "Apaga só as conquistas; saves e configurações não mudam", v ->
            new AlertDialog.Builder(this)
                .setTitle(T("Zerar conquistas?"))
                .setMessage(T("Todas as conquistas voltam a bloqueadas. Os saves continuam."))
                .setPositiveButton(T("Zerar"), (d, w) -> {
                    boolean ok = !achievementsFile().exists() || achievementsFile().delete();
                    Diag.log(this, "main", "achievements reset (" + (ok ? "done" : "NOT deleted") + ")");
                    showTab(4);
                })
                .setNegativeButton(T("Cancelar"), null)
                .show()));
    }

    // Recovery: the graphic options alone back to the Android profile (a black screen or a failed start after a graphics
    // change); the audio, the controls, the language, the ISO, the saves and the achievements are not touched
    private void confirmRestoreGraphics() {
        new AlertDialog.Builder(this)
            .setTitle(T("Restaurar apenas gráficos?"))
            .setMessage(T("Filtro, resolução, proporção, FPS, VSync, anisotrópica, FXAA, dithering, LOD e contador voltam ao padrão."))
            .setPositiveButton(T("Restaurar"), (d, w) -> {
                Settings st = new Settings(this);
                boolean ok = true;
                for (Choice c : GRAPHICS) ok &= st.set(c.key, c.def);
                ok &= st.set(Settings.FULLSCREEN, "fullscreen");
                Diag.log(this, "main", "graphics restored to the defaults (" + (ok ? "written" : "NOT all written") + ")");
                Toast.makeText(this, ok ? T("Gráficos restaurados") : T("Falha ao salvar em ") + st.file(), Toast.LENGTH_LONG).show();
                showTab(currentTab);
            })
            .setNegativeButton(T("Cancelar"), null)
            .show();
    }

    private void confirmRestore() {
        new AlertDialog.Builder(this)
            .setTitle(T("Restaurar configurações padrão?"))
            .setMessage(T("Widescreen 16:9, VSync ligado, filtro bilinear, anisotrópica, FXAA e dithering desligados, resolução 1x, 60 FPS, contador desligado, LOD original, OpenSL ES com ")
                    + T("buffer 2048, analógicos no padrão. A ISO e os saves não mudam; a opção widescreen do jogo fica no save (Opções → Opções gráficas)."))
            .setPositiveButton(T("Restaurar"), (d, w) -> {
                Settings st = new Settings(this);
                boolean ok = true;
                for (Choice[] group : new Choice[][] {GRAPHICS, AUDIO, CONTROLS})
                    for (Choice c : group) ok &= st.set(c.key, c.def);
                Diag.log(this, "main", "settings restored to the defaults (" + (ok ? "written" : "NOT all written") + ")");
                Toast.makeText(this, ok ? T("Configurações padrão restauradas") : T("Falha ao salvar em ") + st.file(), Toast.LENGTH_LONG).show();
                showTab(currentTab);
            })
            .setNegativeButton(T("Cancelar"), null)
            .show();
    }

    // ---- the game's state on the left -----------------------------------------------------------------------------------
    private void refresh() {
        String source = prefs().getString("iso_source", null);
        String[] disc = Diag.findDiscImage(this);
        if (disc[0] == null) {
            isoName.setText(T("Nenhuma ISO selecionada"));
            isoState.setText(T("Toque em \"Selecionar ISO…\" e escolha a SUA imagem do Crash Twinsanity (PAL ou NTSC-U)."));
            isoState.setTextColor(BAD);
        } else {
            StringBuilder r = new StringBuilder();
            String err = Diag.checkDiscImage(disc[0], r);
            isoName.setText(source != null ? source : new File(disc[0]).getName());
            isoState.setText(err == null ? T("Pronta — ") + r : T("Não utilizável: ") + err);
            isoState.setTextColor(err == null ? GOOD : BAD);
        }
        lastRun.setText(T(lastRunLine()));
        if (status != null) status.setText(statusText());
    }

    // what the last start did, in one line (the Diagnóstico page has the detail)
    private String lastRunLine() {
        File f = new File(Diag.logsDir(this), "stage-game.txt");
        String s = Diag.readFile(f, 2000);
        if (s == null) return "Ainda não executado nesta instalação.";
        s = s.trim();
        if (s.startsWith("running")) {
            if (Diag.processAlive(this, "game")) return "O jogo está em execução.";
            lastRun.setTextColor(BAD);
            return "A última execução terminou de forma inesperada. Veja Diagnóstico → Compartilhar relatório.";
        }
        if (s.startsWith("crash")) {
            lastRun.setTextColor(BAD);
            return "A última execução travou. Veja Diagnóstico → Compartilhar relatório.";
        }
        lastRun.setTextColor(SUBTLE);
        return "A última execução terminou normalmente.";
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("twinsanity", MODE_PRIVATE);
    }

    private void startGame(boolean force) {
        if (copying) {
            Toast.makeText(this, T("Aguarde a cópia da ISO terminar."), Toast.LENGTH_SHORT).show();
            return;
        }
        if (starting) return;
        String[] disc = Diag.findDiscImage(this);
        StringBuilder r = new StringBuilder();
        String err = disc[0] == null ? disc[1] : Diag.checkDiscImage(disc[0], r);
        if (err != null && !force) {
            new AlertDialog.Builder(this)
                .setTitle(T("Imagem do disco não disponível"))
                .setMessage(T("O jogo precisa da SUA imagem do Crash Twinsanity (PAL SLES-52568 ou NTSC-U SLUS-20909 v2.00).\n\n")
                        + T("Toque em \"Selecionar ISO\" e escolha o arquivo no gerenciador de arquivos do Android.\n\n")
                        + T("Detalhe: ") + err)
                .setPositiveButton(T("Selecionar ISO"), (d, w) -> pickIso())
                .setNeutralButton(T("Cancelar"), null)
                .setNegativeButton(T("Iniciar mesmo assim"), (d, w) -> startGame(true))
                .show();
            return;
        }
        starting = true;
        if (playButton instanceof FrameLayout && ((FrameLayout) playButton).getChildAt(0) instanceof TextView)
            ((TextView) ((FrameLayout) playButton).getChildAt(0)).setText(T("Iniciando…"));
        playButton.setEnabled(false);
        Diag.log(this, "main", "starting the game (disc: " + disc[0] + ", " + r + ")");
        Intent i = new Intent(this, MainActivity.class);
        if (err == null) i.putExtra(MainActivity.EXTRA_ISO, disc[0]);
        startActivity(i);
    }

    private void startSdlTest() {
        Diag.log(this, "main", "starting the SDL test (no game)");
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra(MainActivity.EXTRA_SDL_TEST, true);
        startActivity(i);
    }

    private String describeStage(String proc, String label) {
        File f = new File(Diag.logsDir(this), "stage-" + proc + ".txt");
        String s = Diag.readFile(f, 2000);
        if (s == null) return label + ": nunca executado nesta instalação\n";
        s = s.trim();
        StringBuilder b = new StringBuilder(label).append(" (")
            .append(new java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.US).format(new java.util.Date(f.lastModified())))
            .append("): ");
        boolean alive = Diag.processAlive(this, proc);
        if (s.startsWith("running")) {
            if (alive) b.append("em execução agora — ").append(s.substring(8));
            else b.append("TERMINOU DE FORMA INESPERADA durante a etapa ").append(s.substring(8))
                  .append("\n    (nenhum sinal de falha foi registrado: o processo pode ter sido encerrado pelo Android, por exemplo por falta de memória, ou por uma exceção Java — veja o relatório)");
        } else if (s.startsWith("crash")) {
            b.append("TRAVOU — ").append(s);
        } else if (s.startsWith("exited")) {
            b.append("encerrado por exit() — ").append(s.substring(7));
        } else if (s.startsWith("finished")) {
            b.append("terminou normalmente — ").append(s.substring(9));
        } else {
            b.append(s);
        }
        b.append('\n');
        String jc = Diag.readFile(new File(Diag.logsDir(this), "java-crash-" + proc + ".txt"), 1500);
        if (jc != null) b.append("    exceção Java registrada:\n").append(jc).append('\n');
        return b.toString();
    }

    private String statusText() {
        StringBuilder b = new StringBuilder();
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        b.append("versão ").append(version).append(" — ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
         .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ABI ")
         .append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?").append("\n\n");

        File ext = getExternalFilesDir(null);
        b.append("IMAGEM DO DISCO\n");
        String source = prefs().getString("iso_source", null);
        if (source != null) b.append("  selecionada: ").append(source).append('\n');
        String[] disc = Diag.findDiscImage(this);
        if (disc[0] == null) {
            b.append("  NENHUMA imagem encontrada — ").append(disc[1]).append('\n')
             .append("  Toque em \"Selecionar ISO\" e escolha a SUA imagem do jogo no gerenciador de arquivos.\n")
             .append("  (pasta alternativa: ").append(ext != null ? ext.getAbsolutePath() : "indisponível").append("/twinsanity.iso)\n");
        } else {
            StringBuilder r = new StringBuilder();
            String err = Diag.checkDiscImage(disc[0], r);
            b.append("  ").append(disc[0]).append(" (").append(disc[1]).append(")\n  ").append(r)
             .append(err == null ? " — OK" : " — NÃO UTILIZÁVEL: " + err).append('\n');
            if (err != null && err.contains("EACCES"))
                b.append("  O arquivo existe, mas o Android não deixa o app lê-lo (EACCES). Use \"Selecionar ISO\": o app copia a\n")
                 .append("  imagem pelo seletor do Android para o seu armazenamento privado, sem pedir permissões de armazenamento.\n");
        }
        Settings st = new Settings(this);
        b.append("\nÁUDIO: buffer ").append(st.get(Settings.AUDIO_BUFFER, "2048")).append(" frames, driver ")
         .append(st.get(Settings.AUDIO_DRIVER, DEFAULT_AUDIO_DRIVER)).append(" (").append(st.file()).append(")\n");
        b.append("MODO DIAGNÓSTICO: ").append(prefs().getBoolean("diagnostic_mode", false) ? "ligado" : "desligado").append('\n');
        b.append('\n').append("ÚLTIMAS EXECUÇÕES\n");
        b.append(describeStage("game", "  jogo / teste SDL"));
        b.append(describeStage("probe", "  diagnóstico"));

        String probe = Diag.readFile(new File(Diag.logsDir(this), "probe-result.txt"), 6000);
        if (probe != null && !probe.isEmpty()) b.append("\nÚLTIMO DIAGNÓSTICO\n").append(probe).append('\n');

        String java = Diag.tailLines(new File(Diag.logsDir(this), "java-game.log"), 12);
        if (java != null) b.append("\nLOG JAVA DO JOGO (fim)\n").append(java);
        String nat = Diag.tailLines(new File(Diag.logsDir(this), "latest-game.log"), 40);
        if (nat != null) b.append("\nLOG NATIVO DO JOGO (fim)\n").append(nat);
        String fullNat = Diag.readFile(new File(Diag.logsDir(this), "latest-game.log"), 200000);
        if (fullNat != null && fullNat.contains("FATAL")) {
            String frames = Diag.symbolizeFrames(this, fullNat);
            if (!frames.isEmpty()) b.append("\nFUNÇÕES NO TRAVAMENTO (libmain.so)\n").append(frames);
        }
        b.append("\nLogs: ").append(Diag.logsDir(this).getAbsolutePath()).append('\n');
        return b.toString();
    }

    // --- the disc image picker (Storage Access Framework) and the copy into the app's private storage ---------------------
    // The engine reads a file path; a document URI is not one, and a file another app put in Android/data may not be
    // readable by this app (EACCES on Android 11+). So the chosen document is copied through the ContentResolver into
    // files/disc/twinsanity.iso (private, readable by every process of the app), checked, and then used. No storage
    // permission is asked for: the picker grants this one document, and the grant is kept (takePersistableUriPermission).
    private void pickIso() {
        if (copying) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQUEST_PICK_ISO);
        } catch (Exception e) {
            Diag.log(this, "main", "ACTION_OPEN_DOCUMENT failed: " + e);
            Toast.makeText(this, T("O seletor de arquivos do Android não abriu: ") + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_ISO) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            Diag.log(this, "main", "ISO picker: cancelled");
            return;
        }
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Diag.log(this, "main", "ISO picker: persistable read permission kept for " + uri);
        } catch (SecurityException e) {
            Diag.log(this, "main", "ISO picker: no persistable permission for " + uri + " (" + e.getMessage() + ")");
        }
        prefs().edit().putString("iso_uri", uri.toString()).apply();
        copyIso(uri);
    }

    private static String mb(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private void copyIso(final Uri uri) {
        String name = "(sem nome)";
        long size = -1;
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                if (!c.isNull(0)) name = c.getString(0);
                if (!c.isNull(1)) size = c.getLong(1);
            }
        } catch (Exception e) {
            Diag.log(this, "main", "ISO picker: query failed: " + e);
        }
        Diag.log(this, "main", "ISO picker: chose " + name + " (" + (size >= 0 ? mb(size) : "size unknown") + ") " + uri);
        final File target = Diag.importedDisc(this);
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        long free = new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes();
        long existing = target.isFile() ? target.length() : 0;
        if (size > 0 && size + 64L * 1048576 > free + existing) {
            new AlertDialog.Builder(this).setTitle(T("Espaço insuficiente"))
                .setMessage(T("A ISO tem ") + mb(size) + T(" e o armazenamento interno tem ") + mb(free + existing) + T(" livres. Libere espaço e tente de novo."))
                .setPositiveButton(T("OK"), null).show();
            Diag.log(this, "main", "ISO copy refused: " + mb(size) + " needed, " + mb(free + existing) + " free");
            return;
        }

        final String displayName = name;
        final long total = size;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        final TextView label = new TextView(this);
        label.setText(T("Copiando ") + displayName + "…");
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(total <= 0);
        box.addView(label);
        box.addView(bar);
        copyCancel.set(false);
        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(T("Copiando a ISO para o app"))
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(T("Cancelar"), (d, w) -> copyCancel.set(true))
            .show();
        copying = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final File tmp = new File(target.getParentFile(), "twinsanity.iso.part");

        new Thread(() -> {
            String error = null;
            long copied = 0;
            final long start = System.nanoTime();
            try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(tmp, false)) {
                if (in == null) throw new java.io.IOException("the provider returned no stream");
                byte[] buf = new byte[4 * 1024 * 1024];
                long lastUi = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (copyCancel.get()) throw new java.io.IOException("cópia cancelada");
                    out.write(buf, 0, n);
                    copied += n;
                    long now = System.nanoTime();
                    if (now - lastUi > 250_000_000L) {
                        lastUi = now;
                        final long c = copied;
                        final double secs = (now - start) / 1e9;
                        runOnUiThread(() -> {
                            if (total > 0) bar.setProgress((int) (c * 1000 / total));
                            label.setText(displayName + "\n" + mb(c) + (total > 0 ? T(" de ") + mb(total) : "")
                                    + String.format(Locale.US, " — %.1f MB/s", c / 1048576.0 / Math.max(secs, 0.001)));
                        });
                    }
                }
                out.flush();
                out.getFD().sync();
                if (total > 0 && copied != total) throw new java.io.IOException("copiados " + copied + " bytes de " + total);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            StringBuilder report = new StringBuilder();
            if (error == null) {
                String bad = Diag.checkDiscImage(tmp.getAbsolutePath(), report);
                if (bad != null) error = "o arquivo copiado não é uma imagem de DVD de PS2: " + bad;
            }
            if (error == null) {
                // the copy's name by what it is: a CHD (chdman createdvd) keeps its own name, and the other one goes
                File dest = Diag.isChd(tmp) ? Diag.importedChd(this) : target;
                target.delete();
                Diag.importedChd(this).delete();
                if (!tmp.renameTo(dest)) error = "não foi possível renomear a cópia";
            }
            if (error != null) tmp.delete();
            final String err = error;
            final long finalCopied = copied;
            final double secs = (System.nanoTime() - start) / 1e9;
            Diag.log(this, "main", err == null
                    ? "ISO copied: " + displayName + ", " + mb(finalCopied) + " in " + String.format(Locale.US, "%.0f s", secs) + " -> " + target + " (" + report + ")"
                    : "ISO copy FAILED after " + mb(finalCopied) + ": " + err);
            runOnUiThread(() -> {
                copying = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                dialog.dismiss();
                if (err == null) {
                    prefs().edit().putString("iso_source", displayName + " (" + mb(finalCopied) + ")").apply();
                    new AlertDialog.Builder(this).setTitle(T("ISO pronta"))
                        .setMessage(displayName + T(" foi copiada e verificada:\n") + report + T("\n\nVocê pode apagar o arquivo original se quiser liberar espaço."))
                        .setPositiveButton(T("Iniciar jogo"), (d, w) -> startGame(false))
                        .setNegativeButton(T("OK"), null).show();
                } else {
                    new AlertDialog.Builder(this).setTitle(T("A cópia da ISO falhou"))
                        .setMessage(err).setPositiveButton(T("OK"), null).show();
                }
                refresh();
            });
        }, "te-iso-copy").start();
    }

    private void buildReport(final boolean share) {
        Toast.makeText(this, T("Gerando relatório…"), Toast.LENGTH_SHORT).show();
        final Context c = this;
        new Thread(() -> {
            File dir = Diag.logsDir(c);
            Diag.write(new File(dir, "logcat.txt"), Diag.captureLogcat(4000));
            StringBuilder b = new StringBuilder();
            b.append("TwinsanityEvolved Android — relatório de diagnóstico — ").append(Diag.now()).append("\n\n");
            b.append(statusText()).append('\n');
            String[] order = {"stage-game.txt", "java-crash-game.txt", "java-game.log", "latest-game.log", "previous-game.log",
                              "stage-probe.txt", "java-crash-probe.txt", "probe-result.txt", "latest-probe.log",
                              "java-crash-main.txt", "java-main.log", "logcat.txt"};
            for (String name : order) {
                String content = Diag.readFile(new File(dir, name), name.equals("logcat.txt") ? 300000 : 120000);
                if (content == null) continue;
                b.append("\n===== ").append(name).append(" =====\n").append(content).append('\n');
            }
            String game = Diag.readFile(new File(dir, "latest-game.log"), 0);
            if (game != null) {
                StringBuilder in = new StringBuilder(), au = new StringBuilder();
                for (String line : game.split("\n")) {
                    if (line.contains("input:") || line.contains("] input")) in.append(line).append('\n');
                    if (line.contains("audio") || line.contains("AAudio")) au.append(line).append('\n');
                }
                b.append("\n===== CONTROLES (linhas de input do latest-game.log) =====\n").append(in.length() > 0 ? in : "(nenhuma)\n");
                b.append("\n===== ÁUDIO (linhas de áudio do latest-game.log) =====\n").append(au.length() > 0 ? au : "(nenhuma)\n");
                StringBuilder gx = new StringBuilder();
                for (String line : game.split("\n"))
                    if (line.contains("[gl") || line.contains("display:") || line.contains("particles") || line.contains("TE_GFX")
                            || line.contains("te.gfx.capture") || line.contains("te.gfx.submit"))
                        gx.append(line).append('\n');
                b.append("\n===== GRÁFICOS ([gl], [gl.es], [gl.debug], display:, partículas) =====\n").append(gx.length() > 0 ? gx : "(nenhuma)\n");
                StringBuilder pf = new StringBuilder();
                for (String line : game.split("\n"))
                    if (line.contains("[te.gfx] pace") || line.contains("[te.gfx] lod") || line.contains("[te.android] perf") || line.contains("android settings:")
                            || line.contains("] display: aspect"))
                        pf.append(line).append('\n');
                b.append("\n===== DESEMPENHO (fps, tempo de quadro, CPU, memória, configurações) =====\n").append(pf.length() > 0 ? pf : "(nenhuma)\n");
            }
            String frames = Diag.symbolizeFrames(c, b.toString());
            if (!frames.isEmpty()) b.append("\n===== libmain.so frames, symbolised (installed libmain.so .symtab) =====\n").append(frames);
            final String report = b.toString();
            Diag.write(new File(dir, "report.txt"), report);
            runOnUiThread(() -> {
                refresh();
                if (share) {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_SUBJECT, "TwinsanityEvolved Android — relatório");
                    send.putExtra(Intent.EXTRA_STREAM, LogProvider.uriFor("report.txt"));
                    send.putExtra(Intent.EXTRA_TEXT, "Relatório de diagnóstico do TwinsanityEvolved (anexo report.txt).");
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Compartilhar relatório"));
                } else {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    String clip = report.length() > 250000 ? report.substring(report.length() - 250000) : report;
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("TwinsanityEvolved report", clip));
                    Toast.makeText(c, T("Relatório copiado (") + clip.length() + T(" caracteres)"), Toast.LENGTH_LONG).show();
                }
            });
        }, "te-report").start();
    }
}
