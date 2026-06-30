package com.duvitech.tello;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class JoystickView extends View {

    public interface JoystickListener {
        void onMove(int x, int y); // -100 to 100
    }

    private final Paint ringPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float cx, cy;
    private float thumbX, thumbY;
    private float outerRadius;
    private float thumbRadius;
    private int activePointerId = -1;
    private boolean touched = false;

    private JoystickListener listener;

    public JoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public JoystickView(Context context) {
        super(context);
        init();
    }

    private void init() {
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(Color.argb(120, 0, 212, 255));
        ringPaint.setStrokeWidth(2.5f);

        crossPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setColor(Color.argb(50, 0, 212, 255));
        crossPaint.setStrokeWidth(1.5f);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setColor(Color.argb(60, 0, 212, 255));
        glowPaint.setStrokeWidth(12f);
        glowPaint.setMaskFilter(null);

        thumbPaint.setStyle(Paint.Style.FILL);

        arrowPaint.setStyle(Paint.Style.FILL);
        arrowPaint.setColor(Color.argb(80, 0, 212, 255));
        arrowPaint.setTextSize(18f);
        arrowPaint.setTextAlign(Paint.Align.CENTER);
        arrowPaint.setAntiAlias(true);
    }

    public void setListener(JoystickListener l) { this.listener = l; }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        cx = w / 2f;
        cy = h / 2f;
        thumbX = cx;
        thumbY = cy;
        outerRadius = Math.min(w, h) / 2f - 12f;
        thumbRadius = outerRadius * 0.30f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // outer glow ring
        canvas.drawCircle(cx, cy, outerRadius, glowPaint);
        // outer ring
        canvas.drawCircle(cx, cy, outerRadius, ringPaint);
        // inner ring
        canvas.drawCircle(cx, cy, outerRadius * 0.5f, crossPaint);
        // crosshair lines
        canvas.drawLine(cx - outerRadius, cy, cx + outerRadius, cy, crossPaint);
        canvas.drawLine(cx, cy - outerRadius, cx, cy + outerRadius, crossPaint);

        // directional arrow hints
        float ao = outerRadius * 0.78f;
        canvas.drawText("▲", cx, cy - ao + 8, arrowPaint);
        canvas.drawText("▼", cx, cy + ao + 6, arrowPaint);
        canvas.drawText("◀", cx - ao + 6, cy + 6, arrowPaint);
        canvas.drawText("▶", cx + ao - 2, cy + 6, arrowPaint);

        // thumb with radial gradient (lit when touched)
        int centerColor = touched ? Color.argb(230, 0, 212, 255) : Color.argb(160, 0, 160, 200);
        int edgeColor   = touched ? Color.argb(100, 0, 100, 180) : Color.argb(60, 0, 80, 120);
        RadialGradient gradient = new RadialGradient(
                thumbX, thumbY, thumbRadius,
                centerColor, edgeColor,
                Shader.TileMode.CLAMP);
        thumbPaint.setShader(gradient);
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbPaint);

        // thumb outline
        ringPaint.setAlpha(touched ? 200 : 100);
        canvas.drawCircle(thumbX, thumbY, thumbRadius, ringPaint);
        ringPaint.setAlpha(120);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                activePointerId = event.getPointerId(event.getActionIndex());
                touched = true;
                updateThumb(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                updateThumb(event);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                reset();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void updateThumb(MotionEvent event) {
        int idx = event.findPointerIndex(activePointerId);
        if (idx < 0) return;
        float dx = event.getX(idx) - cx;
        float dy = event.getY(idx) - cy;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist > outerRadius) {
            dx = dx / dist * outerRadius;
            dy = dy / dist * outerRadius;
        }
        thumbX = cx + dx;
        thumbY = cy + dy;
        invalidate();
        if (listener != null)
            listener.onMove((int)(dx / outerRadius * 100), (int)(dy / outerRadius * 100));
    }

    private void reset() {
        thumbX = cx;
        thumbY = cy;
        activePointerId = -1;
        touched = false;
        invalidate();
        if (listener != null) listener.onMove(0, 0);
    }
}
