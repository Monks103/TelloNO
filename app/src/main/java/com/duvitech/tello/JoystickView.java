package com.duvitech.tello;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class JoystickView extends View {

    public interface JoystickListener {
        void onMove(int x, int y); // -100 to 100
    }

    private final Paint outerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float cx, cy;       // center
    private float thumbX, thumbY; // current thumb position
    private float outerRadius;
    private float thumbRadius;
    private int activePointerId = -1;

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
        outerPaint.setStyle(Paint.Style.STROKE);
        outerPaint.setColor(Color.argb(120, 255, 255, 255));
        outerPaint.setStrokeWidth(3f);

        thumbPaint.setStyle(Paint.Style.FILL);
        thumbPaint.setColor(Color.argb(180, 255, 255, 255));

        crossPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setColor(Color.argb(60, 255, 255, 255));
        crossPaint.setStrokeWidth(2f);
    }

    public void setListener(JoystickListener l) {
        this.listener = l;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        cx = w / 2f;
        cy = h / 2f;
        thumbX = cx;
        thumbY = cy;
        outerRadius = Math.min(w, h) / 2f - 8f;
        thumbRadius = outerRadius * 0.35f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // crosshair
        canvas.drawLine(cx - outerRadius, cy, cx + outerRadius, cy, crossPaint);
        canvas.drawLine(cx, cy - outerRadius, cx, cy + outerRadius, crossPaint);
        // outer ring
        canvas.drawCircle(cx, cy, outerRadius, outerPaint);
        // thumb
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                activePointerId = event.getPointerId(event.getActionIndex());
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
        if (listener != null) {
            listener.onMove((int)(dx / outerRadius * 100), (int)(dy / outerRadius * 100));
        }
    }

    private void reset() {
        thumbX = cx;
        thumbY = cy;
        activePointerId = -1;
        invalidate();
        if (listener != null) listener.onMove(0, 0);
    }
}
