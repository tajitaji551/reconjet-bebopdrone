package com.parrot.bebopdronepilotingnewapi;

import android.os.Handler;
import android.util.Log;

import com.parrot.arsdk.arcontroller.ARDeviceController;

/**
 * Created by tsuyoshi on 2015/08/21.
 */
public class PIDSynchronizer implements Runnable {
    public static final int MODE_ENTRY = 0; // 15%以上の動作に反応
    public static final int MODE_NORMAL = 1; // 10%以上の動作に反応
    public static final int MODE_PRO = 2; // 5%以上の動作に反応
    /**
     * 検知モード
     * 0: 初心者モード
     * 1: ノーマルモード
     * 2: プロモード
     */
    public static int mode;
    public float rawThreshold, slopeThreshold;

    /**
     * ループ処理
     */
    public boolean stop = false, term = false;
    public float rate = 1000 * 0.05f;
    public double dt = 0.1;
    public long nextTick;

    /**
     * デバイスコントローラー
     */
    ARDeviceController _controller;
    Handler _handler;

    /**
     * グラスの目的値
     */
    public double targetYaw, targetPitch, targetRoll;

    /**
     * ドローンの現在の値
     */
    public double droneYaw, dronePitch, droneRoll;

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

    /**
     * 加速
     */
    double accelY = 10, accelP = 10, accelR = 10;

    /**
     * 命令を送るべきかどうか
     */
    boolean shouldSendYawCommand = false;
    boolean shouldSendPitchCommand = false;
    boolean shouldSendRollCommand = false;

    public PIDSynchronizer(Handler handler, ARDeviceController controller) {
        setMode(MODE_NORMAL);
        _controller = controller;
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

    /**
     * ドローンの現在の状態を保持する
     * @param yaw
     * @param pitch
     * @param roll
     */
    public void updateCurrentYPRState(double yaw, double pitch, double roll) {
        droneYaw = yaw;
        dronePitch = pitch;
        droneRoll = roll;
    }

    /**
     * グラスの現在の状態
     * @param yaw
     * @param pitch
     * @param roll
     */
    public void updateTargetYPRState(double yaw, double pitch, double roll) {
        if (yaw >= 0 && yaw <= 180) {
            targetYaw = yaw / 60;
        } else {
            yaw -= 180;
            targetYaw = yaw / 60 - 3.0;
        }
        targetPitch = -1.5 * pitch / 90;
        targetRoll = -roll / 30;
        // コマンドフラグ計算
        switch (mode) {
            case MODE_PRO:
                shouldSendYawCommand = Math.abs(targetYaw) >= 0.15;
                shouldSendPitchCommand = Math.abs(targetPitch) >= 0.075;
                shouldSendRollCommand = Math.abs(targetRoll) >= 0.075;
                break;
            case MODE_NORMAL:
                shouldSendYawCommand = Math.abs(targetYaw) >= 0.3;
                shouldSendPitchCommand = Math.abs(targetPitch) >= 0.15;
                shouldSendRollCommand = Math.abs(targetRoll) >= 0.15;
                break;
            case MODE_ENTRY:
                shouldSendYawCommand = Math.abs(targetYaw) >= 0.45;
                shouldSendPitchCommand = Math.abs(targetPitch) >= 0.225;
                shouldSendRollCommand = Math.abs(targetRoll) >= 0.0225;
            default:
                break;
        }
        Log.d("TARGET", shouldSendYawCommand+" "+shouldSendPitchCommand+" "+shouldSendRollCommand);
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
        //Log.d("YAW", "pOut:" + pOut+ " iOut:"+iOut+" dOut:"+dOut+" MVn1Y: "+dMVnY);
        //double accelledY = MVn0Y * accelY;
        double accelledY = dMVnY * accelY;
        if (accelledY >= 100) {
            accelledY = 100;
        }
        if (accelledY <= -100) {
            accelledY = -100;
        }
        return accelledY;
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
        //Log.d("PITCH", shouldSendPitchCommand + " " + pOut + " " + iOut + " " + dOut +" MVn0P: "+dMVnP);
        MVn0P = MVn1P + dMVnP;
        MVn1P = MVn0P;
        //double accelledP = MVn0P * accelP;
        double accelledP = dMVnP * accelP;
        if (accelledP >= 100) {
            accelledP = 100;
        }
        if (accelledP <= -100) {
            accelledP = -100;
        }
        return accelledP;

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
        //Log.d("ROLL", "past " +MVn1R+" total:"+MVn0R);
        MVn1R = MVn0R;
        //double accelledR = MVn0R * accelR;
        double accelledR = dMVnR * accelR;
        //Log.d("ROLL", shouldSendRollCommand + " " +pOut + " " + iOut + " " + dOut + " MVn0R: "+dMVnR);
        //Log.d("ROLL", "past " +MVn1R+" total:"+MVn0R+" acceled:"+accelledR);
        if (accelledR >= 100) {
            accelledR = 100;
        }
        if (accelledR <= -100) {
            accelledR = -100;
        }
        return accelledR;
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
                double y = getPIDYawControl(droneYaw);
                double p = getPIDPitchControl(dronePitch);
                double r = getPIDRollControll(droneRoll);
                sendYPRCommand(y, p, r);
                Log.d("RUN", "SEND CMD");
            }
            nextTick += (long) rate;
        }
    }

    /**
     * ドローン操作量
     * PID計算でられる符号の逆を操作量として与える
     * @param y yaw操作量
     * @param p pitch操作量
     * @param r roll操作量
     */
    private void sendYPRCommand(double y, double p, double r) {
        final int yCtrl = (int) -y;
        final int pCtrl = (int) p;
        final int rCtrl = (int) -r;
        if (_controller != null) {
            /*
            _handler.post(new Runnable() {
                @Override
                public void run() {*/
            Log.d("Flag", shouldSendYawCommand+" "+shouldSendPitchCommand+" "+shouldSendRollCommand);
                    if (shouldSendYawCommand) {
                        _controller.getFeatureARDrone3().setPilotingPCMDYaw((byte) yCtrl);
                        Log.d("CTRL", "Yaw " + yCtrl);
                    } else {
                        _controller.getFeatureARDrone3().setPilotingPCMDYaw((byte) 0);
                        Log.d("CTRL", "NONE");
                    }
                    if (shouldSendRollCommand) {
                        _controller.getFeatureARDrone3().setPilotingPCMDRoll((byte) rCtrl);
                        _controller.getFeatureARDrone3().setPilotingPCMDFlag((byte) 1);
                        Log.d("CTRL", "Roll " + rCtrl);
                    } else {
                        _controller.getFeatureARDrone3().setPilotingPCMDRoll((byte) 0);
                        _controller.getFeatureARDrone3().setPilotingPCMDFlag((byte) 0);
                        Log.d("CTRL", "NONE");
                    }
                    if (shouldSendPitchCommand) {
                        _controller.getFeatureARDrone3().setPilotingPCMDPitch((byte) pCtrl);
                        _controller.getFeatureARDrone3().setPilotingPCMDFlag((byte) 1);
                        Log.d("CTRL", "Pitch " + pCtrl);
                    } else {
                        _controller.getFeatureARDrone3().setPilotingPCMDPitch((byte) 0);
                        _controller.getFeatureARDrone3().setPilotingPCMDFlag((byte) 0);
                        Log.d("CTRL", "NONE");
                    }
/*                }
            });*/
        }
    }

    public void term() {
        stop = false;
        term = true;
    }
}
