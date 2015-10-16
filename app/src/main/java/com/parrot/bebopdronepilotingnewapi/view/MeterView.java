package com.parrot.bebopdronepilotingnewapi.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.graphics.PixelFormat;

/**
 * Created by tsuyoshi on 2015/09/11.
 */
public class MeterView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
    SurfaceHolder holder;

    float minYaw, maxYaw, minPitch, maxPitch, minRoll, maxRoll;
    float jetYaw, jetPitch, jetRoll, droneYaw, dronePitch, droneRoll;
    float width, height, wharf, hharf;

    // 描画ループ
    Thread looper;
    boolean term = false, stop = true;

    // 目盛表示
    float hstep;
    float vstep;

    boolean upDownMode;
    int battery;
    double speedX, speedY, speedZ;
    double lat, lang, alti;
    double wifi;

    public MeterView(Context context) {
        super(context);
        holder = getHolder();
        holder.addCallback(this);
        // 半透明を設定
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        // フォーカス不可
        setFocusable(false);
        // 重ね表示した時最前面に表示
        setZOrderOnTop(true);
    }

    /**
     * JETのキャリブレーション済み値セット
     * @param calibrated
     */
    public void initJetCalibration(float [] calibrated) {
        minYaw = calibrated[0];
        maxYaw = calibrated[1];
        minPitch = calibrated[2];
        maxPitch = calibrated[3];
        minRoll = calibrated[4];
        maxRoll = calibrated[5];
    }

    public void changeUpsideDown(boolean isUpDownMode) {
        upDownMode = isUpDownMode;
    }

    public void updateBattery(int battery) {
        this.battery = battery;
    }

    public void updateSpeed(double dx, double dy, double dz) {
        speedX = dx;
        speedY = dy;
        speedZ = dz;
    }

    public void updateGPS(double lat, double lang, double alti) {
        this.lat = lat;
        this.lang = lang;
        this.alti = alti;
    }

    public void updateJet(float jetYaw, float jetPitch, float jetRoll) {
        this.jetYaw = jetYaw;
        this.jetPitch = jetPitch;
        this.jetRoll = jetRoll;
    }

    public void updateDrone(float droneYaw, float dronePitch, float droneRoll) {
        this.droneYaw = droneYaw;
        this.dronePitch = dronePitch;
        this.droneRoll = droneRoll;
    }

    private void doDraw() {
        // Canvasをロックする（他のスレッドから描画されないようにするため）
        Canvas canvas = this.holder.lockCanvas();
        if (canvas == null) return;
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        Paint p = new Paint();
        p.setColor(Color.RED);
        p.setAlpha(255);
        // Drone roll
        canvas.save();
        canvas.rotate(droneRoll, wharf, hharf);
        canvas.drawRect(wharf - 30, hharf - 2, wharf + 30, hharf + 2, p);
        canvas.restore();
        // JET roll
        p.setColor(Color.GREEN);
        canvas.rotate((float) -jetRoll, wharf, hharf);
        canvas.drawRect(wharf - 30, hharf - 2, wharf + 30, hharf + 2, p);
        canvas.restore();
        p.setAlpha(100);
        // 下部にYawメーター描画
        float h, detect;
        for (int i = 0; i < 36; i++) {
            float nowPos = i * hstep;
            detect = (i + 1) %5;
            h = detect == 0 ? height - 15 : height - 10;
            canvas.drawRect(nowPos - 1, h, nowPos + 1, height, p);
            if (detect == 0) {
                canvas.drawText((i * 10 + 10) + "°", nowPos - 6, height - 17, p);
            }
        }
        // 左端にピッチメータ描画
        for (int i = 0; i < 18; i++) {
            float nowPos = i * vstep;
            detect = (i + 1) % 5;
            h = detect == 0 ? 15 : 10;
            canvas.drawRect(0, nowPos - 1, h, nowPos + 1, p);
            if (detect == 0) {
                canvas.drawText((-90 + i * 10) + "°", 17, nowPos + 6, p);
            }
        }
        // 右側に高度描画
        for (int i = 0; i < 20; i++) {
            float nowPos = i * vstep;
            detect = (i + 1) % 5;
            h = detect == 0 ? 15 : 10;
            canvas.drawRect(width - h, nowPos - 1, width, nowPos + 1, p);
            if (detect == 0) {
                canvas.drawText((190 - i * 10) + "m", width - 50, nowPos + 5, p);
            }
        }
        p.setAlpha(255);
        p.setColor(Color.RED);
        // Drone pitch, yaw
        canvas.drawCircle(droneYaw / 360 * width, height - 8, 5, p);
        canvas.drawCircle(8, dronePitch / 90 * hharf + hharf, 5, p);
        // JET pitch, yaw
        p.setColor(Color.GREEN);
        canvas.drawCircle(jetYaw / 360 * width, height - 8, 5, p);
        canvas.drawCircle(8, jetPitch / 90 * hharf + hharf, 5, p);

        // バッテリ残業
        if (battery <= 20) {
            p.setColor(Color.RED);
        } else if (battery > 20 && battery < 80) {
            p.setColor(Color.YELLOW);
        } else {
            p.setColor(Color.GREEN);
        }
        canvas.drawCircle(wharf, 10, (float)battery * 0.1f, p);
        // ロックしたCanvasを開放
        this.holder.unlockCanvasAndPost(canvas);
    }

    public float droneYawToDegree(float yaw) {
        return (float) Math.sin(Math.random()*yaw);
    }

    public float dronePitchToDegree(float pitch) {
        return (float) Math.tan(Math.random()*pitch);
    }

    public float droneRollToDegree(float roll) {
        return (float) Math.cos(Math.random()*roll);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        looper = new Thread(this);
        looper.start();
        stop = false;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        this.width = width;
        this.height = height;
        this.wharf = this.width / 2;
        this.hharf = this.height / 2;
        hstep = this.width / 36;
        vstep = this.height / 18;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stop = false;
        term = true;
        looper = null;
    }

    @Override
    public void run() {
        while (!term) {
            if (stop) continue;
            doDraw();
            try {
                Thread.sleep(1000 / 10); // 20fpsくらい
            } catch (InterruptedException e) {

            }
        }
    }

    public void term() {
        stop = false;
        term = true;
    }
}
