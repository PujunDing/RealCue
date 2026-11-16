package com.example.inspireface_example.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

/** Draws compact, color-matched Track ID badges over the OpenGL tracking overlay. */
public final class TrackIdOverlayView extends View {

    private static final int MAX_FACES = FaceTrackingGlView.MAX_FACES;

    private final Object lock = new Object();
    /** Per-face badge text. Legacy callers supply "ID n"; auto-id supplies "P001". */
    private final String[] labels = new String[MAX_FACES];
    private final int[] colors = new int[MAX_FACES];
    /** Packed left, top, right, bottom values in the upright camera image. */
    private final float[] rects = new float[MAX_FACES * 4];
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF badgeRect = new RectF();
    private final float horizontalPadding;
    private final float verticalPadding;
    private final float badgeGap;
    private int faceCount;
    private int imageWidth;
    private int imageHeight;
    private boolean mirrored;

    public TrackIdOverlayView(Context context) {
        this(context, null);
    }

    public TrackIdOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        horizontalPadding = 7f * density;
        verticalPadding = 4f * density;
        badgeGap = 4f * density;
        badgePaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(0xE6000000);
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                12f, getResources().getDisplayMetrics()));
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * Legacy API: renders {@code "ID <trackId>"} badges for the original tracking demo.
     * Kept so {@link com.example.inspireface_example.FaceDetectionActivity} is unchanged.
     */
    public void submit(int[] ids, float[] faceRects, int[] faceColors, int count,
                       int sourceWidth, int sourceHeight, boolean mirror) {
        int safeCount = Math.min(Math.max(count, 0), MAX_FACES);
        String[] converted = new String[safeCount];
        for (int i = 0; i < safeCount; i++) {
            converted[i] = "ID " + (ids != null && i < ids.length ? ids[i] : -1);
        }
        submitLabels(converted, faceRects, faceColors, safeCount,
                sourceWidth, sourceHeight, mirror);
    }

    /**
     * Renders arbitrary per-face labels (e.g. persistent gallery {@code P001}).
     * Safe to call from the CameraX analysis thread; data is copied before returning.
     */
    public void submitLabels(String[] labelTexts, float[] faceRects, int[] faceColors,
                             int count, int sourceWidth, int sourceHeight, boolean mirror) {
        synchronized (lock) {
            faceCount = Math.min(Math.max(count, 0), MAX_FACES);
            if (faceCount > 0) {
                for (int i = 0; i < faceCount; i++) {
                    labels[i] = labelTexts != null && i < labelTexts.length
                            && labelTexts[i] != null ? labelTexts[i] : "…";
                }
                System.arraycopy(faceColors, 0, colors, 0, faceCount);
                System.arraycopy(faceRects, 0, rects, 0, faceCount * 4);
            }
            imageWidth = sourceWidth;
            imageHeight = sourceHeight;
            mirrored = mirror;
        }
        postInvalidateOnAnimation();
    }

    public void clearTracking() {
        synchronized (lock) {
            faceCount = 0;
            imageWidth = 0;
            imageHeight = 0;
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        synchronized (lock) {
            if (faceCount <= 0 || imageWidth <= 0 || imageHeight <= 0
                    || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }

            // PreviewView uses FILL_CENTER, matching FaceTrackingGlView's transform.
            float scale = Math.max((float) getWidth() / imageWidth,
                    (float) getHeight() / imageHeight);
            float dx = (getWidth() - imageWidth * scale) / 2f;
            float dy = (getHeight() - imageHeight * scale) / 2f;
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float textHeight = metrics.descent - metrics.ascent;
            float badgeHeight = textHeight + verticalPadding * 2f;

            for (int i = 0; i < faceCount; i++) {
                int offset = i * 4;
                float sourceLeft = rects[offset];
                float sourceTop = rects[offset + 1];
                float sourceRight = rects[offset + 2];
                float x = mirrored
                        ? imageWidth * scale + dx - sourceRight * scale
                        : sourceLeft * scale + dx;
                float y = sourceTop * scale + dy;
                String label = labels[i] != null ? labels[i] : "…";
                float badgeWidth = textPaint.measureText(label) + horizontalPadding * 2f;
                float left = clamp(x, 0f, Math.max(0f, getWidth() - badgeWidth));
                float top = y - badgeHeight - badgeGap;
                if (top < 0f) {
                    top = y + badgeGap;
                }
                top = clamp(top, 0f, Math.max(0f, getHeight() - badgeHeight));

                badgeRect.set(left, top, left + badgeWidth, top + badgeHeight);
                int color = colors[i];
                badgePaint.setColor(Color.argb(235,
                        Color.red(color), Color.green(color), Color.blue(color)));
                float radius = badgeHeight * 0.32f;
                canvas.drawRoundRect(badgeRect, radius, radius, badgePaint);
                float baseline = top + verticalPadding - metrics.ascent;
                canvas.drawText(label, left + horizontalPadding, baseline, textPaint);
            }
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
