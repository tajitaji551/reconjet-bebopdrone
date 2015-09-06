package com.parrot.bebopdronepilotingnewapi;

import android.util.Log;

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

    /**
     * コマンド判定ループ
     */
    public void run() {
        long now, diff;

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
                float[] command = detectCommand();
                if (command != null) {
                    commandQueue.add(command);
                }
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
                if (Math.abs(currentYaw - MainActivity.norYaw) / 360 >= rawThreshold
                        && Math.toDegrees(getSlope(yawList)) <= slopeThreshold) {
                    command[0] = currentYaw;
                    detected = true;
                    MainActivity.norYaw = currentYaw;
                }
            }
        }
        if (!pitchList.isEmpty()) {
            synchronized (pitchList) {
                float currentPitch = pitchList.get(pitchList.size() - 1);
                if (Math.abs(currentPitch - MainActivity.norPitch) / 360 >= rawThreshold
                        && Math.toDegrees(getSlope(pitchList)) <= slopeThreshold) {
                    command[1] = currentPitch;
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
