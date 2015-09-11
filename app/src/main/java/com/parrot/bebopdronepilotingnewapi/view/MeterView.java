package com.parrot.bebopdronepilotingnewapi.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * Created by tsuyoshi on 2015/09/11.
 */
public class MeterView extends SurfaceView implements SurfaceHolder.Callback {
    SurfaceHolder holder;

    double minYaw, maxYaw, minPitch, maxPitch, minRoll, maxRoll;
    double jetYaw, jetPitch, jetRoll, droneYaw, dronePitch, droneRoll;
    double width, height;

    public MeterView(Context context) {
        super(context);
        width = getMeasuredWidth();
        height = getMeasuredHeight();
        holder = getHolder();
        holder.addCallback(this);
        // 最前面に描画
        setZOrderOnTop(true);
    }

    /**
     * JETのキャリブレーション済み値セット
     * @param calibrated
     */
    public void initJetCalibration(double [] calibrated) throws Exception {
        if (calibrated.length < 6) throw new Exception("Invalid parameters!");
        minYaw = calibrated[0];
        minYaw = calibrated[1];
        minPitch = calibrated[2];
        maxPitch = calibrated[3];
        minRoll = calibrated[4];
        maxRoll = calibrated[5];
    }

    public void update(double jetYaw, double jetPitch, double jetRoll, double droneYaw, double dronePitch, double droneRoll) {
        this.jetYaw = jetYaw;
        this.jetPitch = jetPitch;
        this.jetRoll = jetRoll;
        this.droneYaw = droneYaw;
        this.dronePitch = dronePitch;
        this.droneRoll = droneRoll;
        // 更新
        invalidate();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {

    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Canvas canvas = holder.lockCanvas();
        float hstep = (float) width / 36;
        float vstep = (float) width / 18;
        Paint p = new Paint();
        p.setColor(Color.BLUE);
        // Roll描画
        canvas.rotate((float) jetRoll);
        //canvas.drawRect();
        // 下部にYawメーター描画
        for (int i = 0; i < 36; i++) {
            float nowPos = i * hstep;
            float h = (i + 1) % 5 == 0 ? 0 : (float) height / 2;
            canvas.drawRect(nowPos - 1, h, nowPos + 1, (float) height, p);
        }
        // 左端にピッチメータ描画
        for (int i = 0; i < 18; i++) {
            float nowPos = i * vstep;
            float w = (i + 1) % 5 == 0 ? 0 : (float) width / 2;
            canvas.drawRect(nowPos - 1, w, nowPos + 1, (float) width, p);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {

    }
}
