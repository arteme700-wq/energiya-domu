package ua.sister.energy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/** QR-код для прив'язки телефона. */
final class QrView extends View {
    private BitMatrix m;
    private final Paint black = new Paint(), white = new Paint();

    QrView(Context c) { super(c); black.setColor(0xFF000000); white.setColor(0xFFFFFFFF); }

    void setText(String s) {
        try {
            Map<EncodeHintType, Object> h = new EnumMap<>(EncodeHintType.class);
            h.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M); h.put(EncodeHintType.MARGIN, 2);
            m = new QRCodeWriter().encode(s, BarcodeFormat.QR_CODE, 0, 0, h);
        } catch (Exception e) { m = null; }
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        if (m == null) return;
        int n = m.getWidth();
        float cell = Math.min(getWidth(), getHeight()) / (float) n;
        c.drawRect(0, 0, n * cell, n * cell, white);
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++)
            if (m.get(x, y)) c.drawRect(x * cell, y * cell, (x + 1) * cell, (y + 1) * cell, black);
    }
}
