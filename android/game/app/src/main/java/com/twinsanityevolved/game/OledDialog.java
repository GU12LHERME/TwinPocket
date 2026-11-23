package com.twinsanityevolved.game;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The launcher's dialogs: black (#000000), white text, the selected button framed in white. Controller first:
 * left / right (d-pad or stick) move between the buttons, A chooses the selected one, B is the cancel button (when the
 * dialog has one). A destructive question starts on its cancel button. Touch chooses a button directly. Every key is
 * the dialog's while it is shown (the launcher behind sees none: START cannot start the game under a dialog).
 */
final class OledDialog extends Dialog {
    interface OnChoice {
        void chosen(int index);
    }

    /** A dialog that takes the next key itself (the button remap's capture): true = handled */
    interface KeyInterceptor {
        boolean onKey(KeyEvent e);
    }

    static final int BG = 0xFF000000, FG = 0xFFFFFFFF, SUBTLE = 0xFF8C8C8C, LINE = 0xFF333333;

    private final Activity activity;
    private final List<TextView> buttons = new ArrayList<>();
    private int selected;
    private int cancelIndex = -1;
    private OnChoice onChoice;
    private KeyInterceptor interceptor;
    private boolean chosen;
    private int stickDir = Nav.NONE;
    private final LinearLayout box;
    private final TextView message;

    private OledDialog(Activity a) {
        super(a);
        activity = a;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setStroke(dp(1), LINE);
        box.setBackground(bg);
        box.setPadding(dp(24), dp(20), dp(24), dp(16));
        message = new TextView(a);
        message.setTextColor(FG);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        message.setLineSpacing(0, 1.15f);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.getResources().getDisplayMetrics());
    }

    /**
     * Shows a dialog. labels: its buttons, left to right; defaultIndex: the one selected first; cancelIndex: the one B
     * chooses (-1: B does nothing -- the dialog waits for a choice); onChoice: called after the dialog is closed (may be null).
     */
    static OledDialog show(Activity a, String title, String text, View extra, String[] labels, int defaultIndex, int cancelIndex,
                           OnChoice onChoice) {
        OledDialog d = new OledDialog(a);
        d.build(title, text, extra, labels);
        d.selected = Math.max(0, Math.min(labels.length - 1, defaultIndex));
        d.cancelIndex = cancelIndex;
        d.onChoice = onChoice;
        d.setCancelable(false);   // B is handled here (the cancel button), never the system's own dismiss
        d.paint();
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.min(a.getResources().getDisplayMetrics().widthPixels * 7 / 10, d.dp(720)), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.7f);
        }
        return d;
    }

    // A long text's scroller (the Ferramentas tab's viewers): up / down scroll it, left / right still pick the buttons
    private android.widget.ScrollView scrollTarget;

    void setScrollTarget(android.widget.ScrollView v) {
        scrollTarget = v;
    }

    private boolean scroll(int delta) {
        if (scrollTarget == null) return false;
        scrollTarget.smoothScrollBy(0, delta * dp(140));
        return true;
    }

    void setKeyInterceptor(KeyInterceptor k) {
        interceptor = k;
    }

    void setMessage(String text) {
        message.setText(text);
    }

    private void build(String title, String text, View extra, String[] labels) {
        TextView t = new TextView(activity);
        t.setText(title);
        t.setTextColor(FG);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);
        if (text != null && !text.isEmpty()) {
            message.setText(text);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.topMargin = dp(12);
            box.addView(message, p);
        }
        if (extra != null) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.topMargin = dp(12);
            box.addView(extra, p);
        }
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView b = new TextView(activity);
            b.setText(labels[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setPadding(dp(18), dp(10), dp(18), dp(10));
            b.setClickable(true);
            b.setOnClickListener(v -> {
                selected = index;
                paint();
                choose(index, null);
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.leftMargin = dp(8);
            row.addView(b, p);
            buttons.add(b);
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(18);
        box.addView(row, p);
        setContentView(box);
    }

    private void paint() {
        for (int i = 0; i < buttons.size(); i++) {
            TextView b = buttons.get(i);
            boolean on = i == selected;
            if (on) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(BG);
                g.setStroke(dp(2), FG);
                g.setCornerRadius(dp(6));
                b.setBackground(g);
            } else {
                b.setBackgroundColor(BG);
            }
            b.setTextColor(on ? FG : SUBTLE);
        }
    }

    private void choose(int index, KeyEvent e) {
        if (chosen) return;
        chosen = true;
        Haptics.tap(activity, e, true);
        dismiss();
        if (onChoice != null) onChoice.chosen(index);
    }

    /** Closes it as its cancel button would (a copy's end, a timeout): the callback is not called. */
    void close() {
        chosen = true;
        if (isShowing()) dismiss();
    }

    private void step(int delta) {
        if (buttons.isEmpty()) return;
        int next = Math.max(0, Math.min(buttons.size() - 1, selected + delta));
        if (next != selected) {
            selected = next;
            paint();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (interceptor != null && interceptor.onKey(e)) return true;
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE)
            return super.dispatchKeyEvent(e);
        if (scrollTarget != null && e.getAction() == KeyEvent.ACTION_DOWN
                && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
            scroll(code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);   // a held direction keeps scrolling
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) return true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
                step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                step(1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!buttons.isEmpty()) choose(selected, e);
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (cancelIndex >= 0) choose(cancelIndex, e);
                return true;
            default:
                return true;   // START, the shoulders...: nothing under a dialog
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if ((ev.getSource() & (InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_GAMEPAD)) != 0 && ev.getAction() == MotionEvent.ACTION_MOVE) {
            float x = ev.getAxisValue(MotionEvent.AXIS_X), y = ev.getAxisValue(MotionEvent.AXIS_Y);
            float hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X), hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(hx) > 0.5f || Math.abs(hy) > 0.5f) { x = hx; y = hy; }
            int d = Nav.stickDir(x, y, stickDir);
            if (d != stickDir) {
                stickDir = d;
                if ((d == Nav.UP || d == Nav.DOWN) && scroll(d == Nav.UP ? -1 : 1)) return true;
                if (d == Nav.LEFT || d == Nav.UP) step(-1);
                else if (d == Nav.RIGHT || d == Nav.DOWN) step(1);
            }
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }
}
