/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4149
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.preference.Preference;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.shared.settings.preference.SeekBarPreference.SeekBarConfig;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.CaptionViewportPatch;
import app.morphe.extension.youtube.settings.Settings;

/**
 * One caption appearance panel: vertical position, width and text size, with a live preview.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class CaptionAppearancePreference extends Preference {

    private static final int POSITION = 0, WIDTH = 1, SIZE = 2;

    public CaptionAppearancePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public CaptionAppearancePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public CaptionAppearancePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CaptionAppearancePreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setSelectable(true);
        setPersistent(false);
    }

    @Override
    protected void onClick() {
        showDialog();
    }

    @Override
    public CharSequence getSummary() {
        return StringRef.str("morphe_caption_position_label") + " "
                + CaptionViewportPatch.captionPosition(Settings.CAPTION_VERTICAL_OFFSET.get())
                + "%"
                + "  ·  " + StringRef.str("morphe_caption_width_label") + " "
                + Settings.CAPTION_WIDTH.get() + "%"
                + "  ·  " + StringRef.str("morphe_caption_size_label") + " "
                + Settings.CAPTION_SCALE.get() + "%";
    }

    private void showDialog() {
        final SeekBarConfig position = SeekBarPreference.configFor(Settings.CAPTION_VERTICAL_OFFSET);
        final SeekBarConfig width = SeekBarPreference.configFor(Settings.CAPTION_WIDTH);
        final SeekBarConfig size = SeekBarPreference.configFor(Settings.CAPTION_SCALE);
        if (position == null || width == null || size == null) {
            throw new IllegalStateException("CaptionAppearancePreference: missing seek bar config");
        }

        final Context context = getContext();
        final int[] pending = {
                CaptionViewportPatch.captionPosition(Settings.CAPTION_VERTICAL_OFFSET.get()),
                Settings.CAPTION_WIDTH.get(),
                Settings.CAPTION_SCALE.get()
        };

        final CaptionPreview preview = new CaptionPreview(context);
        preview.setValues(pending);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, Dim.dp8, 0, Dim.dp8);
        content.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp(180)));

        final Slider[] sliders = {
                addSlider(context, content, StringRef.str("morphe_caption_position_label"),
                        position, pending, POSITION, preview),
                addSlider(context, content, StringRef.str("morphe_caption_width_label"),
                        width, pending, WIDTH, preview),
                addSlider(context, content, StringRef.str("morphe_caption_size_label"),
                        size, pending, SIZE, preview)
        };

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                getTitle() != null ? getTitle().toString()
                        : StringRef.str("morphe_caption_appearance_title"),
                null,
                null,
                null,
                () -> {
                    Settings.CAPTION_VERTICAL_OFFSET.save(pending[POSITION]);
                    Settings.CAPTION_WIDTH.save(pending[WIDTH]);
                    Settings.CAPTION_SCALE.save(pending[SIZE]);
                    notifyChanged();
                    CaptionViewportPatch.requestRemeasure();
                },
                () -> {},
                StringRef.str("morphe_settings_reset"),
                () -> {
                    pending[POSITION] = 0;
                    pending[WIDTH] = 100;
                    pending[SIZE] = 100;
                    for (Slider slider : sliders) slider.refresh(pending);
                    preview.setValues(pending);
                },
                false
        );

        // Insert the panel between the title and the buttons.
        dialogPair.second.addView(content, 1);
        dialogPair.first.show();
    }

    private static Slider addSlider(Context context, LinearLayout content, String title,
                                    SeekBarConfig config, int[] pending, int index,
                                    CaptionPreview preview) {
        TextView label = new TextView(context);
        label.setTextColor(ThemeUtils.getAppForegroundColor());
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = Dim.dp(12);
        content.addView(label, labelParams);

        SeekBar bar = new SeekBar(context);
        bar.setMax((config.max() - config.min()) / config.step());
        bar.setProgress(SeekBarPreference.valueToProgress(config, pending[index]));
        final int accent = accentColor(context);
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setThumbTintList(ColorStateList.valueOf(accent));
        content.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Slider slider = new Slider(label, bar, config, title, index, pending, preview);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                slider.update();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        slider.update();
        return slider;
    }

    private static int accentColor(Context context) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.colorAccent, value, true);
        return value.data;
    }

    private static final class Slider {
        private final TextView label;
        private final SeekBar bar;
        private final SeekBarConfig config;
        private final String title;
        private final int index;
        private final int[] pending;
        private final CaptionPreview preview;

        Slider(TextView label, SeekBar bar, SeekBarConfig config, String title,
               int index, int[] pending, CaptionPreview preview) {
            this.label = label;
            this.bar = bar;
            this.config = config;
            this.title = title;
            this.index = index;
            this.pending = pending;
            this.preview = preview;
        }

        void update() {
            pending[index] = SeekBarPreference.progressToValue(config, bar.getProgress());
            label.setText(title + ": " + SeekBarPreference.formatLabel(pending[index], config));
            preview.setValues(pending);
        }

        void refresh(int[] values) {
            bar.setProgress(SeekBarPreference.valueToProgress(config, values[index]));
            update();
        }
    }

    /** Mock player with the caption placed and sized exactly as the settings describe. */
    @SuppressLint("ViewConstructor")
    private static final class CaptionPreview extends View {
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint playerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String sample;

        private int positionPercent;
        private int widthPercent = 100;
        private int sizePercent = 100;

        CaptionPreview(Context context) {
            super(context);
            sample = StringRef.str("morphe_caption_preview_text");
            textPaint.setColor(Color.WHITE);
            playerPaint.setColor(0xFF141414);
            boxPaint.setColor(0xB3000000);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(Dim.dp(1));
            borderPaint.setColor(0x33FFFFFF);
        }

        void setValues(int[] pending) {
            positionPercent = pending[0];
            widthPercent = pending[1];
            sizePercent = pending[2];
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);

            final float pad = Dim.dp(8);
            final float availableW = getWidth() - 2 * pad;
            final float availableH = getHeight() - 2 * pad;
            float playerW = availableW;
            float playerH = playerW * 9f / 16f;
            if (playerH > availableH) {
                playerH = availableH;
                playerW = playerH * 16f / 9f;
            }
            final float left = (getWidth() - playerW) / 2f;
            final float top = (getHeight() - playerH) / 2f;
            final RectF player = new RectF(left, top, left + playerW, top + playerH);
            final float radius = Dim.dp(6);
            canvas.drawRoundRect(player, radius, radius, playerPaint);
            canvas.drawRoundRect(player, radius, radius, borderPaint);

            final float captionW = Math.max(Dim.dp(40), playerW * widthPercent / 100f);
            textPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 16f * sizePercent / 100f,
                    getResources().getDisplayMetrics()));
            final int layoutWidth = Math.max(1, (int) (captionW - Dim.dp(12)));
            StaticLayout layout = StaticLayout.Builder.obtain(
                            sample, 0, sample.length(), textPaint, layoutWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .build();

            final float nativeBottom = player.bottom - Dim.dp(10);
            final float boxHeight = layout.getHeight() + Dim.dp(8);
            final float nativeTop = nativeBottom - boxHeight;
            final float available = Math.max(0, nativeTop - (player.top + Dim.dp(24)));
            final float offset =
                    available * Math.max(0, Math.min(100, positionPercent)) / 100f;
            final float bottom = nativeBottom - offset;
            final float boxTop = bottom - boxHeight;
            final RectF box = new RectF(player.centerX() - captionW / 2f, boxTop,
                    player.centerX() + captionW / 2f, bottom);
            canvas.drawRoundRect(box, Dim.dp(4), Dim.dp(4), boxPaint);

            canvas.save();
            canvas.translate(box.left + Dim.dp(6), boxTop + Dim.dp(4));
            layout.draw(canvas);
            canvas.restore();
        }
    }
}
