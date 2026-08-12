package com.example.autoprint;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Desenha uma grelha "regra dos terços" (3x3) por cima da pré-visualização da câmara,
 * para ajudar a compor a fotografia. Não desenha nada quando está com visibility="gone".
 */
public class GridOverlayView extends View {

    private final Paint linePaint = new Paint();

    public GridOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        linePaint.setColor(0x80FFFFFF); // branco semi-transparente
        linePaint.setStrokeWidth(2f);
        linePaint.setAntiAlias(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) return;

        float x1 = width / 3f;
        float x2 = width * 2f / 3f;
        float y1 = height / 3f;
        float y2 = height * 2f / 3f;

        // Duas linhas verticais
        canvas.drawLine(x1, 0, x1, height, linePaint);
        canvas.drawLine(x2, 0, x2, height, linePaint);

        // Duas linhas horizontais
        canvas.drawLine(0, y1, width, y1, linePaint);
        canvas.drawLine(0, y2, width, y2, linePaint);
    }
}