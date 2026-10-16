package ua.sister.energy.notify;

import android.app.Activity;
import android.content.Intent;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

/** Простий сканер QR (Camera1 + ZXing core), без сторонніх застосунків і Google Play Services. */
@SuppressWarnings("deprecation")
public final class ScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private Camera cam;
    private long lastTry;
    private final QRCodeReader reader = new QRCodeReader();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout f = new FrameLayout(this);
        SurfaceView sv = new SurfaceView(this); sv.getHolder().addCallback(this);
        f.addView(sv);
        TextView t = Ui.text(this, "Наведіть камеру на QR-код\nна екрані планшета", 26, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER); t.setBackgroundColor(0x99000000);
        f.addView(t, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(f);
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        try {
            cam = Camera.open();
            cam.setDisplayOrientation(90);
            Camera.Parameters p = cam.getParameters();
            if (p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            cam.setParameters(p);
            cam.setPreviewDisplay(h);
            cam.setPreviewCallback(this);
            cam.startPreview();
        } catch (Exception e) { finish(); }
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }

    @Override public void surfaceDestroyed(SurfaceHolder h) { release(); }

    @Override protected void onPause() { super.onPause(); release(); finish(); }

    private void release() { if (cam != null) { cam.setPreviewCallback(null); cam.stopPreview(); cam.release(); cam = null; } }

    @Override public void onPreviewFrame(byte[] data, Camera c) {
        long now = System.currentTimeMillis();
        if (now - lastTry < 400 || cam == null) return;
        lastTry = now;
        try {
            Camera.Size s = c.getParameters().getPreviewSize();
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, s.width, s.height, 0, 0, s.width, s.height, false);
            Result r = reader.decode(new BinaryBitmap(new HybridBinarizer(src)));
            if (Store.parseTopic(r.getText()) != null) {
                setResult(RESULT_OK, new Intent().putExtra("text", r.getText()));
                release(); finish();
            }
        } catch (Exception ignored) { reader.reset(); }
    }
}
