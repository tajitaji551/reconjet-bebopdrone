package com.parrot.bebopdronepilotingnewapi;

import android.util.Log;

import java.io.PipedOutputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Created by tsuyoshi on 2015/08/21.
 */
public class CommandDetector implements Runnable {
    public static final int MODE_ENTRY = 0;
    public static final int MODE_NORMAL = 1;
    public static final int MODE_PRO = 2;
    /**
     * 検知モード
     * 0: 初心者モード
     * 1: ノーマルモード
     * 2: プロモード
     */
    public int mode;
    public float rawThreshold, slopeThreshold;

    /**
     * ループ処理
     */
    public boolean stop = false, term = false;
    public float rate = 1000 / 5;
    public double dt = 5 / 1000;
    public long nextTick;

    /**
     * データバッファ
     */
    List<Float> yawList, pitchList, rollList;

    /**
     * コマンドバッファ(FIFO)
     */
    public Queue<float[]> commandQueue = new LinkedList<float[]>();

    static final float[] xAxis = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

    /**
     * 水平軸回転計算用
     */
    float lastYawSlope;

    /**
     * 目的値
     */
    public double targetYaw, targetPitch, targetRoll;
    /**
     * Yaw用PID変数
     */
    double e0Y = 0, e1Y = 0, e2Y = 0; // 偏差（今回、前回、前々回）
    double MVn0Y = 0, MVn1Y = 0; // 操作量（今回、前回）
    double dMVnY = 0; // 今回の増分
    /**
     * Pitch用PID変数
     */
    double e0P = 0, e1P = 0, e2P = 0; // 偏差（今回、前回、前々回）
    double MVn0P = 0, MVn1P = 0; // 操作量（今回、前回）
    double dMVnP = 0; // 今回の増分
    /**
     * Roll用PID変数
     */
    double e0R = 0, e1R = 0, e2R = 0; // 偏差（今回、前回、前々回）
    double MVn0R = 0, MVn1R = 0; // 操作量（今回、前回）
    double dMVnR = 0; // 今回の増分


    public CommandDetector(List<Float> yaw, List<Float> pitch, List<Float> roll) {
        setMode(MODE_NORMAL);
        yawList = yaw;
        pitchList = pitch;
        rollList = roll;
    }

    /**
     * モード設定
     * [TODO] しきい値の調整、定数化
     * @param mode
     */
    public void setMode(int mode) {
        this.mode = mode;
        switch (mode) {
            case MODE_ENTRY:
                rawThreshold = 0.15f;
                slopeThreshold = 15f;
                break;
            case MODE_PRO:
                rawThreshold = 0.05f;
                slopeThreshold = 5f;
                break;
            case MODE_NORMAL:
            default:
                rawThreshold = 0.1f;
                slopeThreshold = 10f;
                break;
        }
    }

    public float getSlope(List<Float> data) {
        int size = data.size();
        double sumXY = 0, sumX = 0, sumY = 0, sumX2 = 0;
        double A, B;

        synchronized (data) {
            for (int i = 0; i < size; i++) {
                sumXY += (double) xAxis[i] * (double) data.get(i);
                sumX += xAxis[i];
                sumY += data.get(i);
                sumX2 = Math.pow(xAxis[i], 2);
            }
        }

        // 傾き
        A = (size * sumXY - sumX * sumY) / (size * sumX2 - Math.pow(sumX, 2));
        // 切片
        B = (sumX2 * sumY - sumXY * sumX) / (size * sumX2 - Math.pow(sumX, 2));
        return (float) A;
    }

    public double getPIDYawControl(double input) {
        double pOut = 0, iOut = 0, dOut = 0;
        // 偏差計算
        e2Y = e1Y;
        e1Y = e0Y;
        e0Y = input - targetYaw;
        // P制御
        pOut = PilotingActivity.Kp * (e0Y - e1Y);
        // I制御
        iOut = PilotingActivity.Kp * (this.dt / PilotingActivity.Ki) * e0Y;
        // D制御
        dOut = PilotingActivity.Kp * (PilotingActivity.Kd / this.dt) * (e0Y - 2 * e1Y + e2Y);
        dMVnY = pOut + iOut + dOut;
        MVn0Y = MVn1Y + dMVnY;
        MVn1Y = MVn0Y;
        // [TODO] 制御量によって方向決定
        // [TODO] 上限値（-100 - +100）設定
        return MVn0Y;
    }

    public double getPIDPitchControl(double input) {
        double pOut = 0, iOut = 0, dOut = 0;
        // 偏差計算
        e2P = e1P;
        e1P = e0P;
        e0P = input - targetPitch;
        // P制御
        pOut = PilotingActivity.Kp * (e0P - e1P);
        // I制御
        iOut = PilotingActivity.Kp * (this.dt / PilotingActivity.Ki) * e0P;
        // D制御
        dOut = PilotingActivity.Kp * (PilotingActivity.Kd / this.dt) * (e0P - 2 * e1P + e2P);
        dMVnP = pOut + iOut + dOut;
        MVn0P = MVn1P + dMVnP;
        MVn1P = MVn0P;
        // [TODO] 制御量によって方向決定
        // [TODO] 上限値（-100 - +100）設定
        return MVn0P;

    }

    public double getPIDRollControll(double input) {
        double pOut = 0, iOut = 0, dOut = 0;
        // 偏差計算
        e2R = e1R;
        e1R = e0R;
        e0R = input - targetRoll;
        // P制御
        pOut = PilotingActivity.Kp * (e0R - e1R);
        // I制御
        iOut = PilotingActivity.Kp * (this.dt / PilotingActivity.Ki) * e0R;
        // D制御
        dOut = PilotingActivity.Kp * (PilotingActivity.Kd / this.dt) * (e0R - 2 * e1R + e2R);
        dMVnR = pOut + iOut + dOut;
        MVn0R = MVn1R + dMVnR;
        MVn1R = MVn0R;
        // [TODO] 制御量によって方向決定
        // [TODO] 上限値（-100 - +100）設定
        return MVn0R;
    }

    /**
     * コマンド判定ループ
     */
    public void run() {
        long now, diff;
        double e0, e1, e2; // 偏差（今回、前回、前々回）
        double MVn0, MVn1; // 操作量（今回、前回）
        double dMVn; // 今回の増分
        double Kp, Ki, Kd; // PIDパラメータ

        nextTick = System.currentTimeMillis();

        while (!term) {
            if (stop) {
                nextTick += (long) rate;
                continue;
            }
            now = System.currentTimeMillis();
            diff = nextTick - now;
            if (diff > 0) {
                try {
                    Thread.sleep(diff);
                    continue;
                } catch (InterruptedException sleepException) {
                    stop = false;
                    term = true;
                }
            } else {
                // コマ落ち判定
                if ((nextTick + rate) < now) {
                    nextTick += (long) rate;
                    continue;
                }
                // 処理
                /*
                float[] command = detectCommand();
                if (command != null) {
                    commandQueue.add(command);
                }
                */
            }
            nextTick += (long) rate;
        }
    }

    private float[] detectCommand() {
        boolean detected = false;
        float [] command = new float[]{0, 0, 0};
        // [TODO] もしかしたらロックできないかも
        if (!yawList.isEmpty()) {
            synchronized (yawList) {
                float currentYaw = yawList.get(yawList.size() - 1);
                float currentYawSlope = getSlope(yawList);
                if (Math.abs(currentYaw - MainActivity.norYaw) / 360 >= rawThreshold
                        && Math.toDegrees(currentYawSlope) <= slopeThreshold) {
                    Log.d("YAW", "current:" +currentYaw+ " nor:" + MainActivity.norYaw + " slope:" + currentYawSlope + " sin:"+Math.sin(currentYaw));
                    command[0] = MainActivity.norYaw - currentYaw;
                    detected = true;
                    MainActivity.norYaw = currentYaw;
                    lastYawSlope = currentYawSlope;
                }
            }
        }
        if (!pitchList.isEmpty()) {
            synchronized (pitchList) {
                float currentPitch = pitchList.get(pitchList.size() - 1);
                if (Math.abs(currentPitch - MainActivity.norPitch) / 360 >= rawThreshold
                        && Math.toDegrees(getSlope(pitchList)) <= slopeThreshold) {
                    command[1] = - currentPitch;
                    detected = true;
                }
            }
        }
        if (!rollList.isEmpty()) {
            synchronized (rollList) {
                float currentRoll = rollList.get(rollList.size() - 1);
                if (Math.abs(currentRoll - MainActivity.norRoll) / 360 >= rawThreshold
                        && Math.toDegrees(getSlope(rollList)) <= slopeThreshold) {
                    command[2] = currentRoll;
                    detected = true;
                }
            }
        }
        if (!detected) {
            command = null;
        }
        return command;
    }

    public float[] popCommand() {
        return commandQueue.poll();
    }

    public void term() {
        stop = false;
        term = true;
    }
}
