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

    public MeterView(Context context) {
        super(context);
        holder = getHolder();
        holder.addCallback(this);
        // 半透明を設定
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        // フォーカス不可
        setFocusable(false);
        // 最前面に持ってくる
        setZOrderOnTop(true);
    }

    /**
     * JETのキャリブレーション済み値セット
     * @param calibrated
     */
    public void initJetCalibration(float [] calibrated) throws Exception {
        if (calibrated.length < 6) throw new Exception("Invalid parameters!");
        minYaw = calibrated[0];
        minYaw = calibrated[1];
        minPitch = calibrated[2];
        maxPitch = calibrated[3];
        minRoll = calibrated[4];
        maxRoll = calibrated[5];
    }

    public void update(float jetYaw, float jetPitch, float jetRoll, float droneYaw, float dronePitch, float droneRoll) {
        this.jetYaw = jetYaw;
        this.jetPitch = jetPitch;
        this.jetRoll = jetRoll;
        this.droneYaw = droneYaw;
        this.dronePitch = dronePitch;
        this.droneRoll = droneRoll;
    }

    private void doDraw() {
        // Canvasをロックする（他のスレッドから描画されないようにするため）
        Canvas canvas = this.holder.lockCanvas();
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        Paint p = new Paint();
        p.setColor(Color.BLUE);
        // TODO Roll描画、計算がまだ適当だからちゃんとする
        canvas.rotate((float) -jetRoll, wharf, hharf);
        canvas.drawRect(wharf - 30, hharf - 5, wharf + 30, hharf + 5, p);
        canvas.restore();
        p.setColor(Color.RED);
        // 下部にYawメーター描画
        for (int i = 0; i < 36; i++) {
            float nowPos = i * hstep;
            float h = (i + 1) % 5 == 0 ? height - 15 : height - 10;
            canvas.drawRect(nowPos - 1, h, nowPos + 1, height, p);
        }
        // 左端にピッチメータ描画
        for (int i = 0; i < 18; i++) {
            float nowPos = i * vstep;
            float w = (i + 1) % 5 == 0 ? 15 : 10;
            canvas.drawRect(0, nowPos - 1, w, nowPos + 1, p);
        }
        // TODO JET描画、計算がまだ適当だからちゃんとする
        p.setColor(Color.BLUE);
        canvas.drawCircle(jetYaw / 360 * width, height - 8, 5, p);
        canvas.drawCircle(8, jetPitch / 90 * hharf, 5, p);
        // ロックしたCanvasを開放
        this.holder.unlockCanvasAndPost(canvas);
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
                Thread.sleep(1000 / 30); // 30fpsくらい
            } catch (InterruptedException e) {

            }
        }
    }

    public void term() {
        term = true;
    }
}
